# ADR 목록

형식: 각 파일은 `- 상태:` 줄(제안 / 채택 / 대체됨)과 `## 맥락`, `## 결정`, `## 검토한 대안`, `## 결과` 섹션을 가진다. doc-lint가 검사한다.

| 번호 | 제목 | 상태 |
|---|---|---|
| [0001](0001-idempotency-key-in-db.md) | 멱등 키를 DB에 둔다 | 채택 |
| [0002](0002-polling-is-source-of-truth.md) | 최종 기준은 조회 API | 채택 |
| [0003](0003-status-rank-ordering.md) | 도착 순서 대신 상태 우선순위로 판정 | 채택 |
| [0004](0004-global-rate-limiter.md) | 전역 레이트 리미터, 429는 DLQ로 보내지 않음 | 채택 |
| [0005](0005-cancel-vs-shipment-flow.md) | 취소·출고 경합 처리 | 채택 |
