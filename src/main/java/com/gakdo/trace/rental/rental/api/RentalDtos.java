package com.gakdo.trace.rental.rental.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.gakdo.trace.rental.common.infra.PublicIds;
import com.gakdo.trace.rental.rental.domain.Checkpoint;
import com.gakdo.trace.rental.rental.domain.CheckpointStatus;
import com.gakdo.trace.rental.rental.domain.CheckpointType;
import com.gakdo.trace.rental.rental.domain.Rental;
import com.gakdo.trace.rental.rental.domain.RentalDetails;
import com.gakdo.trace.rental.rental.domain.RentalStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 계약 rental-server.yaml의 렌탈 스키마(RentalCreateRequest·Rental·RentalSummary·RentalPage·Checkpoint). */
public final class RentalDtos {

    private RentalDtos() {
    }

    /** customerName·dueAt·note는 11/10 추가(선택, contracts #23-2). 공백만 있으면 없음으로 본다. */
    public record RentalCreateRequest(@NotNull @Pattern(regexp = PublicIds.PATTERN) String deviceId, @NotBlank @Size(max = 128) String renterRef,
            @Size(max = RentalDetails.CUSTOMER_NAME_MAX) String customerName, LocalDate dueAt,
            @Size(max = RentalDetails.NOTE_MAX) String note) {

        public RentalDetails details() {
            return RentalDetails.of(customerName, dueAt, note);
        }
    }

    public record CheckpointCreateRequest(@NotNull CheckpointType type) {
    }

    /** grade: 이 시점 검사의 마지막 등급(11/10, 목록에서 왜 문제인지 보이게). 없으면 null */
    public record CheckpointResponse(String checkpointId, CheckpointType type, CheckpointStatus status,
            OffsetDateTime occurredAt, String grade) {

        static CheckpointResponse from(Checkpoint c) {
            return new CheckpointResponse(c.getPublicId(), c.getType(), c.getStatus(), c.getOccurredAt(), c.getGrade());
        }
    }

    /**
     * serial은 계약 1.0.x 추가 필드(화면이 기기를 따로 부르지 않게).
     * customerName·dueAt·note는 11/10 추가. note(관리자 메모)는 ADMIN이 아니면 null이다.
     */
    public record RentalResponse(String rentalId, String deviceId, String serial, String renterRef,
            RentalStatus status, OffsetDateTime startedAt, OffsetDateTime endedAt, OffsetDateTime createdAt,
            String customerName, LocalDate dueAt, String note, List<CheckpointResponse> checkpoints) {

        static RentalResponse from(Rental r, boolean admin) {
            return new RentalResponse(r.getPublicId(), r.getDevice().getPublicId(), r.getDevice().getSerial(),
                    r.getRenterRef(), r.getStatus(), r.getStartedAt(), r.getEndedAt(), r.getCreatedAt(),
                    r.getCustomerName(), r.getDueAt(), admin ? r.getNote() : null,
                    r.getCheckpoints().stream().map(CheckpointResponse::from).toList());
        }
    }

    /**
     * currentCheckpoint는 계약 1.0.x 추가 필드: 가장 늦게 시작한 시점, 없으면 null.
     * renterRef·startedAt·endedAt은 11/6 추가(기기별 렌탈 이력 표). 값이 없어도 null로 항상 보낸다.
     */
    public record RentalSummaryResponse(String rentalId, String deviceId, String serial, String modelCode,
            String renterRef, RentalStatus status, OffsetDateTime startedAt, OffsetDateTime endedAt,
            OffsetDateTime createdAt, CheckpointResponse currentCheckpoint, String customerName, LocalDate dueAt) {

        /** customerName(11/10)은 ADMIN에게만, 아니면 null(부스 태블릿·고객 목록에 이름을 내보내지 않는다). */
        static RentalSummaryResponse from(Rental r, boolean admin) {
            return new RentalSummaryResponse(r.getPublicId(), r.getDevice().getPublicId(), r.getDevice().getSerial(),
                    r.getDevice().getModel().getModelCode(), r.getRenterRef(), r.getStatus(), r.getStartedAt(),
                    r.getEndedAt(), r.getCreatedAt(), r.currentCheckpoint().map(CheckpointResponse::from).orElse(null),
                    admin ? r.getCustomerName() : null, r.getDueAt());
        }
    }

    /** totalCount(11/10): 같은 조건의 전체 건수 */
    public record RentalPageResponse(List<RentalSummaryResponse> items, String nextCursor, long totalCount) {
    }
}
