package com.gakdo.trace.rental.device.infra;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.gakdo.trace.rental.device.domain.Device;

import jakarta.persistence.LockModeType;

public interface DeviceRepository extends JpaRepository<Device, Long>, JpaSpecificationExecutor<Device> {

    boolean existsBySerial(String serial);

    @EntityGraph(attributePaths = "model")
    Optional<Device> findWithModelByPublicId(String publicId);

    /** 렌탈 생성·상태 변경이 엇갈리지 않게 기기 행을 잠근다(SELECT … FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Device d join fetch d.model where d.publicId = :publicId")
    Optional<Device> lockByPublicId(String publicId);
}
