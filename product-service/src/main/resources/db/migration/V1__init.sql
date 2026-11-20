CREATE TABLE categories (id UUID PRIMARY KEY, name VARCHAR(100) UNIQUE NOT NULL);
CREATE TABLE products (id UUID PRIMARY KEY, category_id UUID REFERENCES categories(id), name VARCHAR(255) NOT NULL, description TEXT, price NUMERIC(19,2) NOT NULL CHECK (price >= 0), active BOOLEAN NOT NULL DEFAULT TRUE, version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL DEFAULT now(), updated_at TIMESTAMP NOT NULL DEFAULT now());
CREATE INDEX idx_products_category ON products(category_id);
CREATE INDEX idx_products_name ON products(lower(name));
