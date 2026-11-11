package com.gakdo.trace.rental.boothcode.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.rental.boothcode.domain.BoothCode;
import com.gakdo.trace.rental.boothcode.infra.BoothCodeRepository;
import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.common.domain.RateLimitedException;
import com.gakdo.trace.rental.common.infra.Times;
import com.gakdo.trace.rental.device.domain.Device;
import com.gakdo.trace.rental.rental.domain.Checkpoint;
import com.gakdo.trace.rental.rental.domain.CheckpointStatus;
import com.gakdo.trace.rental.rental.domain.CheckpointType;
import com.gakdo.trace.rental.rental.domain.Rental;
import com.gakdo.trace.rental.rental.domain.RentalStatus;
import com.gakdo.trace.rental.rental.infra.RentalRepository;

/**
 * 부스 6자리 코드(11/4 민서 요청). 관리자가 렌탈 상세에서 발급하고, 부스가 코드를 넣으면 기기·렌탈·시점 ID를 받는다.
 * 21자 ID 세 개를 손으로 옮기다 틀리는 일을 없애려고 만든다. 코드는 10분, 한 번만, 해시만 저장한다.
 */
@Service
public class BoothCodeService {

    static final Duration TTL = Duration.ofMinutes(10);
    private static final int ISSUE_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BoothCodeRepository codes;
    private final RentalRepository rentals;
    private final BoothCodeFailureLimit limit;
    private final Supplier<String> generator;

    @Autowired
    public BoothCodeService(BoothCodeRepository codes, RentalRepository rentals, BoothCodeFailureLimit limit) {
        this(codes, rentals, limit, () -> String.format("%06d", RANDOM.nextInt(1_000_000)));
    }

    BoothCodeService(BoothCodeRepository codes, RentalRepository rentals, BoothCodeFailureLimit limit,
            Supplier<String> generator) {
        this.codes = codes;
        this.rentals = rentals;
        this.limit = limit;
        this.generator = generator;
    }

    /** 발급 결과. code 원문은 이 응답에만 있다. */
    public record Issued(String code, OffsetDateTime expiresAt, String checkpointId, CheckpointType checkpointType) {
    }

    /** 부스가 받는 촬영 대상 */
    public record Target(String rentalId, String deviceId, String serial, String modelCode, String checkpointId,
            CheckpointType checkpointType) {
    }

    @Transactional
    public Issued issue(String rentalId) {
        Rental rental = rentals.lockByPublicId(rentalId).orElseThrow(() -> DomainException.notFound("렌탈"));
        if (rental.getStatus() != RentalStatus.OPEN) {
            throw orderViolation("진행 중(OPEN)인 렌탈에만 부스 코드를 발급할 수 있습니다.");
        }
        Checkpoint checkpoint = rental.currentCheckpoint()
                .orElseThrow(() -> orderViolation("출고 시점을 먼저 시작하세요."));
        if (checkpoint.getStatus() != CheckpointStatus.OPEN) {
            throw orderViolation(checkpoint.getType() + " 시점은 이미 찍었습니다. 다음 시점을 먼저 시작하세요.");
        }
        OffsetDateTime now = Times.now();
        codes.purgeExpired(now.minusDays(1));
        codes.retireUnused(rental.getId(), now);
        for (int i = 0; i < ISSUE_ATTEMPTS; i++) {
            String code = generator.get();
            String hash = hash(code);
            if (codes.existsLive(hash, now)) {
                continue; // 지금 살아 있는 다른 렌탈의 코드와 같으면 다시 뽑는다
            }
            OffsetDateTime expiresAt = now.plus(TTL);
            codes.save(BoothCode.issue(hash, rental, checkpoint, now, expiresAt));
            return new Issued(code, expiresAt, checkpoint.getPublicId(), checkpoint.getType());
        }
        throw new IllegalStateException("부스 코드를 만들지 못했습니다(겹침이 계속됨).");
    }

    @Transactional
    public Target redeem(String code) {
        // DB를 읽기 전에 실패로 미리 센다. 동시에 와도 한 구간 30번을 넘는 대입은 조회까지 가지 않는다
        long wait = limit.tryAcquire();
        if (wait > 0) {
            throw new RateLimitedException("틀린 부스 코드가 너무 많아 잠시 막았습니다. 잠시 뒤 다시 넣어 주세요.", wait);
        }
        OffsetDateTime now = Times.now();
        List<BoothCode> rows = codes.findByCodeHash(hash(code));
        if (rows.isEmpty()) {
            throw new DomainException(ErrorCode.BOOTH_CODE_INVALID, "관리자 화면에 보이는 6자리를 다시 확인하세요.");
        }
        limit.release(); // 발급한 적 있는 코드(맞음·만료·사용됨)는 대입 실패로 세지 않는다
        BoothCode live = rows.stream().filter(r -> r.isLive(now)).findFirst()
                .orElseThrow(() -> expired("만료됐거나 이미 쓴 코드예요. 관리자 화면에서 다시 발급하세요."));
        Checkpoint checkpoint = live.getCheckpoint();
        Rental rental = live.getRental();
        if (rental.getStatus() != RentalStatus.OPEN || checkpoint.getStatus() != CheckpointStatus.OPEN) {
            throw expired("이 시점은 이미 찍었어요. 관리자 화면에서 다시 확인하세요.");
        }
        Device device = rental.getDevice();
        Target target = new Target(rental.getPublicId(), device.getPublicId(), device.getSerial(),
                device.getModel().getModelCode(), checkpoint.getPublicId(), checkpoint.getType());
        if (codes.markUsed(live.getId(), now) != 1) {
            throw expired("다른 부스가 먼저 이 코드를 썼어요. 관리자 화면에서 다시 발급하세요.");
        }
        return target;
    }

    static String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DomainException orderViolation(String detail) {
        return new DomainException(ErrorCode.CHECKPOINT_ORDER_VIOLATION, detail);
    }

    private static DomainException expired(String detail) {
        return new DomainException(ErrorCode.BOOTH_CODE_EXPIRED, detail);
    }
}
