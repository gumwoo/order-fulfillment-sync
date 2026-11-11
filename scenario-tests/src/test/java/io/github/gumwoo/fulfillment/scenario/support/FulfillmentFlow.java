package io.github.gumwoo.fulfillment.scenario.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.gumwoo.fulfillment.worker.message.ProcessingStats;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 3PL 연동 시나리오(F-08~F-13)의 공통 준비: mock·inbound·worker(연동 켬)를 띄우고 결제완료 주문을 출고요청까지 진행한다. */
public final class FulfillmentFlow {

    private final ScenarioStack stack;
    private final MockShopby mock;

    private FulfillmentFlow(ScenarioStack stack, MockShopby mock) {
        this.stack = stack;
        this.mock = mock;
    }

    /** @param extraApps 추가로 띄울 앱 (예: ADMIN, RECONCILER) */
    public static FulfillmentFlow start(Set<App> extraApps) {
        ScenarioStack stack = ScenarioStack.get();
        Map<App, Map<String, Object>> apps = new LinkedHashMap<>();
        for (App app : App.values()) {
            if (app == App.MOCK || app == App.INBOUND) {
                apps.put(app, Map.of());
            } else if (app == App.WORKER) {
                apps.put(app, Map.of("app.worker.fulfillment-enabled", "true"));
            } else if (extraApps.contains(app)) {
                apps.put(app, Map.of());
            }
        }
        stack.use(apps);
        stack.reset();
        MockShopby mock = new MockShopby(stack.mockPort());
        mock.reset();
        mock.webhook(stack.inboundWebhookUrl(), 0, false, 1, 1);
        stack.bean(App.WORKER, ProcessingStats.class).reset();
        return new FulfillmentFlow(stack, mock);
    }

    public MockShopby mock() {
        return mock;
    }

    public ScenarioStack stack() {
        return stack;
    }

    /** 결제완료 주문을 만들고, 우리 시스템이 3PL 출고요청과 샵바이 상품준비중까지 마치기를 기다린다 */
    public void createPaidOrderAndAwaitRequested(long optionNo, long deliveryNo) {
        mock.createOrder("20261111" + optionNo, deliveryNo, "PAY_DONE", true,
                List.of(MockShopby.option(optionNo, "색상", "검정")));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(mock.option(optionNo).path("orderStatusType").asText()).isEqualTo("PRODUCT_PREPARE");
            assertThat(mock.tplOrder(optionNo).path("status").asText()).isEqualTo("REQUESTED");
        });
    }

    public String cancelState(long optionNo) {
        List<String> s = stack.jdbc().queryForList(
                "select state from cancel_case where order_product_option_no = ?", String.class, optionNo);
        return s.isEmpty() ? null : s.get(0);
    }

    public Map<String, Object> cancelCase(long optionNo) {
        return stack.jdbc().queryForMap(
                "select state, tpl_status_at_request, fee from cancel_case where order_product_option_no = ?", optionNo);
    }
}
