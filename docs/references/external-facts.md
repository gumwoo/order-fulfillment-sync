# 외부 시스템 제약 (확인한 것만)

코드는 이 문서에 있는 동작만 가정한다. 확인 날짜: 2026-11-11.

## 샵바이 서버 API

출처: 공개 OpenAPI 스펙
- https://server-docs.shopby.co.kr/spec/order-server-public.yml
- https://server-docs.shopby.co.kr/spec/claim-server-public.yml
- https://server-docs.shopby.co.kr/spec/workspace-server-public.yml

| API | 제약 |
|---|---|
| `GET /webhooks/failed` | 검색 기간 최대 7일. 실패 내역 보관 6개월 |
| `GET /orders` (v1.1) | `searchDateType=STATUS_CHANGE` 지원. pageSize 최대 200 |
| `PUT /orders/hold-delivery` | PRODUCT_PREPARE, DELIVERY_PREPARE 상태만. 최대 1,000건. 하나라도 실패하면 전체 미반영 |
| `PUT /orders/change-status/by-shipping-no` | 최대 100건. 부분 실패 (successCount, failCount, failures 반환) |
| `PUT /claims/{claimNo}/already-delivery` | 취소 신청 후 이미 출고된 경우: 클레임 철회, 배송중으로 변경 |
| 호출 제한 | Token Bucket, 초당 100회. 초과 시 429. 헤더 `ratelimit-available-level` 값 `EXHAUSTED` |
| 인증 | systemKey + Bearer 토큰. 앱 등록과 심사 필요 |

### 요청·응답 형태 (2026-11-11 스펙에서 추가 확인)

| API | 확인한 내용 |
|---|---|
| `GET /orders` v1.1 | 쿼리 `startYmdt`, `endYmdt`(`YYYY-MM-DD HH:mm:ss`), `searchDateType`(STATUS_CHANGE 포함), `orderRequestTypes`, `pageNumber`(1부터), `pageSize`(최대 200). 헤더 `Version`, `systemKey`, `Authorization`. 응답은 주문번호 내림차순 |
| `GET /orders` 응답 | `totalCount`, `contents[].orderNo`, `contents[].deliveryGroups[].deliveryNo`·`invoiceNo`·`deliveryCompanyType`, `...orderProducts[].orderProductOptions[]`의 `orderProductOptionNo`, `orderStatusType`, `claimStatusType`, `claimNo`, `optionName`, `optionValue`, `orderYmdt`, `payYmdt`, `orderAcceptYmdt`, `releaseReadyYmdt`, `releaseYmdt`, `deliveryCompleteYmdt`, `buyConfirmYmdt`, `statusChangeYmdt`, `holdDeliveryYn` |
| `GET /webhooks/failed` | 쿼리 `startDateTime`, `endDateTime`(필수), `page`, `pageSize`, `eventType`. 응답 `totalCount`, `contents[]`의 `eventType`, `data`(웹훅 송신 데이터), `exceptionDateTime`, `exceptionType`, `exceptionMessage`, `webhookUrl`, `httpMethod`, `mallNo` |
| `PUT /orders/prepare-product` | 결제완료 → 상품준비중. Body는 주문옵션번호 배열. 최대 100건 권장. 하나라도 실패하면 전부 미반영. 성공 204 |
| `PUT /orders/hold-delivery` | Body `orderOptionNos`, `holdDeliveryReason`(nullable). 성공 204 |
| `PUT /orders/change-status/by-shipping-no` | Body `orderStatusType`, `changeStatusList[]`(`shippingNo`, `deliveryCompanyType`, `invoiceNo`, 최대 100). 응답 `totalCount`, `successCount`, `failCount`, `failures[]`(`originalRequest`, `errorCode`, `errorMessage`). 출고 전(PAY_DONE, PRODUCT_PREPARE, DELIVERY_PREPARE)에서 출고 후(DELIVERY_ING, DELIVERY_DONE, BUY_CONFIRM)로만 변경 |
| `PUT /claims/{claimNo}/approve` | 클레임 승인. 주문 취소·옵션 취소는 호출 성공 즉시 클레임 완료. 성공 204 |
| `PUT /claims/{claimNo}/already-delivery` | Body `deliveryCompanyType`(nullable), `releaseYmd`(`yyyy-MM-dd`), `invoiceNo`(nullable). 클레임 철회 후 배송중으로 변경. 성공 204 |
| 취소 클레임 상태 | `CANCEL_REQUEST`(취소신청, 승인대기), `CANCEL_PROC_REQUEST_REFUND`, `CANCEL_PROC_WAITING_REFUND`, `CANCEL_DONE`, `CANCEL_NO_REFUND` |

- `shippingNo`(by-shipping-no)와 `deliveryNo`(조회·웹훅)는 둘 다 배송번호다.

## 샵바이 웹훅

출처: https://workspace-help.nhn-commerce.com/app-guide/shopby_webhook ,
https://workspace-help.nhn-commerce.com/contents/recommended/webhook

- 실패한 웹훅은 재전송하지 않는다. 주문 상태 조회 API와 함께 쓰라고 안내한다.
- `CHANGE_ORDER_STATUS`는 주문상품 옵션(`orderProductOptionNo`) 단위 JSON 배열.
- 이벤트 ID와 이벤트 발생 시각 필드가 없다. 단계별 시각만 있다 (orderYmdt, payYmdt, releaseReadyYmdt, releaseYmdt 등).
- 주문 상태: DEPOSIT_WAIT, PAY_DONE, PRODUCT_PREPARE, DELIVERY_PREPARE, DELIVERY_ING, DELIVERY_DONE, BUY_CONFIRM.
- `CHANGE_ORDER_STATUS` 원소 필드(가이드 샘플, 2026-11-11 확인)
  - 식별: `eventType`, `orderProductOptionNo`, `orderNo`, `deliveryNo`, `mallProductNo`, `productName`, `optionName`, `optionValue`, `orderCnt`
  - 상태: `orderStatusType`, `claimStatusType`(nullable), `claimNo`(nullable)
  - 단계별 시각: `orderYmdt`, `payYmdt`, `orderAcceptYmdt`, `releaseReadyYmdt`, `releaseYmdt`, `deliveryCompleteYmdt`, `buyConfirmYmdt`, `registerYmdt`
  - 배송: `deliveryCompanyType`, `invoiceNo`
  - 그 외: `requestChannelType`
  - 조회 API에 있는 `statusChangeYmdt`는 웹훅에 없다.
- 웹훅 설정 method는 POST 또는 PUT.

## 확인하지 못한 것 (샵바이)

- `GET /webhooks/failed`의 `data` 필드 형식: 스펙 예시가 `"data"` 문자열뿐이다.
  - 그래서 코드는 `data`를 파싱하지 않는다.
  - 대신 `exceptionDateTime` 앞뒤 구간을 `searchDateType=STATUS_CHANGE`로 다시 조회한다.
- 오류 응답 본문 형식: 코드는 HTTP 상태 코드만 본다.
- `ratelimit-available-level`의 `EXHAUSTED` 외 값: 코드는 `EXHAUSTED`만 판단한다.

## AWS SQS

출처:
- https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/standard-queues.html
- https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/using-messagededuplicationid-property.html
- https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/quotas-messages.html

- Standard: 최소 1회 전달, 순서가 바뀔 수 있음. 소비자를 멱등하게 만들라고 명시.
- FIFO: MessageDeduplicationId 중복 제거 범위 5분. MessageGroupId 단위로 순서 보장.
- FIFO 처리량(일반 모드): 초당 300건, 배치 사용 시 3,000건.
- 메시지 타이머(메시지별 `DelaySeconds`): 0초~15분. **FIFO 큐는 메시지별 타이머를 지원하지 않는다.**
  큐 전체 지연만 가능하다.
  출처: https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-message-timers.html (2026-11-11 확인)
- DLQ `maxReceiveCount`: 소비자가 메시지를 **수신한 횟수**가 이 값을 넘으면 DLQ로 옮긴다.
  visibility timeout을 늘려도 다시 수신하면 횟수가 오른다.
  출처: https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-dead-letter-queues.html (2026-11-11 확인)

## 파스토 (3PL)

출처: https://guide-kr.fassto.ai/linkage/shopby , 파스토 출고 취소 안내 (가이드 사이트)

- 샵바이 주문 수집 1분, 취소 20분, 송장 15분 주기.
- 쇼핑몰에서 옵션명을 바꾸면 수집 실패, "미연동 내역"에 표시.
- 샵바이 교환·반품은 수집하지 않는다.
- 출고 취소는 '출고요청' 상태에서 가능. '출고작업' 이후에는 피킹 전까지만, 수수료 1,000원.

## 참고한 실무 글

- 올리브영 WMS(GMS) 구축기: https://oliveyoung.tech/2025-07-23/gms-open-story/ (순서 역전, 지연 재처리, Slack 묶음 알림)
- 웹훅 처리 시리즈 1~5편: https://velog.io/@cjy1705/series (Redis 락 한계, SQS FIFO, 전역 레이트 리미터, 지표 기반 협의)
- 취소 후 출고 사례: https://blog.naver.com/loopra/224313892055

## 확인하지 못한 것

- 실계정에서의 실제 응답 형태, 웹훅 지연 시간 분포
- 실제 운영 환경의 3PL과 연동 방식
