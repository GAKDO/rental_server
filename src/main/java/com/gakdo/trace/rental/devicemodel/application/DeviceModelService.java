package com.gakdo.trace.rental.devicemodel.application;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gakdo.trace.rental.common.domain.DomainException;
import com.gakdo.trace.rental.common.domain.ErrorCode;
import com.gakdo.trace.rental.device.infra.DeviceRepository;
import com.gakdo.trace.rental.devicemodel.domain.DeviceModel;
import com.gakdo.trace.rental.devicemodel.infra.DeviceModelRepository;

/** 기종 등록·수정·조회·지우기 (FR-RNT-03). */
@Service
public class DeviceModelService {

    private final DeviceModelRepository repository;
    private final DeviceRepository devices;

    public DeviceModelService(DeviceModelRepository repository, DeviceRepository devices) {
        this.repository = repository;
        this.devices = devices;
    }

    /**
     * 기종 지우기(11/10 민서 요청). 이 기종으로 등록된 기기가 있으면 409 DEVICE_MODEL_IN_USE.
     * 확인과 삭제 사이에 기기가 등록되면 외래 키가 막으므로 같은 409로 바꾼다.
     */
    @Transactional
    public void delete(String modelCode) {
        DeviceModel model = repository.findByModelCode(modelCode).orElseThrow(() -> DomainException.notFound("기종"));
        long count = devices.countByModel_Id(model.getId());
        if (count > 0) {
            throw inUse("이 기종으로 등록된 기기 " + count + "대를 먼저 지워 주세요.");
        }
        try {
            repository.delete(model);
            repository.flush();
        } catch (DataIntegrityViolationException e) {
            throw inUse("이 기종으로 등록된 기기를 먼저 지워 주세요.");
        }
    }

    private static DomainException inUse(String detail) {
        return new DomainException(ErrorCode.DEVICE_MODEL_IN_USE, detail);
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
