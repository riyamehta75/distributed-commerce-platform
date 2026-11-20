# Reduced-cost demo: Vercel storefront + single-VM backend

This layout keeps the website separate from the backend and runs the backend on one Linux VM using the existing Docker Compose setup.

## Cost and availability warning

This is a reduced-cost demo design, **not a promise of lifetime-free hosting**. A cloud provider's free allowance can change, expire, require eligibility, or suspend an instance. A VM small enough to fit a free tier may not have enough memory or disk for seven Java services, PostgreSQL, and Kafka to run reliably. Check current provider terms and configure billing alerts before deploying.

## What runs where

- **Vercel Hobby** serves the Next.js storefront from `frontend/`.
- **One Linux VM** runs the seven Java services, one PostgreSQL server with six logical databases, and one self-managed Kafka broker through Docker Compose.
- The Compose stack also defines Redis, Grafana, Prometheus, Kafka UI, and a frontend container. The VM command below omits the Vercel-replaced frontend and optional monitoring UIs.
- This single-node setup is for a demo. The Kafka broker has replication factor one and no high availability.

## 1. Prepare the VM and domain

Create a Linux VM with enough memory and disk for the entire stack; do not assume the smallest free-tier instance is sufficient. Install Docker Engine, the Docker Compose plugin, Java 21, and Maven 3.9 or later. Maven builds the service JARs on the VM so your laptop does not need to build or run the backend.

Point an API hostname such as `api.example.com` to the VM's public IP. Configure the provider's network firewall to allow inbound HTTPS (443), HTTP (80, for TLS certificate provisioning), and SSH only from your IP. The Compose ports bind to loopback; do not open database, Kafka, monitoring, or application container ports publicly.

Install Caddy or another TLS reverse proxy on the VM. For Caddy, a site entry such as this proxies HTTPS requests to the local API Gateway:

```caddyfile
api.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Caddy can obtain and renew a certificate when the DNS record is correct and ports 80/443 reach the VM.

## 2. Configure and start the backend

Clone the repository on the VM and enter its project directory. Create `.env` from `.env.example` only if you do not already have a server-side `.env`; keep the real file off GitHub:

```bash
git clone https://github.com/riyamehta75/distributed-commerce-platform.git
cd distributed-commerce-platform
test -f .env || cp .env.example .env
```

Set strong, unique values in `.env` for `POSTGRES_PASSWORD`, `JWT_SECRET` (at least 32 bytes), `INTERNAL_API_TOKEN`, `ADMIN_EMAIL`, and `ADMIN_PASSWORD` (at least 12 characters). Set `FRONTEND_ORIGIN` to the exact Vercel site origin, for example `https://your-project.vercel.app`. Do not use the example credentials or commit secrets.

Build the Java services, then start the backend services and their dependencies:

```bash
mvn clean package -DskipTests
docker compose up -d --build \
  postgres redis kafka kafka-init \
  user-service product-service inventory-service order-service \
  payment-service notification-service api-gateway
```

Compose initializes six logical databases in the single PostgreSQL container and creates the Kafka topics before the order, payment, and notification services start. Keep the named `postgres_data` volume; back it up regularly. Kafka runs as one broker and is not a highly available or production configuration.

## 3. Deploy the storefront to Vercel

1. Import the GitHub repository into Vercel and set **Root Directory** to `frontend`.
2. Set `NEXT_PUBLIC_API_URL` to `https://api.example.com` (your public API hostname).
3. Deploy the site. Vercel rebuilds the frontend with that API URL.
4. Ensure the backend `.env` has `FRONTEND_ORIGIN` set to the exact deployed Vercel origin. Restart the API Gateway after changing it.

## 4. Verify and update

On the VM, check service status with `docker compose ps`. From a browser or another machine, verify the public HTTPS endpoint:

```bash
curl -fsS https://api.example.com/actuator/health
curl -fsS https://api.example.com/api/products
```

Then open the Vercel site, register a test customer, sign in with the seeded admin to create a product and stock, and try a simulated checkout. Payments and notifications are demos; no real charge or email is sent.

To deploy later code changes on the VM:

```bash
git pull --ff-only
mvn clean package -DskipTests
docker compose up -d --build \
  postgres redis kafka kafka-init \
  user-service product-service inventory-service order-service \
  payment-service notification-service api-gateway
```

The database volume is on the VM's disk, so plan backups and recovery. Avoid `docker compose down -v` unless you intend to delete local database data.

## Scaling beyond the demo

The repository does not provision cloud infrastructure or include a VM deployment pipeline. For higher availability, move the services to ECS/Fargate or an equivalent container platform, use managed PostgreSQL and Kafka, keep backend services private, and terminate TLS at a load balancer. Those resources are paid services and will incur ongoing costs.
