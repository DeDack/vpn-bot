package com.abcvpn.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    public UserDetailsService userDetailsService(AbcVpnProperties properties,PasswordEncoder encoder) {
        if (properties.adminPassword() == null || properties.adminPassword().length() < 12) {
            throw new IllegalStateException("ADMIN_WEB_PASSWORD must contain at least 12 characters");
        }
        if (properties.adminSessionSecret() == null || properties.adminSessionSecret().length() < 16) {
            throw new IllegalStateException("ADMIN_WEB_SESSION_SECRET must contain at least 16 characters");
        }
        return new InMemoryUserDetailsManager(User.withUsername("admin")
                .password(encoder.encode(properties.adminPassword())).roles("ADMIN").build());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health","/ready","/actuator/health/**","/payment/**","/remnawave/webhook","/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html","/error").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN").anyRequest().permitAll())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/payment/callback","/remnawave/webhook"))
                .formLogin(login -> login.defaultSuccessUrl("/admin",true))
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
                .build();
    }
}
