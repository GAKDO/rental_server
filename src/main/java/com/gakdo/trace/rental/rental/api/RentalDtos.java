package com.gakdo.trace.rental.rental.api;

import java.time.OffsetDateTime;
import java.util.List;

import com.gakdo.trace.rental.common.infra.PublicIds;
import com.gakdo.trace.rental.rental.domain.Checkpoint;
import com.gakdo.trace.rental.rental.domain.CheckpointStatus;
import com.gakdo.trace.rental.rental.domain.CheckpointType;
import com.gakdo.trace.rental.rental.domain.Rental;
import com.gakdo.trace.rental.rental.domain.RentalStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 계약 rental-server.yaml의 렌탈 스키마(RentalCreateRequest·Rental·RentalSummary·RentalPage·Checkpoint). */
public final class RentalDtos {

    private RentalDtos() {
    }

    public record RentalCreateRequest(@NotNull @Pattern(regexp = PublicIds.PATTERN) String deviceId, @NotBlank @Size(max = 128) String renterRef) {
    }

    public record CheckpointCreateRequest(@NotNull CheckpointType type) {
    }

    public record CheckpointResponse(String checkpointId, CheckpointType type, CheckpointStatus status,
            OffsetDateTime occurredAt) {

        static CheckpointResponse from(Checkpoint c) {
            return new CheckpointResponse(c.getPublicId(), c.getType(), c.getStatus(), c.getOccurredAt());
        }
    }

    /** serial은 계약 1.0.x 추가 필드(화면이 기기를 따로 부르지 않게). */
    public record RentalResponse(String rentalId, String deviceId, String serial, String renterRef,
            RentalStatus status, OffsetDateTime startedAt, OffsetDateTime endedAt, OffsetDateTime createdAt,
            List<CheckpointResponse> checkpoints) {

        static RentalResponse from(Rental r) {
            return new RentalResponse(r.getPublicId(), r.getDevice().getPublicId(), r.getDevice().getSerial(),
                    r.getRenterRef(), r.getStatus(), r.getStartedAt(), r.getEndedAt(), r.getCreatedAt(),
                    r.getCheckpoints().stream().map(CheckpointResponse::from).toList());
        }
    }

    /**
     * currentCheckpoint는 계약 1.0.x 추가 필드: 가장 늦게 시작한 시점, 없으면 null.
     * renterRef·startedAt·endedAt은 11/6 추가(기기별 렌탈 이력 표). 값이 없어도 null로 항상 보낸다.
     */
    public record RentalSummaryResponse(String rentalId, String deviceId, String serial, String modelCode,
            String renterRef, RentalStatus status, OffsetDateTime startedAt, OffsetDateTime endedAt,
            OffsetDateTime createdAt, CheckpointResponse currentCheckpoint) {

        static RentalSummaryResponse from(Rental r) {
            return new RentalSummaryResponse(r.getPublicId(), r.getDevice().getPublicId(), r.getDevice().getSerial(),
                    r.getDevice().getModel().getModelCode(), r.getRenterRef(), r.getStatus(), r.getStartedAt(),
                    r.getEndedAt(), r.getCreatedAt(), r.currentCheckpoint().map(CheckpointResponse::from).orElse(null));
        }
    }

    public record RentalPageResponse(List<RentalSummaryResponse> items, String nextCursor) {
    }
}
