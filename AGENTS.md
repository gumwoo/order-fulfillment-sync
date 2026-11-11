# AGENTS.md

샵바이 주문 웹훅과 3PL(파스토 규칙 mock) 출고 상태를 맞추는 주문·출고 동기화 허브.
이 파일은 지도다. 규칙의 근거와 상세는 아래 링크 문서에 있다.

## 먼저 읽을 것 (세션 시작 순서)

0. 코드가 아직 없다면 [docs/exec-plans/active/kickoff.md](docs/exec-plans/active/kickoff.md)부터 읽는다.
1. [progress.md](progress.md): 직전 세션이 무엇을 했고 무엇이 남았는지
2. `git log --oneline -20`
3. [feature_list.json](feature_list.json): `passes:false` 중 우선순위가 가장 높은 것 하나를 고른다
4. 고른 기능과 관련된 설계 문서만 읽는다. 전체를 읽지 않는다.

## 문서 지도

| 알고 싶은 것 | 문서 |
|---|---|
| 모듈 구조, 의존 방향 | [ARCHITECTURE.md](ARCHITECTURE.md) |
| 전체 설계와 시나리오 | [docs/design-docs/order-sync-design.md](docs/design-docs/order-sync-design.md) |
| 왜 이렇게 정했는지 | [docs/adr/](docs/adr/README.md) |
| 샵바이·SQS·파스토 공식 제약 | [docs/references/external-facts.md](docs/references/external-facts.md) |
| 지금 진행 중인 계획과 수정 허용 범위 | [docs/exec-plans/active/](docs/exec-plans/active/0001-mvp.md) |
| 이 하네스를 왜 이렇게 만들었는지 | [docs/harness/harness-design.md](docs/harness/harness-design.md) |

## 작업 규칙

- 한 세션에 기능 하나. 끝나면 테스트 통과 확인 → `progress.md` 갱신 → 커밋 메시지 초안 작성.
- `feature_list.json`은 `passes` 값만 바꾼다. 항목 삭제·문구 수정 금지 (doc-lint가 개수 감소를 막는다).
- `passes:true`는 해당 항목의 `verify` 테스트가 실제로 통과한 뒤에만 바꾼다.
- 외부 API 동작은 [external-facts.md](docs/references/external-facts.md)에 있는 것만 가정한다.
  없는 동작이 필요하면 추측하지 말고 문서에 "미확인"으로 적고 사람에게 묻는다.
- 수정은 활성 실행 계획의 `허용 경로` 안에서만 한다. Stop 훅의 scope-check가 막는다.
- 새 설계 결정은 ADR로 남긴다. 기존 ADR을 뒤집을 때는 새 ADR을 쓰고 이전 것을 `대체됨`으로 바꾼다.

## 명령

```bash
./gradlew check                    # 빌드 + 테스트 + ArchUnit
bash scripts/harness/doc-lint.sh   # 문서 링크, ADR 형식, feature_list 검사
bash scripts/harness/scope-check.sh
docker compose up -d               # MySQL, Redis, LocalStack(SQS)
```

## 하지 말 것

- 웹훅을 유일한 진실로 취급하지 않는다. 최종 기준은 주문 조회 API다 ([ADR-0002](docs/adr/0002-polling-is-source-of-truth.md)).
- 429를 DLQ로 보내지 않는다 ([ADR-0004](docs/adr/0004-global-rate-limiter.md)).
- 중복 제거를 SQS FIFO에만 맡기지 않는다 ([ADR-0001](docs/adr/0001-idempotency-key-in-db.md)).
