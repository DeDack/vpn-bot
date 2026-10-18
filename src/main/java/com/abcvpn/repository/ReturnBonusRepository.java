package com.abcvpn.repository;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReturnBonusRepository {
    private final JdbcTemplate jdbcTemplate;
    public ReturnBonusRepository(JdbcTemplate jdbcTemplate) {this.jdbcTemplate=jdbcTemplate;}
    public boolean create(long telegramId,Instant activatedAt,Instant previousExpiresAt) {
        return jdbcTemplate.update("""
                INSERT INTO return_bonuses(telegram_id,days,activated_at,previous_expires_at) VALUES(?,2,?,?)
                ON CONFLICT(telegram_id) DO NOTHING
                """,telegramId,Timestamp.from(activatedAt),previousExpiresAt==null?null:Timestamp.from(previousExpiresAt))==1;
    }
    public boolean hasVpnTraffic(long telegramId) {
        Integer count=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analytics_events WHERE telegram_id=? AND event_type='vpn_connected'",Integer.class,telegramId);
        return count!=null&&count>0;
    }
    public boolean hasReliableNoTrafficHistory(long telegramId) {
        Boolean reliable=jdbcTemplate.queryForObject("""
                SELECT u.created_at >= COALESCE((SELECT dateexecuted FROM databasechangelog
                  WHERE id='008-analytics-events' ORDER BY orderexecuted DESC LIMIT 1),NOW())
                FROM users u WHERE u.telegram_id=?
                """,Boolean.class,telegramId);
        return Boolean.TRUE.equals(reliable)&&!hasVpnTraffic(telegramId);
    }
}
