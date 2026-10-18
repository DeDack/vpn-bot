package com.abcvpn.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.abcvpn.entity.Subscription;
import com.abcvpn.enumeration.SyncStatus;

@Repository
public class SubscriptionRepository {
    private final JdbcTemplate jdbcTemplate;
    public SubscriptionRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    public Optional<Subscription> findByTelegramId(long telegramId) {
        try { return Optional.ofNullable(jdbcTemplate.queryForObject("SELECT * FROM subscriptions WHERE telegram_id = ?", this::map, telegramId)); }
        catch (EmptyResultDataAccessException exception) { return Optional.empty(); }
    }

    public Optional<Subscription> findByTelegramIdForUpdate(long telegramId) {
        try { return Optional.ofNullable(jdbcTemplate.queryForObject("SELECT * FROM subscriptions WHERE telegram_id = ? FOR UPDATE", this::map, telegramId)); }
        catch (EmptyResultDataAccessException exception) { return Optional.empty(); }
    }

    public Optional<Subscription> findByToken(String token) {
        try { return Optional.ofNullable(jdbcTemplate.queryForObject(
                "SELECT * FROM subscriptions WHERE subscription_url LIKE ?",this::map,"%/"+token)); }
        catch (EmptyResultDataAccessException exception) { return Optional.empty(); }
    }

    public Optional<Subscription> findBySubscriptionUrl(String subscriptionUrl) {
        try { return Optional.ofNullable(jdbcTemplate.queryForObject(
                "SELECT * FROM subscriptions WHERE subscription_url = ?",this::map,subscriptionUrl)); }
        catch(EmptyResultDataAccessException exception) { return Optional.empty(); }
    }

    public void upsertAccess(long telegramId, long tariffId, Instant expiresAt) {
        String sql = """
                INSERT INTO subscriptions(telegram_id,tariff_id,expires_at,revoked,sync_status)
                VALUES(?,?,?,FALSE,'PENDING')
                ON CONFLICT(telegram_id) DO UPDATE SET tariff_id=EXCLUDED.tariff_id, expires_at=EXCLUDED.expires_at,
                    revoked=FALSE, sync_status='PENDING', sync_next_at=NULL, updated_at=NOW()
                """;
        jdbcTemplate.update(sql, telegramId, tariffId, timestamp(expiresAt));
    }

    public void markProvisioned(long telegramId, String remnawaveUserId, String subscriptionUrl) {
        String sql = """
                UPDATE subscriptions SET remnawave_user_id=?, subscription_url=COALESCE(subscription_url, ?),
                    sync_status='SYNCED', sync_retry_count=0, sync_next_at=NULL, sync_error=NULL, updated_at=NOW()
                WHERE telegram_id=?
                """;
        jdbcTemplate.update(sql, remnawaveUserId, subscriptionUrl, telegramId);
    }

    public void markSynced(long telegramId) {
        jdbcTemplate.update("""
                UPDATE subscriptions SET sync_status='SYNCED',sync_retry_count=0,sync_next_at=NULL,
                    sync_error=NULL,updated_at=NOW() WHERE telegram_id=?
                """, telegramId);
    }

    public void markError(long telegramId, int retryCount, Instant nextAt, String error) {
        jdbcTemplate.update("""
                UPDATE subscriptions SET sync_status='ERROR',sync_retry_count=?,sync_next_at=?,sync_error=?,updated_at=NOW()
                WHERE telegram_id=?
                """, retryCount, Timestamp.from(nextAt), abbreviate(error), telegramId);
    }

    public void markPending(long telegramId) {
        jdbcTemplate.update("UPDATE subscriptions SET sync_status='PENDING',sync_next_at=NULL,updated_at=NOW() WHERE telegram_id=?", telegramId);
    }

    public void revoke(long telegramId) {
        jdbcTemplate.update("UPDATE subscriptions SET revoked=TRUE,sync_status='PENDING',sync_next_at=NULL,updated_at=NOW() WHERE telegram_id=?", telegramId);
    }

    public List<Subscription> findDueForSync(Instant now, int limit) {
        String sql = """
                SELECT * FROM subscriptions WHERE sync_status='PENDING'
                    OR (sync_status='ERROR' AND sync_next_at <= ?)
                ORDER BY updated_at LIMIT ?
                """;
        return jdbcTemplate.query(sql, this::map, Timestamp.from(now), limit);
    }

    public List<Subscription> claimDueForSync(Instant now,int limit) {
        String sql="""
                WITH due AS (
                    SELECT telegram_id FROM subscriptions
                    WHERE sync_status='PENDING' OR (sync_status='ERROR' AND sync_next_at <= ?)
                    ORDER BY updated_at FOR UPDATE SKIP LOCKED LIMIT ?
                )
                UPDATE subscriptions s SET sync_status='ERROR',sync_next_at=?,sync_error='Sync in progress',updated_at=NOW()
                FROM due WHERE s.telegram_id=due.telegram_id RETURNING s.*
                """;
        return jdbcTemplate.query(sql,this::map,Timestamp.from(now),limit,Timestamp.from(now.plusSeconds(300)));
    }

    public List<Subscription> findExpiredNotQueued(Instant now, int limit) {
        String sql = """
                SELECT * FROM subscriptions WHERE revoked=FALSE AND expires_at <= ? AND sync_status='SYNCED'
                    AND updated_at <= expires_at
                ORDER BY expires_at LIMIT ?
                """;
        return jdbcTemplate.query(sql, this::map, Timestamp.from(now), limit);
    }

    public List<Subscription> findProvisioned(int limit) {
        return jdbcTemplate.query("SELECT * FROM subscriptions WHERE remnawave_user_id IS NOT NULL ORDER BY updated_at LIMIT ?", this::map, limit);
    }

    public List<Subscription> findErrors(int limit) {
        return jdbcTemplate.query("SELECT * FROM subscriptions WHERE sync_status='ERROR' ORDER BY updated_at DESC LIMIT ?", this::map, limit);
    }

    private String abbreviate(String error) { return error == null ? "Unknown error" : error.substring(0, Math.min(error.length(), 1000)); }
    private Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private Instant instant(ResultSet rs, String name) throws SQLException { Timestamp value = rs.getTimestamp(name); return value == null ? null : value.toInstant(); }
    private Subscription map(ResultSet rs, int rowNum) throws SQLException {
        return new Subscription(rs.getLong("telegram_id"), rs.getLong("tariff_id"), instant(rs,"expires_at"),
                rs.getBoolean("revoked"), rs.getString("remnawave_user_id"), rs.getString("subscription_url"),
                SyncStatus.valueOf(rs.getString("sync_status")), rs.getInt("sync_retry_count"),
                instant(rs,"sync_next_at"), rs.getString("sync_error"), instant(rs,"created_at"), instant(rs,"updated_at"));
    }
}
