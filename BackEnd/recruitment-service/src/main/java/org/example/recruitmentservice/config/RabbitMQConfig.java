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
                .with(CV_UPLOAD_DLQ_ROUTING_KEY);
    }


    /* ============================================================
     * 2. JD CHUNKED FLOW (published by PositionService, consumed by embedding-service)
     * ============================================================ */
    public static final String JD_CHUNKED_QUEUE = "jd.chunked.queue";
    public static final String JD_CHUNKED_DLQ = "jd.chunked.queue.dlq";
    public static final String JD_CHUNKED_EXCHANGE = "jd.chunked.exchange";
    public static final String JD_CHUNKED_ROUTING_KEY = "jd.chunked";
    public static final String JD_CHUNKED_DLQ_ROUTING_KEY = "jd.chunked.dlq";

    @Bean
    public Queue jdChunkedQueue() {
        return QueueBuilder.durable(JD_CHUNKED_QUEUE)
                .withArgument("x-dead-letter-exchange", JD_CHUNKED_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", JD_CHUNKED_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue jdChunkedDlq() {
        return QueueBuilder.durable(JD_CHUNKED_DLQ).build();
    }

    @Bean
    public DirectExchange jdChunkedExchange() {
        return new DirectExchange(JD_CHUNKED_EXCHANGE);
    }

    @Bean
    public Binding jdChunkedBinding(Queue jdChunkedQueue, DirectExchange jdChunkedExchange) {
        return BindingBuilder.bind(jdChunkedQueue)
                .to(jdChunkedExchange)
                .with(JD_CHUNKED_ROUTING_KEY);
    }

    @Bean
    public Binding jdChunkedDlqBinding(Queue jdChunkedDlq, DirectExchange jdChunkedExchange) {
        return BindingBuilder.bind(jdChunkedDlq)
                .to(jdChunkedExchange)
                .with(JD_CHUNKED_DLQ_ROUTING_KEY);
    }

    public static final String JD_EMBED_REPLY_QUEUE = "jd.embed.reply.queue";

    @Bean
    public Queue jdEmbedReplyQueue() {
        return QueueBuilder.durable(JD_EMBED_REPLY_QUEUE).build();
    }

    /* ============================================================
     * 3. CV EMBED FLOW (Two-Stage Pipeline — Stage 2 input)
     * ============================================================ */
    public static final String CV_EMBED_QUEUE = "cv.embed.queue";
    public static final String CV_EMBED_DLQ = "cv.embed.queue.dlq";
    public static final String CV_EMBED_EXCHANGE = "cv.embed.exchange";
    public static final String CV_EMBED_ROUTING_KEY = "cv.embed";
    public static final String CV_EMBED_DLQ_ROUTING_KEY = "cv.embed.dlq";

    @Bean
    public Queue cvEmbedQueue() {
        return QueueBuilder.durable(CV_EMBED_QUEUE)
                .withArgument("x-dead-letter-exchange", CV_EMBED_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", CV_EMBED_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue cvEmbedDlq() {
        return QueueBuilder.durable(CV_EMBED_DLQ).build();
    }

    @Bean
    public DirectExchange cvEmbedExchange() {
        return new DirectExchange(CV_EMBED_EXCHANGE);
    }

    @Bean
    public Binding cvEmbedBinding(Queue cvEmbedQueue, DirectExchange cvEmbedExchange) {
        return BindingBuilder.bind(cvEmbedQueue)
                .to(cvEmbedExchange)
                .with(CV_EMBED_ROUTING_KEY);
    }

    @Bean
    public Binding cvEmbedDlqBinding(Queue cvEmbedDlq, DirectExchange cvEmbedExchange) {
        return BindingBuilder.bind(cvEmbedDlq)
                .to(cvEmbedExchange)
                .with(CV_EMBED_DLQ_ROUTING_KEY);
    }


    /* ============================================================
     * 4. EXTRACTION FLOW (Two-Stage Pipeline — Stage 1)
     *    recruitment-service publishes here after parsing.
     *    ExtractCVListener consumes, calls Gemini, then publishes to cv.embed.queue.
     * ============================================================ */
    public static final String CV_EXTRACT_QUEUE = "cv.extract.queue";
    public static final String CV_EXTRACT_DLQ = "cv.extract.queue.dlq";
    public static final String CV_EXTRACT_EXCHANGE = "cv.extract.exchange.dlx";
    public static final String CV_EXTRACT_DLQ_ROUTING_KEY = "cv.extract.dlq";

    @Bean
    public Queue cvExtractQueue() {
        return QueueBuilder.durable(CV_EXTRACT_QUEUE)
                .withArgument("x-dead-letter-exchange", CV_EXTRACT_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", CV_EXTRACT_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue cvExtractDlqQueue() {
        return QueueBuilder.durable(CV_EXTRACT_DLQ).build();
    }

    @Bean
    public DirectExchange cvExtractExchange() {
        return new DirectExchange(CV_EXTRACT_EXCHANGE);
    }

    @Bean
    public Binding cvExtractDlqBinding(Queue cvExtractDlqQueue, DirectExchange cvExtractExchange) {
        return BindingBuilder.bind(cvExtractDlqQueue)
                .to(cvExtractExchange)
                .with(CV_EXTRACT_DLQ_ROUTING_KEY);
    }

    /* ============================================================
     * 5. EMBED REPLY FLOW (Two-Stage Pipeline — Stage 2 reply)
     *    embedding-service publishes here after Qdrant upsert.
     *    EmbedReplyListener consumes to update DB status.
     * ============================================================ */
    public static final String CV_EMBED_REPLY_QUEUE = "cv.embed.reply.queue";
    public static final String CV_EMBED_REPLY_DLQ = "cv.embed.reply.queue.dlq";
    public static final String CV_EMBED_REPLY_EXCHANGE = "cv.embed.reply.exchange.dlx";
    public static final String CV_EMBED_REPLY_DLQ_ROUTING_KEY = "cv.embed.reply.dlq";

    @Bean
    public Queue cvEmbedReplyQueue() {
        return QueueBuilder.durable(CV_EMBED_REPLY_QUEUE)
                .withArgument("x-dead-letter-exchange", CV_EMBED_REPLY_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", CV_EMBED_REPLY_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue cvEmbedReplyDlqQueue() {
        return QueueBuilder.durable(CV_EMBED_REPLY_DLQ).build();
    }

    @Bean
    public DirectExchange cvEmbedReplyExchange() {
        return new DirectExchange(CV_EMBED_REPLY_EXCHANGE);
    }

    @Bean
    public Binding cvEmbedReplyDlqBinding(Queue cvEmbedReplyDlqQueue, DirectExchange cvEmbedReplyExchange) {
        return BindingBuilder.bind(cvEmbedReplyDlqQueue)
                .to(cvEmbedReplyExchange)
                .with(CV_EMBED_REPLY_DLQ_ROUTING_KEY);
    }


    /* ============================================================
     * 6. COMMON SETTINGS
     * ============================================================ */

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * Default factory – dùng cho các Queue xử lý nhanh (scoring result, JD parsed…).
     * concurrency nhỏ, có RetryTemplate, có transaction.
     */
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

    /**
     * Factory chuyên dụng cho CV Parsing Queue (cv.upload.queue).
     * Mỗi CV parse mất 10-60s (polling LlamaParse API) nên cần nhiều thread song song.
     * - concurrency=5 / max=10: xử lý 5-10 CVs đồng thời.
     * - prefetchCount=1: mỗi thread chỉ nhận 1 message, tránh tồn đọng.
     * - KHÔNG dùng RetryTemplate: logic retry được xử lý thủ công bên trong LlamaParseClient.
     * - KHÔNG transaction: operation chính là HTTP call ra ngoài (LlamaParse API).
     */
    @Bean
    public SimpleRabbitListenerContainerFactory cvParsingContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setConcurrentConsumers(5);
        factory.setMaxConcurrentConsumers(10);
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    /**
     * Factory chuyên dụng cho ExtractCVListener (cv.extract.queue).
     * Gemini API có rate limit, nên giới hạn concurrency thấp.
     * - concurrency=1 / max=2: xử lý tuần tự để tránh bị throttle.
     * - prefetchCount=1: mỗi thread chỉ nhận 1 message.
     * - KHÔNG RetryTemplate: exception throw ra sẽ để RabbitMQ route sang DLQ.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory cvExtractionContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(2);
        factory.setPrefetchCount(1);
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
