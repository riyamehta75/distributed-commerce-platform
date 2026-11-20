package com.example.distributedcommerce.apigateway;

import com.example.distributedcommerce.security.JwtTokenService;
import com.example.distributedcommerce.security.JwtTokenService.Claims;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JwtAuthenticationFilter implements GlobalFilter {
    private final JwtTokenService tokens;

    public JwtAuthenticationFilter(JwtTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        HttpMethod method = request.getMethod();
        if (method == HttpMethod.OPTIONS || !path.startsWith("/api/") || isPublic(method, path)) {
            return chain.filter(exchange);
        }

        String authorization = request.getHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        final Claims claims;
        try {
            claims = tokens.verify(authorization.substring(7));
        } catch (JwtTokenService.InvalidTokenException ex) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }

        if (requiresAdmin(method, path) && !claims.role().equals("ADMIN")) {
            return reject(exchange, HttpStatus.FORBIDDEN, "Administrator role required");
        }
        if (method == HttpMethod.GET && path.equals("/api/orders")) {
            String requestedUser = request.getQueryParams().getFirst("userId");
            if (requestedUser != null && !requestedUser.equals(claims.subject())) {
                return reject(exchange, HttpStatus.FORBIDDEN, "Cannot access another user's orders");
            }
        }
        if (path.matches("/api/users/[^/]+")
                && !path.substring("/api/users/".length()).equals(claims.subject())) {
            return reject(exchange, HttpStatus.FORBIDDEN, "Cannot access another user's profile");
        }

        ServerHttpRequest authenticated = request.mutate().headers(headers -> {
            headers.remove("X-Internal-Token");
            headers.set("X-User-Id", claims.subject());
            headers.set("X-User-Role", claims.role());
        }).build();
        return chain.filter(exchange.mutate().request(authenticated).build());
    }

    private boolean isPublic(HttpMethod method, String path) {
        if (method == HttpMethod.POST && (path.equals("/api/users/register") || path.equals("/api/users/login"))) {
            return true;
        }
        return method == HttpMethod.GET
                && (path.equals("/api/products") || path.matches("/api/products/[^/]+"));
    }

    private boolean requiresAdmin(HttpMethod method, String path) {
        if (path.startsWith("/api/notifications")) return true;
        if (path.startsWith("/api/products/") || path.equals("/api/products")) {
            return method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.DELETE;
        }
        return method == HttpMethod.PUT && path.matches("/api/inventory/[^/]+");
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
