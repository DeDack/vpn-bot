package com.abcvpn.config;

import java.time.Clock;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;

@Configuration
@EnableConfigurationProperties({AbcVpnProperties.class, PlategaProperties.class})
public class ApplicationConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        HttpClient httpClient=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        return RestClient.builder().requestFactory(new JdkClientHttpRequestFactory(httpClient));
    }
}
