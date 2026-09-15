#!/usr/bin/env bash
#
# k6 스크립트 문법 검사.
#
# ⚠️ `node --check` 를 그냥 쓰면 안 된다. Node 22 기준으로 `import` 가 있는 파일은
# ESM 으로 판정하고 **파싱 자체를 건너뛰어 항상 exit 0 을 돌려준다.**
# 실제로 `val x = 1` 같은 명백한 오류도 그대로 통과한다. 직접 확인:
#
#     printf 'import x from "y"\nval broken = 1\n' > /tmp/t.js && node --check /tmp/t.js; echo $?   # 0
#     printf 'val broken = 1\n'                    > /tmp/t.js && node --check /tmp/t.js; echo $?   # 1
#
# 그래서 ESM 구문만 걷어낸 사본을 만들어 CJS 로 실제 파싱시킨다.
set -u

targets=("$@")
if [ ${#targets[@]} -eq 0 ]; then
  targets=("$(dirname "$0")/claim-contention.js" "$(dirname "$0")/knee-compare.mjs")
fi

status=0
for file in "${targets[@]}"; do
  tmp="$(mktemp "/tmp/$(basename "$file").XXXXXX.cjs")"
  sed -e 's/^import .*$//' \
      -e 's/^export default function/function __default/' \
      -e 's/^export //' "$file" > "$tmp"
  if node --check "$tmp"; then
    echo "OK   $file"
  else
    echo "FAIL $file"
    status=1
  fi
  rm -f "$tmp" 2>/dev/null || true
done
exit $status
