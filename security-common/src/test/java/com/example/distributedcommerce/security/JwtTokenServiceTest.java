package com.example.distributedcommerce.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenServiceTest {
    private static final String SECRET = "a-local-test-secret-with-at-least-32-bytes";
    private final JwtTokenService tokens = new JwtTokenService(SECRET, new ObjectMapper());

    @Test
    void issuedTokenContainsUserAndRoleAndHasTwelveHourLifetime() {
        var claims = tokens.verify(tokens.issue("user-123", "ADMIN"));

        assertEquals("user-123", claims.subject());
        assertEquals("ADMIN", claims.role());
        assertEquals(43_200, tokens.expiresInSeconds());
    }

    @Test
    void rejectsTamperedToken() {
        String[] parts = tokens.issue("user-123", "CUSTOMER").split("\\.");
        parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);

        assertThrows(JwtTokenService.InvalidTokenException.class,
                () -> tokens.verify(String.join(".", parts)));
    }

    @Test
    void rejectsSecretShorterThanThirtyTwoBytes() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new JwtTokenService("too-short", new ObjectMapper()));

        assertTrue(exception.getMessage().contains("32 bytes"));
    }
}
