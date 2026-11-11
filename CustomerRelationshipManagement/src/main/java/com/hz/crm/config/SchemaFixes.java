package com.hz.crm.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Small database corrections that automatic schema updates do not make. Hibernate adds a CHECK constraint listing
 * the values of an enum column when it creates a table, but never widens it when a value is added later, so the
 * new value would be rejected. The application already validates these columns, so the outdated checks are dropped.
 */
@Component
public class SchemaFixes {

	private static final Logger log = LoggerFactory.getLogger(SchemaFixes.class);

	private final JdbcTemplate jdbc;

	public SchemaFixes(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void apply() {
		run("ALTER TABLE notifications DROP CONSTRAINT IF EXISTS notifications_type_check");
		// activities against a task have no customer; the column was created NOT NULL when every activity had one
		run("ALTER TABLE activities ALTER COLUMN customer_id DROP NOT NULL");
		// a customer needs a name and at least one of mobile, email or address; email used to be required
		run("ALTER TABLE customers ALTER COLUMN email DROP NOT NULL");
	}

	private void run(String sql) {
		try {
			jdbc.execute(sql);
		} catch (RuntimeException e) {
			log.warn("Schema fix not applied ({}): {}", sql, e.getMessage());
		}
	}
}
