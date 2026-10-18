package com.abcvpn.repository;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AnalyticsEventRepository {
    private final JdbcTemplate jdbcTemplate;
    public AnalyticsEventRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate=jdbcTemplate; }

    public boolean record(long telegramId,String event,Instant occurredAt) {
        return jdbcTemplate.update("""
                INSERT INTO analytics_events(telegram_id,event_type,occurred_at)
                SELECT ?,?,? WHERE EXISTS(SELECT 1 FROM users WHERE telegram_id=?)
                ON CONFLICT(telegram_id,event_type) DO NOTHING
                """,telegramId,event,Timestamp.from(occurredAt),telegramId)==1;
    }
}
