# 새 세션 시작 문서 (2026-11-11 작성)

이 저장소를 처음 여는 세션이 읽는 문서다. 설계와 하네스는 이전 세션에서 만들었고, 코드는 아직 한 줄도 없다.

## 1. 왜 만드는가

- 백엔드 포트폴리오용 개인 프로젝트다. 스택(Java, Spring Boot, JPA, QueryDSL, Gradle 멀티모듈, MySQL, Redis, SQS)과 쇼핑몰(샵바이)·3PL 연동 업무를 다룬다.
- 풀려는 문제: 쇼핑몰 주문 상태와 3PL 출고 상태가 어긋나는 문제(웹훅 유실·중복·순서 역전, 취소와 출고 경합, 호출 제한 429).
- 목표: 장애를 일부러 넣어도 불일치가 0으로 수렴하는 것을 **측정해서** 보여주는 것. 결과는 나중에 포트폴리오에 넣는다.

## 2. 먼저 읽을 순서

1. `AGENTS.md` (지도와 규칙)
2. `docs/exec-plans/active/0001-mvp.md` (일정, 수정 허용 경로)
3. `docs/design-docs/order-sync-design.md` (설계 전체)
4. 작업할 기능과 관련된 ADR, `docs/references/external-facts.md`의 해당 부분만

## 3. 첫 작업: F-01, F-14 (실행 계획 1주 차 전반)

- **F-01:** Gradle 멀티모듈 골격(`core`, `infra`, `inbound-api`, `worker`, `reconciler`, `admin-api`, `mock-servers`), `docker-compose.yml`(MySQL 8, Redis, LocalStack SQS), `./gradlew check` 통과.
- **F-14:** `ArchitectureTest`(ArchUnit). `core`는 Spring·JPA·AWS SDK를 참조하지 않음, 실행 모듈끼리 참조 금지, 샵바이 호출은 `ShopbyClient`로만. 실패 메시지에 고치는 방법을 적는다.
- 끝나면 `gradlew check` 통과 확인 → `reviewer` 에이전트 검토 → `feature_list.json`의 `passes`만 true로 → `progress.md` 갱신 → 커밋 메시지 초안 작성(커밋은 사용자 승인 후).

## 4. 환경 (2026-11-11 확인)

| 항목 | 상태 |
|---|---|
| OS / 셸 | Windows 11, Git Bash와 PowerShell |
| Java | 17.0.17 설치됨 |
| Gradle | **설치 안 됨.** 저장소에 Gradle Wrapper를 만들어야 한다. 방법은 사용자에게 확인하고 정한다(예: Spring Initializr 결과물에서 wrapper 가져오기, Docker gradle 이미지로 `gradle wrapper` 실행) |
| Docker / Compose | 29.4.3 / v5.1.3 설치됨 |
| Python | 3.14 (하네스 스크립트용) |
| Git | 저장소 초기화만 됨. **커밋 없음** |

## 5. 하네스 동작

- 훅과 권한 규칙은 **이 폴더를 루트로 연 세션에서만** 적용된다(`.claude/settings.json`).
- Stop 훅: `doc-lint`(문서 링크, ADR 형식, feature_list 항목 삭제·수정 금지) → `scope-check`(실행 계획 허용 경로 밖 수정 차단) → `./gradlew check`(wrapper가 생긴 뒤부터). 실패하면 종료가 막히고 원인이 돌아온다. 훅을 끄거나 우회하지 않는다.
- feature_list 삭제 금지 검사는 마지막 커밋과 비교하므로, 첫 커밋 전에는 동작하지 않는다.
- Testcontainers 테스트가 붙으면 Stop 훅이 느려질 수 있다. 그때는 빠른 검사와 느린 검사를 나누는 방안을 사용자와 정하고 `docs/harness/harness-design.md`에 기록한다.
- 리뷰: `.claude/agents/reviewer.md` (Read, Grep, Glob만 가능한 읽기 전용).

## 6. 사용자와 일하는 방식

- 답변은 한국어. 결론부터 말한다.
- 확인한 것과 확인하지 못한 것을 구분해서 보고한다.
- 숫자는 실제로 측정한 값만 쓴다. 예측치는 "예측"이라고 표시하고 표에 넣지 않는다.
- 요청과 관련 없는 리팩터링이나 새 라이브러리 도입은 하지 않는다. 필요하면 이유를 먼저 설명한다.
- 커밋·푸시는 사용자가 요청할 때만 한다.

## 7. 정해진 것 / 정하지 않은 것

- 정해진 것: ADR 0001~0005, 실제 샵바이 계정 대신 공개 스펙 기반 mock, 재고·반품·교환·실제 클라우드 배포는 범위 밖.
- 정하지 않은 것: SQS FIFO 사용 여부와 그룹 키(주문번호 vs 옵션번호), Gradle Wrapper 생성 방법.
- 저장소: `order-fulfillment-sync`, 원격 https://github.com/gumwoo/order-fulfillment-sync (2026-11-11 기준 비어 있음)
