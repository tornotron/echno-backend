# CockroachDB Operations Handbook
## Quick Reference for Your DigitalOcean Cluster

---

## Your Cluster At a Glance

| Component | Details |
|-----------|---------|
| Nodes | 3 (cockroachdb-1, cockroachdb-2, cockroachdb-3) |
| Region | Bangalore (BLR1) |
| Locality | `region=blr,zone=blr1` |
| Load Balancer | DigitalOcean Managed LB |
| LB Health Check | TCP on port 26257 |
| SQL Port | 26257 |
| Admin UI Port | 8080 |
| Certs Location | `/var/lib/cockroach/certs/` |
| Data Location | `/var/lib/cockroach/data/` |
| Admin Node | cockroachdb-1 (has root client certs) |
| App Connection | `jdbc:postgresql://<DO-LB-IP>:26257/myapp?sslmode=verify-full&sslrootcert=/certs/ca.crt` |
| Replication Factor | 3 |

---

## Daily Commands Cheat Sheet

### Connect to SQL Shell

```bash
# SSH into cockroachdb-1 first (the admin node)
cockroach sql --certs-dir=/var/lib/cockroach/certs --host=localhost:26257
```

### Check Cluster Health

```bash
# Are all nodes alive?
cockroach node status --certs-dir=/var/lib/cockroach/certs --host=localhost:26257

# Quick health check from any node
curl -k https://<node-private-ip>:8080/health?ready=1
```

### Check Node Status from SQL

```sql
SELECT node_id, address, is_live, locality FROM crdb_internal.gossip_nodes;
```

### Check Disk Usage

```sql
SELECT node_id, store_id,
  capacity / 1073741824 AS capacity_gb,
  available / 1073741824 AS available_gb,
  round((1 - available::float / capacity::float) * 100, 1) AS used_pct
FROM crdb_internal.kv_store_status;
```

### Check Active Connections

```sql
SELECT node_id, user_name, application_name, client_address
FROM [SHOW SESSIONS];
```

### Check Slow Queries

```sql
SELECT query, count, mean_service_lat, max_service_lat
FROM crdb_internal.node_statement_statistics
ORDER BY mean_service_lat DESC
LIMIT 10;
```

### Check Replication Status

```sql
-- Any under-replicated ranges? (should be 0)
SELECT count(*) AS underreplicated
FROM crdb_internal.ranges
WHERE array_length(replicas, 1) < 3;
```

---

## Service Management

### Start / Stop / Restart a Node

```bash
# On the specific node via SSH
sudo systemctl start cockroachdb
sudo systemctl stop cockroachdb
sudo systemctl restart cockroachdb

# Check status
sudo systemctl status cockroachdb

# View logs
sudo journalctl -u cockroachdb -f                    # Live tail
sudo journalctl -u cockroachdb --since "1 hour ago"  # Last hour
sudo journalctl -u cockroachdb --since today          # Today
```

### Systemd Service File Location

```
/etc/systemd/system/cockroachdb.service
```

After editing, always run:

```bash
sudo systemctl daemon-reload
sudo systemctl restart cockroachdb
```

---

## Scaling Operations

### Add a New Node

1. Provision a new DigitalOcean droplet in BLR1 (same specs as existing nodes)

2. Install CockroachDB on the new droplet:
```bash
curl https://binaries.cockroachdb.com/cockroach-v23.2.4.linux-amd64.tgz | tar -xz
sudo cp cockroach-v23.2.4.linux-amd64/cockroach /usr/local/bin/
sudo mkdir -p /var/lib/cockroach
sudo useradd -r -s /bin/false cockroach
sudo chown cockroach:cockroach /var/lib/cockroach
```

3. Regenerate node certs to include the new node's IP (on your cert machine):
```bash
cockroach cert create-node \
  <node1-ip> <node2-ip> <node3-ip> <new-node-ip> \
  localhost 127.0.0.1 \
  <DO-LB-IP> <DO-LB-hostname> \
  --certs-dir=certs \
  --ca-key=my-safe-directory/ca.key \
  --overwrite
```

4. Distribute new certs to ALL nodes (existing + new):
```bash
scp certs/ca.crt certs/node.crt certs/node.key root@<each-node-ip>:/var/lib/cockroach/certs/
```

5. Fix permissions on each node:
```bash
sudo chown -R cockroach:cockroach /var/lib/cockroach/certs
sudo chmod 700 /var/lib/cockroach/certs
sudo chmod 600 /var/lib/cockroach/certs/*
```

6. Create systemd service on the new node (same as others, update IP):
```ini
ExecStart=/usr/local/bin/cockroach start \
  --certs-dir=/var/lib/cockroach/certs \
  --store=/var/lib/cockroach/data \
  --listen-addr=<new-node-private-ip>:26257 \
  --http-addr=0.0.0.0:8080 \
  --advertise-addr=<new-node-private-ip>:26257 \
  --join=<node1-ip>:26257,<node2-ip>:26257,<node3-ip>:26257 \
  --locality=region=blr,zone=blr1 \
  --max-sql-memory=.25 \
  --cache=.25
```

7. Start the new node:
```bash
sudo systemctl daemon-reload
sudo systemctl enable cockroachdb
sudo systemctl start cockroachdb
```

8. Restart existing nodes to pick up new certs:
```bash
# On each existing node
sudo systemctl restart cockroachdb
```

9. Add the new droplet to your DigitalOcean Load Balancer (dashboard → LB → Settings → Droplets)

10. Verify:
```sql
SELECT node_id, address, is_live, locality FROM crdb_internal.gossip_nodes;
```

### Remove a Node

**NEVER go below 3 nodes.**

1. Find the node ID:
```bash
cockroach node status --certs-dir=/var/lib/cockroach/certs --host=localhost:26257
```

2. Decommission (moves all data off — can take minutes to hours):
```bash
cockroach node decommission <NODE_ID> \
  --certs-dir=/var/lib/cockroach/certs \
  --host=localhost:26257
```

3. Wait until it shows `is_decommissioning=true` and replicas=0

4. Stop the service on the decommissioned node:
```bash
sudo systemctl stop cockroachdb
sudo systemctl disable cockroachdb
```

5. Remove the droplet from the DigitalOcean Load Balancer

6. Optionally destroy the droplet

---

## When to Scale — Decision Guide

| Metric | How to Check | Threshold | Action |
|--------|-------------|-----------|--------|
| CPU | Admin UI or `top` on each node | > 60% sustained 10+ min | Add a node |
| CPU | Admin UI or `top` on each node | < 20% sustained 30+ min | Remove a node (if > 3) |
| Disk | SQL query above | > 60% used | Add node or expand disk |
| P99 Latency | Admin UI → SQL Activity | > 200ms sustained | Check queries, then add node |
| Connections | `SHOW SESSIONS` | Near pool max | Increase pool or add node |
| Under-replicated | SQL query above | > 0 for 5+ min | Check node health |

---

## Backup & Recovery

### Set Up Scheduled Backups

```sql
-- To DigitalOcean Spaces (S3-compatible)
CREATE SCHEDULE daily_backup FOR BACKUP INTO
  's3://your-bucket/crdb-backups?AWS_ACCESS_KEY_ID=xxx&AWS_SECRET_ACCESS_KEY=xxx&AWS_ENDPOINT=https://blr1.digitaloceanspaces.com'
  RECURRING '@daily'
  WITH SCHEDULE OPTIONS first_run = 'now';

-- To local filesystem (simpler but less safe)
CREATE SCHEDULE daily_backup FOR BACKUP INTO
  'nodelocal://1/backups'
  RECURRING '@daily'
  WITH SCHEDULE OPTIONS first_run = 'now';
```

### Manual Backup

```sql
BACKUP DATABASE myapp INTO
  's3://your-bucket/crdb-backups?AWS_ACCESS_KEY_ID=xxx&AWS_SECRET_ACCESS_KEY=xxx&AWS_ENDPOINT=https://blr1.digitaloceanspaces.com';
```

### Restore

```sql
RESTORE DATABASE myapp FROM LATEST IN
  's3://your-bucket/crdb-backups?AWS_ACCESS_KEY_ID=xxx&AWS_SECRET_ACCESS_KEY=xxx&AWS_ENDPOINT=https://blr1.digitaloceanspaces.com';
```

### Check Backup Schedules

```sql
SHOW SCHEDULES;
```

---

## User & Database Management

### Create a New Database

```sql
CREATE DATABASE newdb;
```

### Create a New User

```sql
CREATE USER new_user WITH PASSWORD 'strong-password';
GRANT ALL ON DATABASE myapp TO new_user;
```

### Change a Password

```sql
ALTER USER app_user WITH PASSWORD 'new-password';
```

### Revoke Access

```sql
REVOKE ALL ON DATABASE myapp FROM some_user;
DROP USER some_user;
```

---

## Troubleshooting Runbooks

### Node Won't Start

```bash
# 1. Check logs
sudo journalctl -u cockroachdb --since "10 min ago"

# 2. Common causes:
#    - Cert permissions wrong → chmod 600 on all cert files
#    - Disk full → check with df -h
#    - Port in use → ss -tlnp | grep 26257
#    - Wrong --join addresses → check systemd service file
```

### Node Shows as Dead

```bash
# 1. Check from admin node
cockroach node status --certs-dir=/var/lib/cockroach/certs --host=localhost:26257

# 2. SSH into the dead node and check
sudo systemctl status cockroachdb
sudo journalctl -u cockroachdb --since "30 min ago"

# 3. Try restarting
sudo systemctl restart cockroachdb

# 4. If the server is unreachable:
#    - Your cluster still works (2 of 3 nodes = quorum)
#    - Data is safe (replicated on other 2 nodes)
#    - Ranges will re-replicate after 5 minutes automatically
#    - Replace the server and rejoin with same --join flags
```

### High Query Latency

```sql
-- 1. Find slow queries
SELECT query, count, mean_service_lat, max_service_lat
FROM crdb_internal.node_statement_statistics
ORDER BY mean_service_lat DESC
LIMIT 10;

-- 2. Analyze a slow query
EXPLAIN ANALYZE <your-slow-query-here>;

-- 3. Look for:
--    - Full table scans → CREATE INDEX
--    - Contention → Reduce transaction scope
--    - Too many rows → Add LIMIT or better WHERE clauses
```

### Disk Getting Full

```sql
-- 1. Check disk per node
SELECT node_id,
  capacity / 1073741824 AS capacity_gb,
  available / 1073741824 AS available_gb
FROM crdb_internal.kv_store_status;

-- 2. Check table sizes
SELECT table_name,
  pg_size_pretty(sum(range_size_mb * 1024 * 1024)) AS size
FROM [SHOW RANGES FROM DATABASE myapp]
GROUP BY table_name
ORDER BY sum(range_size_mb) DESC;
```

Options: add a node, resize the droplet's disk, or delete old data.

### Connection Refused from App

```
Check in this order:
1. Is CockroachDB running?     → sudo systemctl status cockroachdb
2. Is the LB healthy?          → Check DO dashboard
3. Is the firewall blocking?   → sudo ufw status
4. Is the cert correct?        → Verify ca.crt on app server matches cluster
5. Is the user/password right? → Try connecting via cockroach sql
6. Is the database created?    → \l in SQL shell to list databases
```

### Certificate Errors

```bash
# Check cert details
cockroach cert list --certs-dir=/var/lib/cockroach/certs

# If certs expired, regenerate ALL certs (CA, node, client)
# and redistribute to all nodes, then restart all nodes
```

---

## Rolling Upgrade Procedure

Upgrade one node at a time. Never skip nodes.

```bash
# 1. On node 1: stop, upgrade binary, start
sudo systemctl stop cockroachdb
curl https://binaries.cockroachdb.com/cockroach-v<NEW_VERSION>.linux-amd64.tgz | tar -xz
sudo cp cockroach-v<NEW_VERSION>.linux-amd64/cockroach /usr/local/bin/
sudo systemctl start cockroachdb

# 2. Wait for node to rejoin and verify
cockroach node status --certs-dir=/var/lib/cockroach/certs --host=localhost:26257
# All nodes should show is_live=true

# 3. Repeat for node 2, then node 3

# 4. After ALL nodes are upgraded, finalize:
cockroach sql --certs-dir=/var/lib/cockroach/certs --host=localhost:26257 \
  -e "SET CLUSTER SETTING version = crdb_internal.node_executable_version();"
```

---

## Admin UI Access

Open in browser: `https://<any-node-public-ip>:8080`

Key pages to check regularly:
- **Overview** — node health at a glance
- **Metrics → Hardware** — CPU, memory, disk, network
- **Metrics → SQL** — query latency, QPS, active connections
- **SQL Activity → Statements** — slow queries and execution plans
- **SQL Activity → Sessions** — who is connected right now
- **Databases** — table sizes and index usage

---

## Spring Boot Connection Reference

### application.yml

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 10000
  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    hibernate:
      ddl-auto: update
```

### docker-compose.yml (backend service)

```yaml
services:
  backend:
    image: your-spring-boot-app
    volumes:
      - /opt/certs/ca.crt:/certs/ca.crt:ro
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://<DO-LB-IP>:26257/myapp?sslmode=verify-full&sslrootcert=/certs/ca.crt
      SPRING_DATASOURCE_USERNAME: app_user
      SPRING_DATASOURCE_PASSWORD: your-strong-password-here
```

### Connection Pool Sizing

Rule: **4 connections per vCPU per node**

| Cluster Size | vCPUs/Node | Total Pool |
|-------------|-----------|------------|
| 3 nodes     | 4         | 48         |
| 3 nodes     | 8         | 96         |
| 5 nodes     | 4         | 80         |
| 5 nodes     | 8         | 160        |

Set `maximum-pool-size` in Hikari accordingly. Start conservative (20) and increase as needed.

---

## Important Files & Locations

| What | Where |
|------|-------|
| CockroachDB binary | `/usr/local/bin/cockroach` |
| Systemd service file | `/etc/systemd/system/cockroachdb.service` |
| TLS certificates | `/var/lib/cockroach/certs/` |
| Data directory | `/var/lib/cockroach/data/` |
| Logs | `journalctl -u cockroachdb` |
| Root client certs | cockroachdb-1 only: `/var/lib/cockroach/certs/client.root.*` |
| App server CA cert | `/opt/certs/ca.crt` |
| CA private key | Keep offline in `my-safe-directory/ca.key` — NOT on any server |

---

## Emergency Contacts & Links

- CockroachDB Docs: https://www.cockroachlabs.com/docs/
- CockroachDB Status Page: https://status.cockroachlabs.cloud/
- DigitalOcean Status: https://status.digitalocean.com/
- Your Admin UI: `https://<cockroachdb-1-public-ip>:8080`
- Your DO Load Balancer: Check DO dashboard → Networking → Load Balancers
