package com.abcvpn.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.abcvpn.enumeration.ReferralEvent;

@Repository
public class ReferralRewardRepository {
    private final JdbcTemplate jdbcTemplate;
    public ReferralRewardRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    public boolean create(long referrerId,long referredId,ReferralEvent event,int days) {
        return jdbcTemplate.update("""
                INSERT INTO referral_rewards(referrer_telegram_id,referred_telegram_id,event,days)
                VALUES(?,?,?,?) ON CONFLICT (referrer_telegram_id,referred_telegram_id,event) DO NOTHING
                """,referrerId,referredId,event.name(),days)==1;
    }

    public ReferralStats stats(long referrerId) {
        String sql = """
                SELECT COUNT(DISTINCT u.telegram_id) invited,
                    COUNT(DISTINCT u.telegram_id) FILTER(WHERE u.trial_used) trials,
                    COUNT(DISTINCT p.telegram_id) FILTER(WHERE p.status='PAID') paid
                FROM users u LEFT JOIN payments p ON p.telegram_id=u.telegram_id
                WHERE u.referrer_telegram_id=?
                """;
        return jdbcTemplate.queryForObject(sql,(rs,n)->new ReferralStats(rs.getLong("invited"),rs.getLong("trials"),rs.getLong("paid")),referrerId);
    }
    public record ReferralStats(long invited,long trials,long paid) {}
}
