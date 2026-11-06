package com.gakdo.trace.rental.pilot.domain;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * rental.pilot_participant(FR-PIL-01). 실명은 두지 않고 별칭(P01…)·기기·동의 시각만 둔다.
 * 실명 대장은 팀이 따로 보관하고, 측정값(diagnostics·InfluxDB)은 기기 ID로만 이어진다.
 */
@Entity
@Table(name = "pilot_participant", schema = "rental")
public class PilotParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, length = 21)
    private String publicId;

    @Column(name = "alias", nullable = false, length = 16)
    private String alias;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "consent_at", nullable = false)
    private OffsetDateTime consentAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected PilotParticipant() {
    }

    public PilotParticipant(String publicId, String alias, Long deviceId, OffsetDateTime consentAt,
            OffsetDateTime now) {
        this.publicId = publicId;
        this.alias = alias;
        this.deviceId = deviceId;
        this.consentAt = consentAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getAlias() {
        return alias;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public OffsetDateTime getConsentAt() {
        return consentAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
