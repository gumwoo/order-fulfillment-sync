# 진행 기록

세션이 끝날 때마다 위에 추가한다. 형식: 날짜 / 한 일 / 확인한 것 / 남은 것 / 다음 세션이 먼저 할 일.

## 2026-11-11 (5) F-04 순서 역전

- 한 일: OutOfOrderScenarioTest. 구현은 F-03의 StatusTransitionPolicy(ADR-0003).
- 확인한 것: `scenarioTest --tests '*OutOfOrderScenarioTest'` 3개 통과. 리뷰(Sonnet) 문제 없음.
  - DELIVERY_ING 반영 후 PAY_DONE → 상태 유지, conflict 1건(BACKWARD_TRANSITION)
  - 같은 PAY_DONE 2회 → conflict 1, DUPLICATE 1

## 2026-11-11 (4) F-03 멱등 반영

- 한 일
  - core: `OrderStatus`(우선순위), `OrderStatusChange`(멱등 키 sha256), `StatusTransitionPolicy`(ADR-0003),
    `ApplyStatusChangeService`(한 트랜잭션: processed_event INSERT IGNORE → 판정 → 반영+이력 또는 conflict)
  - 포트: `ShopbyClient`(이후 기능 메서드까지 정의), `OrderSyncStore`, `TransactionRunner`
  - infra
    - JPA 구현(`order_line_state` @Version, `status_history`, `conflict`, `sync_watermark`)
    - 샵바이 어댑터: Redis Lua 토큰 버킷(10 + 70/s, Redis TIME), EXHAUSTED·429 처리
  - worker: SQS 소비 → 라우터 → 반영, 처리 통계(`ProcessingStats`)
  - reconciler: STATUS_CHANGE 재조회 작업(watermark, overlap 2분, totalCount가 바뀌면 다시 조회)
  - mock-servers: 샵바이 mock
    - 공개 스펙 API: 주문 조회, 실패 웹훅, 상품준비중, 배송보류, 송장 일괄 반영, 클레임 승인, 이미출고
    - 제어 API, 호출 기록·429 주입 필터
  - 리뷰(Sonnet) 지적 반영
    - 페이징 누락
    - 파싱 실패 메시지를 지우지 않고 DLQ로
    - 단계 시각이 과거로 되돌아가지 않게
    - 클레임 철회 시 클레임 정보 지우기
    - mock 가정을 external-facts에 "미확인"으로 기록
    - ArchUnit 예외를 ShopbyConfig 하나로 한정
- 확인한 것
  - `./gradlew check` 통과 (StatusTransitionPolicyTest 포함)
  - `scenarioTest --tests '*IdempotencyScenarioTest'`: 2개 통과
    - 웹훅 3회 + 재조회 1회: APPLIED 1, DUPLICATE 2, POLLING DUPLICATE 1, status_history 1
- 확인하지 못한 것
  - 실제 샵바이에서 웹훅과 조회 응답의 단계 시각이 같은지 (mock은 같은 값으로 직렬화)
  - 같은 라인의 동시 경합을 시나리오로 검사하지는 않았다 (PK·@Version 충돌 시 롤백 후 재전송 경로)
- 다음: F-04 (테스트 작성됨), F-05·F-06 (코드와 테스트 작성됨, 리뷰 전)

## 2026-11-11 (3) F-02 웹훅 수신

- 한 일
  - 위임 사항을 실행 계획 결정 로그에 기록. 빠른 검사(`check`)와 시나리오 검사(`scenarioTest`)를 분리했다.
  - 공식 자료 확인: 샵바이 OpenAPI 스펙(주문 조회, 실패 웹훅, 상태 변경 API), 웹훅 가이드, SQS 문서를 external-facts.md에 추가
  - ADR-0006: SQS Standard 큐, FIFO 미사용 (FIFO는 메시지별 지연 불가)
  - ADR-0007: 429는 재발행으로 재시도. ADR-0004 대체 (`maxReceiveCount`는 수신 횟수를 센다)
  - inbound-api 흐름: `webhook_inbox` 저장 → 같은 트랜잭션에서 원소별 `outbox` → 200 → 릴레이가 SQS 발행
    - 릴레이는 `FOR UPDATE SKIP LOCKED`로 행을 선점한다
  - SQS 어댑터(발행, 소비 루프, Disposition), `scenario-tests` 모듈(ScenarioStack: 컨테이너 1회 기동, 필요한 앱만 기동)
  - 리뷰(Sonnet) 지적 반영
    - 릴레이 다중 인스턴스 중복 발행
    - 실패 행이 뒤 행을 막는 문제
    - 소비자 배치 중단
    - 혼합 배열과 단일 객체 처리
    - ARCHITECTURE에 infra 직접 사용 범위 명시
- 확인한 것
  - `./gradlew check` 통과
  - `scenarioTest --tests '*WebhookInboundScenarioTest'`: 6개 통과
  - 변이 테스트: SKIP LOCKED를 빼면 동시 릴레이 테스트가 300건 대신 1,400건 발행으로 실패, 되돌리면 통과
- 확인하지 못한 것: 실제 다중 프로세스(별도 JVM) 환경. 테스트는 한 JVM의 4개 스레드 + 스케줄러로 흉내냈다.
- 다음: F-03 (멱등 반영). core 도메인과 영속성 일부는 작성 중이라 커밋하지 않았다.

## 2026-11-11 (2) F-01, F-14

- 한 일
  - Gradle Wrapper 8.14.3: 로컬 캐시(`~/.gradle/wrapper/dists/gradle-8.14.3-all`)의 gradle로 `gradle wrapper` 실행
  - 멀티모듈 골격 7개: Spring Boot 3.5.16, Java 17 toolchain, 루트 패키지 `io.github.gumwoo.fulfillment`
  - `docker-compose.yml`: mysql:8.4.13, redis:7.4.12-alpine, localstack/localstack:4.14.0
    - LocalStack은 2026.x(캘린더 버전) 이전 마지막 4.x로 고정
  - `core.port.ShopbyClient`: 메서드 없는 포트
  - `ArchitectureTest`: core 순수성, 실행 모듈 간 참조 금지, ShopbyClient 구현체 위치, infra.shopby 캡슐화, HTTP 클라이언트 허용 패키지, 모듈별 패키지 위치
  - `ArchitectureRulesViolationTest` + `archfixture`: 규칙마다 위반 픽스처로 실제 실패하는지 검사
  - 리뷰어(새 컨텍스트) 지적 반영: 구현체 위치 규칙, HTTP 패키지 목록 확대(HttpURLConnection 등), infra 내부 HTTP 제한, 모듈-패키지 일치 검사
  - Stop 훅: Gradle 콘솔 출력이 Windows/Java 17에서 MS949라 한글 실패 메시지가 깨진다. XML 리포트(UTF-8)에서 실패 내용을 다시 출력하는 `test_failures.py` 추가
  - reviewer 에이전트 모델을 sonnet으로 지정 (사용자 요청)
- 확인한 것
  - `./gradlew clean check` 통과: 테스트 21개, 실패 0
  - `docker compose up -d --wait`: 세 서비스 healthy
    - SQS 큐 생성·삭제, Redis PING, MySQL 접속 확인
    - LocalStack 4.14.0은 인증 토큰 없이 동작
  - 변이 테스트: worker 모듈에 다른 패키지 클래스를 넣으면 실패하고, 지우면 통과
  - Stop 훅 세 경우
    - 테스트 실패 → 한글 메시지 정상 출력, exit 2
    - 컴파일 실패 → 이전 리포트를 출력하지 않음
    - 정상 → exit 0
- 확인하지 못한 것
  - 각 실행 모듈의 애플리케이션 기동: DB 설정이 없고, 기동 테스트는 해당 기능에서 붙인다
  - Spring Boot 3.5의 OSS 지원 종료 시점
- 남은 것
  - `infra.shopby` 캡슐화 규칙과 구현체 위치 규칙은 아직 검사 대상 클래스가 없어 `allowEmptyShould(true)`다
  - F-07에서 ShopbyClient 구현체가 생기면 실제 클래스로 검사된다
- 다음 세션: F-02 (웹훅 수신 → inbox → SQS). SQS FIFO 사용 여부와 그룹 키는 아직 미결이라 F-02 전에 정한다

## 2026-11-11

- 한 일: 설계 문서, ADR 0001~0005, 외부 제약 문서, 하네스 문서, feature_list, 훅과 검사 스크립트 작성
- 확인한 것: doc-lint.sh, scope-check.sh, stop-check.sh 실행 (코드 없음 상태)
- 남은 것: 코드 전부. gradlew가 없어서 Stop 훅의 빌드 검사는 아직 건너뜀
- 다음 세션: [kickoff.md](docs/exec-plans/active/kickoff.md)를 읽고 실행 계획 0001의 1주 전반 (F-01, F-14)부터
- 추가: 읽기 전용 리뷰어 에이전트 `.claude/agents/reviewer.md`, 새 세션 시작 문서 `kickoff.md`
