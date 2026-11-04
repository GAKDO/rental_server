package com.gakdo.trace.rental.boothcode.domain;

import java.time.OffsetDateTime;

import com.gakdo.trace.rental.rental.domain.Checkpoint;
import com.gakdo.trace.rental.rental.domain.Rental;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** 부스 6자리 코드(rental.booth_code). 원문은 발급 응답에만 있고 여기에는 해시만 남는다. */
@Entity
@Table(name = "booth_code")
public class BoothCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code_hash", nullable = false, updatable = false, length = 64)
    private String codeHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rental_id", nullable = false, updatable = false)
    private Rental rental;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "checkpoint_id", nullable = false, updatable = false)
    private Checkpoint checkpoint;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected BoothCode() {
    }

    public static BoothCode issue(String codeHash, Rental rental, Checkpoint checkpoint, OffsetDateTime now,
            OffsetDateTime expiresAt) {
        BoothCode code = new BoothCode();
        code.codeHash = codeHash;
        code.rental = rental;
        code.checkpoint = checkpoint;
        code.createdAt = now;
        code.expiresAt = expiresAt;
        return code;
    }

    /** 아직 안 썼고 만료 전이면 true */
    public boolean isLive(OffsetDateTime now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public Long getId() {
        return id;
    }

    public Rental getRental() {
        return rental;
    }

    public Checkpoint getCheckpoint() {
        return checkpoint;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }
}
