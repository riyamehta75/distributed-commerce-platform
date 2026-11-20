package com.example.distributedcommerce.userservice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.UUID;

@Configuration
public class AdminSeeder {
    @Bean
    ApplicationRunner seedAdministrator(JdbcTemplate jdbc,
                                        @Value("${demo.admin.email}") String email,
                                        @Value("${demo.admin.password}") String password) {
        return args -> {
            if (email.isBlank() || password.length() < 12) {
                throw new IllegalStateException("Configure an admin email and a password of at least 12 characters");
            }
            Integer count = jdbc.queryForObject("SELECT count(*) FROM users WHERE email=?", Integer.class,
                    email.trim().toLowerCase());
            if (count != null && count > 0) {
                jdbc.update("UPDATE users SET role='ADMIN',password_hash=?,updated_at=now() WHERE email=?",
                        new BCryptPasswordEncoder().encode(password), email.trim().toLowerCase());
            } else {
                jdbc.update("INSERT INTO users(id,email,password_hash,name,role) VALUES (?,?,?,?, 'ADMIN')",
                        UUID.randomUUID(), email.trim().toLowerCase(),
                        new BCryptPasswordEncoder().encode(password), "Store Administrator");
            }
        };
    }
}
