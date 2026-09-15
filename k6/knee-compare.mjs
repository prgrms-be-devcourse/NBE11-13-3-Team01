#!/usr/bin/env node
/**
 * 무릎점 비교 리포트.
 *
 * `PROFILE=knee` 실행이 남긴 k6/knee-*.json 을 읽어 비교한다.
 * 같은 label 의 파일이 여러 개면 반복 측정으로 보고 **중앙값**을 쓴다.
 *
 *   node k6/knee-compare.mjs                       # k6/knee-*.json 자동 수집
 *   node k6/knee-compare.mjs k6/knee-contended-r1.json k6/knee-contended-r2.json ...
 *
 * 이 스크립트는 무릎 판정을 **다시 계산하지 않는다.** 판정 규칙의 단일 출처는
 * claim-contention.js 이고, 여기서는 각 실행이 내놓은 값을 모아 비교만 한다.
 */
import fs from 'node:fs'
import path from 'node:path'

const args = process.argv.slice(2)
const files = args.length > 0 ? args : autoDiscover()

function autoDiscover() {
  const dir = fs.existsSync('k6') ? 'k6' : '.'
  return fs.readdirSync(dir)
    .filter((name) => /^knee.*\.json$/.test(name))
    .map((name) => path.join(dir, name))
    .sort()
}

if (files.length === 0) {
  console.error('읽을 knee JSON 이 없다. 먼저 PROFILE=knee 로 실행해야 한다.')
  console.error('  ./k6/run-knee.sh')
  process.exit(1)
}

const fmt = (v, d = 1) => (v === null || v === undefined || Number.isNaN(v) ? '-' : Number(v).toFixed(d))
const pctOf = (v) => (v === null || v === undefined ? '-' : `${(v * 100).toFixed(2)}%`)
const vuOf = (v) => (v === null || v === undefined ? '특정 못 함' : `${Math.round(v)} VU`)

function median(values) {
  const clean = values.filter((v) => v !== null && v !== undefined && !Number.isNaN(v)).sort((a, b) => a - b)
  if (clean.length === 0) return null
  const mid = Math.floor(clean.length / 2)
  return clean.length % 2 ? clean[mid] : (clean[mid - 1] + clean[mid]) / 2
}

// ---------------------------------------------------------------- 그룹 구성
const groups = new Map()
for (const file of files) {
  const payload = JSON.parse(fs.readFileSync(file, 'utf8'))
  const key = payload.label || (payload.contention ? 'contended' : 'isolated')
  if (!groups.has(key)) groups.set(key, [])
  groups.get(key).push({ file, payload })
}

/** 같은 label 의 반복 실행을 VU별 중앙값으로 접는다. */
function fold(key, entries) {
  const first = entries[0].payload
  const ladder = first.ladder
  const numeric = ['attemptRate', 'requestRate', 'goodput', 'successRate', 'rate', 'efficiency', 'p50', 'p95', 'p99', 'waiting95', 'blocked95', 'little']
  const rows = ladder.map((n) => {
    const row = { n }
    for (const field of numeric) {
      row[field] = median(entries.map((e) => (e.payload.rows.find((r) => r.n === n) || {})[field]))
    }
    // 실행 간 최대/최소 배수. 이 값이 크면 중앙값을 몇으로 읽든 판정이 성립하지 않는다.
    const observed = entries
      .map((e) => (e.payload.rows.find((r) => r.n === n) || {}).requestRate)
      .filter((v) => typeof v === 'number' && v > 0)
    row.spread = observed.length > 1 ? Math.max(...observed) / Math.min(...observed) : null
    return row
  })
  // 같은 경고가 반복 실행마다 숫자만 다르게 나오므로 code 로 중복을 제거한다.
  const warnings = new Map()
  entries.forEach((e) => (e.payload.warnings || []).forEach((w) => {
    const entry = typeof w === 'string' ? { code: w, text: w } : w
    if (!warnings.has(entry.code)) warnings.set(entry.code, entry.text)
  }))
  return {
    key,
    runs: entries.length,
    files: entries.map((e) => e.file),
    contention: first.contention,
    metric: first.metric,
    plans: first.plans,
    ladder,
    measureSeconds: first.measureSeconds,
    efficiencyFloor: first.efficiencyFloor,
    rows,
    warnings: [...warnings.values()],
    anchorVu: median(entries.map((e) => e.payload.anchorVu)),
    knees: {
      efficiency: median(entries.map((e) => e.payload.knees.efficiency)),
      throughputPeak: median(entries.map((e) => e.payload.knees.throughputPeak)),
      latency: median(entries.map((e) => e.payload.knees.latency)),
      clientSuspect: median(entries.map((e) => e.payload.knees.clientSuspect)),
    },
    // 유효하지 않다고 표시된 적합은 아예 집계하지 않는다.
    usl: (() => {
      const ok = entries.map((e) => e.payload.usl).filter((u) => u && u.valid)
      if (ok.length === 0) return { valid: false, discarded: entries.length }
      return {
        valid: true,
        of: ok.length,
        sigma: median(ok.map((u) => u.sigma)),
        kappa: median(ok.map((u) => u.kappa)),
        r2: median(ok.map((u) => u.r2)),
        nStar: median(ok.map((u) => u.nStar)),
      }
    })(),
    // 성공률이 구간에 따라 얼마나 변하는지. 실행끼리 iteration 처리량을 비교해도 되는지의 판단 근거.
    successSpread: (() => {
      const values = rows.map((r) => r.successRate).filter((v) => v !== null)
      return values.length ? Math.max(...values) - Math.min(...values) : null
    })(),
  }
}

const folded = [...groups.entries()].map(([key, entries]) => fold(key, entries))
// 경합 있는 쪽을 왼쪽에 세운다.
folded.sort((a, b) => Number(b.contention) - Number(a.contention))

const out = []
out.push('# 무릎점 비교')
out.push('')
out.push(`- 생성: ${new Date().toISOString()}`)
folded.forEach((g) => {
  out.push(`- **${g.contention ? '경합 있음' : '경합 없음'}** — 반복 ${g.runs}회 / 업무 ${g.plans}건 / 사다리 ${g.ladder.join(',')} / 구간당 ${g.measureSeconds}초 집계`)
  g.files.forEach((f) => out.push(`  - \`${f}\``))
})
if (folded.some((g) => g.runs > 1)) {
  out.push('')
  out.push('> 반복 실행은 VU별 **중앙값**으로 접었다. 무릎 값도 각 실행이 낸 판정의 중앙값이다.')
}
if (new Set(folded.map((g) => g.ladder.join(','))).size > 1) {
  out.push('')
  out.push('> ⚠️ 사다리가 서로 다르다. VU별 비교표는 공통 VU 만 표시한다.')
}

// ---------------------------------------------------------------- 요약
out.push('')
out.push('## 요약')
out.push('')
out.push(`| 항목 | ${folded.map((g) => (g.contention ? '경합 있음' : '경합 없음')).join(' | ')} |`)
out.push(`| --- | ${folded.map(() => '---:').join(' | ')} |`)
const row = (label, pick) => out.push(`| ${label} | ${folded.map(pick).join(' | ')} |`)
row('판정 지표', (g) => `\`${g.metric}\``)
row('선형 기준 구간', (g) => vuOf(g.anchorVu))
row(`효율 무릎 (효율 ${folded[0].efficiencyFloor} 기준)`, (g) => vuOf(g.knees.efficiency))
row('처리량 정점', (g) => vuOf(g.knees.throughputPeak))
row('지연 무릎', (g) => vuOf(g.knees.latency))
row('USL N*', (g) => (g.usl.valid ? vuOf(g.usl.nStar) : '폐기 (유효 적합 없음)'))
row('σ (직렬화)', (g) => (g.usl.valid ? fmt(g.usl.sigma, 5) : '-'))
row('구간별 성공률 편차', (g) => (g.successSpread === null ? '-' : pctOf(g.successSpread)))

// ---------------------------------------------------------------- VU별
if (folded.length === 2) {
  const [contended, isolated] = folded
  const common = contended.ladder.filter((n) => isolated.ladder.includes(n))

  out.push('')
  out.push('## VU별 비교')
  out.push('')
  out.push('두 실행은 iteration 의 구성이 다르다. 경합 실행에서 409 로 끝난 iteration 은 요청 1건이고,')
  out.push('비경합 실행의 iteration 은 전부 claim + release 2건이다. 그래서 **iteration(시도/초)은 비교하지 않고**')
  out.push('요청 한 건 단위인 **요청/초**와, 업무 관점의 **성공/초**만 나란히 둔다.')
  out.push('')
  out.push('| VU | 요청/초 경합 | 편차 | 요청/초 무경합 | 편차 | 요청 처리량 비 | 성공/초 경합 | 성공/초 무경합 | 성공률 경합 | p95 경합 | p95 무경합 |')
  out.push('| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |')
  common.forEach((n) => {
    const a = contended.rows.find((r) => r.n === n)
    const b = isolated.rows.find((r) => r.n === n)
    if (!a || !b) return
    const ratio = b.requestRate > 0 ? `${(a.requestRate / b.requestRate).toFixed(2)}배` : '-'
    const spreadOf = (row) => (row.spread === null ? '-' : `${row.spread.toFixed(1)}배`)
    out.push(
      `| ${n} | ${fmt(a.requestRate)} | ${spreadOf(a)} | ${fmt(b.requestRate)} | ${spreadOf(b)} | ${ratio} | ` +
      `${fmt(a.goodput)} | ${fmt(b.goodput)} | ${pctOf(a.successRate)} | ${fmt(a.p95)}ms | ${fmt(b.p95)}ms |`,
    )
  })

  // ---------------------------------------------------------------- 해석
  out.push('')
  out.push('## 해석')
  out.push('')

  const kA = contended.knees.efficiency
  const kB = isolated.knees.efficiency
  if (kA !== null && kB !== null) {
    if (Math.abs(kA - kB) < 1e-9) {
      out.push(`- 두 실행의 효율 무릎이 **${vuOf(kA)} 로 같다.** 이 사다리 해상도에서는 경합 유무가 무릎 위치를 바꾸지 못했다는 뜻이고,`)
      out.push('  꺾이는 원인이 락 경합이 아니라 두 경우에 공통인 무언가(커넥션 풀, 요청 스레드, CPU)일 가능성이 크다.')
      out.push('  경합 비용을 보려면 무릎 주변을 촘촘히 훑은 뒤 다시 비교해야 한다.')
    } else if (kB > kA) {
      out.push(`- 경합이 없으면 **${vuOf(kB)}** 까지 선형으로 확장하지만, 같은 업무를 두고 다투게 하면 **${vuOf(kA)}** 에서 꺾인다 (약 ${fmt(kB / kA, 1)}배 차이).`)
      out.push('- 이 차이가 선착순 경합 자체의 비용이다. 업무 풀이 얕을수록 더 벌어진다.')
    } else {
      out.push(`- 경합 실행의 무릎(**${vuOf(kA)}**)이 비경합(**${vuOf(kB)}**)보다 크게 나왔다. 정상적인 순서가 아니므로 표본 잡음을 먼저 의심해야 한다.`)
    }
  } else {
    out.push('- 최소 한쪽에서 선형 구간을 잡지 못했다. 해당 실행의 `LADDER` 를 다시 잡아야 한다.')
  }

  const worstSpread = Math.max(
    ...[...contended.rows, ...isolated.rows].map((r) => (r.spread === null ? 0 : r.spread)),
  )
  if (worstSpread >= 2) {
    out.push(`- ⚠️ **같은 설정을 반복했는데 실행 간 처리량이 최대 ${worstSpread.toFixed(1)}배까지 벌어진다.**`)
    out.push('  판정이 기대는 차이(효율 바닥선)보다 노이즈가 크다는 뜻이라, 무릎을 한 자리 숫자로 확정할 수 없다.')
    out.push('  서버·DB·k6 를 같은 기기에서 돌리는 한 이 편차는 줄지 않는다. 방향성(어느 쪽이 먼저 꺾이는가)만 근거로 쓴다.')
  }

  const spread = contended.successSpread
  if (spread !== null && spread > 0.2) {
    out.push(`- ⚠️ 경합 실행의 성공률이 구간에 따라 ${pctOf(spread)} 만큼 움직인다. **두 실행의 절대 처리량 차이를 "경합 손실"이라고 부를 수 없다.**`)
    out.push('  성공률이 낮은 구간은 빠른 409 경로가 대부분이라 iteration 당 작업량이 저절로 가벼워지기 때문이다.')
    out.push('  비교에 쓸 수 있는 것은 요청 단위 처리량과 무릎 위치뿐이다.')
  }

  if (contended.usl.valid && isolated.usl.valid) {
    out.push(`- σ 는 ${fmt(isolated.usl.sigma, 5)} → ${fmt(contended.usl.sigma, 5)} 로 ${contended.usl.sigma >= isolated.usl.sigma ? '늘었다' : '줄었다'}. σ 는 "줄 서서 처리되는 비율"이라, 늘었다면 조건부 UPDATE·행 잠금이 실제 직렬화 지점이 됐다는 뜻이다.`)
  } else {
    out.push('- USL 적합이 유효하지 않아 σ·κ 로 원인을 나누지는 못했다. 표본을 안정시킨 뒤(유지 시간 ↑, 반복 측정) 다시 봐야 한다.')
  }

  folded.forEach((g) => {
    g.warnings.forEach((w) => out.push(`- (${g.contention ? '경합 있음' : '경합 없음'}) ⚠️ ${w.replace(/\*\*/g, '')}`))
  })
  out.push('- 두 실행 모두 서버·DB·k6 가 같은 기기에서 돌았다면, 절대 수치가 아니라 **두 값의 차이**만 근거로 쓴다.')
}

out.push('')
console.log(out.join('\n'))
