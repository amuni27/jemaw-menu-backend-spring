package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.AdminAuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.List;

/** AGF E4-M1: insert and read only (no update or delete methods on purpose). */
public interface AdminAuditEventRepository extends Repository<AdminAuditEvent, String> {

    AdminAuditEvent save(AdminAuditEvent event);

    Page<AdminAuditEvent> findAllByOrderByOccurredAtDesc(Pageable pageable);

    /** AGF E4-M6 (D-M6-1): the records that bind a transition date to the configuration version it was set under. */
    List<AdminAuditEvent> findByActionAndTargetId(String action, String targetId);
}
