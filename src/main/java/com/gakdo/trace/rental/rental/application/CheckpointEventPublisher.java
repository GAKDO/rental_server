package com.gakdo.trace.rental.rental.application;

/** 시점 이벤트를 내보낸다. 구현은 infra(RabbitMQ), 테스트는 가짜로 바꿔 끼운다. */
public interface CheckpointEventPublisher {

    void publishStarted(CheckpointStartedEvent event);

    void publishPurged(CheckpointPurgedEvent event);
}
