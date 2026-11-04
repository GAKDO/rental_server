package com.gakdo.trace.rental.rental.infra;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.rental.common.infra.PublicIds;
import com.gakdo.trace.rental.rental.application.CheckpointAdvance;
import com.gakdo.trace.rental.rental.application.CheckpointProgressService;
import com.gakdo.trace.rental.rental.domain.CheckpointStatus;

/**
 * 이벤트 3종으로 시점 상태를 옮긴다. 봉투(rentalId·checkpointId·occurredAt)만 읽고 나머지 필드는 무시한다(너그러운 소비자).
 * 형식이 깨진 메시지와 렌탈·시점 짝이 rental-server 기록과 다른 메시지는 재시도해도 소용없어 바로 DLX로 보낸다.
 * 짝이 다른 메시지는 trace.rental.checkpoints.dlq에 남아 사람이 확인한다(부스가 다른 렌탈 시점으로 올린 경우 등).
 */
@Component
public class CheckpointEventListener {

    private static final Logger log = LoggerFactory.getLogger(CheckpointEventListener.class);

    private final CheckpointProgressService progress;
    private final ObjectMapper objectMapper;

    public CheckpointEventListener(CheckpointProgressService progress, ObjectMapper objectMapper) {
        this.progress = progress;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = AmqpConfig.QUEUE)
    public void onMessage(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        handle(routingKey, new String(message.getBody(), StandardCharsets.UTF_8));
    }

    /** @return 상태가 바뀌었으면 true */
    public boolean handle(String routingKey, String body) {
        CheckpointStatus next = statusFor(routingKey);
        if (next == null) {
            log.warn("받지 않는 라우팅 키를 건너뜁니다: {}", routingKey);
            return false;
        }
        JsonNode event = parse(body);
        String rentalId = publicId(event, "rentalId");
        String checkpointId = publicId(event, "checkpointId");
        OffsetDateTime occurredAt = occurredAt(event);
        CheckpointAdvance result = progress.advance(rentalId, checkpointId, next, occurredAt);
        if (result.isMismatch()) {
            log.error("렌탈·시점 짝이 맞지 않는 이벤트를 DLQ로 보냅니다: {} eventId={} rentalId={} checkpointId={} 사유={}",
                    routingKey, event.path("eventId").asText(), rentalId, checkpointId, result);
            throw reject("렌탈·시점 짝이 맞지 않습니다: " + result);
        }
        boolean changed = result == CheckpointAdvance.CHANGED;
        if (log.isInfoEnabled()) {
            log.info("시점 이벤트 {} eventId={} checkpointId={} → {}{}", routingKey, event.path("eventId").asText(),
                    checkpointId, next, changed ? "" : " (변화 없음)");
        }
        return changed;
    }

    static CheckpointStatus statusFor(String routingKey) {
        if (routingKey == null) {
            return null;
        }
        return switch (routingKey) {
            case AmqpConfig.CAPTURE_UPLOADED -> CheckpointStatus.CAPTURED;
            case AmqpConfig.CHECKPOINT_GRADED -> CheckpointStatus.GRADED;
            case AmqpConfig.RECORD_SEALED -> CheckpointStatus.SEALED;
            default -> null;
        };
    }

    private JsonNode parse(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node == null || !node.isObject()) {
                throw reject("이벤트가 JSON 객체가 아닙니다");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw reject("이벤트 JSON을 읽을 수 없습니다");
        }
    }

    private static String publicId(JsonNode event, String field) {
        String value = event.path(field).asText(null);
        if (!PublicIds.isValid(value)) {
            throw reject("봉투의 " + field + "가 올바르지 않습니다");
        }
        return value;
    }

    private static OffsetDateTime occurredAt(JsonNode event) {
        try {
            return OffsetDateTime.parse(event.path("occurredAt").asText());
        } catch (DateTimeParseException e) {
            throw reject("봉투의 occurredAt이 올바르지 않습니다");
        }
    }

    private static AmqpRejectAndDontRequeueException reject(String reason) {
        return new AmqpRejectAndDontRequeueException(reason);
    }
}
