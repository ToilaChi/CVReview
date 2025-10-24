package org.example.aiservice.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableRabbit
public class RabbitMQConfig {

    // ====== CONSTANTS ======
    public static final String AI_EXCHANGE = "cv.analysis.exchange";
    public static final String AI_EXCHANGE_DLX = "cv.analysis.exchange.dlx";

    public static final String CV_ANALYZE_QUEUE = "cv.analyze.queue";
    public static final String CV_ANALYZE_DLQ = "cv.analyze.queue.dlq";
    public static final String CV_ANALYZE_ROUTING_KEY = "cv.analyze";
    public static final String CV_ANALYZE_DLQ_ROUTING_KEY = "cv.analyze.dlq";

    public static final String CV_ANALYSIS_RESULT_QUEUE = "cv.analysis.result.queue";
    public static final String CV_ANALYSIS_RESULT_ROUTING_KEY = "cv.analysis.result";

    public static final String CV_ANALYSIS_FAILED_ROUTING_KEY = "cv.analysis.failed";

    // ====== EXCHANGES ======
    @Bean
    public DirectExchange aiExchange() {
        return new DirectExchange(AI_EXCHANGE);
    }

    @Bean
    public DirectExchange aiDeadLetterExchange() {
        return new DirectExchange(AI_EXCHANGE_DLX);
    }

    // ====== QUEUES - AI-Service consumes these ======

    // ✅ cv.analyze.queue - KHÔNG CÓ x-dead-letter-exchange (dùng RepublishMessageRecoverer)
    @Bean
    public Queue cvAnalyzeQueue() {
        return QueueBuilder.durable(CV_ANALYZE_QUEUE).build();
    }

    // ✅ cv.analyze.queue.dlq - DLQ cho analyze queue
    @Bean
    public Queue cvAnalyzeDlqQueue() {
        return QueueBuilder.durable(CV_ANALYZE_DLQ).build();
    }

    // ====== BINDINGS ======
    @Bean
    public Binding cvAnalyzeBinding(Queue cvAnalyzeQueue, DirectExchange aiExchange) {
        return BindingBuilder.bind(cvAnalyzeQueue)
                .to(aiExchange)
                .with(CV_ANALYZE_ROUTING_KEY);
    }

    @Bean
    public Binding cvAnalyzeDlqBinding(Queue cvAnalyzeDlqQueue, DirectExchange aiDeadLetterExchange) {
        return BindingBuilder.bind(cvAnalyzeDlqQueue)
                .to(aiDeadLetterExchange)
                .with(CV_ANALYZE_DLQ_ROUTING_KEY);
    }

    // ====== MESSAGE CONVERTER ======
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ====== RETRY TEMPLATE - 3 lần retry với exponential backoff ======
    @Bean
    public RetryTemplate retryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();

        // Exponential backoff: 2.5s, 5s, 10s
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(2500);
        backOffPolicy.setMultiplier(2.0);
        backOffPolicy.setMaxInterval(10000);
        retryTemplate.setBackOffPolicy(backOffPolicy);

        // Retry 3 lần cho RuntimeException, skip IllegalArgumentException
        Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();
        retryableExceptions.put(RuntimeException.class, true);
        retryableExceptions.put(IllegalArgumentException.class, false);

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(3, retryableExceptions, true);
        retryTemplate.setRetryPolicy(retryPolicy);

        return retryTemplate;
    }

    // ====== MESSAGE RECOVERER - Sau 3 retry thất bại → DLQ ======
    @Bean
    public MessageRecoverer messageRecoverer(RabbitTemplate rabbitTemplate) {
        // ✅ Sau khi hết 3 lần retry → Publish vào DLQ 1 lần duy nhất
        return new RepublishMessageRecoverer(
                rabbitTemplate,
                AI_EXCHANGE_DLX,
                CV_ANALYZE_DLQ_ROUTING_KEY
        );
    }

    // ====== LISTENER FACTORY ======
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            MessageRecoverer messageRecoverer) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);

        // Concurrency settings
        factory.setConcurrentConsumers(2);
        factory.setMaxConcurrentConsumers(3);
        factory.setPrefetchCount(1);

        // ✅ KHÔNG requeue message
        factory.setDefaultRequeueRejected(false);

        return factory;
    }

    // ====== RABBIT TEMPLATE ======
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);

        // ✅ Cấu hình retry cho RabbitTemplate
        template.setRetryTemplate(retryTemplate());

        return template;
    }
}
