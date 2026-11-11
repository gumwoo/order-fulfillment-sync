# order-fulfillment-sync

쇼핑몰(샵바이) 주문 상태와 3PL(파스토 규칙) 출고 상태를 맞추는 주문·출고 동기화 허브.

> **mock 기반 프로젝트입니다.** 실제 샵바이 서버 API는 앱 등록·심사가 필요해서 쓰지 않았습니다.
> 샵바이는 공개 OpenAPI 스펙과 웹훅 가이드, 파스토는 공개 연동 가이드의 규칙을 그대로 따르는 mock 서버(`mock-servers`)로 검증했습니다.
> 확인한 외부 동작과 mock이 가정한 동작은 [docs/references/external-facts.md](docs/references/external-facts.md)에 나눠 적었습니다.
> 성능·복구 수치는 모두 이 mock과 로컬 PC 기준의 실측값입니다 ([docs/measurements.md](docs/measurements.md)).

## 풀려는 문제

| 문제 | 원인 | 이 프로젝트의 처리 |
|---|---|---|
| 웹훅 유실 | 샵바이는 실패한 웹훅을 재전송하지 않는다 | 실패 웹훅 조회 + STATUS_CHANGE 재조회 + 대사 배치 ([ADR-0002](docs/adr/0002-polling-is-source-of-truth.md)) |
| 웹훅 중복 | 같은 변경이 웹훅·실패 웹훅·재조회로 여러 번 들어온다 (이벤트 ID 없음) | 직접 만든 멱등 키를 DB UNIQUE로 ([ADR-0001](docs/adr/0001-idempotency-key-in-db.md)) |
| 순서 역전 | SQS Standard, 복구 경로의 늦은 도착 | 도착 순서 대신 상태 우선순위, 역전이는 conflict ([ADR-0003](docs/adr/0003-status-rank-ordering.md)) |
| 호출 제한 429 | 샵바이 초당 100회 토큰 버킷 | Redis 전역 토큰 버킷 80/s, 429는 재발행으로 DLQ에 보내지 않음 ([ADR-0007](docs/adr/0007-rate-limit-retry-by-republish.md)) |
| 취소·출고 경합 | 고객 취소 시 창고는 이미 작업 중 | 3PL 상태로 분기: 취소 / 배송보류 후 취소 / 이미출고+회수 안내 ([ADR-0005](docs/adr/0005-cancel-vs-shipment-flow.md), [ADR-0009](docs/adr/0009-cancel-state-machine-rules.md)) |
| 송장 일괄 반영 부분 실패 | by-shipping-no는 건별로 실패한다 | 실패한 배송만 다시 보냄 |
| 출고확정이 송장보다 먼저 옴 | 3PL 이벤트 순서 | 메시지 타이머로 지연 재처리, 초과 시 conflict + 묶음 알림 |

## 구조

```
inbound-api ─┐
worker ──────┼──▶ core ◀── infra          mock-servers (샵바이 mock, 3PL mock)
reconciler ──┤
admin-api ───┘
```

- `core`: 도메인 규칙(상태 우선순위, 멱등 키, 취소 상태 기계)과 포트. Spring·JPA·AWS 의존 없음
- `infra`: JPA·QueryDSL, SQS, Redis 레이트 리미터, 샵바이·3PL HTTP 어댑터
- `inbound-api`: 웹훅 수신 → 원본 저장 + outbox → SQS
- `worker`: 상태 반영, 출고 요청, 취소 처리, 3PL 이벤트, 송장 반영
- `reconciler`: 실패 웹훅 복구, STATUS_CHANGE 재조회, 대사 배치
- `admin-api`: CS 어드민 API (미연동·충돌·회수 목록, 재처리, 이력). 화면은 범위 밖
- 의존 규칙은 ArchUnit으로 검사합니다 ([ARCHITECTURE.md](ARCHITECTURE.md)).

설계 전체는 [docs/design-docs/order-sync-design.md](docs/design-docs/order-sync-design.md), 결정 이유는 [docs/adr/](docs/adr/README.md)에 있습니다.

## 실행

요구: Java 17, Docker.

```bash
docker compose up -d                 # MySQL 8.4, Redis 7.4, LocalStack 4.14(SQS)
./gradlew :mock-servers:bootRun      # 샵바이·3PL mock (8090)
./gradlew :inbound-api:bootRun       # 8081
./gradlew :worker:bootRun
./gradlew :reconciler:bootRun
./gradlew :admin-api:bootRun         # 8083
```

## 테스트와 측정

```bash
./gradlew check                       # 컴파일, 단위 테스트, ArchUnit (빠른 검사)
./gradlew :scenario-tests:scenarioTest   # 시나리오 F-02~F-13 (Testcontainers로 전체 모듈을 한 JVM에서 띄움)
./gradlew :scenario-tests:measure        # 장애 주입·부하 측정 (k6 필요) → scenario-tests/build/measurements/
```

기능 목록과 검증 테스트는 [feature_list.json](feature_list.json)에 있습니다. 각 기능은 해당 시나리오 테스트가 통과했을 때만 완료로 표시합니다.

## 범위 밖과 알려진 한계

- 실제 샵바이·파스토 연동, 실제 Slack 발송(알림은 `notification` 테이블까지), 재고·반품·교환, 클라우드 배포, 프론트엔드
- CS 어드민 인증: 처리자를 `X-Actor` 헤더로 받습니다. 위조할 수 있으므로 이력의 "누가"는 신뢰할 수 없습니다(인증은 범위 밖).
- 확인하지 못한 외부 동작은 [external-facts.md](docs/references/external-facts.md)의 "확인하지 못한 것"에 있습니다.
- "여러 인스턴스"는 한 JVM의 Spring 컨텍스트 여러 개로 흉내 냈습니다. 별도 프로세스로는 검증하지 않았습니다.

## 개발 방식

이 저장소는 AI 코딩 에이전트(Claude Code)와 함께 개발했습니다. 에이전트가 지킬 규칙과 검사 장치(문서 lint, 수정 범위 검사, Stop 훅, ArchUnit, 기능별 시나리오 테스트, 별도 컨텍스트 리뷰)는 [docs/harness/harness-design.md](docs/harness/harness-design.md)에 있습니다.
