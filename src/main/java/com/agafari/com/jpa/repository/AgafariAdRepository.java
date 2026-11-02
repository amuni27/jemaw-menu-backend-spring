package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.AgafariAd;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** AGF E4-M7: insert, read and update; no delete method on purpose (the DB refuses deletes too). */
public interface AgafariAdRepository extends Repository<AgafariAd, String> {

    AgafariAd saveAndFlush(AgafariAd ad);

    Optional<AgafariAd> findById(String id);

    List<AgafariAd> findAllByOrderByCreatedAtDesc();

    /** Servable now, in the stable rotation order (C3): ACTIVE, started, not ended. */
    @Query("select a from AgafariAd a where a.status = 'ACTIVE' and a.startsAt <= :now and (a.endsAt is null or a.endsAt > :now) "
            + "order by a.startsAt, a.createdAt, a.id")
    List<AgafariAd> findServable(Instant now);
}
