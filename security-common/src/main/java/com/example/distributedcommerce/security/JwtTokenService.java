package com.example.distributedcommerce.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@Component
public class JwtTokenService {
    private static final String ISSUER = "distributed-commerce";
    private static final long TOKEN_TTL_SECONDS = 43_200;
    private final byte[] secret;
    private final ObjectMapper objectMapper;

    public JwtTokenService(@Value("${jwt.secret}") String secret, ObjectMapper objectMapper) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must contain at least 32 bytes");
        }
        this.objectMapper = objectMapper;
    }

    public String issue(String subject, String role) {
        long issuedAt = Instant.now().getEpochSecond();
        try {
            String header = encode(objectMapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
            String payload = encode(objectMapper.writeValueAsBytes(Map.of(
                    "iss", ISSUER,
                    "sub", subject,
                    "role", role,
                    "iat", issuedAt,
                    "exp", issuedAt + TOKEN_TTL_SECONDS)));
            String content = header + "." + payload;
            return content + "." + encode(sign(content));
        } catch (JsonProcessingException | GeneralSecurityException ex) {
            throw new IllegalStateException("Could not encode authentication token", ex);
        }
    }

    public Claims verify(String token) {
        try {
            String[] segments = token.split("\\.", -1);
            if (segments.length != 3) throw new InvalidTokenException();
            String content = segments[0] + "." + segments[1];
            byte[] expected = sign(content);
            byte[] supplied = Base64.getUrlDecoder().decode(segments[2]);
            if (!MessageDigest.isEqual(expected, supplied)) throw new InvalidTokenException();

            var header = objectMapper.readTree(Base64.getUrlDecoder().decode(segments[0]));
            var payload = objectMapper.readTree(Base64.getUrlDecoder().decode(segments[1]));
            if (!"HS256".equals(header.path("alg").asText())
                    || !ISSUER.equals(payload.path("iss").asText())
                    || payload.path("exp").asLong(0) <= Instant.now().getEpochSecond()) {
                throw new InvalidTokenException();
            }
            String subject = payload.path("sub").asText();
            String role = payload.path("role").asText();
            if (subject.isBlank() || !(role.equals("ADMIN") || role.equals("CUSTOMER"))) {
                throw new InvalidTokenException();
            }
            return new Claims(subject, role);
        } catch (InvalidTokenException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new InvalidTokenException();
        }
    }

    public long expiresInSeconds() {
        return TOKEN_TTL_SECONDS;
    }

    private byte[] sign(String content) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(content.getBytes(StandardCharsets.US_ASCII));
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public record Claims(String subject, String role) {}

    public static final class InvalidTokenException extends RuntimeException {
        public InvalidTokenException() {
            super("Invalid or expired token");
        }
    }
}
