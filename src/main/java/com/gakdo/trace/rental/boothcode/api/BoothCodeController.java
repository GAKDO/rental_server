package com.gakdo.trace.rental.boothcode.api;

import java.time.OffsetDateTime;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gakdo.trace.rental.boothcode.application.BoothCodeService;
import com.gakdo.trace.rental.common.infra.PublicIds;
import com.gakdo.trace.rental.rental.domain.CheckpointType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 부스 6자리 코드 (contracts issueBoothCode·redeemBoothCode). 역할은 SecurityConfig가 본다(발급 ADMIN, 조회 BOOTH). */
@RestController
public class BoothCodeController {

    static final String CODE_PATTERN = "^[0-9]{6}$";

    private final BoothCodeService service;

    public BoothCodeController(BoothCodeService service) {
        this.service = service;
    }

    public record BoothCodeResponse(String code, OffsetDateTime expiresAt, String checkpointId,
            CheckpointType checkpointType) {
    }

    public record BoothCodeRedeemRequest(@NotNull @Pattern(regexp = CODE_PATTERN) String code) {
    }

    public record BoothCodeTarget(String rentalId, String deviceId, String serial, String modelCode,
            String checkpointId, CheckpointType checkpointType) {
    }

    @PostMapping("/api/v1/rentals/{rentalId}/booth-code")
    @ResponseStatus(HttpStatus.CREATED)
    public BoothCodeResponse issue(@PathVariable @Pattern(regexp = PublicIds.PATTERN) String rentalId) {
        BoothCodeService.Issued issued = service.issue(rentalId);
        return new BoothCodeResponse(issued.code(), issued.expiresAt(), issued.checkpointId(), issued.checkpointType());
    }

    @PostMapping("/api/v1/rentals/booth-codes/redeem")
    public BoothCodeTarget redeem(@Valid @RequestBody BoothCodeRedeemRequest request) {
        BoothCodeService.Target t = service.redeem(request.code());
        return new BoothCodeTarget(t.rentalId(), t.deviceId(), t.serial(), t.modelCode(), t.checkpointId(),
                t.checkpointType());
    }
}
