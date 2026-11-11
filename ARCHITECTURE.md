# ARCHITECTURE

## 모듈 (Gradle 멀티모듈)

```
order-fulfillment-sync/
├── core/            도메인 모델, 상태 우선순위, 멱등 키, 포트(interface). 외부 의존 없음
├── inbound-api/     웹훅 수신 컨트롤러. 원본 저장 후 즉시 200, SQS 발행
├── worker/          SQS 소비, 상태 반영, 3PL 연동, 지연 재처리
├── reconciler/      실패 웹훅 조회 · STATUS_CHANGE 재조회 · 대사 배치
├── admin-api/       CS 어드민: 미연동/충돌 목록, 재처리, 이력
├── infra/           JPA·QueryDSL 리포지토리, SQS/Redis/HTTP 어댑터 (포트 구현)
└── mock-servers/    샵바이 서버 API mock, 파스토 규칙 3PL mock, 장애 주입 스위치
```

## 의존 방향

```
inbound-api ─┐
worker ──────┼──▶ core ◀── infra
reconciler ──┤
admin-api ───┘
```

- `core`는 Spring, JPA, AWS SDK를 import하지 않는다.
- 실행 모듈(inbound-api, worker, reconciler, admin-api)끼리는 서로 참조하지 않는다.
- 외부 호출(샵바이, 3PL, SQS, Redis)은 `core`의 포트를 통해서만 한다. 구현은 `infra`.
- 샵바이 호출은 반드시 전역 레이트 리미터를 거친다 (`ShopbyClient` 하나로만 나간다).

이 규칙은 ArchUnit 테스트(`core/src/test/.../ArchitectureTest`)로 검사하고, 실패 메시지에 고치는 방법을 적는다.

## 데이터 흐름

```
샵바이 웹훅 ─▶ inbound-api ─▶ webhook_inbox(원본) ─▶ SQS ─▶ worker ─▶ order_line_state
                                                                │
reconciler (실패 웹훅 조회, STATUS_CHANGE 재조회) ───────────────┘
                                                                ▼
                                              3PL mock ◀─▶ shipment / conflict / unlinked
```

상세는 [docs/design-docs/order-sync-design.md](docs/design-docs/order-sync-design.md).
