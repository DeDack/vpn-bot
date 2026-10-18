package com.abcvpn.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PlategaCallback(@NotNull UUID id, @NotNull BigDecimal amount,
                              @NotBlank String currency, @NotBlank String status, Integer paymentMethod) {}
