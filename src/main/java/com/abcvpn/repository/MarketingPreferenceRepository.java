package com.abcvpn.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MarketingPreferenceRepository {
    private final JdbcTemplate jdbcTemplate;
    public MarketingPreferenceRepository(JdbcTemplate jdbcTemplate) {this.jdbcTemplate=jdbcTemplate;}
    public void optOut(long telegramId) {jdbcTemplate.update("UPDATE users SET marketing_opt_out=TRUE WHERE telegram_id=?",telegramId);}
    public void optIn(long telegramId) {jdbcTemplate.update("UPDATE users SET marketing_opt_out=FALSE,telegram_blocked=FALSE WHERE telegram_id=?",telegramId);}
    public void blocked(long telegramId) {jdbcTemplate.update("UPDATE users SET telegram_blocked=TRUE WHERE telegram_id=?",telegramId);}
}
