package com.abcvpn.controller;

import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.abcvpn.service.HealthService;

@RestController
@Tag(name="Health",description="Проверки состояния приложения")
public class HealthController {
    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    @Operation(summary="Проверить процесс приложения",description="Возвращает UP, если HTTP-процесс работает.")
    @ApiResponse(responseCode="200",description="Приложение работает")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/ready")
    @Operation(summary="Проверить готовность",description="Проверяет доступность PostgreSQL для бизнес-операций.")
    @ApiResponses({@ApiResponse(responseCode="200",description="Приложение готово"),@ApiResponse(responseCode="500",description="PostgreSQL недоступен")})
    public ResponseEntity<Map<String, String>> ready() {
        return healthService.ready()
                ? ResponseEntity.ok(Map.of("status", "UP"))
                : ResponseEntity.internalServerError().body(Map.of("status", "DOWN"));
    }
}
