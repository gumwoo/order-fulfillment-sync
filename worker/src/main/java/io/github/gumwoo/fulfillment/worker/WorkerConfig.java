package io.github.gumwoo.fulfillment.worker;

import io.github.gumwoo.fulfillment.infra.sqs.SqsMessageQueue;
import io.github.gumwoo.fulfillment.infra.sqs.SqsProperties;
import io.github.gumwoo.fulfillment.infra.sqs.SqsQueueConsumer;
import io.github.gumwoo.fulfillment.infra.sqs.SqsQueues;
import io.github.gumwoo.fulfillment.worker.message.MessageRouter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class WorkerConfig {

    @Bean
    @ConditionalOnProperty(name = "app.worker.consumer-enabled", havingValue = "true", matchIfMissing = true)
    public SqsQueueConsumer sqsQueueConsumer(SqsClient sqs, SqsQueues queues, SqsProperties props,
                                             SqsMessageQueue publisher, MessageRouter router) {
        return new SqsQueueConsumer(sqs, queues, props, publisher, router);
    }
}
