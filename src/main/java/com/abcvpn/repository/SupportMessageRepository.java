package com.abcvpn.repository;

import java.util.OptionalLong;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SupportMessageRepository {
    private final JdbcTemplate jdbcTemplate;

    public SupportMessageRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate=jdbcTemplate; }

    public boolean claim(long updateId,long telegramId,int userMessageId,String direction) {
        try {
            return jdbcTemplate.update("""
                    INSERT INTO support_message_routes(update_id,telegram_id,user_message_id,direction)
                    VALUES(?,?,?,?) ON CONFLICT(update_id) DO NOTHING
                    """,updateId,telegramId,userMessageId,direction)==1;
        } catch(DuplicateKeyException exception) { return false; }
    }

    public void attachAdminMessage(long updateId,int adminMessageId) {
        jdbcTemplate.update("UPDATE support_message_routes SET admin_message_id=? WHERE update_id=?",adminMessageId,updateId);
    }

    public OptionalLong findUserByAdminMessage(int adminMessageId) {
        var ids=jdbcTemplate.query("""
                SELECT telegram_id FROM support_message_routes
                WHERE direction='USER_TO_ADMIN' AND admin_message_id=?
                """,(rs,row)->rs.getLong(1),adminMessageId);
        return ids.isEmpty()?OptionalLong.empty():OptionalLong.of(ids.getFirst());
    }

    public void release(long updateId) {
        jdbcTemplate.update("DELETE FROM support_message_routes WHERE update_id=?",updateId);
    }
}
