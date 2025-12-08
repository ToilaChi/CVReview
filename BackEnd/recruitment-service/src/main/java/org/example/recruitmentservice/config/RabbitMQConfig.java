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
import org.springframework.transaction.PlatformTransactionManager;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableRabbit
public class RabbitMQConfig {

    /* ============================================================
     * 1. UPLOAD FLOW (Recruitment-service OWN, consumes)
     * ============================================================ */
    public static final String CV_UPLOAD_QUEUE = "cv.upload.queue";
    public static final String CV_UPLOAD_DLQ = "cv.upload.queue.dlq";
    public static final String CV_UPLOAD_EXCHANGE = "cv.upload.exchange.dlx";
    public static final String CV_UPLOAD_DLQ_ROUTING_KEY = "cv.upload.dlq";

    // Main upload queue
    @Bean
    public Queue cvUploadQueue() {
        return QueueBuilder.durable(CV_UPLOAD_QUEUE)
                .withArgument("x-dead-letter-exchange", CV_UPLOAD_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", CV_UPLOAD_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue cvUploadDlqQueue() {
        return QueueBuilder.durable(CV_UPLOAD_DLQ).build();
    }

    @Bean
    public DirectExchange cvUploadExchange() {
        return new DirectExchange(CV_UPLOAD_EXCHANGE);
    }

    @Bean
    public Binding cvUploadBinding(Queue cvUploadQueue, DirectExchange cvUploadExchange) {
        return BindingBuilder.bind(cvUploadQueue)
                .to(cvUploadExchange)
                .with(CV_UPLOAD_DLQ_ROUTING_KEY);
    }

    @Bean
    public Binding cvUploadDlqBinding(Queue cvUploadDlqQueue, DirectExchange cvUploadExchange) {
        return BindingBuilder.bind(cvUploadDlqQueue)
                .to(cvUploadExchange)
                .with(CV_UPLOAD_DLQ_ROUTING_KEY + ".dlq");
    }


    /* ============================================================
     * 2. JD PARSED FLOW (Recruitment-service OWN, consumes)
     * ============================================================ */
    public static final String JD_PARSED_QUEUE = "jd.parsed.queue";
    public static final String JD_PARSED_DLQ = "jd.parsed.queue.dlq";
    public static final String JD_PARSED_EXCHANGE = "jd.parsed.exchange";
    public static final String JD_PARSED_ROUTING_KEY = "jd.parsed";
    public static final String JD_PARSED_DLQ_ROUTING_KEY = "jd.parsed.dlq";

    @Bean
    public Queue jdParsedQueue() {
        return QueueBuilder.durable(JD_PARSED_QUEUE)
                .withArgument("x-dead-letter-exchange", JD_PARSED_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", JD_PARSED_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue jdParsedDlq() {
        return QueueBuilder.durable(JD_PARSED_DLQ).build();
    }

    @Bean
    public DirectExchange jdParsedExchange() {
        return new DirectExchange(JD_PARSED_EXCHANGE);
    }

    @Bean
    public Binding jdParsedBinding(Queue jdParsedQueue, DirectExchange jdParsedExchange) {
        return BindingBuilder.bind(jdParsedQueue)
                .to(jdParsedExchange)
                .with(JD_PARSED_ROUTING_KEY);
    }

    @Bean
    public Binding jdParsedDlqBinding(Queue jdParsedDlq, DirectExchange jdParsedExchange) {
        return BindingBuilder.bind(jdParsedDlq)
                .to(jdParsedExchange)
                .with(JD_PARSED_DLQ_ROUTING_KEY);
    }

    /* ============================================================
     * 3. CV CHUNKED FLOW
     * ============================================================ */
    public static final String CV_CHUNKED_QUEUE = "cv.chunked.queue";
    public static final String CV_CHUNKED_DLQ = "cv.chunked.queue.dlq";
    public static final String CV_CHUNKED_EXCHANGE = "cv.chunked.exchange";
    public static final String CV_CHUNKED_ROUTING_KEY = "cv.chunked";
    public static final String CV_CHUNKED_DLQ_ROUTING_KEY = "cv.chunked.dlq";

    @Bean
    public Queue cvChunkedQueue() {
        return QueueBuilder.durable(CV_CHUNKED_QUEUE)
                .withArgument("x-dead-letter-exchange", CV_CHUNKED_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", CV_CHUNKED_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue cvChunkedDlq() {
        return QueueBuilder.durable(CV_CHUNKED_DLQ).build();
    }

    @Bean
    public DirectExchange cvChunkedExchange() {
        return new DirectExchange(CV_CHUNKED_EXCHANGE);
    }

    @Bean
    public Binding cvChunkedBinding(Queue cvChunkedQueue, DirectExchange cvChunkedExchange) {
        return BindingBuilder.bind(cvChunkedQueue)
                .to(cvChunkedExchange)
                .with(CV_CHUNKED_ROUTING_KEY);
    }

    @Bean
    public Binding cvChunkedDlqBinding(Queue cvChunkedDlq, DirectExchange cvChunkedExchange) {
        return BindingBuilder.bind(cvChunkedDlq)
                .to(cvChunkedExchange)
                .with(CV_CHUNKED_DLQ_ROUTING_KEY);
    }


    /* ============================================================
     * 4. AI ANALYSIS FLOW
     *    Recruitment-service consumes, AI-service owns exchange
     * ============================================================ */
    public static final String AI_EXCHANGE = "cv.analysis.exchange";
    public static final String AI_EXCHANGE_DLX = "cv.analysis.exchange.dlx";

    public static final String CV_ANALYZE_QUEUE = "cv.analyze.queue";
    public static final String CV_ANALYZE_ROUTING_KEY = "cv.analyze";

    public static final String CV_ANALYSIS_RESULT_QUEUE = "cv.analysis.result.queue";
    public static final String CV_ANALYSIS_RESULT_DLQ = "cv.analysis.result.queue.dlq";
    public static final String CV_ANALYSIS_RESULT_ROUTING_KEY = "cv.analysis.result";
    public static final String CV_ANALYSIS_RESULT_DLQ_ROUTING_KEY = "cv.analysis.result.dlq";

    public static final String CV_ANALYSIS_FAILED_QUEUE = "cv.analysis.failed.queue";
    public static final String CV_ANALYSIS_FAILED_ROUTING_KEY = "cv.analysis.failed";

    // Exchanges (AI-service owns but recruitment declares to consume)
    @Bean
    public DirectExchange aiExchange() {
        return new DirectExchange(AI_EXCHANGE);
    }

    @Bean
    public DirectExchange aiDeadLetterExchange() {
        return new DirectExchange(AI_EXCHANGE_DLX);
    }

    // Result queue
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

    // Failed queue
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


    /* ============================================================
     * 5. COMMON SETTINGS
     * ============================================================ */

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            RetryTemplate retryTemplate,
            PlatformTransactionManager transactionManager) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setConcurrentConsumers(2);
        factory.setMaxConcurrentConsumers(3);
        factory.setPrefetchCount(2);
        factory.setRetryTemplate(retryTemplate);
        factory.setDefaultRequeueRejected(false);
        factory.setChannelTransacted(true);
        factory.setTransactionManager(transactionManager);
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
