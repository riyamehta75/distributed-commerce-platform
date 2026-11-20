#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env"
  set +a
fi
API="${API_URL:-http://localhost:8080}"
: "${ADMIN_EMAIL:?Set ADMIN_EMAIL in .env}"
: "${ADMIN_PASSWORD:?Set ADMIN_PASSWORD in .env}"
RUN_ID="$(date +%s)-$$"

request() {
  curl --fail-with-body --silent --show-error "$@"
}

ADMIN="$(request "$API/api/users/login" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg email "$ADMIN_EMAIL" --arg password "$ADMIN_PASSWORD" '{email:$email,password:$password}')")"
ADMIN_TOKEN="$(jq -r .token <<<"$ADMIN")"
PRODUCT="$(request "$API/api/products" -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg name "Smoke Test $RUN_ID" '{name:$name,description:"Created by smoke-test.sh",price:1.25}')")"
PRODUCT_ID="$(jq -r .id <<<"$PRODUCT")"
request -X PUT "$API/api/inventory/$PRODUCT_ID" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' -d '{"quantity":5}' >/dev/null

CUSTOMER="$(request "$API/api/users/register" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg email "smoke-$RUN_ID@example.test" '{email:$email,password:"test-password-123",name:"Smoke Test"}')")"
CUSTOMER_TOKEN="$(jq -r .token <<<"$CUSTOMER")"
USER_ID="$(jq -r .user.id <<<"$CUSTOMER")"
ORDER="$(request "$API/api/orders" -H "Authorization: Bearer $CUSTOMER_TOKEN" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg userId "$USER_ID" --arg productId "$PRODUCT_ID" '{userId:$userId,items:[{productId:$productId,quantity:2}]}')")"
ORDER_ID="$(jq -r .id <<<"$ORDER")"
PAYMENT="$(request "$API/api/payments" -H "Authorization: Bearer $CUSTOMER_TOKEN" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg orderId "$ORDER_ID" --arg key "smoke-$RUN_ID" '{orderId:$orderId,idempotencyKey:$key}')")"
ORDER_STATUS="$(request "$API/api/orders/$ORDER_ID" -H "Authorization: Bearer $CUSTOMER_TOKEN" | jq -r .status)"

[[ "$ORDER_STATUS" == "CONFIRMED" ]]
[[ "$(jq -r .status <<<"$PAYMENT")" == "COMPLETED" ]]
printf 'PASS: checkout order %s confirmed; payment %s completed.\n' "$ORDER_ID" "$(jq -r .id <<<"$PAYMENT")"
