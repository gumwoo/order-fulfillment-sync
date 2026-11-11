package io.github.gumwoo.fulfillment.scenario.support;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/** 테스트에서 큐 내용을 직접 꺼내 보거나 개수를 센다. */
public final class SqsProbe {

    private SqsProbe() {
    }

    /** 일정 시간 동안 받을 수 있는 메시지를 모두 받아 지운다 */
    public static List<Message> drain(SqsClient sqs, String queueUrl, Duration within) {
        List<Message> all = new ArrayList<>();
        Instant until = Instant.now().plus(within);
        while (Instant.now().isBefore(until)) {
            List<Message> batch = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(1)
                    .messageAttributeNames("All").build()).messages();
            for (Message m : batch) {
                all.add(m);
                sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle(m.receiptHandle()).build());
            }
        }
        return all;
    }

    /** 대기 중 + 처리 중(보이지 않는) + 지연 메시지 수 */
    public static int approximateTotal(SqsClient sqs, String queueUrl) {
        var attrs = sqs.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED).build()).attributes();
        return attrs.values().stream().mapToInt(Integer::parseInt).sum();
    }
}
