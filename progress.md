# 진행 기록

세션이 끝날 때마다 위에 추가한다. 형식: 날짜 / 한 일 / 확인한 것 / 남은 것 / 다음 세션이 먼저 할 일.

## 2026-11-11 (11) 측정, README, 실행 계획 0001 완료

- 한 일
  - 측정 태스크(`./gradlew :scenario-tests:measure`): 장애 주입 종합, 취소 경합, k6 수신 부하
  - docs/measurements.md에 실측값과 측정 환경 기록
  - README(mock 기반 명시, 구조, 실행, 범위 밖·한계), 설계 문서를 구현에 맞게 갱신
- 확인한 것 (실측, 로컬 PC, mock)
  - 장애 주입 300주문: 복구 전 불일치 159 → 실패 웹훅 복구 후 30 → 재조회 후 0 (14.7초), DLQ 0
  - 취소 경합 60건: 취소 후 출고 0건
  - 수신 258.6 요청/초, p95 108ms
  - `./gradlew check`, 시나리오 31개, Stop 훅 모두 통과
  - 첫 측정은 다른 빌드와 겹쳐 무효였다(jar 교체로 클래스 로딩 실패). 단독으로 다시 돌린 값만 기록했다
- 확인하지 못한 것: docs/measurements.md "측정하지 않은 것", external-facts.md "확인하지 못한 것"
- 다음: 원격 푸시는 사용자 승인 후. 측정에서 보인 반영 병목(worker 1개)과 3PL 등록 중복 호출 3건은 후속 과제

## 2026-11-11 (10) F-13 CS 어드민 재처리

- 한 일
  - admin-api: 미연동·충돌·회수 목록(QueryDSL 동적 조건), 미연동 재처리, 처리 이력
  - 재처리 멱등: 요청 ID를 admin_action에 INSERT IGNORE. 같은 ID는 결과만 돌려준다
  - 다른 요청 ID 동시: 미연동 행의 "처리 중" 표시(짧은 잠금 트랜잭션)로 한 요청만 처리. 외부 호출은 잠금 밖, 단계마다 커밋
  - 리뷰(Sonnet) 지적 반영
    - 전체 한 트랜잭션이라 중간 실패 시 3PL 등록 기록이 롤백되던 문제
    - 잠금 중 외부 호출
    - 예상 밖 예외에서 IN_PROGRESS가 남던 문제
    - 취소된 주문의 미연동 종료
    - 재거절 시 사유 갱신
    - 요청 ID 재사용 409, 잘못된 상태값 400
- 확인한 것: `scenarioTest --tests '*AdminReprocessScenarioTest'` 4개 통과
  - 미연동 목록에 OPTION_NOT_MAPPED·옵션값이 보임
  - 같은 요청 ID 동시 2회 → 처리 1회(중복 1), 다른 요청 ID 동시 2회 → 처리 1회
  - 3PL 등록 호출 2회(최초 거절 + 재처리 1회), 이력 2건(requestId·처리자·결과)
- 확인하지 못한 것
  - 실제 3PL의 중복 등록 응답 (mock은 멱등)
  - 인증 없음: X-Actor는 위조할 수 있다 (README에 명시)

## 2026-11-11 (9) F-11 송장 부분 실패, F-12 지연 재처리

- 한 일
  - 3PL 이벤트 수집(cursor) → TPL_EVENT 메시지 → 출고 상태·송장 저장
  - 출고완료가 송장보다 먼저 오면 메시지 타이머로 지연 재발행(기본 5분, 6회), 초과 시 conflict + 경보
  - 송장 반영: 배송번호 단위 100건 by-shipping-no, 응답 failures만 미반영 → 다음 실행에 그 건만
  - 묶음 알림: 미발송 경보를 알림 1건으로 (notification 테이블, 실제 Slack 발송은 범위 밖)
  - 리뷰(Sonnet) 지적 반영
    - 3PL 상태 앞으로만 (늦게 처리된 이벤트가 되돌리던 문제)
    - 배송의 모든 옵션이 출고완료·같은 송장일 때만 송장 반영
    - 반영 대상 행 잠금
    - 응답 불일치 시 전부 실패 처리
    - 5회 실패 시 conflict + 경보 후 중단 (취소된 배송의 무한 재시도 방지)
    - 포기 conflict 중복 방지
    - 다중 옵션 배송 시나리오 추가
- 확인한 것: 시나리오 전체 29개 통과 (아래는 이번 기능 관련)
  - F-11: 첫 반영 (100, 성공 93, 실패 7), 두 번째 (7, 7, 0), 세 번째 0건. 두 번째 요청의 배송번호 집합 = 실패 7건
  - F-12: 출고완료 → 지연 → 송장 도착 → 반영(conflict 0). 송장 미도착 3건 → conflict 3, 알림 1건(경보 3)
  - 다중 옵션 배송: 한 옵션만 출고완료면 0건, 둘 다 되면 1건. 송장이 다르면 conflict, 요청 0건
- 확인하지 못한 것
  - 실제 샵바이에서 한 배송의 옵션별 송장이 다를 수 있는지 (external-facts 미확인)
  - 지연 간격이 실제로 지켜지는지를 시간으로 단언하지는 않았다 (코드로 확인: SQS DelaySeconds)

## 2026-11-11 (8) F-08~F-10 취소·출고 경합

- 한 일
  - CancelService 상태 기계: RECEIVED → (3PL 없음·출고요청) TPL_CANCELED → DONE / (출고작업) HOLD_PENDING → HELD → TPL_CANCELED → DONE / (피킹 이후) RECALL_PENDING → RECALL_REQUIRED
  - 배송보류 일괄(최대 1,000) → 거절 시 건별 재시도. 건별 400이면 3PL 재확인(피킹 이후면 회수 경로, 아니면 HOLD_FAILED + conflict)
  - CS 회수 목록(recall_task), 주기 작업(배송보류 일괄 → 이어 하기)
  - 리뷰(Sonnet) 지적 반영 → ADR-0009
    - 단계마다 행 잠금 안에서 진행 (동시 처리 시 단계 역행·중복 호출 방지)
    - 회수 건을 이미출고 호출 전에 기록
    - 승인·이미출고 400은 conflict 후 다음 단계
    - 늦게 온 취소 신청 무시
    - 3PL 등록 직후 클레임이 생기면 바로 3PL 취소
    - F-09 테스트에서 배송보류 상태를 직접 확인
  - mock 가정(승인·이미출고 시 배송보류 해제 등)을 external-facts에 미확인으로 기록
  - F-11·F-12 코드(3PL 이벤트 수집, 송장 반영, 지연 재처리, 묶음 알림)도 같은 커밋에 있다. 검증은 다음 커밋
- 확인한 것: `scenarioTest --tests '*Cancel*'` 6개 통과
  - F-08: 3PL 취소 수수료 0, 승인 1회, 배송보류 미호출. 같은 취소 웹훅 3회여도 3PL 취소·승인 각 1회
  - F-09: 배송보류 호출 [3, 1, 1, 1] (일괄 실패 후 건별), 보류 직후 holdDeliveryYn=Y·3PL WORKING, 이후 DONE, 수수료 1,000
  - F-10: 이미출고 1회, 회수 목록 1건, 샵바이 배송중·클레임 철회, 우리 라인 클레임 정보 지워짐
- 확인하지 못한 것
  - 외부 호출 성공 후 저장 전 크래시 재개 경로 (RECALL_PENDING, TPL_CANCELED)를 시나리오로 재현하지 않았다
  - 샵바이가 이미 배송중일 때 이미출고로 클레임이 철회되면 우리 쪽 클레임 정보가 남는다 (대사 배치가 드러냄, ADR-0009 결과)

## 2026-11-11 (7) F-07 전역 레이트 리미터

- 한 일
  - 출고 요청 흐름: 결제완료 → 3PL 출고요청 등록 → 샵바이 상품준비중 (ShipmentRequestService, 단계별 멱등)
    - 3PL 매핑 실패는 미연동, 상품준비중 400은 conflict, 그 외 4xx는 재전송→DLQ
  - 3PL mock(파스토 규칙: 출고요청→출고작업→피킹→출고완료, 취소 수수료, 옵션명 매핑, 이벤트 순번)
  - worker: 샵바이 레이트 리밋 → 재발행(지수 백오프, ADR-0007), 3PL 일시 장애 → 재발행 20회(ADR-0008)
  - 재조회: 구간 끝을 현재 초-1초로 자르고 마지막 페이지부터 읽는다 (조회 중 상태가 바뀐 주문 때문에 누락되는 문제를 부하에서 재현해 고침)
  - mock 샵바이: 잠금 안에서 상태만 바꾸고 웹훅은 잠금 밖에서 발송 (호출이 직렬화돼 부하가 막히던 문제)
  - SQS 소비자: 묶음 처리 중 visibility timeout 절반이 지나면 남은 메시지 연장
    - maxReceiveCount=1 테스트로 정상 처리 중 재전송 7건을 찾아 고침
  - 리뷰(Sonnet) 지적 반영: DLQ 단언 강화, 상품준비중 4xx 처리, 3PL 연결 오류 통일, 미연동·출고 행 동시 생성, ADR-0008
  - 취소 처리 코드(F-08~F-10)도 같은 커밋에 들어 있다 (worker 처리기가 함께 다룸). 검증은 다음 커밋
- 확인한 것 (실측, 로컬 PC, mock)
  - 한도: worker 컨텍스트 3개 × 16스레드 직접 호출 6초 → 고정 1초 최대 77, 슬라이딩 1초 최대 79, 성공 469
    - 변이: 리미터를 풀면 1초 최대 762로 실패
  - DLQ: 400주문 처리 중 429 30건·EXHAUSTED 30건 → 재발행 32, DLQ 0 (maxReceiveCount=1로 낮춘 상태), 처리 15.7초
    - 변이: 429를 재전송으로 처리하면 DLQ로 32건, 실패
  - 전체 처리 흐름은 이 PC에서 1초 최대 50~70회라 한도까지 차지 않는다 (리미터를 풀어도 48~67)
- 확인하지 못한 것
  - 실제 별도 프로세스 3개 (같은 JVM의 컨텍스트 3개로 흉내)
  - 리미터 보장은 토큰 발급 시각 기준 80이다. mock 도착 시각 기준 슬라이딩 최대 79~80으로 여유가 거의 없다 (실제 한도 100)
  - 샵바이 서버 시계와 우리 시계 차이 (재조회 끝 1초 전 가정, external-facts 미확인)

## 2026-11-11 (6) F-05 웹훅 유실 복구, F-06 오래된 watermark

- 한 일
  - FailedWebhookRecoveryJob: 실패 웹훅 조회(7일 한도) → 실패 시각 앞 5분·뒤 1분 구간 STATUS_CHANGE 재조회
    - `data` 필드는 형식 미확인이라 파싱하지 않는다
  - watermark가 7일 초과: 실패 웹훅 API를 호출하지 않고 경보(STALE_WATERMARK, 1시간 1회) → STATUS_CHANGE 재조회로 복구
  - ReconciliationJob(대사): 최근 2일 STATUS_CHANGE 기준으로 샵바이와 DB 비교 → mismatch 기록
  - 스케줄: 재조회 5분, 실패 웹훅 10분, 대사 매일 04:30
  - 리뷰(Sonnet) 지적 반영
    - F-05 테스트에 경로별 완전성 검증 추가 (배치 1은 실패 웹훅 경로만으로 0)
    - 구간 앞뒤 문서·코드 일치, 시각 없는 실패 내역 로그
    - 경보 중복 방지
    - 미확인 사항(실패 시각과 변경 시각의 관계, STATUS_CHANGE 최대 구간)을 external-facts에 기록
- 확인한 것 (실측, 로컬 PC, mock, 시드 고정)
  - F-05: 웹훅 200건 중 53건 버림(26.5%), 복구 전 불일치 23, 실패 웹훅 경로 복구 10, 재조회 경로 복구 13, 복구 후 불일치 0
  - F-06: 7일 초과 시 실패 웹훅 API 호출 0회, 경보 1건, 재조회로 5건 복구, 불일치 0. 7일 이내는 경보 없이 실패 웹훅 경로
- 확인하지 못한 것
  - 대사는 "최근 2일 샵바이 기준"만 본다. 우리 DB에만 있는 건은 보지 않는다.
  - 실제 샵바이의 실패 시각·변경 시각 관계
- 다음: F-07 (출고 요청 흐름과 3PL 연동 일부 작성 중)

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
