package com.gakdo.trace.rental.devicemodel.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.devicemodel.domain.DeviceModel;
import com.gakdo.trace.rental.devicemodel.infra.DeviceModelRepository;

/** 기종 등록·수정·조회 (FR-RNT-03). */
@Service
public class DeviceModelService {

    private final DeviceModelRepository repository;

    public DeviceModelService(DeviceModelRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public DeviceModel upsert(String modelCode, DeviceModel.Attributes attributes, String actor) {
        return repository.findByModelCode(modelCode)
                .map(existing -> {
                    existing.apply(attributes, actor);
                    return existing;
                })
                .orElseGet(() -> repository.save(DeviceModel.create(modelCode, attributes, actor)));
    }

    @Transactional(readOnly = true)
    public DeviceModel get(String modelCode) {
        return repository.findByModelCode(modelCode).orElseThrow(() -> DomainException.notFound("기종"));
    }

    @Transactional(readOnly = true)
    public List<DeviceModel> list() {
        return repository.findAllByOrderByModelCodeAsc();
    }
}
