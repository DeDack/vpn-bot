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
import com.abcvpn.entity.User;
import com.abcvpn.enumeration.SourceType;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    public Optional<User> findById(long telegramId) {
        String sql = "SELECT * FROM users WHERE telegram_id = ?";
        try { return Optional.ofNullable(jdbcTemplate.queryForObject(sql, this::map, telegramId)); }
        catch (EmptyResultDataAccessException exception) { return Optional.empty(); }
    }

    public Optional<User> findByIdForUpdate(long telegramId) {
        try { return Optional.ofNullable(jdbcTemplate.queryForObject("SELECT * FROM users WHERE telegram_id = ? FOR UPDATE",this::map,telegramId)); }
        catch(EmptyResultDataAccessException exception) { return Optional.empty(); }
    }

    public Optional<User> findByReferralCode(String code) {
        String sql = "SELECT * FROM users WHERE referral_code = ?";
        try { return Optional.ofNullable(jdbcTemplate.queryForObject(sql, this::map, code)); }
        catch (EmptyResultDataAccessException exception) { return Optional.empty(); }
    }

    public User create(long telegramId, String username, String firstName, SourceType sourceType,
                       Long campaignId, Long referrerId, String referralCode, Instant now) {
        String sql = """
                INSERT INTO users (telegram_id, username, first_name, source_type, campaign_id,
                                   referrer_telegram_id, referral_code, created_at, last_activity_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (telegram_id) DO NOTHING
                """;
        jdbcTemplate.update(sql, telegramId, username, firstName, sourceType.name(), campaignId,
                referrerId, referralCode, Timestamp.from(now), Timestamp.from(now));
        return findById(telegramId).orElseThrow();
    }

    public void updateActivity(long telegramId, String username, String firstName, Instant now) {
        String sql = "UPDATE users SET username = ?, first_name = ?, last_activity_at = ? WHERE telegram_id = ?";
        jdbcTemplate.update(sql, username, firstName, Timestamp.from(now), telegramId);
    }

    public boolean markTrialUsed(long telegramId) {
        String sql = "UPDATE users SET trial_used = TRUE WHERE telegram_id = ? AND trial_used = FALSE";
        return jdbcTemplate.update(sql, telegramId) == 1;
    }

    public void setBanned(long telegramId, boolean banned) {
        jdbcTemplate.update("UPDATE users SET is_banned = ? WHERE telegram_id = ?", banned, telegramId);
    }

    public List<User> search(String query, int limit) {
        String sql = """
                SELECT * FROM users
                WHERE ? = '' OR CAST(telegram_id AS TEXT) = ? OR LOWER(COALESCE(username, '')) LIKE LOWER(?)
                    OR LOWER(COALESCE(first_name, '')) LIKE LOWER(?)
                ORDER BY created_at DESC LIMIT ?
                """;
        String pattern = "%" + query + "%";
        return jdbcTemplate.query(sql, this::map, query, query, pattern, pattern, limit);
    }

    public boolean delete(long telegramId) {
        jdbcTemplate.update("UPDATE users SET referrer_telegram_id=NULL WHERE referrer_telegram_id=?",telegramId);
        jdbcTemplate.update("DELETE FROM referral_rewards WHERE referrer_telegram_id=? OR referred_telegram_id=?",telegramId,telegramId);
        jdbcTemplate.update("DELETE FROM payments WHERE telegram_id=?",telegramId);
        return jdbcTemplate.update("DELETE FROM users WHERE telegram_id=?",telegramId)==1;
    }

    private User map(ResultSet rs, int rowNum) throws SQLException {
        return new User(rs.getLong("telegram_id"), rs.getString("username"), rs.getString("first_name"),
                rs.getBoolean("trial_used"), SourceType.valueOf(rs.getString("source_type")),
                (Long) rs.getObject("campaign_id"), (Long) rs.getObject("referrer_telegram_id"),
                rs.getString("referral_code"), rs.getBoolean("is_banned"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("last_activity_at").toInstant());
    }
}
