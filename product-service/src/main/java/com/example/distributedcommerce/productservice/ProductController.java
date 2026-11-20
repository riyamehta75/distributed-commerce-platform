package com.example.distributedcommerce.productservice;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final JdbcTemplate jdbc;

    public ProductController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<ProductView> list(@RequestParam(defaultValue = "") String q) {
        return jdbc.query("SELECT id,name,description,price FROM products WHERE active=true AND lower(name) LIKE lower(?) ORDER BY name",
                (rs, row) -> new ProductView(UUID.fromString(rs.getString("id")), rs.getString("name"),
                        rs.getString("description"), rs.getBigDecimal("price")), "%" + q.trim() + "%");
    }

    @GetMapping("/{id}")
    public ProductView get(@PathVariable UUID id) {
        return find(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductView create(@Valid @RequestBody ProductRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO products(id,name,description,price) VALUES (?,?,?,?)",
                id, request.name().trim(), request.description(), request.price());
        return find(id);
    }

    @PutMapping("/{id}")
    public ProductView update(@PathVariable UUID id, @Valid @RequestBody ProductRequest request) {
        int updated = jdbc.update("UPDATE products SET name=?,description=?,price=?,updated_at=now(),version=version+1 WHERE id=? AND active=true",
                request.name().trim(), request.description(), request.price(), id);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        return find(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        int updated = jdbc.update("UPDATE products SET active=false,updated_at=now(),version=version+1 WHERE id=? AND active=true", id);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
    }

    private ProductView find(UUID id) {
        return jdbc.query("SELECT id,name,description,price FROM products WHERE id=? AND active=true",
                (rs, row) -> new ProductView(UUID.fromString(rs.getString("id")), rs.getString("name"),
                        rs.getString("description"), rs.getBigDecimal("price")), id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }

    public record ProductRequest(@NotBlank @Size(max = 255) String name, String description,
                                 @NotNull @DecimalMin("0.01") BigDecimal price) {}
    public record ProductView(UUID id, String name, String description, BigDecimal price) {}
}
