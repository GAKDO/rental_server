package com.gakdo.trace.rental.devicemodel.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.gakdo.trace.rental.common.infra.Times;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 기종(rental.device_model). modelCode로 찾고, 치수는 고객 화면 3D 트윈이 읽는다(FR-RNT-03). */
@Entity
@Table(name = "device_model")
public class DeviceModel {

    /** DB 기본 키(순번). 밖에 내보내지 않는다. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_code", nullable = false, updatable = false, length = 64)
    private String modelCode;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 64)
    private String manufacturer;

    @Enumerated(EnumType.STRING)
    @Column(name = "form_factor", nullable = false, length = 16)
    private FormFactor formFactor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private ModelSpec spec;

    @Column(name = "width_mm", nullable = false, precision = 6, scale = 1)
    private BigDecimal widthMm;

    @Column(name = "depth_mm", nullable = false, precision = 6, scale = 1)
    private BigDecimal depthMm;

    @Column(name = "base_h_mm", nullable = false, precision = 5, scale = 1)
    private BigDecimal baseHMm;

    @Column(name = "lid_h_mm", nullable = false, precision = 5, scale = 1)
    private BigDecimal lidHMm;

    @Column(name = "updated_by", length = 21)
    private String updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected DeviceModel() {
    }

    public static DeviceModel create(String modelCode, Attributes attributes, String actor) {
        DeviceModel model = new DeviceModel();
        model.modelCode = modelCode;
        model.createdAt = Times.now();
        model.apply(attributes, actor);
        return model;
    }

    /** PUT 업서트: modelCode 외 모든 값을 바꾼다. */
    public void apply(Attributes attributes, String actor) {
        Dimensions d = attributes.dimensions().rounded();
        this.name = attributes.name();
        this.manufacturer = attributes.manufacturer();
        this.formFactor = attributes.formFactor();
        this.spec = attributes.spec();
        this.widthMm = d.widthMm();
        this.depthMm = d.depthMm();
        this.baseHMm = d.baseHMm();
        this.lidHMm = d.lidHMm();
        this.updatedBy = actor;
        this.updatedAt = Times.now();
    }

    /** 기종에서 바꿀 수 있는 값 묶음. */
    public record Attributes(String name, String manufacturer, FormFactor formFactor, ModelSpec spec,
            Dimensions dimensions) {
    }

    public Long getId() {
        return id;
    }

    public String getModelCode() {
        return modelCode;
    }

    public String getName() {
        return name;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public FormFactor getFormFactor() {
        return formFactor;
    }

    public ModelSpec getSpec() {
        return spec;
    }

    public Dimensions getDimensions() {
        return new Dimensions(widthMm, depthMm, baseHMm, lidHMm);
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
