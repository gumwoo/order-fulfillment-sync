# 주문·출고 동기화 허브 설계

- 상태: 초안 (2026-11-11)
- 근거 문서: [external-facts.md](../references/external-facts.md)

## 1. 풀려는 문제

쇼핑몰(샵바이)의 주문 상태와 3PL 창고의 출고 상태가 어긋나는 문제.

- 웹훅은 실패해도 다시 보내지 않는다. 놓친 주문은 창고로 넘어가지 않는다.
- 같은 웹훅이 두 번 오거나 순서가 바뀌어 온다.
- 고객이 취소했는데 창고에서는 이미 출고 작업이 시작됐다.
- 샵바이 API는 초당 100회 제한이 있어 재처리가 몰리면 429가 난다.

목표: 위 상황을 일부러 만들어도 **불일치 건수가 0으로 수렴하고**, 사람이 봐야 하는 건만 CS 화면에 남는 것.

## 2. 범위

| 포함 | 제외 |
|---|---|
| 주문 수집, 출고 요청, 송장 반영, 취소·출고 경합 | 재고 동기화 |
| 실패 웹훅 복구, 상태 재조회, 대사 배치 | 반품·교환 (파스토도 샵바이 교환·반품은 수집하지 않음) |
| CS 어드민 API, Slack 알림 묶음 | 실제 클라우드 배포, 실제 샵바이 계정 연동 |
| 장애 주입과 측정 | 프론트엔드 화면 (API + 간단한 표만) |

실제 샵바이 서버 API는 앱 등록과 심사가 필요해서 쓰지 않는다. 공개 OpenAPI 스펙과 파스토 공개 가이드를 그대로 따르는 mock 서버를 만든다. README에 이 사실을 밝힌다.

## 3. 스택

Java 17, Spring Boot 3, JPA, QueryDSL, MySQL 8, Redis, SQS(LocalStack), Gradle 멀티모듈, Testcontainers, ArchUnit, k6(부하), Docker Compose.

## 4. 핵심 설계

### 4.1 수신과 처리 분리

1. 웹훅 수신 즉시 원본 JSON을 `webhook_inbox`에 저장하고 200을 응답한다. 비즈니스 처리는 하지 않는다.
2. 저장과 같은 트랜잭션에서 outbox 행을 쓰고, 릴레이가 SQS로 발행한다.
3. `CHANGE_ORDER_STATUS` 웹훅은 주문상품 옵션 단위 배열이므로, 배열 원소 하나를 메시지 하나로 쪼갠다.

### 4.2 멱등 키 ([ADR-0001](../adr/0001-idempotency-key-in-db.md))

웹훅에 이벤트 ID와 발생 시각이 없다. 그래서 키를 직접 만든다.

```
idem_key = sha256(orderProductOptionNo | orderStatusType | claimStatusType | claimNo | 단계별 시각 중 가장 늦은 값)
```

- `processed_event(idem_key UNIQUE)`에 insert 성공한 경우에만 상태를 반영한다.
- SQS FIFO 중복 제거는 5분 범위라, 며칠 뒤 복구 경로로 다시 들어오는 같은 이벤트는 DB 키로만 막을 수 있다.

### 4.3 상태 판정 ([ADR-0003](../adr/0003-status-rank-ordering.md))

도착 순서를 믿지 않는다. 주문 라인마다 현재 상태를 두고, 들어온 상태가 **앞선 단계일 때만** 반영한다.

```
DEPOSIT_WAIT < PAY_DONE < PRODUCT_PREPARE < DELIVERY_PREPARE < DELIVERY_ING < DELIVERY_DONE < BUY_CONFIRM
클레임(CANCEL_*, RETURN_*, EXCHANGE_*)은 별도 축으로 관리
```

- 같은 순위면 단계별 시각(payYmdt, releaseReadyYmdt, releaseYmdt 등)이 더 늦은 쪽을 채택한다.
- 되돌아가는 전이(예: DELIVERY_ING → PRODUCT_PREPARE)가 오면 반영하지 않고 `conflict`로 남긴다.
- FIFO 그룹 키를 주문번호로 할지 옵션번호로 할지는 처리량과 동시성 비교 후 ADR로 확정한다 (미결).

### 4.4 복구 경로 ([ADR-0002](../adr/0002-polling-is-source-of-truth.md))

| 경로 | 주기 | 하는 일 |
|---|---|---|
| 실패 웹훅 조회 `GET /webhooks/failed` | 10분 | 마지막 조회 시각(watermark)부터 지금까지. 검색 범위가 7일이라, watermark가 7일보다 오래되면 경보 |
| 상태 변경 재조회 `GET /orders?searchDateType=STATUS_CHANGE` | 5분 | 겹치는 구간을 두고 조회, 페이지당 최대 200건 |
| 대사 배치 | 매일 | 우리 DB와 샵바이 조회 결과, 3PL mock 상태를 비교해 차이 건을 `mismatch`로 기록 |

웹훅은 "빨리 알기 위한 신호", 조회 API는 "최종 기준".

### 4.5 전역 호출 제한 ([ADR-0004](../adr/0004-global-rate-limiter.md))

- Redis Lua 토큰 버킷 하나를 모든 인스턴스가 함께 쓴다. 한도는 80/s로 여유를 둔다.
- 응답 헤더 `ratelimit-available-level: EXHAUSTED`가 오면 버킷을 비우고 잠시 멈춘다.
- 429는 실패가 아니라 대기 신호다. 메시지 visibility timeout을 늘려 다시 받고, receive count에 포함되지 않게 별도 카운트로 관리한다.

### 4.6 취소·출고 경합 ([ADR-0005](../adr/0005-cancel-vs-shipment-flow.md))

취소 요청이 들어오면 3PL 상태를 먼저 본다.

| 3PL 상태 | 처리 | 샵바이 쪽 |
|---|---|---|
| 출고요청 | 3PL 취소 → 샵바이 취소 승인 | 정상 취소 |
| 출고작업 (피킹 전) | 3PL 취소 요청(수수료 발생). 결과가 오기 전까지 샵바이 배송보류 | `PUT /orders/hold-delivery` |
| 피킹 후 / 출고 완료 | 취소 불가 | `PUT /claims/{claimNo}/already-delivery`(이미출고) 호출, CS 회수 안내 목록에 추가 |

- 배송보류는 최대 1,000건, 하나라도 실패하면 전부 반영되지 않는다. 실패하면 건별로 쪼개 다시 시도한다.
- 송장 반영 `PUT /orders/change-status/by-shipping-no`는 최대 100건, 일부만 실패한다. 실패한 건만 골라 재시도한다.

### 4.7 3PL mock (파스토 규칙)

- 주문 수집 1분, 취소 수집 20분, 송장 15분 주기 폴링을 흉내낸다 (시간 배율 조정 가능).
- 출고 상태: 출고요청 → 출고작업 → 피킹 → 출고완료.
- 옵션명이 매핑표에 없으면 수집 실패 → `unlinked`(미연동)로 기록하고 사유 코드를 남긴다.
- 장애 스위치: 응답 지연, 타임아웃, 송장 늦게 주기(순서 역전).

### 4.8 지연 재처리

출고 확정이 송장보다 먼저 오는 경우(올리브영 WMS 글과 같은 상황) 바로 실패시키지 않고 지연 큐에 넣어 N분 뒤 다시 처리한다. 최대 횟수를 넘으면 `conflict`로 보내고 Slack 알림을 10분 단위로 묶어 보낸다.

### 4.9 CS 어드민 API

- 미연동 목록 (사유 코드: OPTION_NOT_MAPPED, PRODUCT_NOT_FOUND ...)
- 취소·출고 충돌 목록
- 재처리: 같은 요청을 여러 번 눌러도 한 번만 처리 (요청 ID 멱등)
- 처리 이력: 누가 언제 무엇을 했는지

## 5. 데이터 모델 (초안)

| 테이블 | 주요 컬럼 |
|---|---|
| webhook_inbox | id, received_at, event_type, raw_json, status |
| processed_event | idem_key(UQ), source(WEBHOOK/FAILED_WEBHOOK/POLLING), processed_at |
| order_line_state | order_product_option_no(PK), order_no, status, claim_status, stage_ymdt, version |
| shipment | order_product_option_no, tpl_status, invoice_no, updated_at |
| conflict / unlinked / mismatch | 대상 키, reason_code, detected_at, resolved_at, resolver |
| sync_watermark | source, last_success_at |

## 6. 장애 주입과 측정

| 넣을 장애 | 기대 결과 |
|---|---|
| 웹훅 30% 유실 | 재조회 주기 안에 불일치 0 |
| 같은 웹훅 3회 중복 | 상태 반영 1회 |
| 순서 역전 (DELIVERY_ING 다음 PAY_DONE) | 되돌아가지 않음 |
| 샵바이 mock 429 | DLQ 0건, 지연 후 처리 |
| 3PL 타임아웃, 송장 지연 | 지연 재처리 후 반영 |
| 피킹 이후 취소 | 이미출고 처리 + CS 목록 |

잴 지표: 불일치 건수 추이, 복구까지 걸린 시간, 웹훅 누락률과 복구 경로로 되찾은 건수, 취소 후 출고 건수, DLQ 건수.
**아직 측정 전이다. 숫자는 측정 후에만 문서와 포트폴리오에 넣는다.**

## 7. 미결 사항

- FIFO 그룹 키: 주문번호 vs 옵션번호
- 실제 운영 환경의 3PL과 연동 방식 (확인 불가, 공개 자료 없음)
- 샵바이 실제 응답이 스펙과 다를 수 있는 부분 (실계정 없이 검증 불가)
