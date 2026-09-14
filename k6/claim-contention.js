import http from 'k6/http'
import { check, sleep } from 'k6'
import { Counter, Rate } from 'k6/metrics'
import exec from 'k6/execution'

/**
 * 배송 업무 선착순 수령 경합 부하 테스트.
 *
 * JUnit 동시성 테스트가 "정확성"(한 명만 성공, 한도 초과 없음)을 증명한다면
 * 이 스크립트는 그 보장이 실제 HTTP 부하에서 어떤 비용으로 지켜지는지를 관측한다.
 *
 * 핵심 설계: 수령에 성공한 VU 는 곧바로 반납해 업무를 다시 풀에 돌려놓는다.
 * 적은 수의 업무만으로 지속적인 경합을 만들 수 있고(=지오코딩 호출 최소화),
 * claim 과 release 양쪽 경로를 모두 부하 구간에 넣을 수 있다.
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080'
const ADMIN_ID = __ENV.ADMIN_ID || 'admin'
const ADMIN_PW = __ENV.ADMIN_PW || '1234'
const VU_COUNT = Number(__ENV.VUS || 50)
/** contention: 고정 VU 경합 / stress: VU 를 단계적으로 올려 포화 지점 탐색 */
const PROFILE = __ENV.PROFILE || 'contention'
const PEAK_VUS = PROFILE === 'stress' ? Number(__ENV.PEAK_VUS || 300) : VU_COUNT
// VU 와 기사 계정을 1:1 로 맞춘다. 한 토큰을 여러 VU 가 나눠 쓰면
// 같은 기사가 같은 업무를 동시에 반납하려 해서 무의미한 404 가 발생한다.
const DRIVER_COUNT = Number(__ENV.DRIVERS || PEAK_VUS)

/**
 * stress 프로필의 단계.
 *
 * 각 목표 VU 까지 짧게 ramp 한 뒤 **같은 시간 동안 유지(plateau)** 한다.
 * 계속 상승하는 ramp 만으로 구간을 나누면 버킷 폭과 체류 시간이 달라져
 * 선형으로 잘 확장되는 서버도 포화로 오판하게 된다.
 * 집계는 유지 구간만 사용하고 ramp·ramp-down 표본은 제외한다.
 */
const RAMP_SECONDS = Number(__ENV.RAMP_SECONDS || 15)
const HOLD_SECONDS = Number(__ENV.HOLD_SECONDS || 45)

function buildPlateauTargets() {
  const ratios = [0.15, 0.35, 0.7, 1]
  const targets = []
  ratios.forEach(function (ratio) {
    const value = Math.max(1, Math.round(PEAK_VUS * ratio))
    if (targets.indexOf(value) === -1) targets.push(value)
  })
  return targets
}
const PLATEAU_TARGETS = buildPlateauTargets()

function buildStressStages() {
  const stages = []
  PLATEAU_TARGETS.forEach(function (target) {
    stages.push({ duration: `${RAMP_SECONDS}s`, target: target })
    stages.push({ duration: `${HOLD_SECONDS}s`, target: target })
  })
  stages.push({ duration: `${RAMP_SECONDS}s`, target: 0 })
  return stages
}
const STRESS_STAGES = buildStressStages()

/** 각 유지 구간의 [시작초, 끝초). ramp 표본을 집계에서 빼기 위해 쓴다. */
function buildPlateauWindows() {
  const windows = []
  let cursor = 0
  PLATEAU_TARGETS.forEach(function (target) {
    cursor += RAMP_SECONDS
    windows.push({ target: target, from: cursor, to: cursor + HOLD_SECONDS })
    cursor += HOLD_SECONDS
  })
  return windows
}
const PLATEAU_WINDOWS = buildPlateauWindows()

function padVu(value) {
  let text = String(value)
  while (text.length < 4) text = `0${text}`
  return text
}

/**
 * 지금이 어느 유지 구간인지 돌려준다.
 * ramp 상승 구간과 마지막 ramp-down 은 null 이라 집계에 섞이지 않는다.
 */
function currentPlateau() {
  const elapsed = (Date.now() - exec.scenario.startTime) / 1000
  for (let i = 0; i < PLATEAU_WINDOWS.length; i += 1) {
    const window = PLATEAU_WINDOWS[i]
    if (elapsed >= window.from && elapsed < window.to) return padVu(window.target)
  }
  return null
}
const PLAN_COUNT = Number(__ENV.PLANS || 10)
const DRIVER_PW = 'loadtest1234'

// 지오코딩 호출이 계획 생성마다 발생하므로 주소는 소수만 돌려 쓴다.
const ADDRESSES = [
  '서울특별시 중구 세종대로 110',
  '서울특별시 강남구 테헤란로 152',
  '서울특별시 서초구 반포대로 45',
  '서울특별시 송파구 올림픽로 300',
  '서울특별시 마포구 양화로 45',
]

const claimAttempts = new Counter('claim_attempts')
const claimSuccess = new Counter('claim_success')
const claimConflict = new Counter('claim_conflict')
const claimLimitExceeded = new Counter('claim_limit_exceeded')
const claimPriorityBlocked = new Counter('claim_priority_blocked')
const claimLockConflict = new Counter('claim_lock_conflict')
const claimIdempotent = new Counter('claim_idempotent')
/** 설계상 나오면 안 되는 응답. threshold 로 0 을 강제한다. */
const claimUnexpected = new Counter('claim_unexpected')
const claimWinRate = new Rate('claim_win_rate')
const releaseSuccess = new Counter('release_success')
/** 반납 실패는 해당 업무가 경합 풀에서 빠졌다는 뜻이라 별도로 센다. */
const releaseFailed = new Counter('release_failed')
/** 같은 기사의 다른 VU 가 먼저 반납해 404 가 난 경우. 앱 오류가 아니라 스크립트 특성이다. */
const releaseAlreadyReturned = new Counter('release_already_returned')
/** 테스트 종료 시점에 OPEN 으로 돌아오지 못한 업무 수. 0 이어야 한다. */
const plansNotReturned = new Counter('plans_not_returned')

function buildScenarios() {
  if (PROFILE === 'stress') {
    // 포화 지점을 찾는 프로필. 단계적으로 VU 를 올리며 지연이 꺾이는 지점을 본다.
    return {
      stress: {
        executor: 'ramping-vus',
        startVUs: 10,
        gracefulRampDown: '15s',
        stages: STRESS_STAGES,
      },
    }
  }
  return {
    contention: {
      executor: 'constant-vus',
      vus: VU_COUNT,
      duration: __ENV.DURATION || '60s',
      gracefulStop: '10s',
    },
  }
}

function buildThresholds() {
  // 경합에서 밀리는 409 는 정상 동작이다. http_req_failed 는 이 테스트에서 장애율이 아니므로
  // (이번 실행 기준 약 68% 가 정상 409) 판정에 쓰지 않고 아래 지표로만 본다.
  const thresholds = {
    claim_unexpected: ['count==0'],
    // 반납이 실패하면 그 업무가 경합 풀에서 이탈해 부하 강도 자체가 조용히 낮아진다.
    // 지연만 보면 통과해 버리므로 별도 기준으로 고정한다.
    release_failed: ['count==0'],
  }

  if (DRIVER_COUNT >= PEAK_VUS) {
    // VU 와 기사가 1:1 이면 같은 기사가 중복 반납할 일이 없어 404 도 0 이어야 한다.
    thresholds.release_already_returned = ['count==0']
  }

  if (PROFILE === 'stress') {
    // k6 는 threshold 가 선언된 하위 지표만 요약에 포함한다.
    // 통합 p95 만으로는 어느 VU 구간에서 꺾였는지 알 수 없으므로
    // 밴드별 하위 지표를 "항상 통과" 기준으로 선언해 강제로 노출시킨다.
    // 지연 기준은 걸지 않되, 요약 표에 값이 찍히도록 하위 지표 자체는 만들어 둔다.
    // threshold 가 없으면 k6 가 그 하위 지표를 수집하지 않아 전체 응답 시간 표가 통째로 비어 버린다.
    thresholds['http_req_duration{endpoint:claim}'] = ['p(95)>=0']
    thresholds['http_req_duration{endpoint:release}'] = ['p(95)>=0']

    PLATEAU_TARGETS.forEach(function (target) {
      const label = padVu(target)
      thresholds[`http_req_duration{endpoint:claim,plateau:${label}}`] = ['p(95)>=0']
      thresholds[`claim_attempts{plateau:${label}}`] = ['count>=0']
    })
  }

  if (PROFILE !== 'stress') {
    // 포화 탐색에서는 지연이 꺾이는 것 자체가 목적이므로 지연 기준을 걸지 않는다.
    thresholds['http_req_duration{endpoint:claim}'] = ['p(95)<500', 'p(99)<1500']
    thresholds['http_req_duration{endpoint:release}'] = ['p(95)<500']
    // 램프다운 중 잘린 반복이 없는 고정 VU 프로필에서만 종료 불변식을 강제한다.
    thresholds.plans_not_returned = ['count==0']
  }

  return thresholds
}

export const options = {
  // k6 기본 요약에는 p(99) 가 없어 리포트에 값이 비게 된다. 명시적으로 요청한다.
  setupTimeout: '5m',
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: buildScenarios(),
  thresholds: buildThresholds(),
}

function jsonHeaders(token) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  return headers
}

function login(loginId, password) {
  const res = http.post(
    `${BASE_URL}/api/users/login`,
    JSON.stringify({ loginId, password }),
    { headers: jsonHeaders(), tags: { endpoint: 'login' } },
  )
  if (res.status !== 200) {
    throw new Error(`로그인 실패 (${loginId}): ${res.status} ${res.body}`)
  }
  return res.json('accessToken')
}

function ensureDriver(index) {
  const loginId = `loadtest-driver-${index}`
  // 이미 있으면 400(중복 아이디)이 떨어지므로 그대로 로그인으로 넘어간다.
  http.post(
    `${BASE_URL}/api/users/join`,
    JSON.stringify({ loginId, password: DRIVER_PW, name: `부하기사${index}` }),
    { headers: jsonHeaders(), tags: { endpoint: 'join' } },
  )
  return login(loginId, DRIVER_PW)
}

function createOpenPlan(adminToken, index) {
  const departureAt = new Date(Date.now() + 3_600_000).toISOString().slice(0, 19)
  const body = {
    departureAddress: ADDRESSES[index % ADDRESSES.length],
    scheduledDepartureAt: departureAt,
    stops: [
      {
        address: ADDRESSES[(index + 1) % ADDRESSES.length],
        items: [{ productName: '부하테스트 상품', productType: 'NORMAL', quantity: 1 }],
      },
    ],
  }
  const res = http.post(`${BASE_URL}/api/admin/delivery-plans`, JSON.stringify(body), {
    headers: jsonHeaders(adminToken),
    tags: { endpoint: 'create-plan' },
  })
  if (res.status !== 201) {
    throw new Error(
      `업무 등록 실패: ${res.status} ${res.body}\n` +
      'KAKAO_LOCAL_API_KEY 가 설정되어 있고 지오코딩이 가능한지 확인하세요.',
    )
  }
  return res.json('planId')
}

export function setup() {
  const adminToken = login(ADMIN_ID, ADMIN_PW)

  const driverTokens = []
  for (let i = 0; i < DRIVER_COUNT; i += 1) {
    driverTokens.push(ensureDriver(i))
  }

  const planIds = []
  for (let i = 0; i < PLAN_COUNT; i += 1) {
    planIds.push(createOpenPlan(adminToken, i))
  }

  console.log(`기사 ${driverTokens.length}명 / 업무 ${planIds.length}건 준비 완료: ${planIds}`)
  return { driverTokens, planIds }
}

function releasePlan(token, planId) {
  return http.del(`${BASE_URL}/api/delivery-plans/${planId}/claim`, null, {
    headers: jsonHeaders(token),
    tags: { endpoint: 'release' },
  })
}

export default function (data) {
  // VU 마다 다른 기사 토큰을 써서 기사 행 락이 전체를 직렬화하지 않게 한다.
  const token = data.driverTokens[(__VU - 1) % data.driverTokens.length]
  const planId = data.planIds[Math.floor(Math.random() * data.planIds.length)]
  // stress 프로필에서 구간별 지연을 분리하기 위한 태그
  // 유지 구간에서만 태깅한다. ramp 표본이 섞이면 구간별 비교가 무의미해진다.
  const plateau = PROFILE === 'stress' ? currentPlateau() : null
  const claimTags = plateau ? { endpoint: 'claim', plateau: plateau } : { endpoint: 'claim' }

  if (plateau) claimAttempts.add(1, { plateau: plateau })

  const res = http.post(`${BASE_URL}/api/delivery-plans/${planId}/claim`, null, {
    headers: jsonHeaders(token),
    tags: claimTags,
  })

  const code = res.status === 409 ? res.json('code') : null
  claimWinRate.add(res.status === 200)

  if (res.status === 200) {
    const alreadyOwned = res.json('alreadyOwned')
    if (alreadyOwned) {
      claimIdempotent.add(1)
    } else {
      claimSuccess.add(1)
    }
    // 가져간 업무를 곧바로 반납해 다음 경합 대상으로 되돌린다.
    // 반납이 실패하면 그 업무가 풀에서 빠져 경합 대상이 줄어들므로 한 번 재시도한다.
    let releaseRes = releasePlan(token, planId)
    // 404 는 이미 누군가 반납했다는 뜻이라 재시도할 이유가 없다.
    if (releaseRes.status !== 204 && releaseRes.status !== 404) {
      releaseRes = releasePlan(token, planId)
    }
    if (releaseRes.status === 204) {
      releaseSuccess.add(1)
    } else if (releaseRes.status === 404) {
      releaseAlreadyReturned.add(1)
    } else {
      releaseFailed.add(1)
    }
  } else if (res.status === 409) {
    if (code === 'DELIVERY_PLAN_ALREADY_CLAIMED') claimConflict.add(1)
    else if (code === 'DELIVERY_PLAN_CLAIM_LIMIT_EXCEEDED') claimLimitExceeded.add(1)
    else if (code === 'DELIVERY_PLAN_PRIORITY_WINDOW_ACTIVE') claimPriorityBlocked.add(1)
    else if (code === 'DELIVERY_CLAIM_LOCK_CONFLICT') claimLockConflict.add(1)
    else claimUnexpected.add(1)
  } else {
    claimUnexpected.add(1)
  }

  check(res, {
    '수령 응답은 200 또는 409 다': (r) => r.status === 200 || r.status === 409,
  })

  sleep(Number(__ENV.SLEEP || 0.2))
}

/**
 * 종료 시점 불변식 검사.
 *
 * "성공 수령 수 == 성공 반납 수" 는 간접 증거일 뿐이다.
 * 실제로 모든 업무가 다시 OPEN(소유자 없음) 으로 돌아왔는지 서버에 직접 물어 확인한다.
 */
export function teardown(data) {
  const res = http.get(`${BASE_URL}/api/delivery-plans/open`, {
    headers: jsonHeaders(data.driverTokens[0]),
    tags: { endpoint: 'open-list' },
  })

  if (res.status !== 200) {
    console.error(`종료 검사 실패: 미배정 목록 조회가 ${res.status} 를 반환했다`)
    plansNotReturned.add(data.planIds.length)
    return
  }

  const openIds = res.json().map(function (plan) { return plan.planId })
  const stuck = data.planIds.filter(function (id) { return openIds.indexOf(id) === -1 })

  if (stuck.length > 0) {
    console.error(`종료 검사 실패: OPEN 으로 돌아오지 못한 업무 ${stuck}`)
    plansNotReturned.add(stuck.length)
  } else {
    console.log(`종료 검사 통과: 업무 ${data.planIds.length}건 모두 OPEN 복귀`)
  }
}

// ---------------------------------------------------------------------------
// 결과 리포트
// ---------------------------------------------------------------------------

function counterOf(data, name) {
  const metric = data.metrics[name]
  if (!metric || !metric.values) return 0
  return metric.values.count || 0
}

function trendOf(data, name, stat) {
  const metric = data.metrics[name]
  if (!metric || !metric.values) return null
  const value = metric.values[stat]
  return typeof value === 'number' ? value : null
}

/**
 * k6 기간 표기('3m', '1m30s', '500ms')를 초로 바꾼다.
 * 숫자만 뽑아내면 '3m' 이 3초로 계산되어 처리량이 60배 부풀려진다.
 * 'ms' 를 's' 보다 먼저 매칭해야 '500ms' 가 '500m' 으로 잘리지 않는다.
 */
function parseDuration(text) {
  const pattern = /(\d+(?:\.\d+)?)(ms|h|m|s)/g
  let total = 0
  let matched = false
  let found = pattern.exec(String(text))
  while (found !== null) {
    matched = true
    const value = parseFloat(found[1])
    if (found[2] === 'h') total += value * 3600
    else if (found[2] === 'm') total += value * 60
    else if (found[2] === 's') total += value
    else total += value / 1000
    found = pattern.exec(String(text))
  }
  if (!matched) {
    const plain = parseFloat(String(text))
    return isNaN(plain) ? 0 : plain
  }
  return total
}

function ms(value) {
  return value === null ? '-' : `${value.toFixed(1)}ms`
}

function pct(numerator, denominator) {
  if (!denominator) return '-'
  return `${((numerator / denominator) * 100).toFixed(2)}%`
}

function buildReport(data) {
  const success = counterOf(data, 'claim_success')
  const idempotent = counterOf(data, 'claim_idempotent')
  const conflict = counterOf(data, 'claim_conflict')
  const limitExceeded = counterOf(data, 'claim_limit_exceeded')
  const priorityBlocked = counterOf(data, 'claim_priority_blocked')
  const lockConflict = counterOf(data, 'claim_lock_conflict')
  const unexpected = counterOf(data, 'claim_unexpected')
  const attempts = success + idempotent + conflict + limitExceeded + priorityBlocked + lockConflict + unexpected
  const durationSec = (data.state && data.state.testRunDurationMs ? data.state.testRunDurationMs : 0) / 1000

  const claimKey = 'http_req_duration{endpoint:claim}'
  const releaseKey = 'http_req_duration{endpoint:release}'

  const lines = []
  lines.push('# 배송 업무 수령 경합 부하 테스트 결과')
  lines.push('')
  lines.push(`- 실행 시각: ${new Date().toISOString()}`)
  lines.push(`- 부하 시간: ${durationSec.toFixed(1)}초`)
  lines.push(`- 대상: ${BASE_URL}`)
  lines.push(`- 프로필: ${PROFILE}`)
  lines.push(PROFILE === 'stress'
    ? `- VU 유지 구간: ${PLATEAU_TARGETS.join(' → ')} (각 ${HOLD_SECONDS}초 유지, peak ${PEAK_VUS}) / 기사 계정: ${DRIVER_COUNT} / 경합 업무: ${PLAN_COUNT}`
    : `- VU: ${VU_COUNT} / 기사 계정: ${DRIVER_COUNT} / 경합 업무: ${PLAN_COUNT}`)
  lines.push('')
  lines.push('## 수령 결과')
  lines.push('')
  lines.push('| 결과 | 건수 | 비율 |')
  lines.push('| --- | ---: | ---: |')
  lines.push(`| 성공 | ${success} | ${pct(success, attempts)} |`)
  lines.push(`| 멱등(이미 본인 소유) | ${idempotent} | ${pct(idempotent, attempts)} |`)
  lines.push(`| 경합 충돌 | ${conflict} | ${pct(conflict, attempts)} |`)
  lines.push(`| 보유 한도 초과 | ${limitExceeded} | ${pct(limitExceeded, attempts)} |`)
  lines.push(`| 우선권 차단 | ${priorityBlocked} | ${pct(priorityBlocked, attempts)} |`)
  lines.push(`| 락 경합 | ${lockConflict} | ${pct(lockConflict, attempts)} |`)
  lines.push(`| 예상 밖 응답 | ${unexpected} | ${pct(unexpected, attempts)} |`)
  lines.push(`| **합계** | **${attempts}** | |`)
  lines.push('')
  // stress 는 stage 합계가 실제 부하 구간이다. DURATION 기본값(60s)을 쓰면 처리량이 부풀려진다.
  const loadSec = PROFILE === 'stress'
    ? STRESS_STAGES.reduce(function (sum, stage) { return sum + parseDuration(stage.duration) }, 0)
    : (parseDuration(__ENV.DURATION || '60s') || durationSec)
  lines.push(`- 수령 시도 처리량: ${loadSec > 0 ? (attempts / loadSec).toFixed(1) : '-'} 건/초 (부하 구간 ${loadSec}초 기준)`)
  lines.push(`- 전체 실행 시간: ${durationSec.toFixed(1)}초 (setup 포함)`)
  lines.push(`- 완료 반복: ${counterOf(data, 'iterations')}회`)
  lines.push('')
  lines.push('## 응답 시간')
  lines.push('')
  lines.push('| 경로 | p50 | p90 | p95 | p99 | max |')
  lines.push('| --- | ---: | ---: | ---: | ---: | ---: |')
  lines.push(`| 수령(claim) | ${ms(trendOf(data, claimKey, 'med'))} | ${ms(trendOf(data, claimKey, 'p(90)'))} | ${ms(trendOf(data, claimKey, 'p(95)'))} | ${ms(trendOf(data, claimKey, 'p(99)'))} | ${ms(trendOf(data, claimKey, 'max'))} |`)
  lines.push(`| 반납(release) | ${ms(trendOf(data, releaseKey, 'med'))} | ${ms(trendOf(data, releaseKey, 'p(90)'))} | ${ms(trendOf(data, releaseKey, 'p(95)'))} | ${ms(trendOf(data, releaseKey, 'p(99)'))} | ${ms(trendOf(data, releaseKey, 'max'))} |`)
  lines.push('')
  lines.push('## 반납')
  lines.push('')
  lines.push(`- 성공: ${counterOf(data, 'release_success')}`)
  lines.push(`- 이미 반납됨(404): ${counterOf(data, 'release_already_returned')}`)
  lines.push(`- 실패: ${counterOf(data, 'release_failed')}`)
  if (PROFILE === 'stress') {
    lines.push('')
    lines.push('## VU 유지 구간별 지연 (무릎점 탐색)')
    lines.push('')
    lines.push(`각 구간을 동일하게 ${HOLD_SECONDS}초씩 유지한 표본만 집계했다. ramp 상승·하강 구간은 제외했다.`)
    lines.push('')
    lines.push('| 유지 VU | 수령 시도 | 시도/초 | 확장 효율 | p50 | p90 | p95 | p99 | max |')
    lines.push('| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
    let previousVu = 0
    let previousRate = 0
    PLATEAU_TARGETS.forEach(function (target) {
      const label = padVu(target)
      const key = `http_req_duration{endpoint:claim,plateau:${label}}`
      const attemptCount = counterOf(data, `claim_attempts{plateau:${label}}`)
      const rate = HOLD_SECONDS > 0 ? attemptCount / HOLD_SECONDS : 0
      // VU 증가 배수 대비 처리량 증가 배수. 1.0 이면 선형 확장, 낮아지면 포화 진입이다.
      const efficiency = previousRate > 0 && previousVu > 0
        ? `${((rate / previousRate) / (target / previousVu)).toFixed(2)}x`
        : '기준'
      lines.push(
        `| ${target} | ${attemptCount} | ${rate.toFixed(1)} | ${efficiency} | ` +
        `${ms(trendOf(data, key, 'med'))} | ${ms(trendOf(data, key, 'p(90)'))} | ` +
        `${ms(trendOf(data, key, 'p(95)'))} | ${ms(trendOf(data, key, 'p(99)'))} | ` +
        `${ms(trendOf(data, key, 'max'))} |`,
      )
      previousVu = target
      previousRate = rate
    })
    lines.push('')
    lines.push('> **확장 효율**이 1.0 에 가까우면 선형 확장이다. 0.8 아래로 떨어지는 구간이 포화 후보다.')
    lines.push('> 같은 시점에 p95·p99 가 함께 꺾이는지 확인한다. 원인 판별은 서버 리소스 지표와 함께 봐야 한다.')
  }

  lines.push('')
  lines.push('## 종료 불변식')
  lines.push('')
  const notReturned = counterOf(data, 'plans_not_returned')
  lines.push(notReturned === 0
    ? '- 모든 경합 업무가 OPEN(소유자 없음) 으로 복귀했다.'
    : `- **OPEN 으로 돌아오지 못한 업무 ${notReturned}건** — 소유자가 남아 있다.`)
  lines.push('')
  lines.push('## 판정')
  lines.push('')
  const thresholds = []
  Object.keys(data.metrics).forEach((name) => {
    const metric = data.metrics[name]
    if (!metric.thresholds) return
    Object.keys(metric.thresholds).forEach((rule) => {
      const passed = metric.thresholds[rule].ok
      thresholds.push(`- ${passed ? 'PASS' : 'FAIL'} \`${name}\` ${rule}`)
    })
  })
  lines.push(thresholds.length ? thresholds.join('\n') : '- (threshold 없음)')
  lines.push('')
  return lines.join('\n')
}

export function handleSummary(data) {
  const report = buildReport(data)
  return {
    stdout: `\n${report}\n`,
    'k6/summary.md': report,
    'k6/summary.json': JSON.stringify(data, null, 2),
  }
}
