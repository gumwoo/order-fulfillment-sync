package io.github.gumwoo.fulfillment.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.gumwoo.fulfillment.scenario.support.App;
import io.github.gumwoo.fulfillment.scenario.support.ScenarioStack;
import io.github.gumwoo.fulfillment.scenario.support.SqsProbe;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * F-02: CHANGE_ORDER_STATUS 웹훅 배열을 받으면 원본을 inbox에 저장하고 200을 응답하며,
 * 원소마다 SQS 메시지 1개가 발행된다.
 */
class WebhookInboundScenarioTest {

    ScenarioStack stack = ScenarioStack.get();
    HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        // worker를 띄우지 않아 발행된 메시지가 큐에 그대로 남는다
        stack.use(Set.of(App.INBOUND));
        stack.reset();
    }

    @Test
    void 배열_원소마다_메시지_1개가_발행되고_원본은_inbox에_남는다() throws Exception {
        String body = "[" + element(1001, "PAY_DONE") + "," + element(1002, "PAY_DONE") + ","
                + element(1003, "PRODUCT_PREPARE") + "]";

        HttpResponse<String> res = post(body);

        assertThat(res.statusCode()).isEqualTo(200);
        List<Map<String, Object>> inbox = stack.jdbc().queryForList("select event_type, raw_json, status from webhook_inbox");
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0)).containsEntry("event_type", "CHANGE_ORDER_STATUS").containsEntry("status", "QUEUED");
        assertThat((String) inbox.get(0).get("raw_json")).isEqualTo(body);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(stack.jdbc().queryForObject("select count(*) from outbox where status = 'SENT'", Long.class))
                        .isEqualTo(3L));
        List<Message> messages = SqsProbe.drain(stack.sqs(), stack.queueUrl(ScenarioStack.QUEUE), Duration.ofSeconds(3));
        assertThat(messages).hasSize(3);
        assertThat(messages).allSatisfy(m -> {
            assertThat(m.messageAttributes().get("type").stringValue()).isEqualTo("ORDER_STATUS_CHANGED");
            assertThat(m.messageAttributes().get("source").stringValue()).isEqualTo("WEBHOOK");
        });
        Set<String> optionNos = messages.stream()
                .map(m -> m.body().replaceAll(".*\"orderProductOptionNo\":(\\d+).*", "$1"))
                .collect(Collectors.toSet());
        assertThat(optionNos).containsExactlyInAnyOrder("1001", "1002", "1003");
    }

    @Test
    void 파싱할_수_없는_본문도_200을_주고_원본을_남기며_메시지는_없다() throws Exception {
        HttpResponse<String> res = post("{not-json");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(stack.jdbc().queryForObject("select status from webhook_inbox", String.class)).isEqualTo("INVALID");
        assertThat(stack.jdbc().queryForObject("select count(*) from outbox", Long.class)).isZero();
    }

    @Test
    void 주문상태변경이_아닌_이벤트는_원본만_남기고_메시지는_없다() throws Exception {
        HttpResponse<String> res = post("{\"eventType\":\"MEMBER_CREATED\",\"mallNo\":1}");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(stack.jdbc().queryForObject("select status from webhook_inbox", String.class)).isEqualTo("IGNORED");
        assertThat(stack.jdbc().queryForObject("select count(*) from outbox", Long.class)).isZero();
    }

    @Test
    void 배열에_다른_이벤트나_객체가_아닌_원소가_섞이면_주문상태변경_원소만_발행한다() throws Exception {
        String body = "[" + element(2001, "PAY_DONE") + ",{\"eventType\":\"MEMBER_CREATED\"},5,null,"
                + element(2002, "PAY_DONE") + "]";

        assertThat(post(body).statusCode()).isEqualTo(200);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(stack.jdbc().queryForObject("select count(*) from outbox where status = 'SENT'", Long.class))
                        .isEqualTo(2L));
        assertThat(stack.jdbc().queryForObject("select count(*) from outbox", Long.class)).isEqualTo(2L);
        assertThat(SqsProbe.drain(stack.sqs(), stack.queueUrl(ScenarioStack.QUEUE), Duration.ofSeconds(3))).hasSize(2);
    }

    @Test
    void 단일_객체_형태의_주문상태변경도_메시지_1개로_발행한다() throws Exception {
        assertThat(post(element(3001, "PAY_DONE")).statusCode()).isEqualTo(200);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(stack.jdbc().queryForObject("select count(*) from outbox where status = 'SENT'", Long.class))
                        .isEqualTo(1L));
        assertThat(SqsProbe.drain(stack.sqs(), stack.queueUrl(ScenarioStack.QUEUE), Duration.ofSeconds(3))).hasSize(1);
    }

    @Test
    void 릴레이가_동시에_돌아도_원소마다_메시지는_1개다() throws Exception {
        // inbound 인스턴스 여러 대를 흉내: 같은 PENDING 행을 여러 스레드가 동시에 발행하려 한다 (SKIP LOCKED 검증)
        StringBuilder body = new StringBuilder("[");
        int n = 300;
        for (int i = 0; i < n; i++) {
            body.append(i == 0 ? "" : ",").append(element(10_000 + i, "PAY_DONE"));
        }
        body.append("]");
        assertThat(post(body.toString()).statusCode()).isEqualTo(200);

        var relay = stack.bean(App.INBOUND, io.github.gumwoo.fulfillment.inbound.webhook.OutboxRelay.class);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < 4; t++) {
            futures.add(pool.submit(relay::relay));
        }
        for (var f : futures) {
            f.get();
        }
        pool.shutdown();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(stack.jdbc().queryForObject("select count(*) from outbox where status = 'SENT'", Long.class))
                        .isEqualTo((long) n));
        List<Message> messages = SqsProbe.drain(stack.sqs(), stack.queueUrl(ScenarioStack.QUEUE), Duration.ofSeconds(5));
        Set<String> optionNos = messages.stream()
                .map(m -> m.body().replaceAll(".*\"orderProductOptionNo\":(\\d+).*", "$1"))
                .collect(Collectors.toSet());
        assertThat(messages).hasSize(n);
        assertThat(optionNos).hasSize(n);
    }

    private HttpResponse<String> post(String body) throws Exception {
        URI uri = stack.baseUrl(App.INBOUND).resolve("/webhooks/shopby");
        return http.send(HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static String element(long optionNo, String status) {
        return "{\"eventType\":\"CHANGE_ORDER_STATUS\",\"orderProductOptionNo\":" + optionNo
                + ",\"orderNo\":\"202611110000001\",\"deliveryNo\":5001,\"orderStatusType\":\"" + status
                + "\",\"claimStatusType\":null,\"claimNo\":null,\"payYmdt\":\"2026-11-11 10:00:00\"}";
    }
}
