package com.agafari.com.jpa.repository;

import com.agafari.com.jpa.entity.AdminAuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** AGF E4-M1: insert and read only (no update or delete methods on purpose). */
public interface AdminAuditEventRepository extends Repository<AdminAuditEvent, String> {

    AdminAuditEvent save(AdminAuditEvent event);

    Page<AdminAuditEvent> findAllByOrderByOccurredAtDesc(Pageable pageable);
}
