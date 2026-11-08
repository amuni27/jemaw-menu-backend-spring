package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.ScheduledChange;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** AGF SUB-R2-11: reads only; {@code ScheduledChangeService} writes with conditional statements (BD-12). */
public interface ScheduledChangeRepository extends Repository<ScheduledChange, String> {

    Optional<ScheduledChange> findById(String id);

    @Query("select c from ScheduledChange c where c.businessId = :businessId and c.status in ('PENDING', 'AWAITING_PAYMENT')")
    Optional<ScheduledChange> findOpenByBusinessId(String businessId);

    @Query("select c from ScheduledChange c where c.status = 'PENDING' and c.effectiveAt <= :now and c.id > :after order by c.id")
    List<ScheduledChange> findDue(Instant now, String after, Limit limit);
}
