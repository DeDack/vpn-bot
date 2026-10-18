package com.abcvpn.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI abcVpnOpenApi() {
        return new OpenAPI().info(new Info()
                .title("abc-vpn API")
                .description("Backend API сервиса abc-vpn. Управление пользователями, подписками, платежами, тарифами, рекламными кампаниями и интеграциями.")
                .version("0.0.1-SNAPSHOT"));
    }
}
