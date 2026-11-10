package com.gakdo.trace.rental.rental.api;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
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
        Rental rental = service.open(request.deviceId(), request.renterRef(), request.details());
        return ResponseEntity.created(URI.create("/api/v1/rentals/" + rental.getPublicId()))
                .body(RentalResponse.from(rental, true));
    }

    @GetMapping
    public RentalPageResponse list(@RequestParam(required = false) RentalStatus status,
            @RequestParam(required = false) @Pattern(regexp = PublicIds.PATTERN) String deviceId,
            @RequestParam(defaultValue = "false") boolean problem,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor, @AuthenticationPrincipal Jwt jwt) {
        // deviceId(11/6 추가): 기기별 렌탈 이력. problem(11/10 추가): 지금 시점 등급 B·C·D만. 역할별 거르기와 겹쳐 적용한다
        CurrentUser user = CurrentUser.from(jwt);
        RentalService.Page page = service.list(status, deviceId, problem, Cursor.decode(cursor), limit, user);
        return new RentalPageResponse(page.items().stream().map(r -> RentalSummaryResponse.from(r, user.isAdmin())).toList(),
                page.nextCursor(), page.totalCount());
    }

    @GetMapping("/{rentalId}")
    public RentalResponse get(@PathVariable @Pattern(regexp = PublicIds.PATTERN) String rentalId, @AuthenticationPrincipal Jwt jwt) {
        CurrentUser user = CurrentUser.from(jwt);
        return RentalResponse.from(service.get(rentalId, user), user.isAdmin());
    }

    /** 고객 이름·반납 예정일·메모 고치기(11/10, contracts #23-2). ADMIN만(SecurityConfig). 생략은 그대로, null은 비우기. */
    @PatchMapping("/{rentalId}")
    public RentalResponse update(@PathVariable @Pattern(regexp = PublicIds.PATTERN) String rentalId,
            @RequestBody(required = false) JsonNode body) {
        return RentalResponse.from(service.updateDetails(rentalId, RentalDetailsParser.parse(body)), true);
    }

    /** 시험 렌탈 지우기: 기기 시리얼 TEST-만(아니면 409 NOT_TEST_DATA). ADMIN만(SecurityConfig). */
    @DeleteMapping("/{rentalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTestRental(@PathVariable @Pattern(regexp = PublicIds.PATTERN) String rentalId) {
        service.deleteTestRental(rentalId);
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
