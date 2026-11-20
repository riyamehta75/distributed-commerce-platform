# API Reference

The browser calls the API gateway at `NEXT_PUBLIC_API_URL`. For local use this is `http://localhost:8080`. All requests and responses use JSON except `204 No Content` operations. UUID path values are shown as `{id}`.

All catalog prices, order totals, payment amounts, and seller-entered prices are denominated in Indian rupees (INR). The API represents monetary values as decimal numbers; the storefront formats them as `₹` amounts. Existing numeric values are interpreted as INR without exchange-rate conversion.

## Authentication

`POST /api/users/register` and `POST /api/users/login` return `{ "user": { "id", "email", "name", "role" }, "token": "...", "expiresInSeconds": 43200 }`. Send the returned token on protected routes:

```http
Authorization: Bearer <token>
```

Registration creates a `CUSTOMER`. The administrator account is seeded from `ADMIN_EMAIL` and `ADMIN_PASSWORD`; it must be changed from the local example values before any public deployment. The gateway rejects missing/invalid tokens with `401` and insufficient roles or ownership with `403`.

## Accounts — `/api/users`

- `POST /register` — `{ "email": "customer@example.com", "password": "safe-password", "name": "Customer" }`; public, creates a customer account and returns a token.
- `POST /login` — `{ "email": "customer@example.com", "password": "safe-password" }`; public, returns a token or `401`.
- `GET /{id}` — authenticated; only the account owner can retrieve their profile.

## Catalog — `/api/products`

- `GET /?q=ceramic` — public search of active products.
- `GET /{id}` — public product lookup.
- `POST /` — administrator only; `{ "name": "Cup", "description": "Hand thrown", "price": 2650.00 }` (INR).
- `PUT /{id}` — administrator only; update product fields.
- `DELETE /{id}` — administrator only; soft-delete product.

## Inventory — `/api/inventory`

- `GET /{productId}` — authenticated current available and reserved counts.
- `PUT /{productId}` — administrator only; `{ "quantity": 12 }`, never below outstanding reservations.
- `POST /reservations` — internal service call; `{ "orderId": "...", "productId": "...", "quantity": 1 }`; requires `X-Internal-Token`.
- `POST /reservations/{orderId}/confirm` — internal service call to commit active reservations; requires `X-Internal-Token`.
- `POST /reservations/{orderId}/release` — internal service call to release unconfirmed stock; requires `X-Internal-Token`.

## Orders — `/api/orders`

- `POST /` — authenticated; `{ "userId": "...", "items": [{ "productId": "...", "quantity": 1 }] }`. The `userId` must match the token owner. Reads catalog prices, creates the order, reserves all stock, and returns `AWAITING_PAYMENT`.
- `GET /{id}` — authenticated; owner only.
- `GET /?userId={id}` — authenticated; user ID must match the token owner.
- `POST /{id}/confirm` — internal payment completion callback; requires `X-Internal-Token`.
- `POST /{id}/cancel` — authenticated owner; cancels an unpaid order and releases inventory.

## Payments — `/api/payments`

- `POST /` — authenticated owner; `{ "orderId": "...", "idempotencyKey": "unique-key" }`. Simulates payment and confirms the order. Reuse the same key on retries.
- `GET /{id}` — authenticated payment owner.
- `POST /{id}/refund` — authenticated payment owner; simulates refund of a completed payment.

## Notifications — `/api/notifications`

- `POST /` — administrator only; `{ "recipient": "maker@example.com", "eventType": "ORDER_CONFIRMED", "message": "Your order is confirmed" }`; stores a simulated notification, no email is sent.
- `GET /` — administrator only; most recent notification records.

## Sample end-to-end request sequence

Run this from a shell with `jq`; it uses local Compose values from `.env`:

```bash
set -a
. ./.env
set +a
API=http://localhost:8080
ADMIN=$(curl -fsS "$API/api/users/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}")
ADMIN_TOKEN=$(printf '%s' "$ADMIN" | jq -r .token)
PRODUCT=$(curl -fsS "$API/api/products" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Handmade Cup","description":"Stoneware, small batch","price":32.00}')
PRODUCT_ID=$(printf '%s' "$PRODUCT" | jq -r .id)
curl -fsS -X PUT "$API/api/inventory/$PRODUCT_ID" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' -d '{"quantity":12}'

CUSTOMER=$(curl -fsS "$API/api/users/register" -H 'Content-Type: application/json' \
  -d '{"email":"customer@example.com","password":"safe-password","name":"Customer"}')
CUSTOMER_TOKEN=$(printf '%s' "$CUSTOMER" | jq -r .token)
USER_ID=$(printf '%s' "$CUSTOMER" | jq -r .user.id)
ORDER=$(curl -fsS "$API/api/orders" -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":\"$USER_ID\",\"items\":[{\"productId\":\"$PRODUCT_ID\",\"quantity\":1}]}")
ORDER_ID=$(printf '%s' "$ORDER" | jq -r .id)
curl -fsS "$API/api/payments" -H "Authorization: Bearer $CUSTOMER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"orderId\":\"$ORDER_ID\",\"idempotencyKey\":\"test-$ORDER_ID\"}"
curl -fsS "$API/api/orders/$ORDER_ID" -H "Authorization: Bearer $CUSTOMER_TOKEN"
```

Payment and notification processing are simulations only; never use real payment information.
