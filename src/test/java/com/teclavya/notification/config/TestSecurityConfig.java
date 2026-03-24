package com.teclavya.notification.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Replaces SecurityConfig for all @WebMvcTest slices.
 * Permits everything so tests don't need JWT tokens or spring-security-test.
 */
@TestConfiguration
public class TestSecurityConfig {

    @Bean
    public SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}