# ADR-0007 전역 레이트 리미터와 429 재시도는 재발행으로 (ADR-0004 대체)

- 상태: 채택
- 날짜: 2026-11-11

## 맥락
ADR-0004는 429를 받으면 visibility timeout을 늘려 다시 받고, 재시도 횟수를 receive count와 별도로 센다고 정했다.
그런데 SQS DLQ의 `maxReceiveCount`는 메시지를 수신한 횟수를 센다(external-facts.md).
visibility timeout을 늘려도 다시 수신하는 순간 횟수가 오르므로, 429 대기가 길어지면 결국 DLQ로 간다.

레이트 리미터의 "80/s"도 해석이 필요하다.
토큰 버킷은 버킷 크기 B, 초당 충전량 r일 때 어떤 1초 구간에서든 최대 B + r회를 허용한다.
버킷을 80, 충전을 80/s로 잡으면 1초 구간에 160회까지 나갈 수 있다.

## 결정
- **레이트 리미터:** Redis Lua 토큰 버킷 하나를 모든 인스턴스가 공유한다.
  - 버킷 크기 10, 초당 충전 70으로 어떤 1초 구간에서도 80회 이하를 보장한다.
  - 시각은 Redis `TIME`을 써서 인스턴스 간 시계 차이를 없앤다.
- **EXHAUSTED:** 응답 헤더 `ratelimit-available-level: EXHAUSTED`를 받으면 버킷을 비우고, 일정 시간 모든 인스턴스가 호출을 멈춘다(Redis 키).
- **429와 리미터 대기 초과:** 메시지를 실패로 두지 않는다.
  - 원래 메시지를 지우고, `rateLimitRetries`를 1 올린 새 메시지를 지연(DelaySeconds)을 걸어 다시 발행한다.
  - 새 메시지라 receive count가 0부터 시작하므로 DLQ로 가지 않는다.
  - `rateLimitRetries`는 관측용으로 남긴다.
- **그 외 실패(5xx, 타임아웃, 버그):** 메시지를 지우지 않는다. SQS 재전송과 `maxReceiveCount`를 그대로 따르고, 넘으면 DLQ로 간다.
- **Redis 장애:** 호출을 멈추고(보수적) 메시지를 재발행한다.

## 검토한 대안
- **visibility timeout 연장(ADR-0004 원안):** receive count가 올라 DLQ로 간다.
- **`maxReceiveCount`를 아주 크게:** 진짜 실패한 메시지도 DLQ로 늦게 가서 장애가 가려진다.
- **인스턴스별 Resilience4j RateLimiter:** 인스턴스 수가 늘면 합계가 한도를 넘는다(ADR-0004와 같음).

## 결과
- 재발행과 원래 메시지 삭제 사이에 프로세스가 죽으면 같은 메시지가 두 번 존재할 수 있다.
  소비자가 멱등(ADR-0001)이므로 결과는 같다.
- F-07 시나리오에서 1초 구간 최대 호출 수와 DLQ 건수로 검증한다.
- ADR-0004는 대체됨으로 바꾼다.
