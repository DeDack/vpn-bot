package com.abcvpn.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.abcvpn.entity.Tariff;

@Repository
public class TariffRepository {
    private final JdbcTemplate jdbcTemplate;
    public TariffRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    public Optional<Tariff> findById(long id) { return findOne("SELECT * FROM tariffs WHERE id = ?", id); }
    public Optional<Tariff> findByCode(String code) { return findOne("SELECT * FROM tariffs WHERE code = ?", code); }
    public List<Tariff> findEnabled() {
        return jdbcTemplate.query("SELECT * FROM tariffs WHERE enabled = TRUE AND code <> 'TRIAL' ORDER BY sort_order, id", this::map);
    }
    public List<Tariff> findAll() { return jdbcTemplate.query("SELECT * FROM tariffs ORDER BY sort_order, id", this::map); }

    public long save(Long id, String code, String name, int durationDays, java.math.BigDecimal priceRub,
                     int deviceLimit, Long trafficLimit, UUID squadId, boolean enabled, int sortOrder) {
        if (id == null) {
            String sql = """
                    INSERT INTO tariffs(code,name,duration_days,price_rub,device_limit,traffic_limit,remnawave_squad_id,enabled,sort_order)
                    VALUES(?,?,?,?,?,?,?,?,?) RETURNING id
                    """;
            return jdbcTemplate.queryForObject(sql, Long.class, code, name, durationDays, priceRub,
                    deviceLimit, trafficLimit, squadId, enabled, sortOrder);
        }
        String sql = """
                UPDATE tariffs SET code=?,name=?,duration_days=?,price_rub=?,device_limit=?,traffic_limit=?,
                    remnawave_squad_id=?,enabled=?,sort_order=? WHERE id=?
                """;
        jdbcTemplate.update(sql, code, name, durationDays, priceRub, deviceLimit, trafficLimit,
                squadId, enabled, sortOrder, id);
        return id;
    }

    public void setEnabled(long id, boolean enabled) {
        jdbcTemplate.update("UPDATE tariffs SET enabled = ? WHERE id = ?", enabled, id);
    }

    private Optional<Tariff> findOne(String sql, Object value) {
        try { return Optional.ofNullable(jdbcTemplate.queryForObject(sql, this::map, value)); }
        catch (EmptyResultDataAccessException exception) { return Optional.empty(); }
    }

    private Tariff map(ResultSet rs, int rowNum) throws SQLException {
        return new Tariff(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getInt("duration_days"), rs.getBigDecimal("price_rub"), rs.getInt("device_limit"),
                (Long) rs.getObject("traffic_limit"), rs.getObject("remnawave_squad_id", UUID.class),
                rs.getBoolean("enabled"), rs.getInt("sort_order"));
    }
}
