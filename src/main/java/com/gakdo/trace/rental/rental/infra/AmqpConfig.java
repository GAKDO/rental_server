package com.gakdo.trace.rental.rental.infra;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * trace.events에 rental-server 전용 큐를 붙인다. 이벤트 스키마·라우팅 키는 그대로(contracts events 1.0)이고 소비자만 하나 늘었다.
 * 1.1부터 시점 시작 때 trace.checkpoint.started를 낸다(JSON, 채널 트랜잭션).
 * 계속 실패한 메시지는 trace.dlx(topic)로 보내고, 우리 것만 모이도록 dead-letter 키를 따로 준다.
 */
@Configuration
public class AmqpConfig {

    public static final String EXCHANGE = "trace.events";
    public static final String QUEUE = "trace.rental.checkpoints";
    public static final String DLX = "trace.dlx";
    public static final String DEAD_KEY = "trace.rental.checkpoints.dead";
    public static final String DLQ = "trace.rental.checkpoints.dlq";

    public static final String CAPTURE_UPLOADED = "trace.capture.uploaded";
    public static final String CHECKPOINT_GRADED = "trace.checkpoint.graded";
    public static final String RECORD_SEALED = "trace.record.sealed";

    @Bean
    Declarables rentalCheckpointTopology() {
        TopicExchange events = new TopicExchange(EXCHANGE, true, false);
        TopicExchange dlx = new TopicExchange(DLX, true, false);
        Queue queue = QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(DEAD_KEY)
                .build();
        Queue dlq = QueueBuilder.durable(DLQ).build();
        return new Declarables(events, dlx, queue, dlq,
                bind(queue, events, CAPTURE_UPLOADED),
                bind(queue, events, CHECKPOINT_GRADED),
                bind(queue, events, RECORD_SEALED),
                bind(dlq, dlx, DEAD_KEY));
    }

    /** 시각은 ISO 8601 문자열(Spring의 ObjectMapper 설정 그대로). 리스너는 Message를 직접 읽어 영향 없음. */
    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * 리스너는 본문(byte[])을 직접 읽는다. 기본 Jackson 변환기를 리스너에도 쓰면 다른 서버가 붙인 __TypeId__(보낸 쪽 자바 클래스)를
     * 찾다가 ClassNotFound로 메시지를 DLQ에 버린다(11/4 학교 서버에서 확인). 그래서 리스너만 변환하지 않는 변환기로 바꾼다.
     * 발행(RabbitTemplate)은 그대로 JSON이고, 재시도 등 나머지 리스너 설정은 Boot 설정을 그대로 쓴다.
     */
    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(new SimpleMessageConverter());
        return factory;
    }

    @Bean
    RabbitTemplateCustomizer transactedTemplate() {
        return template -> template.setChannelTransacted(true);
    }

    private static Binding bind(Queue queue, TopicExchange exchange, String key) {
        return BindingBuilder.bind(queue).to(exchange).with(key);
    }
}
