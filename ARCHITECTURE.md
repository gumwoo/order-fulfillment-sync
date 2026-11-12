# ARCHITECTURE

## 모듈 (Gradle 멀티모듈)

```
order-fulfillment-sync/
├── core/            도메인 모델, 상태 우선순위, 멱등 키, 포트(interface). 외부 의존 없음
├── inbound-api/     웹훅 수신 컨트롤러. 원본 저장 후 즉시 200, SQS 발행
├── worker/          SQS 소비, 상태 반영, 3PL 연동, 지연 재처리
├── reconciler/      실패 웹훅 조회 · STATUS_CHANGE 재조회 · 대사 배치
├── admin-api/       CS 어드민: 미연동/충돌 목록, 재처리, 이력, 데모용 현황·대사 이력
├── infra/           JPA·QueryDSL 리포지토리, SQS/Redis/HTTP 어댑터 (포트 구현)
├── mock-servers/    샵바이 서버 API mock, 파스토 규칙 3PL mock, 장애 주입 스위치
└── frontend/        데모 화면 (React + Vite, Gradle 밖). admin-api와 mock 데모 API만 HTTP로 부른다
```

## 의존 방향

```
inbound-api ─┐
worker ──────┼──▶ core ◀── infra
reconciler ──┤
admin-api ───┘
```

- `core`는 Spring, JPA, AWS SDK를 import하지 않는다.
- 실행 모듈은 `infra`도 의존한다. 허용 범위는 다음과 같다.
  - DB 접근: 그 모듈만 쓰는 테이블(예: inbound의 `webhook_inbox`, `outbox`)은 infra의 JPA 엔티티·리포지토리를 직접 쓴다.
    여러 모듈이 공유하는 규칙(상태 반영 등)은 `core` 서비스와 영속성 포트(`OrderSyncStore` 등)를 거친다.
  - 설정: infra의 `@Configuration`을 `@Import`한다.
  - 외부 호출: 아래 규칙대로 반드시 `core` 포트를 쓴다. infra의 어댑터 내부를 직접 쓰지 않는다.
- 실행 모듈(inbound-api, worker, reconciler, admin-api)끼리는 서로 참조하지 않는다.
- `frontend`는 Java 모듈에 의존하지 않는다. 개발 서버 프록시로 `/admin`(admin-api)과 `/mock`(mock-servers의 `/_mock/demo`)만 부른다.
- 외부 호출(샵바이, 3PL, SQS, Redis)은 `core`의 포트를 통해서만 한다. 구현은 `infra`.
- 샵바이 호출은 반드시 전역 레이트 리미터를 거친다 (`ShopbyClient` 하나로만 나간다).
  - `core.port.ShopbyClient`의 구현체는 `infra.shopby`에만 둔다. `infra.shopby`의 다른 클래스는 패키지 밖에서 쓰지 않는다.
  - HTTP 클라이언트(RestClient, WebClient, `java.net.http`, `HttpURLConnection` 등)는 `infra.shopby`, `infra.tpl`(3PL), `mock`에서만 쓴다.
- 각 모듈의 클래스는 자기 패키지 아래에 둔다. 루트는 `io.github.gumwoo.fulfillment`이고,
  모듈별 패키지는 `core`, `infra`, `inbound`(inbound-api), `worker`, `reconciler`, `admin`(admin-api), `mock`(mock-servers)이다.

이 규칙은 ArchUnit 테스트(`core/src/test/.../ArchitectureTest`)로 검사하고, 실패 메시지에 고치는 방법을 적는다.
규칙이 실제로 위반을 잡는지는 일부러 규칙을 어긴 픽스처(`archfixture`)로 `ArchitectureRulesViolationTest`가 검사한다.

정적 검사로 막지 못하는 것: `infra.tpl` 같은 허용 패키지 안에서 샵바이 URL을 직접 호출하는 경우. 코드 리뷰로 확인한다.

## 데이터 흐름

```
샵바이 웹훅 ─▶ inbound-api ─▶ webhook_inbox(원본) ─▶ SQS ─▶ worker ─▶ order_line_state
                                                                │
reconciler (실패 웹훅 조회, STATUS_CHANGE 재조회) ───────────────┘
                                                                ▼
                                              3PL mock ◀─▶ shipment / conflict / unlinked
```

상세는 [docs/design-docs/order-sync-design.md](docs/design-docs/order-sync-design.md).
