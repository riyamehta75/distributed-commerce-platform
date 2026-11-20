# Deployment: Vercel storefront + AWS API

## What runs where

- **Vercel** runs only the Next.js application in `frontend/`.
- **AWS** runs the Java containers. The API Gateway must have a public HTTPS URL for a browser-deployed frontend.
- **PostgreSQL** stores application data. Local Compose creates one database per service. For AWS, create the six databases on RDS or configure equivalent isolated schemas and set each service's `SPRING_DATASOURCE_URL`.
- **Kafka and Redis are not required for the implemented synchronous checkout path.** Their infrastructure is present in Compose, but the current API flow does not publish/consume Kafka events or cache catalog reads.

## Vercel

1. Push the repository to GitHub/GitLab/Bitbucket.
2. In Vercel, import the repository.
3. Set Root Directory to `frontend`.
4. Set `NEXT_PUBLIC_API_URL` to the API Gateway's HTTPS URL, for example `https://api.example.com`.
5. Deploy. The storefront build command is `npm run build`.
6. Set the AWS API gateway's `FRONTEND_ORIGIN` to the exact Vercel origin, such as `https://your-project.vercel.app`.

Do not set `NEXT_PUBLIC_API_URL` to a private IP, `localhost`, or an HTTP URL; the browser needs a public HTTPS endpoint.

## AWS target

The recommended container layout is an ECS/Fargate cluster with one service per Java module, ECS Service Connect for private service discovery, an Application Load Balancer in front of `api-gateway`, and RDS PostgreSQL. Use private subnets for service tasks and the database. Configure TLS with an ACM certificate and a domain you control.

Build the images after the Maven package step. Each Docker build context is its service directory and expects its runnable jar in `target/`.

```bash
export AWS_REGION=us-west-2
export AWS_ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin "$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"
```

Create one ECR repository for each service, tag/push the images, and use those image URIs in ECS task definitions. ECS must set:

- `POSTGRES_USER`, `POSTGRES_PASSWORD`, and each service's `SPRING_DATASOURCE_URL` from AWS Secrets Manager.
- `JWT_SECRET` from Secrets Manager (at least 32 bytes), identically for the gateway and user service.
- `ADMIN_EMAIL` and `ADMIN_PASSWORD` from Secrets Manager; the password must be at least 12 characters.
- `INTERNAL_API_TOKEN` from Secrets Manager, identically for inventory, order, and payment.
- `FRONTEND_ORIGIN` on the gateway.
- `PRODUCT_SERVICE_URL` and `INVENTORY_SERVICE_URL` on order service.
- `ORDER_SERVICE_URL` on payment service.

Use ECS Service Connect aliases `user-service`, `product-service`, `inventory-service`, `order-service`, `payment-service`, and `notification-service` on their configured container ports. Keep all service ports private; only the load balancer's HTTPS listener should be public. Payments and notifications are simulations; do not use real payment data. The gateway enforces JWT, role, and ownership checks for the current demo API.

The gateway validates JWTs; product/stock writes require an administrator token, and order/payment/profile access is owner-checked. Keep backend ports private, store all credentials in Secrets Manager, and never use `.env.example` credentials in AWS.

This repository does not yet include Terraform/CloudFormation that provisions AWS. AWS resources must be created in your account, and the operations have ongoing costs. Never commit AWS credentials or paste them into chat.

## Smoke test after deployment

Set the public gateway URL and replace the example values:

```bash
API=https://api.example.com
curl -fsS "$API/actuator/health"
curl -fsS "$API/api/products"
```

Then open the Vercel deployment, register a throwaway account, add a product in Seller Studio, set starting quantity, place an order, and press **Simulate payment**. To test overselling, reserve the final quantity from concurrent clients; inventory is protected by a conditional PostgreSQL update.

Seller Studio is available only after signing in with the seeded administrator account (`ADMIN_EMAIL` / `ADMIN_PASSWORD`). Customer accounts can browse, place orders, pay through the simulation, and view/cancel their own orders.
