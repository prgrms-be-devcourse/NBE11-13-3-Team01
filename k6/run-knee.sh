#!/usr/bin/env bash
#
# 무릎점(knee) 측정 — 같은 VU 사다리를 경합 O / 경합 X 로 돌리고 비교한다.
#
#   ./k6/run-knee.sh                          # 기본 사다리, 각 1회
#   REPEATS=3 ./k6/run-knee.sh                # 3회 반복 후 중앙값으로 판정 (권장)
#   LADDER=6,8,10,12,14,16,18 REPEATS=3 ./k6/run-knee.sh
#   PROM=1 ./k6/run-knee.sh                   # Grafana 실시간 관측까지 함께
#
# 반복마다 경합/비경합 실행 **순서를 뒤집는다.** 항상 같은 순서로 돌리면
# 먼저 도는 쪽이 늘 차가운 JVM 을, 나중 쪽이 늘 데워진 JVM 을 만나 편향이 한 방향으로 쌓인다.
#
# 반드시 저장소 루트에서 실행한다. k6 는 './k6/...' 로 시작하는 경로만 로컬 파일로 인식한다.
set -euo pipefail

cd "$(dirname "$0")/.."

LADDER="${LADDER:-1,2,4,8,16,32,64,128,256}"
HOLD_SECONDS="${HOLD_SECONDS:-90}"
SETTLE_SECONDS="${SETTLE_SECONDS:-15}"
CONTENDED_PLANS="${CONTENDED_PLANS:-3}"
REPEATS="${REPEATS:-1}"
COOLDOWN="${COOLDOWN:-30}"
STAMP="$(date +%Y%m%d-%H%M)"

# macOS 기본 bash 3.2 는 set -u 에서 빈 배열을 "${arr[@]}" 로 펼치면
# "unbound variable" 로 죽는다. ${arr[@]+"${arr[@]}"} 가 3.2 에서도 안전한 관용구다.
K6_FLAGS=()
if [ "${PROM:-0}" = "1" ]; then
  K6_FLAGS+=(-o experimental-prometheus-rw)
fi

run_one() {
  local mode="$1" repeat="$2"
  local contention plans label
  if [ "$mode" = "contended" ]; then
    contention=on
    plans="$CONTENDED_PLANS"
  else
    contention=off
    plans=""   # 비워 두면 스크립트가 최대 VU 수만큼 만든다
  fi
  label="$mode"

  echo
  echo "==> [$repeat/$REPEATS] ${mode} (경합 ${contention})"
  env \
    PROFILE=knee \
    CONTENTION="$contention" \
    ${plans:+PLANS="$plans"} \
    LADDER="$LADDER" \
    HOLD_SECONDS="$HOLD_SECONDS" \
    SETTLE_SECONDS="$SETTLE_SECONDS" \
    LABEL="$label" \
    OUT="${mode}-r${repeat}" \
    k6 run ${K6_FLAGS[@]+"${K6_FLAGS[@]}"} ./k6/claim-contention.js 2>&1 | tee "k6/run-knee-${mode}-r${repeat}-${STAMP}.log"
}

echo "==> 사다리: ${LADDER}"
echo "==> 구간 유지 ${HOLD_SECONDS}초 (앞 ${SETTLE_SECONDS}초 버림) / 반복 ${REPEATS}회"

# 예전 실행이 남긴 knee JSON 도 지운다. knee-compare 의 자동 수집이 이를 같은 label 로 묶어
# 중앙값을 오염시킨다.
rm -f k6/knee-contended*.json k6/knee-isolated*.json

for ((repeat = 1; repeat <= REPEATS; repeat++)); do
  if ((repeat % 2 == 1)); then
    order=(contended isolated)
  else
    order=(isolated contended)
  fi
  for mode in "${order[@]}"; do
    run_one "$mode" "$repeat"
    echo "==> 서버를 ${COOLDOWN}초 쉬게 둔다 (GC·커넥션 풀 회복)"
    sleep "$COOLDOWN"
  done
done

echo
echo "==> 비교 리포트"
node k6/knee-compare.mjs k6/knee-contended-r*.json k6/knee-isolated-r*.json \
  | tee "k6/knee-compare-${STAMP}.md"

echo
echo "결과 파일"
echo "  k6/summary-contended-r*.md   경합 있음 실행별 리포트 (무릎점 판정 포함)"
echo "  k6/summary-isolated-r*.md    경합 없음 실행별 리포트"
echo "  k6/knee-compare-${STAMP}.md  반복 중앙값 기준 비교"
