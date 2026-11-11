package io.github.gumwoo.fulfillment.infra.sqs;

import java.util.Map;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/** 주문 이벤트 큐와 DLQ의 URL. createQueues면 없을 때 만든다 (DLQ 재전송 정책 포함). */
public class SqsQueues {

    private final String queueUrl;
    private final String dlqUrl;

    public SqsQueues(SqsClient sqs, SqsProperties props) {
        if (props.createQueues()) {
            this.dlqUrl = sqs.createQueue(CreateQueueRequest.builder().queueName(props.dlqName()).build()).queueUrl();
            String dlqArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                            .queueUrl(dlqUrl).attributeNames(QueueAttributeName.QUEUE_ARN).build())
                    .attributes().get(QueueAttributeName.QUEUE_ARN);
            String redrive = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\""
                    + props.maxReceiveCount() + "\"}";
            this.queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                    .queueName(props.queueName())
                    .attributes(Map.of(
                            QueueAttributeName.REDRIVE_POLICY, redrive,
                            QueueAttributeName.VISIBILITY_TIMEOUT, String.valueOf(props.visibilityTimeoutSeconds())))
                    .build()).queueUrl();
        } else {
            this.queueUrl = sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(props.queueName()).build()).queueUrl();
            this.dlqUrl = sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(props.dlqName()).build()).queueUrl();
        }
    }

    public String queueUrl() {
        return queueUrl;
    }

    public String dlqUrl() {
        return dlqUrl;
    }
}
