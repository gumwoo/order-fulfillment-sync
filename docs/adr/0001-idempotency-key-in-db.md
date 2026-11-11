# ADR-0001 멱등 키를 DB에 둔다

- 상태: 채택
- 날짜: 2026-11-11

## 맥락
샵바이 웹훅에는 이벤트 ID가 없다. 같은 변경이 웹훅, 실패 웹훅 조회, 상태 재조회 세 경로로 들어올 수 있고, 실패 웹훅 조회는 최대 7일 전 것까지 다시 가져온다. SQS FIFO의 중복 제거는 5분 범위다.

## 결정
`orderProductOptionNo | orderStatusType | claimStatusType | claimNo | 가장 늦은 단계 시각`의 해시를 멱등 키로 만들고, `processed_event.idem_key` UNIQUE 제약으로 한 번만 반영한다.

## 검토한 대안
- SQS FIFO 중복 제거만 사용: 5분이 지나 들어오는 복구 경로 중복을 못 막는다.
- Redis SETNX: TTL을 7일 이상 잡아야 하고, Redis 장애 시 중복이 통과한다.

## 결과
DB insert 한 번이 추가된다. 키 구성 필드가 실제로 변경을 구분하는지 테스트(F-03)로 확인한다.
