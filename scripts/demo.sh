#!/usr/bin/env bash
# 로컬 데모 실행: 인프라(docker compose) → 앱 5개(demo 프로필) → 프론트 개발 서버.
#   bash scripts/demo.sh        시작 (Ctrl+C로 프론트를 끄면 앱도 함께 끈다)
#   bash scripts/demo.sh stop   남아 있는 앱 프로세스 정리
#   MOCK_PORT=28090 FRONT_PORT=5180 bash scripts/demo.sh   포트를 다른 프로그램이 쓰고 있을 때 바꾸기
# 로그: build/demo/<app>.log
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT="$ROOT/build/demo"
APPS=(mock-servers inbound-api worker reconciler admin-api)
MOCK_PORT="${MOCK_PORT:-8090}"
MOCK="http://localhost:$MOCK_PORT"
FRONT_PORT="${FRONT_PORT:-5173}"

stop_apps() {
  local stopped=0
  for f in "$OUT"/*.pid; do
    [ -f "$f" ] || continue
    kill "$(cat "$f")" 2>/dev/null && stopped=1
    rm -f "$f"
  done
  [ "$stopped" = 1 ] && echo "앱을 껐습니다."
  return 0
}

alive() { # 앱 이름 — 띄운 프로세스가 아직 살아 있는가
  [ -f "$OUT/$1.pid" ] && kill -0 "$(cat "$OUT/$1.pid")" 2>/dev/null
}

if [ "${1:-}" = "stop" ]; then
  stop_apps
  exit 0
fi

port_free() { # 포트 — 방금 끈 앱이 놓아줄 시간을 주고, 그래도 응답하면 시작하지 않는다
  for _ in $(seq 1 15); do
    curl -s -o /dev/null --max-time 2 "http://localhost:$1/" || return 0
    sleep 1
  done
  echo "포트 $1을 이미 다른 프로세스가 쓰고 있습니다. 끄거나 MOCK_PORT, FRONT_PORT로 바꾸세요." >&2
  exit 1
}

responds() { # URL [문구] — 응답이 오고, 문구가 있으면 본문에 그 문구가 있다
  if [ -z "$2" ]; then
    curl -s -o /dev/null "$1"
  else
    local body
    body=$(curl -s "$1" 2>/dev/null) || return 1
    [[ "$body" == *"$2"* ]]
  fi
}

fail() { # 이름
  echo "  $1 시작 실패. 로그: $OUT/$1.log" >&2
  tail -n 20 "$OUT/$1.log" >&2 || true
  return 1
}

wait_http() { # 이름 URL [응답에 있어야 할 문구]
  for _ in $(seq 1 90); do
    alive "$1" || fail "$1" || return 1
    if responds "$2" "${3:-}"; then
      echo "  $1 준비됨"
      return 0
    fi
    sleep 1
  done
  fail "$1"
}

wait_log() { # 이름 문구
  for _ in $(seq 1 90); do
    alive "$1" || fail "$1" || return 1
    if grep -q "$2" "$OUT/$1.log" 2>/dev/null; then
      echo "  $1 준비됨"
      return 0
    fi
    sleep 1
  done
  fail "$1"
}

mkdir -p "$OUT"
stop_apps
for port in "$MOCK_PORT" 8081 8083 "$FRONT_PORT"; do port_free "$port"; done

echo "1/4 인프라 (MySQL, Redis, LocalStack)"
docker compose up -d --wait

echo "2/4 앱 빌드"
./gradlew -q $(printf ':%s:bootJar ' "${APPS[@]}")

echo "3/4 앱 시작 (demo 프로필)"
trap stop_apps EXIT
for app in "${APPS[@]}"; do
  jar=$(ls "$app"/build/libs/*.jar | grep -v -- '-plain.jar' | head -1)
  args=(--spring.profiles.active=demo --app.shopby.base-url="$MOCK" --app.tpl.base-url="$MOCK")
  [ "$app" = mock-servers ] && args+=(--server.port="$MOCK_PORT")
  java -jar "$jar" "${args[@]}" > "$OUT/$app.log" 2>&1 &
  echo $! > "$OUT/$app.pid"
done
wait_http mock-servers "$MOCK/_mock/demo/state" '"shopby"'
wait_http inbound-api http://localhost:8081/
wait_log worker "Started WorkerApplication"
wait_log reconciler "Started ReconcilerApplication"
wait_http admin-api http://localhost:8083/admin/overview

echo "4/4 프론트 (http://localhost:$FRONT_PORT)"
cd frontend
[ -d node_modules ] || npm ci
MOCK_PORT="$MOCK_PORT" FRONT_PORT="$FRONT_PORT" npm run dev
