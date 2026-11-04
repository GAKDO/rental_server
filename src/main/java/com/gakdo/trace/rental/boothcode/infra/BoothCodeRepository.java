package com.gakdo.trace.rental.boothcode.infra;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.gakdo.trace.rental.boothcode.domain.BoothCode;

public interface BoothCodeRepository extends JpaRepository<BoothCode, Long> {

    List<BoothCode> findByCodeHash(String codeHash);

    @Query("select count(b) > 0 from BoothCode b where b.codeHash = :hash and b.usedAt is null and b.expiresAt > :now")
    boolean existsLive(String hash, OffsetDateTime now);

    /** 같은 렌탈에 다시 발급하면 앞서 발급한 안 쓴 코드를 못 쓰게 한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update BoothCode b set b.usedAt = :now where b.rental.id = :rentalId and b.usedAt is null")
    int retireUnused(Long rentalId, OffsetDateTime now);

    /** 한 번만 쓰기: 동시에 두 부스가 넣어도 한쪽만 1을 받는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update BoothCode b set b.usedAt = :now where b.id = :id and b.usedAt is null")
    int markUsed(Long id, OffsetDateTime now);

    /** 하루 지난 코드는 지운다(발급할 때 함께). 해시 충돌 검사 대상을 작게 둔다. */
    @Modifying(flushAutomatically = true)
    @Query("delete from BoothCode b where b.expiresAt < :before")
    int purgeExpired(OffsetDateTime before);
}
