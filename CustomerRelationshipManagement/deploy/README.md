# Deploying HZ CRM as a web application

One small Linux server runs everything in Docker:

```
browser ──https──> Caddy (web)  ──/api──> Spring Boot (app) ──> PostgreSQL (db)
                   │ serves the React app
                   └ gets and renews the HTTPS certificate automatically
```

Only Caddy is reachable from the internet (ports 80 and 443). The backend and the database are internal.
Because the page and the API share one address, the login cookie and CSRF protection work without changes.

| File | What it is |
|---|---|
| `../Dockerfile` | builds and runs the backend (Java 25, non-root, 768 MB heap) |
| `../frontend/Dockerfile` | builds the React app into a Caddy image |
| `docker-compose.yml` | the three services, volumes and settings |
| `Caddyfile` | HTTPS, `/api` proxy, caching and security headers |
| `.env.example` | the settings you fill in (copy to `.env`) |
| `backup.sh` | daily compressed database dump, keeps 14 days |
| `update.sh` | backup, `git pull`, rebuild and restart |

Expected cost: domain about $10/year, server about €5–6/month (Hetzner entry plan with 2 vCPU / 4 GB).

---

## 1. Buy the domain (Cloudflare Registrar)

1. Create a Cloudflare account and register your domain (e.g. `yourcompany.com`).
2. You will add the DNS record in step 3, once you know the server's IP address.

## 2. Create the server (Hetzner Cloud)

1. In Hetzner Cloud Console create a project, then **Add Server**:
   - Image: **Ubuntu 24.04**
   - Type: the shared-vCPU entry plan with **4 GB RAM** (2 GB is too tight for Java + PostgreSQL)
   - Location: the closest to your users (Singapore is closest to India but costs more than Europe)
   - SSH key: add your public key (do not use password login)
   - Optional: enable **Backups** (whole-server snapshots, about +20%)
2. Add a **Firewall** to the server allowing inbound: TCP 22 (ideally only from your IP), TCP 80, TCP 443, UDP 443.

## 3. Point the domain at the server

In Cloudflare DNS add an **A record**, e.g. name `crm` (giving `crm.yourcompany.com`) with the server's IPv4 address,
proxy status **DNS only** (grey cloud). Caddy needs to reach Let's Encrypt directly to get the certificate.
You can switch the orange proxy on later; if you do, set SSL/TLS mode to **Full (strict)**.

If you also want `www.crm.yourcompany.com`, add the same record for `www.crm` and uncomment the `www` block at the
end of `Caddyfile`.

## 4. Prepare the server

```bash
ssh root@SERVER_IP
apt update && apt -y upgrade && apt -y install git unattended-upgrades
curl -fsSL https://get.docker.com | sh
```

Get the code onto the server. With a private GitHub repository, create a read-only **deploy key** and:

```bash
mkdir -p /opt/crm && cd /opt/crm
git clone git@github.com:YOUR_ACCOUNT/YOUR_REPO.git .
cd CustomerRelationshipManagement/deploy
```

## 5. Settings

```bash
cp .env.example .env
nano .env          # DOMAIN, ACME_EMAIL, DB_PASSWORD, CRM_SUPERADMIN_USERNAME, CRM_SUPERADMIN_PASSWORD
chmod 600 .env
chmod +x backup.sh update.sh
```

Generate strong passwords with `openssl rand -base64 32`. Use a new database password, not the one from your laptop.

## 6. Start

```bash
docker compose up -d --build
docker compose logs -f app     # wait for "Started CustomerRelationshipManagementApplication"
```

The first build takes a few minutes (it downloads Maven and npm packages). Then open `https://YOUR_DOMAIN`
and sign in with `CRM_SUPERADMIN_USERNAME` and `CRM_SUPERADMIN_PASSWORD`. Create your tenants from there.

Check everything is up: `docker compose ps` should show `db`, `app` and `web` as running.

## 7. Backups

Run one now and schedule it daily:

```bash
./backup.sh
crontab -e
# add:
15 2 * * * /opt/crm/CustomerRelationshipManagement/deploy/backup.sh >> /var/log/crm-backup.log 2>&1
```

Dumps go to `deploy/backups/`. Keep a copy off the server too (e.g. a Hetzner Storage Box or any S3 bucket with
`rclone`), otherwise losing the server means losing the backups.

**Restore** a dump (this replaces the current data):

```bash
docker compose stop app
gunzip -c backups/crm-YYYY-MM-DD_HHMM.sql.gz | docker compose exec -T db psql -U crm -d crm
docker compose start app
```

For a clean restore into an empty database, first run
`docker compose exec db psql -U crm -d postgres -c "DROP DATABASE crm" -c "CREATE DATABASE crm OWNER crm"`.

## 8. Releasing a new version

Push your changes to the repository, then on the server:

```bash
cd /opt/crm/CustomerRelationshipManagement/deploy
./update.sh
```

It backs up the database first, then pulls, rebuilds and restarts. Users are signed out when the backend restarts.

**About the database structure:** `DDL_AUTO=update` lets the app add new tables, columns and indexes when a new version
needs them. It never drops anything. Keep it until the project moves to versioned migrations (e.g. Flyway); always
let `update.sh` take its backup before an upgrade.

## Moving your existing data to the server (optional)

On your PC:

```powershell
& 'C:\Program Files\PostgreSQL\18\bin\pg_dump.exe' -U postgres -d crm --no-owner -f crm-export.sql
scp crm-export.sql root@SERVER_IP:/opt/crm/
```

On the server, before the app has created anything you want to keep:

```bash
cd /opt/crm/CustomerRelationshipManagement/deploy
docker compose stop app
docker compose exec db psql -U crm -d postgres -c "DROP DATABASE crm" -c "CREATE DATABASE crm OWNER crm"
docker compose exec -T db psql -U crm -d crm < /opt/crm/crm-export.sql
docker compose start app
```

Your existing super admin comes with the data, so `CRM_SUPERADMIN_PASSWORD` is not used in that case.

## Resetting the super admin password

There is no change-password screen yet. The super admin is only created when none exists, so to set a new password:

```bash
nano .env                                   # set a new CRM_SUPERADMIN_PASSWORD
docker compose exec db psql -U crm -d crm -c "DELETE FROM app_users WHERE role = 'SUPER_ADMIN'"
docker compose up -d app                    # recreated on start with the new password
```

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Browser shows a certificate error | DNS does not point at the server yet, or ports 80/443 are blocked. See `docker compose logs web`. |
| Sign-in says "Please reload the page and try again" | The page was opened on a different address than `DOMAIN` in `.env` (e.g. the bare IP). Use the domain. |
| `app` keeps restarting | Wrong `DB_PASSWORD`, or not enough memory. See `docker compose logs app`. |
| Slow first page after a release | Java warms up during the first requests; it settles within a minute. |

Useful commands: `docker compose logs -f app`, `docker compose restart app`, `docker stats`, `df -h`.
