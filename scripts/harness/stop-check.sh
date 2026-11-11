#!/usr/bin/env bash
# Claude Code Stop 훅. 실패하면 exit 2 + stderr로 원인을 돌려줘 종료를 막는다.
cd "$(dirname "$0")/../.." || exit 0
fail=0
bash scripts/harness/doc-lint.sh 1>&2 || fail=1
bash scripts/harness/scope-check.sh 1>&2 || fail=1
if [ -x ./gradlew ]; then
  started=$(date +%s)
  if ! ./gradlew check -q --console=plain 1>&2; then
    fail=1
    # 콘솔 출력의 한글이 깨지는 환경(Windows, Java 17)을 위해 실패 내용을 XML 리포트에서 다시 출력한다
    python scripts/harness/test_failures.py "$started"
  fi
else
  echo "gradlew 없음: 빌드 검사 건너뜀" 1>&2
fi
[ "$fail" -eq 0 ] || exit 2
exit 0
