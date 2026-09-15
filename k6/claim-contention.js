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
/**
 * contention: 고정 VU 경합
 * stress:     VU 를 단계적으로 올려 포화 지점 탐색
 * knee:       1 VU 부터 기하급수 사다리로 올려 무릎점(knee) 을 수치로 판별
 */
const PROFILE = __ENV.PROFILE || 'contention'
/** stress 와 knee 는 같은 plateau 집계 기계를 공유한다. */
const IS_LADDER = PROFILE === 'stress' || PROFILE === 'knee'

/**
 * 무릎점 탐색 사다리.
 *
 * 등간격(150/350/700/1000)으로 올리면 저부하 구간 표본이 하나도 없어
 * "무엇 대비 효율인지"의 기준선 X(1) 을 못 잡는다.
 * 무릎을 사이에 끼우려면(bracketing) 반드시 1 VU 에서 시작하는
 * 기하급수 사다리여야 한다.
 */
const KNEE_LADDER = String(__ENV.LADDER || '1,2,4,8,16,32,64,128,256')
  .split(',')
  .map(function (text) { return Math.round(Number(String(text).trim())) })
  .filter(function (value) { return !isNaN(value) && value >= 1 })
  .sort(function (a, b) { return a - b })

const PEAK_VUS = PROFILE === 'knee'
  ? KNEE_LADDER[KNEE_LADDER.length - 1]
  : (PROFILE === 'stress' ? Number(__ENV.PEAK_VUS || 300) : VU_COUNT)

/**
 * 경합 여부 스위치.
 *
 * on  : 모든 VU 가 소수의 업무를 무작위로 노린다 → 락 경합의 무릎점
 * off : VU 마다 전용 업무를 잡는다        → 경합을 미리 모두 제거한 순수 서버/DB 용량의 무릎점
 *
 * 두 무릎점의 차이가 곰 "선착순 경합 설계가 지불하는 비용"이다.
 */
const CONTENTION = String(__ENV.CONTENTION || 'on').toLowerCase() !== 'off'

/**
 * 무릎을 어느 지표로 판정할지.
 *
 * requests : claim + release 를 합친 **HTTP 요청/초**. 요청 한 건의 비용이
 *            iteration 보다 훨씬 균일해서 성공률이 다른 두 실행을 비교할 때 기본값으로 쓴다.
 * attempts : claim 시도(=iteration)/초. 예전 동작.
 * goodput  : 성공한 수령/초. 사용자 관점의 유효 처리량이지만 경합 실행에서는
 *            업무 풀 크기에 묶여 서버 용량을 나타내지 못한다.
 */
const KNEE_METRIC = String(__ENV.KNEE_METRIC || 'requests').toLowerCase()
/** USL 적합을 신뢰할 최소 R². 이보다 낮으면 N* 를 판정 후보에서 뺀다. */
const USL_MIN_R2 = Number(__ENV.USL_MIN_R2 || 0.8)
/** 효율이 이 아래로 떨어지면 더 이상 선형 확장이 아니다고 본다. */
const EFFICIENCY_FLOOR = Number(__ENV.EFFICIENCY_FLOOR || 0.8)
/** 응답 시간이 최저치의 몇 배가 되는 지점을 지연 무릎으로 볼지. */
const LATENCY_KNEE_FACTOR = Number(__ENV.LATENCY_FACTOR || 2)
/** 결과 파일 접미사. 같은 사다리를 여러 번 돌릴 때 서로 덮어쓰지 않게 한다. */
const OUT_SUFFIX = __ENV.OUT ? `-${__ENV.OUT}` : ''
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
const RAMP_SECONDS = Number(__ENV.RAMP_SECONDS || (PROFILE === 'knee' ? 10 : 15))
// 25초 창은 GC 한 번에 구간 하나가 통째로 흔들린다.
// 실제로 첫 실행에서 처리량이 16→32→64→128 구간에 걸쳐 오르내리는 비단조 곡선이 나왔다.
const HOLD_SECONDS = Number(__ENV.HOLD_SECONDS || (PROFILE === 'knee' ? 90 : 45))

/**
 * 유지 구간에 들어선 직후 버리는 시간.
 *
 * 앞 구간의 ramp-down 이 gracefulRampDown 만큼 늦게 끝나면 그 잔여 부하가
 * 다음 구간 앞머리에 섮여 저VU 구간 처리량이 부풀려진다.
 * 무릎점은 바로 그 저VU 구간의 기울기로 결정되므로
 * 여기가 오염되면 판정 전체가 틀어진다.
 */
const SETTLE_SECONDS = Number(__ENV.SETTLE_SECONDS || (PROFILE === 'knee' ? 15 : 0))
const MEASURE_SECONDS = Math.max(1, HOLD_SECONDS - SETTLE_SECONDS)

/**
 * 사다리 시작 전 예열.
 *
 * JIT 컴파일과 커넥션 풀·버퍼 확보가 끝나기 전에 X(1) 을 재면
 * 기준선이 낮게 잡혀 이후 모든 구간의 확장 효율이 과대평가된다.
 * 예열 구간은 측정 창에서 완전히 제외한다.
 */
const WARMUP_SECONDS = Number(__ENV.WARMUP_SECONDS || (PROFILE === 'knee' ? 30 : 0))
const WARMUP_VUS = Math.max(1, Number(__ENV.WARMUP_VUS || Math.min(32, PEAK_VUS)))
const HAS_WARMUP = PROFILE === 'knee' && WARMUP_SECONDS > 0

function buildPlateauTargets() {
  if (PROFILE === 'knee') return KNEE_LADDER
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
  if (HAS_WARMUP) {
    stages.push({ duration: `${RAMP_SECONDS}s`, target: WARMUP_VUS })
    stages.push({ duration: `${WARMUP_SECONDS}s`, target: WARMUP_VUS })
  }
  PLATEAU_TARGETS.forEach(function (target) {
    stages.push({ duration: `${RAMP_SECONDS}s`, target: target })
    stages.push({ duration: `${HOLD_SECONDS}s`, target: target })
  })
  stages.push({ duration: `${RAMP_SECONDS}s`, target: 0 })
  return stages
}
const STRESS_STAGES = buildStressStages()

/** 각 유지 구간의 [시작초, 끝초). ramp·예열·안정화 표본을 집계에서 빼기 위해 쓴다. */
function buildPlateauWindows() {
  const windows = []
  let cursor = HAS_WARMUP ? RAMP_SECONDS + WARMUP_SECONDS : 0
  PLATEAU_TARGETS.forEach(function (target) {
    cursor += RAMP_SECONDS
    windows.push({
      target: target,
      from: cursor + SETTLE_SECONDS,
      to: cursor + HOLD_SECONDS,
    })
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
/**
 * 업무 수.
 * CONTENTION=off 면 VU 마다 전용 업무가 필요하므로 최대 VU 수만큼 만든다.
 * 업무 생성마다 지오코딩 호출이 나가므로 setup 이 그만큼 길어진다.
 */
const PLAN_COUNT = Number(__ENV.PLANS || (CONTENTION ? 10 : PEAK_VUS))
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
  if (IS_LADDER) {
    // 포화 지점을 찾는 프로필. 단계적으로 VU 를 올리며 지연이 꺾이는 지점을 본다.
    const scenarios = {}
    scenarios[PROFILE] = {
      executor: 'ramping-vus',
      // knee 는 1 VU 기준선부터 재야 하므로 10 에서 시작하면 안 된다.
      startVUs: PROFILE === 'knee' ? 1 : 10,
      gracefulRampDown: '15s',
      stages: STRESS_STAGES,
    }
    return scenarios
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

  if (IS_LADDER) {
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
      // waiting = TTFB(서버가 실제로 요청을 쥐고 있던 시간),
      // blocked = 커넥션을 받기까지 기다린 시간(부하 생성기 쪽 포화).
      // 둘을 나눠 봐야 "서버가 꺾인 것"과 "k6 가 꺾인 것"을 구분할 수 있다.
      thresholds[`http_req_waiting{endpoint:claim,plateau:${label}}`] = ['p(95)>=0']
      thresholds[`http_req_blocked{endpoint:claim,plateau:${label}}`] = ['p(95)>=0']
      // 구간별 결과 코드. k6 는 threshold 가 없는 하위 지표를 아예 수집하지 않으므로
      // "항상 통과" 기준을 걸어 강제로 노출시킨다.
      thresholds[`claim_success{plateau:${label}}`] = ['count>=0']
      thresholds[`claim_conflict{plateau:${label}}`] = ['count>=0']
      thresholds[`claim_priority_blocked{plateau:${label}}`] = ['count>=0']
      thresholds[`release_success{plateau:${label}}`] = ['count>=0']
    })
  }

  if (!IS_LADDER) {
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
  // CONTENTION=off 는 VU 수만큼 업무를 지오코딩해 만들어야 해서 setup 이 길다.
  setupTimeout: __ENV.SETUP_TIMEOUT || '15m',
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
  // CONTENTION=off 면 VU 마다 전용 업무를 잡아 락 경합을 0 으로 만든다.
  // 같은 사다리를 두 번(경합 O/X) 돌려 두 무릎점을 비교하기 위한 스위치다.
  const planId = CONTENTION
    ? data.planIds[Math.floor(Math.random() * data.planIds.length)]
    : data.planIds[(__VU - 1) % data.planIds.length]
  // stress/knee 프로필에서 구간별 지연을 분리하기 위한 태그
  // 유지 구간에서만 태깅한다. ramp 표본이 섞이면 구간별 비교가 무의미해진다.
  const plateau = IS_LADDER ? currentPlateau() : null
  const claimTags = plateau ? { endpoint: 'claim', plateau: plateau } : { endpoint: 'claim' }

  // 구간별 집계용 태그.
  //
  // 이게 없으면 X(N) 이 "iteration/초" 하나뿐이라 구간 비교가 오염된다.
  // 409(충돌) iteration 은 claim 한 번이고, 200 iteration 은 claim + release 두 번이다.
  // 경합이 세지면 409 비중이 올라가 iteration 당 작업량이 저절로 가벼워지므로,
  // iteration 처리량만 보면 "경합이 걸린 쪽이 더 빠르다"는 잘못된 결론이 나온다.
  const plateauTags = plateau ? { plateau: plateau } : {}
  if (plateau) claimAttempts.add(1, plateauTags)

  const res = http.post(`${BASE_URL}/api/delivery-plans/${planId}/claim`, null, {
    headers: jsonHeaders(token),
    tags: claimTags,
  })

  const code = res.status === 409 ? res.json('code') : null
  claimWinRate.add(res.status === 200)

  if (res.status === 200) {
    const alreadyOwned = res.json('alreadyOwned')
    if (alreadyOwned) {
      claimIdempotent.add(1, plateauTags)
    } else {
      claimSuccess.add(1, plateauTags)
    }
    // 가져간 업무를 곧바로 반납해 다음 경합 대상으로 되돌린다.
    // 반납이 실패하면 그 업무가 풀에서 빠져 경합 대상이 줄어들므로 한 번 재시도한다.
    let releaseRes = releasePlan(token, planId)
    // 404 는 이미 누군가 반납했다는 뜻이라 재시도할 이유가 없다.
    if (releaseRes.status !== 204 && releaseRes.status !== 404) {
      releaseRes = releasePlan(token, planId)
    }
    if (releaseRes.status === 204) {
      releaseSuccess.add(1, plateauTags)
    } else if (releaseRes.status === 404) {
      releaseAlreadyReturned.add(1)
    } else {
      releaseFailed.add(1)
    }
  } else if (res.status === 409) {
    if (code === 'DELIVERY_PLAN_ALREADY_CLAIMED') claimConflict.add(1, plateauTags)
    else if (code === 'DELIVERY_PLAN_CLAIM_LIMIT_EXCEEDED') claimLimitExceeded.add(1)
    else if (code === 'DELIVERY_PLAN_PRIORITY_WINDOW_ACTIVE') claimPriorityBlocked.add(1, plateauTags)
    else if (code === 'DELIVERY_CLAIM_LOCK_CONFLICT') claimLockConflict.add(1)
    else claimUnexpected.add(1)
  } else {
    claimUnexpected.add(1)
  }

  check(res, {
    '수령 응답은 200 또는 409 다': (r) => r.status === 200 || r.status === 409,
  })

  // knee 는 VU 수 자체가 유일한 조절 변수여야 한다. think time 이 있으면
  // 서버가 꺾이기 전에 클라이언트가 먼저 부하를 제한해 무릎이 가려진다.
  sleep(Number(__ENV.SLEEP || (PROFILE === 'knee' ? 0 : 0.2)))
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

// ---------------------------------------------------------------------------
// 무릎점(knee) 판별
//
// 무릎점은 "VU 를 더 넣어도 처리량이 그만큼 늘지 않기 시작하는 지점"이다.
// 판정에서 가장 쉽게 깨지는 전제가 **선형 기준선**이다. 기준선이 오염되면
// 효율이 1 을 넘는(=이상적 선형보다 빠른) 물리적으로 불가능한 값이 나온다.
// 아래 analyzeKnee 의 anchor 주석이 그 처리 방식이다.
// ---------------------------------------------------------------------------

/** 유지 구간별 원시 표본을 한 줄씩 모은다. */
function collectPlateauRows(data) {
  const rows = []
  PLATEAU_TARGETS.forEach(function (target) {
    const label = padVu(target)
    const durKey = `http_req_duration{endpoint:claim,plateau:${label}}`
    const attempts = counterOf(data, `claim_attempts{plateau:${label}}`)
    const success = counterOf(data, `claim_success{plateau:${label}}`)
    const conflict = counterOf(data, `claim_conflict{plateau:${label}}`)
    const priorityBlocked = counterOf(data, `claim_priority_blocked{plateau:${label}}`)
    const releases = counterOf(data, `release_success{plateau:${label}}`)
    const perSecond = function (count) { return MEASURE_SECONDS > 0 ? count / MEASURE_SECONDS : 0 }
    rows.push({
      n: target,
      attempts: attempts,
      success: success,
      conflict: conflict,
      priorityBlocked: priorityBlocked,
      releases: releases,
      // iteration/초. 409 는 요청 1건, 200 은 claim+release 2건이라 작업량이 균일하지 않다.
      attemptRate: perSecond(attempts),
      // HTTP 요청/초. 요청 한 건의 비용이 훨씬 균일해서 성공률이 다른 실행끼리 비교할 수 있다.
      requestRate: perSecond(attempts + releases),
      // 성공한 수령/초. 사용자 관점의 유효 처리량.
      goodput: perSecond(success),
      successRate: attempts > 0 ? success / attempts : null,
      avg: trendOf(data, durKey, 'avg'),
      p50: trendOf(data, durKey, 'med'),
      p90: trendOf(data, durKey, 'p(90)'),
      p95: trendOf(data, durKey, 'p(95)'),
      p99: trendOf(data, durKey, 'p(99)'),
      max: trendOf(data, durKey, 'max'),
      waiting95: trendOf(data, `http_req_waiting{endpoint:claim,plateau:${label}}`, 'p(95)'),
      blocked95: trendOf(data, `http_req_blocked{endpoint:claim,plateau:${label}}`, 'p(95)'),
      rate: 0,
      efficiency: null,
      little: null,
    })
  })
  return rows
}

function metricLabel() {
  if (KNEE_METRIC === 'attempts') return '시도/초'
  if (KNEE_METRIC === 'goodput') return '성공/초'
  return '요청/초'
}

function pickRate(row) {
  if (KNEE_METRIC === 'attempts') return row.attemptRate
  if (KNEE_METRIC === 'goodput') return row.goodput
  return row.requestRate
}

/**
 * Universal Scalability Law 적합.
 *
 *   X(N) = N·u / (1 + σ(N-1) + κ·N(N-1))      u = 이상적 1 VU 처리량
 *
 * σ(직렬화)는 공유 자원을 줄 서서 기다리는 비율, κ(일관성)는 참여자끼리
 * 상태를 맞추는 비용이다. κ > 0 이면 처리량이 정점을 찍고 다시 내려간다.
 *
 *   N* = sqrt((1 - σ) / κ)
 *
 * 적합은 Gunther 의 변환법이다. C(N) = X(N)/u 에 대해
 *   y(N)/(N-1) = σ + κ·N   ← N 에 대한 단순 선형회귀 한 번
 *
 * 주의: 이 적합은 표본이 깨끗할 때만 의미가 있다. σ 가 음수로 나오면
 * "요청의 -12% 가 줄 선다"는 해석 불가능한 값이므로 모델을 폐기해야 한다.
 * valid 플래그가 그 판정이다.
 */
function fitUsl(rows, unitRate) {
  if (rows.length < 3 || !(unitRate > 0)) return null

  const points = []
  rows.forEach(function (row) {
    if (row.n < 2 || !(row.rate > 0)) return
    const capacity = row.rate / unitRate
    if (!(capacity > 0)) return
    points.push({ n: row.n, y: (row.n / capacity - 1) / (row.n - 1) })
  })
  if (points.length < 2) return null

  let sumN = 0
  let sumY = 0
  points.forEach(function (point) { sumN += point.n; sumY += point.y })
  const meanN = sumN / points.length
  const meanY = sumY / points.length

  let cov = 0
  let varN = 0
  points.forEach(function (point) {
    cov += (point.n - meanN) * (point.y - meanY)
    varN += (point.n - meanN) * (point.n - meanN)
  })
  if (varN === 0) return null

  const kappa = cov / varN
  const sigma = meanY - kappa * meanN

  let ssTot = 0
  let ssRes = 0
  points.forEach(function (point) {
    const predicted = sigma + kappa * point.n
    ssTot += (point.y - meanY) * (point.y - meanY)
    ssRes += (point.y - predicted) * (point.y - predicted)
  })
  const r2 = ssTot > 0 ? 1 - (ssRes / ssTot) : null

  const reasons = []
  if (sigma < 0) reasons.push(`σ 가 음수(${sigma.toFixed(5)})다. 직렬화 비율이 음수라는 뜻이라 해석 불가능하다`)
  if (sigma >= 1) reasons.push(`σ 가 1 이상(${sigma.toFixed(5)})이라 모델 유효 범위를 벗어났다`)
  if (!(kappa > 0)) reasons.push(`κ 가 0 이하(${kappa.toFixed(7)})라 정점이 존재하지 않는다`)
  if (r2 === null || r2 < USL_MIN_R2) reasons.push(`R² ${r2 === null ? '-' : r2.toFixed(3)} 가 기준 ${USL_MIN_R2} 미만이다`)

  const valid = reasons.length === 0
  let nStar = null
  let peakRate = null
  if (valid) {
    nStar = Math.sqrt((1 - sigma) / kappa)
    const denom = 1 + sigma * (nStar - 1) + kappa * nStar * (nStar - 1)
    peakRate = denom > 0 ? (nStar * unitRate) / denom : null
  }

  return {
    unitRate: unitRate,
    sigma: sigma,
    kappa: kappa,
    r2: r2,
    valid: valid,
    reasons: reasons,
    nStar: nStar,
    peakRate: peakRate,
    sampleCount: points.length,
  }
}

/** 사다리 표본에서 여러 정의의 무릎점을 동시에 뽑는다. */
function analyzeKnee(rows) {
  rows.forEach(function (row) { row.rate = pickRate(row) })

  const usable = []
  rows.forEach(function (row) { if (row.attempts > 0 && row.rate > 0) usable.push(row) })
  if (usable.length < 2) return { ready: false, rows: rows, usable: usable, warnings: [] }

  // 경고는 { code, text } 다. 반복 실행 결과를 합칠 때 본문 숫자가 조금씩 달라도
  // 같은 경고로 묶으려면 본문이 아니라 코드로 중복을 제거해야 한다.
  const warnings = []
  const warn = function (code, text) { warnings.push({ code: code, text: text }) }

  /*
   * 선형 기준선.
   *
   * X(1) 을 그대로 기준선으로 쓰면 안 된다. 노트북에서는 부하가 가벼울수록
   * CPU 가 저전력 코어로 내려가고 JIT 도 덜 돌아, 1 VU 구간이 8 VU 구간보다
   * **느리게** 측정되는 일이 흔하다. 실제로 첫 실행에서 1 VU p50 이 17.5ms,
   * 4 VU p50 이 3.6ms 로 나왔다. 그 상태로 C(N)=X(N)/X(1) 을 계산하면
   * 효율이 5.04 까지 올라가는데, 이는 "이상적 선형보다 5배 빠르다"는 뜻이라
   * 물리적으로 불가능하다.
   *
   * 그래서 관측된 VU당 처리량 중 **가장 좋은 값**을 이상적 선형 단위로 삼는다.
   * 정의상 효율은 1 을 넘지 못하고, 느리게 측정된 저VU 한 점이 판정을 뒤집지 못한다.
   */
  let anchor = usable[0]
  usable.forEach(function (row) {
    if (row.rate / row.n > anchor.rate / anchor.n) anchor = row
  })
  const unitRate = anchor.rate / anchor.n

  usable.forEach(function (row) {
    row.capacity = row.rate / unitRate
    row.efficiency = row.capacity / row.n
    // 리틀의 법칙: N ≈ 처리량 × 평균 응답시간.
    // 실측 VU 수보다 작게 나오면 VU 가 측정 대상(claim) 밖에서 시간을 쓰고 있다는 뜻이다.
    row.little = row.avg !== null ? row.attemptRate * (row.avg / 1000) : null
  })

  if (anchor.n !== usable[0].n) {
    warn('anchor-not-lowest',
      `기준선을 X(1) 이 아니라 **${anchor.n} VU 구간**에서 잡았다. ` +
      `${usable[0].n} VU 구간이 VU당 처리량 기준으로 더 느리게 측정됐기 때문이다 ` +
      '(예열 부족이나 CPU 주파수 스케일링 의심). 저VU 구간의 절대값은 신뢰하지 않는다.',
    )
  }

  // 1) 효율 무릎: 효율이 바닥선 이상인 **가장 큰** N.
  //    "첫 하락 직전"으로 잡으면 저VU 잡음 한 점에 판정이 통째로 끌려간다.
  let efficiencyKnee = null
  usable.forEach(function (row) {
    if (row.efficiency >= EFFICIENCY_FLOOR && (efficiencyKnee === null || row.n > efficiencyKnee.n)) {
      efficiencyKnee = row
    }
  })

  /*
   * 기준점은 정의상 효율이 정확히 1.0 이라 **언제나** 바닥선을 넘는다.
   * 그래서 기준점 말고 바닥선을 넘은 점이 하나도 없으면 efficiencyKnee 가
   * 기준점 자신으로 잡히는데, 이건 무릎을 찾은 게 아니라
   * "이 사다리에서는 찾을 수 없다"는 뜻이다. 숫자로 보고하면 안 된다.
   */
  let clearedBesidesAnchor = false
  usable.forEach(function (row) {
    if (row.n !== anchor.n && row.efficiency >= EFFICIENCY_FLOOR) clearedBesidesAnchor = true
  })
  const kneeIsAnchorOnly = efficiencyKnee !== null && efficiencyKnee.n === anchor.n && !clearedBesidesAnchor
  if (kneeIsAnchorOnly) {
    warn('knee-at-anchor', anchor.n === usable[0].n
      ? `기준점(${anchor.n} VU) 말고 효율 ${EFFICIENCY_FLOOR} 이상인 구간이 없다. 기준점은 정의상 효율이 1.0 이므로 이건 무릎을 찾은 게 아니라 **무릎이 사다리보다 아래에 있다**는 뜻이다. \`LADDER\` 를 더 낮게 다시 잡아야 한다.`
      : `기준점(${anchor.n} VU) 말고 효율 ${EFFICIENCY_FLOOR} 이상인 구간이 없는데, 그 기준점이 사다리 한가운데다. 곡선이 단조롭지 않다는 뜻이라 이 실행만으로는 무릎을 판정할 수 없다.`)
  }

  // 2) 처리량 정점: 여기를 넘으면 VU 를 더 넣을수록 오히려 느려진다
  let peak = usable[0]
  let peakIndex = 0
  usable.forEach(function (row, index) {
    if (row.rate > peak.rate) { peak = row; peakIndex = index }
  })

  // 3) 지연 무릎: 응답 시간이 **최저치가 관측된 지점 이후**로 임계치를 처음 넘는 N.
  //    최저치가 4 VU 에서 나왔는데 0번 인덱스부터 훑으면 1 VU 가 선택된다.
  //    부하 증가에 따른 변곡점이 아니라 그보다 앞선 행을 무릎이라 보고하는 역방향 오류다.
  let minIndex = -1
  usable.forEach(function (row, index) {
    if (row.p50 === null) return
    if (minIndex < 0 || row.p50 < usable[minIndex].p50) minIndex = index
  })
  let latencyKnee = null
  const minLatency = minIndex >= 0 ? usable[minIndex].p50 : null
  const minLatencyVu = minIndex >= 0 ? usable[minIndex].n : null
  if (minLatency !== null) {
    for (let i = minIndex + 1; i < usable.length; i += 1) {
      if (usable[i].p50 !== null && usable[i].p50 >= minLatency * LATENCY_KNEE_FACTOR) {
        latencyKnee = usable[i]
        break
      }
    }
  }

  // 4) 부하 생성기가 먼저 꺾였는지: 커넥션 확보 대기가 응답 시간의 20% 를 넘는 첫 지점
  let clientSuspect = null
  usable.forEach(function (row) {
    if (clientSuspect !== null) return
    if (row.blocked95 !== null && row.p95 !== null && row.p95 > 0 && (row.blocked95 / row.p95) >= 0.2) {
      clientSuspect = row
    }
  })

  // 표본 건전성 경고 ---------------------------------------------------------
  let minSuccess = null
  let maxSuccess = null
  usable.forEach(function (row) {
    if (row.successRate === null) return
    if (minSuccess === null || row.successRate < minSuccess) minSuccess = row.successRate
    if (maxSuccess === null || row.successRate > maxSuccess) maxSuccess = row.successRate
  })
  const mixDrift = minSuccess !== null && maxSuccess !== null ? maxSuccess - minSuccess : null
  if (mixDrift !== null && mixDrift > 0.2) {
    warn('work-mix-drift',
      `구간마다 성공률이 ${pct(minSuccess, 1)} ~ ${pct(maxSuccess, 1)} 로 달라진다. ` +
      '409 는 요청 1건, 200 은 claim+release 2건이라 iteration 당 작업량이 함께 변한다. ' +
      `그래서 무릎 판정은 iteration 이 아니라 **${metricLabel()}** 로 계산했다.`,
    )
  }

  let rebound = false
  for (let i = peakIndex + 1; i < usable.length; i += 1) {
    if (usable[i].rate > usable[i - 1].rate * 1.15) rebound = true
  }
  if (rebound) {
    warn('throughput-rebound',
      '정점을 지난 뒤 처리량이 다시 뚜렷하게 오르는 구간이 있다. 포화 곡선이라면 나오지 않는 모양이라 ' +
      `표본 잡음(GC·다른 프로세스)일 가능성이 높다. \`HOLD_SECONDS\`(현재 ${HOLD_SECONDS}초)를 늘리고 반복 측정해야 한다.`,
    )
  }

  let sawBelow = false
  let recovered = false
  usable.forEach(function (row) {
    if (row.efficiency < EFFICIENCY_FLOOR) sawBelow = true
    else if (sawBelow) recovered = true
  })
  if (recovered) {
    warn('efficiency-not-monotonic', '효율이 바닥선 아래로 내려갔다가 더 큰 VU 에서 다시 올라온다. 단조롭지 않은 곡선이라 무릎을 한 점으로 확정하기 전에 반복 측정이 필요하다.')
  }

  return {
    ready: true,
    rows: rows,
    usable: usable,
    anchor: anchor,
    unitRate: unitRate,
    metric: KNEE_METRIC,
    efficiencyKnee: efficiencyKnee,
    kneeIsAnchorOnly: kneeIsAnchorOnly,
    peak: peak,
    peakIndex: peakIndex,
    minLatency: minLatency,
    minLatencyVu: minLatencyVu,
    latencyKnee: latencyKnee,
    clientSuspect: clientSuspect,
    warnings: warnings,
    usl: fitUsl(usable, unitRate),
  }
}

function num(value, digits) {
  return value === null || value === undefined || isNaN(value) ? '-' : value.toFixed(digits)
}

/** 사다리 원시 측정값 표. */
function buildLadderLines(knee) {
  const lines = []
  lines.push('')
  lines.push(PROFILE === 'knee' ? '## VU 사다리 측정값' : '## VU 유지 구간별 지연 (무릎점 탐색)')
  lines.push('')
  lines.push(
    `각 구간을 ${HOLD_SECONDS}초 유지하고 앞 ${SETTLE_SECONDS}초를 버린 뒤 ${MEASURE_SECONDS}초만 집계했다. ` +
    'ramp 상승·하강과 예열 구간은 제외했다.',
  )
  lines.push('')
  lines.push(`| VU (N) | 시도/초 | 요청/초 | 성공/초 | 성공률 | 효율 | p50 | p95 | p99 |`)
  lines.push('| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
  knee.rows.forEach(function (row) {
    const mark = knee.ready && knee.anchor && row.n === knee.anchor.n ? ' ◀기준' : ''
    lines.push(
      `| ${row.n} | ${num(row.attemptRate, 1)} | ${num(row.requestRate, 1)} | ${num(row.goodput, 1)} | ` +
      `${row.successRate === null ? '-' : pct(row.success, row.attempts)} | ${num(row.efficiency, 3)}${mark} | ` +
      `${ms(row.p50)} | ${ms(row.p95)} | ${ms(row.p99)} |`,
    )
  })
  lines.push('')
  lines.push(`> **효율** = (${metricLabel()} ÷ N) ÷ 최적 단위 처리량. 무릎 판정은 \`KNEE_METRIC=${KNEE_METRIC}\` 기준이다.`)
  lines.push('> 기준선은 X(1) 이 아니라 **VU당 처리량이 가장 좋았던 구간**(◀기준)이다. 정의상 효율은 1 을 넘지 않는다.')
  lines.push('> **시도/초**(iteration)는 409 비중에 따라 작업량이 달라지므로 실행끼리 직접 비교하면 안 된다.')
  lines.push('')
  lines.push('| VU (N) | 서버대기 p95 | 커넥션대기 p95 | 리틀법칙 N | VU 대비 |')
  lines.push('| ---: | ---: | ---: | ---: | ---: |')
  knee.rows.forEach(function (row) {
    const ratio = row.little !== null && row.n > 0 ? row.little / row.n : null
    lines.push(`| ${row.n} | ${ms(row.waiting95)} | ${ms(row.blocked95)} | ${num(row.little, 1)} | ${ratio === null ? '-' : `${num(ratio * 100, 0)}%`} |`)
  })
  lines.push('')
  lines.push('> **서버대기**(TTFB)가 늘면 서버가, **커넥션대기**가 늘면 부하 생성기가 병목이다.')
  lines.push('> **리틀법칙 N** = 시도/초 × 평균 응답시간. VU 대비 비율이 낮으면 그만큼 VU 가 claim 밖(반납 호출 등)에서 시간을 쓴 것이다.')
  return lines
}

/**
 * 무릎을 한 숫자로 확정하기 위한 다음 사다리 제안.
 * 배수 사다리는 "무릎이 A 와 B 사이"까지만 알려 주므로 그 사이를 등간격으로 다시 훑는다.
 * 무릎 바로 아래·위로 한 칸씩 넓혀 경계가 실제로 그 구간 안에 있는지도 확인한다.
 */
function suggestRefinement(knee) {
  // 기준점이 그대로 무릎으로 잡힌 경우, 좁힐 구간은 위가 아니라 **아래**다.
  if (knee.kneeIsAnchorOnly) {
    const top = knee.anchor.n
    if (top <= 2) return null
    const ladder = []
    for (let value = 1; value <= top; value += Math.max(1, Math.round(top / 5))) {
      if (ladder.indexOf(value) === -1) ladder.push(value)
    }
    if (ladder.indexOf(top) === -1) ladder.push(top)
    return { from: 1, to: top, ladder: ladder, below: true }
  }
  if (knee.efficiencyKnee === null) return null
  let index = -1
  knee.usable.forEach(function (row, i) { if (row.n === knee.efficiencyKnee.n) index = i })
  if (index < 0 || index + 1 >= knee.usable.length) return null

  const from = knee.usable[index].n
  const to = knee.usable[index + 1].n
  if (to - from < 2) return null

  const steps = Math.min(5, to - from)
  const step = Math.max(1, Math.round((to - from) / steps))
  const ladder = []
  for (let value = Math.max(1, from - step); value <= to + step; value += step) {
    if (ladder.indexOf(value) === -1) ladder.push(value)
  }
  if (ladder.indexOf(to) === -1) ladder.push(to)
  ladder.sort(function (a, b) { return a - b })
  return { from: from, to: to, ladder: ladder }
}

/** 무릎점 판정. */
function buildKneeLines(knee) {
  const lines = []
  lines.push('')
  lines.push('## 무릎점 판정')
  lines.push('')

  knee.warnings.forEach(function (warning) {
    lines.push(`> ⚠️ ${warning.text}`)
    lines.push('')
  })

  lines.push(`- 판정 지표: **${metricLabel()}** (\`KNEE_METRIC=${KNEE_METRIC}\`)`)
  lines.push(`- 최적 단위 처리량: **${num(knee.unitRate, 1)} ${metricLabel()}/VU** (${knee.anchor.n} VU 구간에서 관측)`)
  lines.push(
    knee.kneeIsAnchorOnly
      ? `- **효율 무릎**: 판정 불가 — 기준점(${knee.anchor.n} VU) 외에 효율 ${EFFICIENCY_FLOOR} 이상인 구간이 없다`
      : knee.efficiencyKnee
        ? `- **효율 무릎**: ${knee.efficiencyKnee.n} VU — 효율 ${EFFICIENCY_FLOOR} 이상인 가장 큰 지점 (효율 ${num(knee.efficiencyKnee.efficiency, 3)})`
        : `- **효율 무릎**: 관측 못 함 — 모든 구간이 효율 ${EFFICIENCY_FLOOR} 미만이다`,
  )
  lines.push(`- **처리량 정점**: ${knee.peak.n} VU — ${num(knee.peak.rate, 1)} ${metricLabel()}`)
  lines.push(knee.latencyKnee
    ? `- **지연 무릎**: ${knee.latencyKnee.n} VU — p50 이 최저치(${knee.minLatencyVu} VU, ${ms(knee.minLatency)})의 ${LATENCY_KNEE_FACTOR}배를 처음 넘은 지점 (${ms(knee.latencyKnee.p50)})`
    : `- **지연 무릎**: 관측 못 함 (p50 이 최저치의 ${LATENCY_KNEE_FACTOR}배에 도달하지 않음)`)

  lines.push('')
  lines.push('### USL (Universal Scalability Law) 적합')
  lines.push('')
  const usl = knee.usl
  if (!usl) {
    lines.push('- 표본이 3점 미만이라 적합하지 못했다.')
  } else if (!usl.valid) {
    lines.push(`- σ = ${num(usl.sigma, 5)} / κ = ${num(usl.kappa, 7)} / R² = ${num(usl.r2, 4)} (표본 ${usl.sampleCount}점)`)
    lines.push('- **이 적합은 폐기한다.** 판정 후보에서 제외했다. 이유:')
    usl.reasons.forEach(function (reason) { lines.push(`  - ${reason}`) })
    lines.push('- 실측 표를 그대로 쓰고, 표본을 안정시킨 뒤 다시 적합해야 한다.')
  } else {
    lines.push(`- σ(직렬화 계수) = **${num(usl.sigma, 5)}** — 요청의 약 ${num(usl.sigma * 100, 2)}% 가 줄 서서 처리된다`)
    lines.push(`- κ(일관성 계수) = **${num(usl.kappa, 7)}**`)
    lines.push(`- 적합도 R² = ${num(usl.r2, 4)} (표본 ${usl.sampleCount}점)`)
    lines.push(`- **N\\* = ${num(usl.nStar, 1)} VU** — 모델이 예측하는 처리량 정점. 예상 최대 ${num(usl.peakRate, 1)} ${metricLabel()}.`)
  }

  lines.push('')
  lines.push('### 결론')
  lines.push('')
  const candidates = []
  if (knee.efficiencyKnee && !knee.kneeIsAnchorOnly) candidates.push(knee.efficiencyKnee.n)
  if (usl && usl.valid && usl.nStar !== null) candidates.push(usl.nStar)
  candidates.push(knee.peak.n)
  if (knee.latencyKnee) candidates.push(knee.latencyKnee.n)
  let recommended = candidates[0]
  candidates.forEach(function (value) { if (value < recommended) recommended = value })

  if (knee.kneeIsAnchorOnly) {
    lines.push(`- **이 실행으로는 무릎을 특정할 수 없다.** 효율 무릎이 기준점 자신으로 잡혔다(위 경고 참고).`)
    lines.push(`- 아래로만 말할 수 있다: 무릎은 **${knee.anchor.n} VU 이하**다.`)
  }
  lines.push(`- 유효한 판정값(${knee.kneeIsAnchorOnly ? '' : '효율 무릎 / '}처리량 정점 / 지연 무릎${usl && usl.valid ? ' / USL N\\*' : ''}) 중 가장 보수적인 값은 **${num(recommended, 0)} VU** 다.`)
  lines.push('- 이 값이 "이 서버가 응답 시간을 희생하지 않고 동시에 감당할 수 있는 요청 수"의 현실적 상한이다.')

  const refine = suggestRefinement(knee)
  if (refine) {
    lines.push(refine.below
      ? `- 다음 실행은 **${refine.to} VU 아래**를 훑어야 한다.`
      : `- 무릎은 **${refine.from} ~ ${refine.to} VU** 사이에 있다. 배수 사다리는 여기까지만 좁혀 준다.`)
    lines.push(`- 한 자리로 확정하려면: \`LADDER=${refine.ladder.join(',')} REPEATS=3 ./k6/run-knee.sh\``)
  }

  if (knee.clientSuspect) {
    lines.push(`- ⚠️ ${knee.clientSuspect.n} VU 구간부터 커넥션 확보 대기(${ms(knee.clientSuspect.blocked95)})가 응답 시간(${ms(knee.clientSuspect.p95)})의 20% 를 넘었다. **부하 생성기(k6)가 먼저 포화**했을 수 있으니 이 지점 이후 값은 서버의 한계로 단정하면 안 된다.`)
  } else {
    lines.push('- 커넥션 확보 대기는 전 구간에서 미미했다. 부하 생성기의 HTTP 커넥션 확보는 병목이 아니었다.')
  }
  lines.push('- 이 리포트는 **어디서** 꺾였는지만 말한다. 조건부 UPDATE·Hikari·MySQL 중 무엇이 원인인지는 서버 리소스 지표를 함께 봐야 한다.')
  return lines
}

/** 비교 스크립트가 읽을 기계 판독용 결과. */
function buildKneePayload(knee) {
  const rows = []
  knee.usable.forEach(function (row) {
    rows.push({
      n: row.n,
      attempts: row.attempts,
      success: row.success,
      conflict: row.conflict,
      priorityBlocked: row.priorityBlocked,
      releases: row.releases,
      attemptRate: row.attemptRate,
      requestRate: row.requestRate,
      goodput: row.goodput,
      successRate: row.successRate,
      rate: row.rate,
      efficiency: row.efficiency,
      avg: row.avg,
      p50: row.p50,
      p95: row.p95,
      p99: row.p99,
      waiting95: row.waiting95,
      blocked95: row.blocked95,
      little: row.little,
    })
  })
  return {
    label: __ENV.LABEL || __ENV.OUT || (CONTENTION ? 'contended' : 'isolated'),
    profile: PROFILE,
    contention: CONTENTION,
    metric: KNEE_METRIC,
    plans: PLAN_COUNT,
    drivers: DRIVER_COUNT,
    ladder: PLATEAU_TARGETS,
    holdSeconds: HOLD_SECONDS,
    settleSeconds: SETTLE_SECONDS,
    measureSeconds: MEASURE_SECONDS,
    efficiencyFloor: EFFICIENCY_FLOOR,
    anchorVu: knee.anchor.n,
    unitRate: knee.unitRate,
    warnings: knee.warnings,
    rows: rows,
    usl: knee.usl === null ? null : {
      sigma: knee.usl.sigma,
      kappa: knee.usl.kappa,
      r2: knee.usl.r2,
      valid: knee.usl.valid,
      reasons: knee.usl.reasons,
      nStar: knee.usl.nStar,
      peakRate: knee.usl.peakRate,
      sampleCount: knee.usl.sampleCount,
    },
    knees: {
      efficiency: knee.kneeIsAnchorOnly || knee.efficiencyKnee === null ? null : knee.efficiencyKnee.n,
      anchorOnly: knee.kneeIsAnchorOnly,
      throughputPeak: knee.peak.n,
      latency: knee.latencyKnee === null ? null : knee.latencyKnee.n,
      clientSuspect: knee.clientSuspect === null ? null : knee.clientSuspect.n,
    },
  }
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
  lines.push(`- 경합: ${CONTENTION ? '있음 (모든 VU 가 소수 업무를 무작위로 노림)' : '없음 (VU 마다 전용 업무)'}`)
  lines.push(IS_LADDER
    ? `- VU 사다리: ${PLATEAU_TARGETS.join(' → ')} (각 ${HOLD_SECONDS}초 유지, 앞 ${SETTLE_SECONDS}초를 버리고 ${MEASURE_SECONDS}초만 집계) / 기사 계정: ${DRIVER_COUNT} / 업무: ${PLAN_COUNT}`
    : `- VU: ${VU_COUNT} / 기사 계정: ${DRIVER_COUNT} / 업무: ${PLAN_COUNT}`)
  if (HAS_WARMUP) {
    lines.push(`- 예열: ${WARMUP_VUS} VU 로 ${WARMUP_SECONDS}초 (집계 제외)`)
  }
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
  const loadSec = IS_LADDER
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
  if (IS_LADDER) {
    const knee = analyzeKnee(collectPlateauRows(data))
    buildLadderLines(knee).forEach(function (line) { lines.push(line) })
    if (PROFILE === 'knee' && knee.ready) {
      buildKneeLines(knee).forEach(function (line) { lines.push(line) })
    }
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
  const out = {}
  out.stdout = `\n${report}\n`
  out[`k6/summary${OUT_SUFFIX}.md`] = report
  out[`k6/summary${OUT_SUFFIX}.json`] = JSON.stringify(data, null, 2)
  if (PROFILE === 'knee') {
    const knee = analyzeKnee(collectPlateauRows(data))
    if (knee.ready) {
      // 비교 스크립트(knee-compare.mjs)가 읽는 기계 판독용 결과.
      out[`k6/knee${OUT_SUFFIX}.json`] = JSON.stringify(buildKneePayload(knee), null, 2)
    }
  }
  return out
}
