package org.example.recruitmentservice.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
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

    // ====== UPLOAD FLOW (Recruitment-service consumes) ======
    public static final String CV_UPLOAD_QUEUE = "cv.upload.queue";
    public static final String CV_UPLOAD_DLQ = "cv.upload.queue.dlq";
    public static final String CV_UPLOAD_EXCHANGE = "cv.upload.exchange.dlx";
    public static final String CV_UPLOAD_DLQ_ROUTING_KEY = "cv.upload.dlq";

    // ====== ANALYSIS FLOW - CONSTANTS ONLY (AI-Service đã declare) ======
    public static final String AI_EXCHANGE = "cv.analysis.exchange";
    public static final String AI_EXCHANGE_DLX = "cv.analysis.exchange.dlx";

    // ❌ KHÔNG declare cv.analyze.queue (AI-Service đã declare)
    public static final String CV_ANALYZE_QUEUE = "cv.analyze.queue";
    public static final String CV_ANALYZE_ROUTING_KEY = "cv.analyze";

    // ✅ Recruitment-service CONSUMES these queues
    public static final String CV_ANALYSIS_RESULT_QUEUE = "cv.analysis.result.queue";
    public static final String CV_ANALYSIS_RESULT_DLQ = "cv.analysis.result.queue.dlq";
    public static final String CV_ANALYSIS_RESULT_ROUTING_KEY = "cv.analysis.result";
    public static final String CV_ANALYSIS_RESULT_DLQ_ROUTING_KEY = "cv.analysis.result.dlq";

    public static final String CV_ANALYSIS_FAILED_QUEUE = "cv.analysis.failed.queue";
    public static final String CV_ANALYSIS_FAILED_ROUTING_KEY = "cv.analysis.failed";

    // ====== UPLOAD QUEUE + DLQ (Recruitment-service owns) ======
    @Bean
    public Queue mainQueue() {
        return QueueBuilder.durable(CV_UPLOAD_QUEUE)
                .withArgument("x-dead-letter-exchange", CV_UPLOAD_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", CV_UPLOAD_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(CV_UPLOAD_DLQ).build();
    }

    @Bean
    public DirectExchange mainExchange() {
        return new DirectExchange(CV_UPLOAD_EXCHANGE);
    }

    @Bean
    public Binding mainBinding(Queue mainQueue, DirectExchange mainExchange) {
        return BindingBuilder.bind(mainQueue)
                .to(mainExchange)
                .with(CV_UPLOAD_DLQ_ROUTING_KEY);
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange mainExchange) {
        return BindingBuilder.bind(deadLetterQueue)
                .to(mainExchange)
                .with(CV_UPLOAD_DLQ_ROUTING_KEY + ".dlq");
    }

    // ====== EXCHANGES (shared with AI-Service) ======
    @Bean
    public DirectExchange aiExchange() {
        return new DirectExchange(AI_EXCHANGE);
    }

    @Bean
    public DirectExchange aiDeadLetterExchange() {
        return new DirectExchange(AI_EXCHANGE_DLX);
    }

    // ====== ANALYSIS RESULT QUEUE (Recruitment-service consumes) ======
    @Bean
    public Queue cvAnalysisResultQueue() {
        return QueueBuilder.durable(CV_ANALYSIS_RESULT_QUEUE)
                .withArgument("x-dead-letter-exchange", AI_EXCHANGE_DLX)
                .withArgument("x-dead-letter-routing-key", CV_ANALYSIS_RESULT_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue cvAnalysisResultDlqQueue() {
        return QueueBuilder.durable(CV_ANALYSIS_RESULT_DLQ).build();
    }

    @Bean
    public Binding cvAnalysisResultBinding(Queue cvAnalysisResultQueue, DirectExchange aiExchange) {
        return BindingBuilder.bind(cvAnalysisResultQueue)
                .to(aiExchange)
                .with(CV_ANALYSIS_RESULT_ROUTING_KEY);
    }

    @Bean
    public Binding cvAnalysisResultDlqBinding(Queue cvAnalysisResultDlqQueue, DirectExchange aiDeadLetterExchange) {
        return BindingBuilder.bind(cvAnalysisResultDlqQueue)
                .to(aiDeadLetterExchange)
                .with(CV_ANALYSIS_RESULT_DLQ_ROUTING_KEY);
    }

    // ====== ANALYSIS FAILED QUEUE (Recruitment-service consumes) ======
    @Bean
    public Queue cvAnalysisFailedQueue() {
        return QueueBuilder.durable(CV_ANALYSIS_FAILED_QUEUE).build();
    }

    @Bean
    public Binding cvAnalysisFailedBinding(Queue cvAnalysisFailedQueue, DirectExchange aiExchange) {
        return BindingBuilder.bind(cvAnalysisFailedQueue)
                .to(aiExchange)
                .with(CV_ANALYSIS_FAILED_ROUTING_KEY);
    }

    // ====== COMMON SETTINGS ======
    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            RetryTemplate retryTemplate) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setConcurrentConsumers(2);
        factory.setMaxConcurrentConsumers(3);
        factory.setPrefetchCount(2);
        factory.setRetryTemplate(retryTemplate);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    @Bean
    public RetryTemplate retryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();

        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(2500);
        backOffPolicy.setMultiplier(2.0);
        backOffPolicy.setMaxInterval(10000);
        retryTemplate.setBackOffPolicy(backOffPolicy);

        Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();
        retryableExceptions.put(RuntimeException.class, true);
        retryableExceptions.put(IllegalArgumentException.class, false);

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(3, retryableExceptions, true);
        retryTemplate.setRetryPolicy(retryPolicy);

        return retryTemplate;
    }
}
