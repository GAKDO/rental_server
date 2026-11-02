package com.gakdo.trace.rental.rental.infra;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.gakdo.trace.rental.rental.domain.Checkpoint;

import jakarta.persistence.LockModeType;

public interface CheckpointRepository extends JpaRepository<Checkpoint, Long> {

    /** 같은 시점 이벤트가 동시에 와도 하나씩 반영한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Checkpoint c join fetch c.rental where c.publicId = :publicId")
    Optional<Checkpoint> lockByPublicId(String publicId);
}
