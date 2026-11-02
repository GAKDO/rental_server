package com.gakdo.trace.rental.rental.infra;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.gakdo.trace.rental.rental.application.CheckpointEventPublisher;
import com.gakdo.trace.rental.rental.application.CheckpointStartedEvent;

/** 메시지 ID = eventId. 채널 트랜잭션이라 시점 저장 트랜잭션이 커밋될 때 함께 나간다(capture-server와 같음). */
@Component
public class RabbitCheckpointEventPublisher implements CheckpointEventPublisher {

    private final RabbitTemplate rabbit;

    public RabbitCheckpointEventPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    @Override
    public void publishStarted(CheckpointStartedEvent event) {
        rabbit.convertAndSend(AmqpConfig.EXCHANGE, CheckpointStartedEvent.ROUTING_KEY, event, message -> {
            message.getMessageProperties().setMessageId(event.eventId());
            return message;
        });
    }
}
