package com.hz.crm.notification;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

	List<Notification> findByRecipientIdOrderByCreatedAtDescIdDesc(Long recipientId, Pageable page);

	long countByRecipientIdAndReadFalse(Long recipientId);

	@Transactional
	@Modifying
	@Query("update Notification n set n.read = true where n.recipientId = ?1 and n.read = false")
	int markAllRead(Long recipientId);
}
