package io.github.gumwoo.fulfillment.infra.sqs;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

/** 실행 모듈이 @Import 해서 큐 발행(MessageQueue)을 쓴다. 소비는 worker가 SqsQueueConsumer를 만든다. */
@Configuration
@EnableConfigurationProperties(SqsProperties.class)
public class SqsConfig {

    @Bean(destroyMethod = "close")
    public SqsClient sqsClient(SqsProperties props) {
        SqsClientBuilder builder = SqsClient.builder().region(Region.of(props.region()));
        if (props.endpoint() != null && !props.endpoint().isBlank()) {
            // LocalStack: 로컬 전용 더미 자격 증명
            builder.endpointOverride(URI.create(props.endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        return builder.build();
    }

    @Bean
    public SqsQueues sqsQueues(SqsClient sqs, SqsProperties props) {
        return new SqsQueues(sqs, props);
    }

    @Bean
    public SqsMessageQueue sqsMessageQueue(SqsClient sqs, SqsQueues queues) {
        return new SqsMessageQueue(sqs, queues);
    }
}
