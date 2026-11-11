package io.github.gumwoo.fulfillment.infra.sqs;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param endpoint                 LocalStack 등 엔드포인트 (비우면 AWS 기본)
 * @param region                   리전
 * @param queueName                주문 이벤트 큐 (Standard, ADR-0006)
 * @param dlqName                  DLQ
 * @param maxReceiveCount          이 횟수보다 많이 수신되면 DLQ로 간다
 * @param visibilityTimeoutSeconds 수신 후 다른 소비자에게 안 보이는 시간
 * @param waitTimeSeconds          long polling 대기
 * @param createQueues             시작 시 큐가 없으면 만든다 (로컬·테스트용)
 * @param consumerThreads          소비 스레드 수
 */
@ConfigurationProperties("app.sqs")
public record SqsProperties(
        String endpoint,
        String region,
        String queueName,
        String dlqName,
        int maxReceiveCount,
        int visibilityTimeoutSeconds,
        int waitTimeSeconds,
        boolean createQueues,
        int consumerThreads) {

    public SqsProperties {
        if (region == null) {
            region = "ap-northeast-2";
        }
        if (queueName == null) {
            queueName = "order-events";
        }
        if (dlqName == null) {
            dlqName = "order-events-dlq";
        }
        if (maxReceiveCount <= 0) {
            maxReceiveCount = 5;
        }
        if (visibilityTimeoutSeconds <= 0) {
            visibilityTimeoutSeconds = 30;
        }
        if (waitTimeSeconds <= 0) {
            waitTimeSeconds = 5;
        }
        if (consumerThreads <= 0) {
            consumerThreads = 2;
        }
    }
}
