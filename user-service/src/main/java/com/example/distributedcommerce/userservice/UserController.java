package com.example.distributedcommerce.userservice;

import com.example.distributedcommerce.security.JwtTokenService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final JwtTokenService tokens;

    public UserController(JdbcTemplate jdbc, JwtTokenService tokens) {
        this.jdbc = jdbc;
        this.tokens = tokens;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO users (id,email,password_hash,name) VALUES (?,?,?,?)",
                    id, request.email().trim().toLowerCase(), passwords.encode(request.password()), request.name().trim());
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
        }
        UserView user = new UserView(id, request.email().trim().toLowerCase(), request.name().trim(), "CUSTOMER");
        return new AuthResponse(user, tokens.issue(id.toString(), user.role()), tokens.expiresInSeconds());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        var users = jdbc.query("SELECT id,email,password_hash,name,role FROM users WHERE email=?",
                (rs, row) -> new StoredUser(UUID.fromString(rs.getString("id")), rs.getString("email"),
                        rs.getString("password_hash"), rs.getString("name"), rs.getString("role")),
                request.email().trim().toLowerCase());
        if (users.isEmpty() || !passwords.matches(request.password(), users.getFirst().passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        var user = users.getFirst();
        UserView profile = new UserView(user.id(), user.email(), user.name(), user.role());
        return new AuthResponse(profile, tokens.issue(user.id().toString(), user.role()), tokens.expiresInSeconds());
    }

    @GetMapping("/{id}")
    public UserView get(@PathVariable UUID id, @RequestHeader("X-User-Id") UUID authenticatedUser) {
        if (!id.equals(authenticatedUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot access another user's profile");
        }
        return jdbc.query("SELECT id,email,name,role FROM users WHERE id=?",
                (rs, row) -> new UserView(UUID.fromString(rs.getString("id")),
                        rs.getString("email"), rs.getString("name"), rs.getString("role")), id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    public record RegisterRequest(@NotBlank @Email String email, @NotBlank @Size(min = 8, max = 72) String password,
                                  @NotBlank @Size(max = 255) String name) {}
    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}
    public record UserView(UUID id, String email, String name, String role) {}
    public record AuthResponse(UserView user, String token, long expiresInSeconds) {}
    private record StoredUser(UUID id, String email, String passwordHash, String name, String role) {}
}
