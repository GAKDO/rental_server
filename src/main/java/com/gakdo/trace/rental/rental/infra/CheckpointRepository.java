package com.gakdo.trace.rental.rental.infra;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.gakdo.trace.rental.rental.domain.Checkpoint;

import jakarta.persistence.LockModeType;

public interface CheckpointRepository extends JpaRepository<Checkpoint, Long> {

    /** 같은 시점 이벤트가 동시에 와도 하나씩 반영한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Checkpoint c join fetch c.rental where c.publicId = :publicId")
    Optional<Checkpoint> lockByPublicId(String publicId);

    /** 시험 렌탈 지우기: 그 렌탈의 시점을 모두 지운다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Checkpoint c where c.rental.id = :rentalId")
    int deleteByRentalId(Long rentalId);
}
