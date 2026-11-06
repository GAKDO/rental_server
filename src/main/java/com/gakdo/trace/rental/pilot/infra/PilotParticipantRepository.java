package com.gakdo.trace.rental.pilot.infra;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gakdo.trace.rental.pilot.domain.PilotParticipant;

public interface PilotParticipantRepository extends JpaRepository<PilotParticipant, Long> {

    boolean existsByAlias(String alias);
}
