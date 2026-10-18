package com.abcvpn.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HealthService {
    private final JdbcTemplate jdbcTemplate;
    public HealthService(JdbcTemplate jdbcTemplate) { this.jdbcTemplate=jdbcTemplate; }
    public boolean ready() {
        Integer value=jdbcTemplate.queryForObject("SELECT 1",Integer.class);
        return value!=null&&value==1;
    }
}
