package com.gakdo.trace.rental.rental.api;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gakdo.trace.rental.common.infra.Cursor;
import com.gakdo.trace.rental.common.infra.CurrentUser;
import com.gakdo.trace.rental.common.infra.PublicIds;
import com.gakdo.trace.rental.rental.api.RentalDtos.CheckpointCreateRequest;
import com.gakdo.trace.rental.rental.api.RentalDtos.CheckpointResponse;
import com.gakdo.trace.rental.rental.api.RentalDtos.RentalCreateRequest;
import com.gakdo.trace.rental.rental.api.RentalDtos.RentalPageResponse;
import com.gakdo.trace.rental.rental.api.RentalDtos.RentalResponse;
import com.gakdo.trace.rental.rental.api.RentalDtos.RentalSummaryResponse;
import com.gakdo.trace.rental.rental.application.RentalService;
import com.gakdo.trace.rental.rental.domain.Rental;
import com.gakdo.trace.rental.rental.domain.RentalStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/** /api/v1/rentals — 렌탈과 세 시점 (FR-RNT-01, FR-RNT-02). 역할은 SecurityConfig, 본인 범위는 서비스가 본다. */
@RestController
@RequestMapping("/api/v1/rentals")
public class RentalController {

    private final RentalService service;

    public RentalController(RentalService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RentalResponse> open(@Valid @RequestBody RentalCreateRequest request) {
        Rental rental = service.open(request.deviceId(), request.renterRef());
        return ResponseEntity.created(URI.create("/api/v1/rentals/" + rental.getPublicId()))
                .body(RentalResponse.from(rental));
    }

    @GetMapping
    public RentalPageResponse list(@RequestParam(required = false) RentalStatus status,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor, @AuthenticationPrincipal Jwt jwt) {
        RentalService.Page page = service.list(status, Cursor.decode(cursor), limit, CurrentUser.from(jwt));
        return new RentalPageResponse(page.items().stream().map(RentalSummaryResponse::from).toList(),
                page.nextCursor());
    }

    @GetMapping("/{rentalId}")
    public RentalResponse get(@PathVariable @Pattern(regexp = PublicIds.PATTERN) String rentalId, @AuthenticationPrincipal Jwt jwt) {
        return RentalResponse.from(service.get(rentalId, CurrentUser.from(jwt)));
    }

    @PostMapping("/{rentalId}/checkpoints")
    @ResponseStatus(HttpStatus.CREATED)
    public CheckpointResponse startCheckpoint(@PathVariable @Pattern(regexp = PublicIds.PATTERN) String rentalId,
            @Valid @RequestBody CheckpointCreateRequest request) {
        return CheckpointResponse.from(service.startCheckpoint(rentalId, request.type()));
    }

    @PostMapping("/{rentalId}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@PathVariable @Pattern(regexp = PublicIds.PATTERN) String rentalId) {
        service.close(rentalId);
    }
}
