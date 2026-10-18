package com.abcvpn.entity;

import java.time.Instant;

public record Campaign(long id, String code, String name, String comment,
                       boolean enabled, Instant createdAt) {}
