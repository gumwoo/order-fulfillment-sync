package io.github.gumwoo.fulfillment.core.port;

/**
 * 샵바이 서버 API 호출의 유일한 출구. 구현은 infra에 두고 전역 레이트 리미터(ADR-0004)를 거친다.
 * 메서드는 해당 기능(F-05 등)을 구현할 때 추가한다.
 */
public interface ShopbyClient {
}
