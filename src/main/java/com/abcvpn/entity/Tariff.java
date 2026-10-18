package com.abcvpn.entity;

import java.math.BigDecimal;
import java.util.UUID;

public record Tariff(long id, String code, String name, int durationDays, BigDecimal priceRub,
                     int deviceLimit, Long trafficLimit, UUID remnawaveSquadId,
                     boolean enabled, int sortOrder) {}
