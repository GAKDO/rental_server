package com.gakdo.trace.rental.devicemodel.infra;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gakdo.trace.rental.devicemodel.domain.DeviceModel;

public interface DeviceModelRepository extends JpaRepository<DeviceModel, Long> {

    Optional<DeviceModel> findByModelCode(String modelCode);

    List<DeviceModel> findAllByOrderByModelCodeAsc();
}
