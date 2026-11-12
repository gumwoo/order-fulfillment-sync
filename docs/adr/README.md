# ADR 목록

형식: 각 파일은 `- 상태:` 줄(제안 / 채택 / 대체됨)과 `## 맥락`, `## 결정`, `## 검토한 대안`, `## 결과` 섹션을 가진다. doc-lint가 검사한다.

| 번호 | 제목 | 상태 |
|---|---|---|
| [0001](0001-idempotency-key-in-db.md) | 멱등 키를 DB에 둔다 | 채택 |
| [0002](0002-polling-is-source-of-truth.md) | 최종 기준은 조회 API | 채택 |
| [0003](0003-status-rank-ordering.md) | 도착 순서 대신 상태 우선순위로 판정 | 채택 |
| [0004](0004-global-rate-limiter.md) | 전역 레이트 리미터, 429는 DLQ로 보내지 않음 | 대체됨 (0007) |
| [0005](0005-cancel-vs-shipment-flow.md) | 취소·출고 경합 처리 | 채택 |
| [0006](0006-sqs-standard-queue.md) | SQS Standard 큐를 쓰고 FIFO는 쓰지 않는다 | 채택 |
| [0007](0007-rate-limit-retry-by-republish.md) | 전역 레이트 리미터와 429 재시도는 재발행으로 (0004 대체) | 채택 |
| [0008](0008-tpl-transient-failure-retry.md) | 3PL 일시 장애는 재발행으로 다시 시도한다 (최대 20회) | 채택 |
| [0009](0009-cancel-state-machine-rules.md) | 취소 처리 단계는 행 잠금 안에서 진행하고, 재시도 가능한 순서로 기록한다 | 채택 |
| [0010](0010-prepare-product-under-row-lock.md) | 상품준비중 변경은 출고 행 잠금 안에서 한 번만 한다 | 채택 |
| [0011](0011-invoice-sync-lost-response.md) | 송장 반영이 거절돼도 샵바이 상태가 이미 배송중이면 반영 완료로 본다 | 채택 |
| [0012](0012-apply-already-delivery-result.md) | 이미출고 성공은 우리 주문 라인에 바로 반영한다 | 채택 |
