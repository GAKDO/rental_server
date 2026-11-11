package com.gakdo.trace.rental.rental.infra;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.gakdo.trace.rental.rental.domain.Rental;
import com.gakdo.trace.rental.rental.domain.RentalStatus;

import jakarta.persistence.LockModeType;

public interface RentalRepository extends JpaRepository<Rental, Long>, JpaSpecificationExecutor<Rental> {

    boolean existsByDevice_IdAndStatus(Long deviceId, RentalStatus status);

    boolean existsByDevice_Id(Long deviceId);

    /** 시험 렌탈 지우기. 시점을 먼저 지운 뒤 부른다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Rental r where r.id = :id")
    int deleteRow(Long id);

    /** 시험 기기 지우기(11/10): 그 기기의 렌탈 전부와 시점 */
    @EntityGraph(attributePaths = {"checkpoints", "device"})
    List<Rental> findWithCheckpointsByDevice_Id(Long deviceId);

    @EntityGraph(attributePaths = {"checkpoints", "device"})
    Optional<Rental> findWithCheckpointsByPublicId(String publicId);

    /** 시점 시작·종료가 겹치지 않게 렌탈 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Rental r where r.publicId = :publicId")
    Optional<Rental> lockByPublicId(String publicId);
}
