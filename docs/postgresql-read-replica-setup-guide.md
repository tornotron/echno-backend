# PostgreSQL Read Replica Architecture — Complete Setup Guide

**Platform:** DigitalOcean · **OS:** Ubuntu 22.04/24.04 · **Topology:** 1 Primary + 2 Read Replicas

---

## Server Plan

| Role | Hostname | Private IP (example) | Droplet Size |
|------|----------|----------------------|--------------|
| Primary (writes) | `pg-primary` | `10.114.0.10` | 8 GB RAM / 4 vCPU / NVMe SSD |
| Replica 1 (reads) | `pg-replica1` | `10.114.0.11` | 8 GB RAM / 4 vCPU / NVMe SSD |
| Replica 2 (reads) | `pg-replica2` | `10.114.0.12` | 8 GB RAM / 4 vCPU / NVMe SSD |
| Load Balancer / Pooler | `pg-proxy` | `10.114.0.20` | 2 GB RAM / 2 vCPU |

> **Important:** Create all droplets in the **same VPC** so they can communicate over private IPs. All commands below assume you are logged in as `root` or using `sudo`.

---

## Phase 1 — Provision Droplets on DigitalOcean

### 1.1 Create the Droplets

In the DigitalOcean console:

1. **Create** → **Droplets**
2. Choose **Ubuntu 24.04 LTS** (or 22.04 LTS)
3. Select **Regular (SSD)** or **Premium (NVMe)** — Premium is strongly recommended for database workloads
4. Choose **8 GB / 4 vCPU** for the three database nodes, **2 GB / 2 vCPU** for the proxy node
5. Choose your region (pick one close to your users)
6. **VPC Network** — select an existing VPC or create a new one. All four droplets **must** be in the same VPC
7. Under **Authentication**, use SSH keys (never password auth for production)
8. Name them: `pg-primary`, `pg-replica1`, `pg-replica2`, `pg-proxy`
9. Create all four

### 1.2 Note the Private IPs

After creation, click each droplet and note its **Private IPv4 address** (under Networking). Replace the example IPs throughout this guide with your actual values.

### 1.3 Set Hostnames (run on each respective droplet)

```bash
# On pg-primary
hostnamectl set-hostname pg-primary

# On pg-replica1
hostnamectl set-hostname pg-replica1

# On pg-replica2
hostnamectl set-hostname pg-replica2

# On pg-proxy
hostnamectl set-hostname pg-proxy
```

### 1.4 Update /etc/hosts on ALL FOUR Droplets

```bash
cat >> /etc/hosts << 'EOF'
10.114.0.10  pg-primary
10.114.0.11  pg-replica1
10.114.0.12  pg-replica2
10.114.0.20  pg-proxy
EOF
```

> Replace the IPs above with your actual private IPs.

### 1.5 Configure the Firewall (on all droplets)

DigitalOcean's cloud firewall is preferred, but also set up `ufw` as defense-in-depth:

```bash
ufw allow from 10.114.0.0/24 to any port 5432 proto tcp   # PostgreSQL
ufw allow from 10.114.0.0/24 to any port 2379 proto tcp   # etcd (for Patroni)
ufw allow from 10.114.0.0/24 to any port 2380 proto tcp   # etcd peer
ufw allow from 10.114.0.0/24 to any port 8008 proto tcp   # Patroni REST API
ufw allow from 10.114.0.0/24 to any port 6432 proto tcp   # PgBouncer
ufw allow from 10.114.0.0/24 to any port 7000 proto tcp   # HAProxy stats
ufw allow OpenSSH
ufw enable
```

---

## Phase 2 — Install PostgreSQL 16 on All Three Database Nodes

Run these commands on `pg-primary`, `pg-replica1`, and `pg-replica2`:

```bash
# Add the official PostgreSQL APT repository
apt update && apt install -y curl ca-certificates gnupg
curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc | \
  gpg --dearmor -o /usr/share/keyrings/postgresql-keyring.gpg

echo "deb [signed-by=/usr/share/keyrings/postgresql-keyring.gpg] \
  https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" \
  > /etc/apt/sources.list.d/pgdg.list

apt update
apt install -y postgresql-16 postgresql-client-16

# Stop and disable the auto-started cluster — we'll manage it ourselves
systemctl stop postgresql
systemctl disable postgresql
```

Verify installation:

```bash
psql --version
# Should output: psql (PostgreSQL) 16.x
```

---

## Phase 3 — Configure the Primary Node

All commands in this phase run on `pg-primary`.

### 3.1 Initialize and Start PostgreSQL

```bash
# If not already initialized (check if /var/lib/postgresql/16/main/PG_VERSION exists)
pg_lsclusters
# Should show: 16  main  5432  down

# Start the cluster
pg_ctlcluster 16 main start
```

### 3.2 Create the Replication User

```bash
sudo -u postgres psql << 'SQL'
-- Dedicated replication user with a strong password
CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD 'YOUR_STRONG_REPL_PASSWORD';

-- A superuser for Patroni administration (we'll use this later)
CREATE ROLE admin_user WITH SUPERUSER CREATEDB CREATEROLE LOGIN PASSWORD 'YOUR_STRONG_ADMIN_PASSWORD';

-- Your application's database and user
CREATE DATABASE myapp;
CREATE ROLE app_user WITH LOGIN PASSWORD 'YOUR_STRONG_APP_PASSWORD';
GRANT ALL PRIVILEGES ON DATABASE myapp TO app_user;
SQL
```

> **Security Note:** Generate strong random passwords. In production, store these in a secrets manager (e.g., DigitalOcean Vault, HashiCorp Vault, or at minimum environment variables). Never commit passwords to version control.

### 3.3 Configure postgresql.conf

Edit `/etc/postgresql/16/main/postgresql.conf`:

```bash
nano /etc/postgresql/16/main/postgresql.conf
```

Set these values (find and uncomment/change each line):

```ini
#----------------------------------------------------------------------
# CONNECTION SETTINGS
#----------------------------------------------------------------------
listen_addresses = '*'                    # Listen on all interfaces
port = 5432
max_connections = 200                     # Reserve headroom; PgBouncer handles pooling

#----------------------------------------------------------------------
# REPLICATION - SENDING SERVER (PRIMARY)
#----------------------------------------------------------------------
wal_level = replica                       # Required for streaming replication
max_wal_senders = 10                      # Max concurrent replication connections
wal_keep_size = '2GB'                     # Retain WAL for replicas to catch up
max_replication_slots = 10                # Persistent slots prevent WAL cleanup
hot_standby = on                          # Required on replicas, harmless on primary

#----------------------------------------------------------------------
# WRITE-AHEAD LOG
#----------------------------------------------------------------------
synchronous_commit = on                   # Can be 'remote_apply' for sync replicas
wal_buffers = '64MB'
max_wal_size = '4GB'
min_wal_size = '1GB'
checkpoint_completion_target = 0.9
archive_mode = on                         # Enable WAL archiving for PITR
archive_command = 'test ! -f /var/lib/postgresql/wal_archive/%f && cp %p /var/lib/postgresql/wal_archive/%f'

#----------------------------------------------------------------------
# MEMORY
#----------------------------------------------------------------------
shared_buffers = '2GB'                    # ~25% of 8GB RAM
effective_cache_size = '6GB'              # ~75% of RAM
work_mem = '64MB'                         # Per-sort operation
maintenance_work_mem = '512MB'            # For VACUUM, CREATE INDEX
huge_pages = try                          # Use if kernel supports it

#----------------------------------------------------------------------
# QUERY PLANNER
#----------------------------------------------------------------------
random_page_cost = 1.1                    # SSD-optimized (default 4.0 is for spinning disk)
effective_io_concurrency = 200            # SSD-optimized

#----------------------------------------------------------------------
# LOGGING
#----------------------------------------------------------------------
logging_collector = on
log_directory = 'log'
log_filename = 'postgresql-%Y-%m-%d.log'
log_min_duration_statement = 500          # Log queries slower than 500ms
log_checkpoints = on
log_connections = on
log_disconnections = on
log_lock_waits = on
log_temp_files = 0                        # Log all temp file usage

#----------------------------------------------------------------------
# STATISTICS
#----------------------------------------------------------------------
shared_preload_libraries = 'pg_stat_statements'
pg_stat_statements.max = 10000
pg_stat_statements.track = all
```

### 3.4 Create the WAL Archive Directory

```bash
mkdir -p /var/lib/postgresql/wal_archive
chown postgres:postgres /var/lib/postgresql/wal_archive
```

### 3.5 Configure pg_hba.conf

Edit `/etc/postgresql/16/main/pg_hba.conf`:

```bash
nano /etc/postgresql/16/main/pg_hba.conf
```

Add these lines (adjust the VPC CIDR to match yours):

```
# TYPE  DATABASE        USER            ADDRESS                 METHOD

# Local connections
local   all             postgres                                peer
local   all             all                                     scram-sha-256

# Application connections from VPC
host    all             app_user        10.114.0.0/24           scram-sha-256
host    all             admin_user      10.114.0.0/24           scram-sha-256

# Replication connections from replicas
host    replication     replicator      10.114.0.11/32          scram-sha-256
host    replication     replicator      10.114.0.12/32          scram-sha-256

# PgBouncer / monitoring from proxy
host    all             app_user        10.114.0.20/32          scram-sha-256
```

### 3.6 Create Replication Slots

```bash
sudo -u postgres psql << 'SQL'
SELECT pg_create_physical_replication_slot('replica_1_slot');
SELECT pg_create_physical_replication_slot('replica_2_slot');
SQL
```

### 3.7 Enable pg_stat_statements

```bash
sudo -u postgres psql -d myapp -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;"
```

### 3.8 Restart the Primary

```bash
pg_ctlcluster 16 main restart
```

### 3.9 Verify the Primary Is Ready

```bash
sudo -u postgres psql -c "SHOW wal_level;"
# Should return: replica

sudo -u postgres psql -c "SELECT slot_name, active FROM pg_replication_slots;"
# Should show both slots, active = f (no replicas connected yet)
```

---

## Phase 4 — Set Up Read Replicas

Run these steps on **both** `pg-replica1` and `pg-replica2`. The only difference is the slot name.

### 4.1 Stop PostgreSQL and Clear the Data Directory

```bash
pg_ctlcluster 16 main stop
rm -rf /var/lib/postgresql/16/main/*
```

### 4.2 Take a Base Backup from the Primary

On `pg-replica1`:

```bash
sudo -u postgres pg_basebackup \
  -h pg-primary \
  -U replicator \
  -D /var/lib/postgresql/16/main \
  -Fp -Xs -P -R \
  -S replica_1_slot
```

On `pg-replica2`:

```bash
sudo -u postgres pg_basebackup \
  -h pg-primary \
  -U replicator \
  -D /var/lib/postgresql/16/main \
  -Fp -Xs -P -R \
  -S replica_2_slot
```

**Flags explained:**
- `-Fp` — plain format (file-per-file copy)
- `-Xs` — stream WAL during backup (no gaps)
- `-P` — show progress
- `-R` — auto-create `standby.signal` and write connection info to `postgresql.auto.conf`
- `-S` — bind to the named replication slot

You'll be prompted for the replicator password.

### 4.3 Verify the Auto-Generated Configuration

After `pg_basebackup -R`, check what was generated:

```bash
cat /var/lib/postgresql/16/main/postgresql.auto.conf
```

It should contain something like:

```
primary_conninfo = 'user=replicator password=YOUR_REPL_PASSWORD host=pg-primary port=5432 sslmode=prefer'
primary_slot_name = 'replica_1_slot'
```

And a `standby.signal` file should exist:

```bash
ls -la /var/lib/postgresql/16/main/standby.signal
```

### 4.4 Tune postgresql.conf on Each Replica

Edit `/etc/postgresql/16/main/postgresql.conf` on each replica:

```ini
#----------------------------------------------------------------------
# REPLICA-SPECIFIC SETTINGS
#----------------------------------------------------------------------
hot_standby = on                          # Serve read queries while replicating
max_standby_streaming_delay = 30s         # Max wait before canceling conflicting queries
hot_standby_feedback = on                 # Inform primary about replica's xmin
wal_receiver_timeout = 60s                # Reconnect if primary is silent this long

#----------------------------------------------------------------------
# MEMORY (same as primary)
#----------------------------------------------------------------------
shared_buffers = '2GB'
effective_cache_size = '6GB'
work_mem = '64MB'
maintenance_work_mem = '512MB'
random_page_cost = 1.1
effective_io_concurrency = 200

#----------------------------------------------------------------------
# LOGGING
#----------------------------------------------------------------------
logging_collector = on
log_directory = 'log'
log_filename = 'postgresql-%Y-%m-%d.log'
log_min_duration_statement = 500

#----------------------------------------------------------------------
# STATISTICS
#----------------------------------------------------------------------
shared_preload_libraries = 'pg_stat_statements'
```

### 4.5 Start the Replicas

```bash
pg_ctlcluster 16 main start
```

### 4.6 Verify Replication Is Working

**On the primary:**

```bash
sudo -u postgres psql << 'SQL'
SELECT
    client_addr,
    state,
    sent_lsn,
    write_lsn,
    flush_lsn,
    replay_lsn,
    sync_state
FROM pg_stat_replication;
SQL
```

You should see two rows — one for each replica — with `state = streaming`.

**On each replica:**

```bash
sudo -u postgres psql << 'SQL'
SELECT
    pg_is_in_recovery() AS is_replica,
    now() - pg_last_xact_replay_timestamp() AS replication_lag;
SQL
```

`is_replica` should be `t` and `replication_lag` should be milliseconds.

**End-to-end test:**

```bash
# On the primary — create test data
sudo -u postgres psql -d myapp -c "CREATE TABLE test_repl (id serial, ts timestamptz DEFAULT now());"
sudo -u postgres psql -d myapp -c "INSERT INTO test_repl DEFAULT VALUES;"

# On replica1 — verify it appears
sudo -u postgres psql -d myapp -c "SELECT * FROM test_repl;"

# Clean up
sudo -u postgres psql -d myapp -c "DROP TABLE test_repl;"
```

---

## Phase 5 — Connection Pooling with PgBouncer

Run on `pg-proxy` (the 4th droplet). PgBouncer dramatically reduces connection overhead.

### 5.1 Install PgBouncer

```bash
apt update && apt install -y pgbouncer
```

### 5.2 Configure PgBouncer

Edit `/etc/pgbouncer/pgbouncer.ini`:

```ini
;; /etc/pgbouncer/pgbouncer.ini

[databases]
; Write pool — connects to primary
myapp_write = host=pg-primary port=5432 dbname=myapp

; Read pool — connects to HAProxy which load-balances replicas
; (We'll set up HAProxy in the next phase on port 5433)
myapp_read = host=127.0.0.1 port=5433 dbname=myapp

[pgbouncer]
listen_addr = 0.0.0.0
listen_port = 6432
auth_type = scram-sha-256
auth_file = /etc/pgbouncer/userlist.txt

; Pool mode: transaction = release connection after each transaction
pool_mode = transaction

; Pool sizing
default_pool_size = 50          ; Connections per user/db pair
min_pool_size = 10              ; Pre-warm connections
reserve_pool_size = 10          ; Emergency overflow
reserve_pool_timeout = 3        ; Seconds before using reserve pool
max_client_conn = 5000          ; Max client-side connections
max_db_connections = 100        ; Max server-side connections per DB

; Timeouts
server_idle_timeout = 300
client_idle_timeout = 0         ; Don't kill idle clients
query_timeout = 0               ; App should set statement_timeout instead
server_connect_timeout = 5

; Logging
log_connections = 1
log_disconnections = 1
log_pooler_errors = 1
stats_period = 60

; Admin
admin_users = admin_user
stats_users = admin_user
```

### 5.3 Create the Userlist File

PgBouncer needs to know user credentials. Generate the scram hash:

```bash
# Generate the password hash
sudo -u postgres psql -h pg-primary -c "SELECT concat('\"', usename, '\" \"', passwd, '\"') FROM pg_shadow WHERE usename IN ('app_user', 'admin_user');" -t

# Put the output into the userlist file
cat > /etc/pgbouncer/userlist.txt << 'EOF'
"app_user" "SCRAM-SHA-256$4096:xxxx..."
"admin_user" "SCRAM-SHA-256$4096:xxxx..."
EOF

chown pgbouncer:pgbouncer /etc/pgbouncer/userlist.txt
chmod 600 /etc/pgbouncer/userlist.txt
```

### 5.4 Start PgBouncer

```bash
systemctl enable pgbouncer
systemctl start pgbouncer
systemctl status pgbouncer
```

### 5.5 Test PgBouncer

```bash
# Write connection (goes to primary)
psql -h 127.0.0.1 -p 6432 -U app_user -d myapp_write -c "SELECT 'write OK';"

# Read connection (will work after HAProxy is set up)
# psql -h 127.0.0.1 -p 6432 -U app_user -d myapp_read -c "SELECT 'read OK';"
```

---

## Phase 6 — Load Balancing Replicas with HAProxy

Also runs on `pg-proxy`.

### 6.1 Install HAProxy

```bash
apt install -y haproxy
```

### 6.2 Create a Health Check Script on Each Replica

On **both replicas** (`pg-replica1` and `pg-replica2`), install a lightweight HTTP health check:

```bash
apt install -y xinetd

cat > /usr/local/bin/pg_replica_check.sh << 'SCRIPT'
#!/bin/bash
# Returns HTTP 200 if PostgreSQL is in recovery (= is a replica) and lag < 30s
# Returns HTTP 503 otherwise

PGUSER="replicator"
PGHOST="localhost"
PGPORT="5432"

# Check if in recovery mode
IS_REPLICA=$(psql -U postgres -h $PGHOST -p $PGPORT -t -A -c "SELECT pg_is_in_recovery();" 2>/dev/null)

if [ "$IS_REPLICA" != "t" ]; then
    echo -e "HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain\r\n\r\nNot a replica"
    exit 1
fi

# Check replication lag
LAG=$(psql -U postgres -h $PGHOST -p $PGPORT -t -A -c \
    "SELECT CASE WHEN pg_last_xact_replay_timestamp() IS NULL THEN 999
     ELSE EXTRACT(EPOCH FROM now() - pg_last_xact_replay_timestamp()) END;" 2>/dev/null)

if [ -z "$LAG" ] || (( $(echo "$LAG > 30" | bc -l) )); then
    echo -e "HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain\r\n\r\nLag too high: ${LAG}s"
    exit 1
fi

echo -e "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nReplica OK - lag: ${LAG}s"
exit 0
SCRIPT

chmod +x /usr/local/bin/pg_replica_check.sh
```

Set up xinetd to serve this check on port 8080:

```bash
cat > /etc/xinetd.d/pg_check << 'EOF'
service pg_check
{
    flags          = REUSE
    socket_type    = stream
    port           = 8080
    wait           = no
    user           = postgres
    server         = /usr/local/bin/pg_replica_check.sh
    log_on_failure += USERID
    disable        = no
    per_source     = UNLIMITED
}
EOF

# Add the service to /etc/services
echo "pg_check        8080/tcp" >> /etc/services

systemctl restart xinetd
```

Test it:

```bash
curl http://localhost:8080
# Should return: Replica OK - lag: 0.xxxs
```

### 6.3 Configure HAProxy on pg-proxy

Edit `/etc/haproxy/haproxy.cfg`:

```cfg
global
    log /dev/log local0
    maxconn 4096
    user haproxy
    group haproxy
    daemon

defaults
    log     global
    mode    tcp
    option  tcplog
    option  clitcpka
    timeout connect 5s
    timeout client  60s
    timeout server  60s
    retries 3

#----------------------------------------------------------------------
# Stats dashboard — accessible at http://pg-proxy:7000/stats
#----------------------------------------------------------------------
listen stats
    bind *:7000
    mode http
    stats enable
    stats uri /stats
    stats refresh 10s
    stats auth admin:YOUR_STATS_PASSWORD

#----------------------------------------------------------------------
# Read replica pool — round-robin across healthy replicas
#----------------------------------------------------------------------
listen pg_read_replicas
    bind *:5433
    mode tcp
    option httpchk GET /
    http-check expect status 200
    balance roundrobin
    default-server inter 5s fall 3 rise 2 on-marked-down shutdown-sessions

    server replica1 pg-replica1:5432 check port 8080
    server replica2 pg-replica2:5432 check port 8080

#----------------------------------------------------------------------
# Primary write endpoint (single server, health-checked)
#----------------------------------------------------------------------
listen pg_primary_write
    bind *:5432
    mode tcp
    option httpchk GET /
    default-server inter 5s fall 3 rise 2

    server primary pg-primary:5432 check port 8080
```

> **Note:** For the primary health check, you'd create a similar script on `pg-primary` that returns 200 only if the server is **not** in recovery (i.e., is the actual primary). We'll do this properly with Patroni later.

### 6.4 Start HAProxy

```bash
haproxy -c -f /etc/haproxy/haproxy.cfg    # Validate config
systemctl enable haproxy
systemctl start haproxy
```

### 6.5 Verify the Full Connection Path

```bash
# From pg-proxy: read through HAProxy → replicas
psql -h 127.0.0.1 -p 5433 -U app_user -d myapp -c "SELECT pg_is_in_recovery();"
# Should return: t (meaning you hit a replica)

# Full path: app → PgBouncer (6432) → HAProxy (5433) → replica
psql -h 127.0.0.1 -p 6432 -U app_user -d myapp_read -c "SELECT pg_is_in_recovery();"
# Should return: t

# Write path: app → PgBouncer (6432) → primary (5432)
psql -h 127.0.0.1 -p 6432 -U app_user -d myapp_write -c "SELECT pg_is_in_recovery();"
# Should return: f
```

---

## Phase 7 — High Availability with Patroni + etcd

This turns your static primary/replica setup into a self-healing cluster with automatic failover.

### 7.1 Install etcd on All Three Database Nodes

```bash
apt install -y etcd
```

Configure etcd for a 3-node cluster. On **each node**, edit `/etc/default/etcd`:

**On pg-primary (10.114.0.10):**

```bash
cat > /etc/default/etcd << 'EOF'
ETCD_NAME="etcd1"
ETCD_DATA_DIR="/var/lib/etcd/default"
ETCD_LISTEN_PEER_URLS="http://10.114.0.10:2380"
ETCD_LISTEN_CLIENT_URLS="http://10.114.0.10:2379,http://127.0.0.1:2379"
ETCD_INITIAL_ADVERTISE_PEER_URLS="http://10.114.0.10:2380"
ETCD_ADVERTISE_CLIENT_URLS="http://10.114.0.10:2379"
ETCD_INITIAL_CLUSTER="etcd1=http://10.114.0.10:2380,etcd2=http://10.114.0.11:2380,etcd3=http://10.114.0.12:2380"
ETCD_INITIAL_CLUSTER_STATE="new"
ETCD_INITIAL_CLUSTER_TOKEN="pg-cluster-token"
EOF
```

**On pg-replica1 (10.114.0.11):**

```bash
cat > /etc/default/etcd << 'EOF'
ETCD_NAME="etcd2"
ETCD_DATA_DIR="/var/lib/etcd/default"
ETCD_LISTEN_PEER_URLS="http://10.114.0.11:2380"
ETCD_LISTEN_CLIENT_URLS="http://10.114.0.11:2379,http://127.0.0.1:2379"
ETCD_INITIAL_ADVERTISE_PEER_URLS="http://10.114.0.11:2380"
ETCD_ADVERTISE_CLIENT_URLS="http://10.114.0.11:2379"
ETCD_INITIAL_CLUSTER="etcd1=http://10.114.0.10:2380,etcd2=http://10.114.0.11:2380,etcd3=http://10.114.0.12:2380"
ETCD_INITIAL_CLUSTER_STATE="new"
ETCD_INITIAL_CLUSTER_TOKEN="pg-cluster-token"
EOF
```

**On pg-replica2 (10.114.0.12):**

```bash
cat > /etc/default/etcd << 'EOF'
ETCD_NAME="etcd3"
ETCD_DATA_DIR="/var/lib/etcd/default"
ETCD_LISTEN_PEER_URLS="http://10.114.0.12:2380"
ETCD_LISTEN_CLIENT_URLS="http://10.114.0.12:2379,http://127.0.0.1:2379"
ETCD_INITIAL_ADVERTISE_PEER_URLS="http://10.114.0.12:2379"
ETCD_ADVERTISE_CLIENT_URLS="http://10.114.0.12:2379"
ETCD_INITIAL_CLUSTER="etcd1=http://10.114.0.10:2380,etcd2=http://10.114.0.11:2380,etcd3=http://10.114.0.12:2380"
ETCD_INITIAL_CLUSTER_STATE="new"
ETCD_INITIAL_CLUSTER_TOKEN="pg-cluster-token"
EOF
```

Start etcd on all three:

```bash
systemctl enable etcd
systemctl start etcd

# Verify cluster health
etcdctl member list
etcdctl endpoint health
```

### 7.2 Install Patroni on All Three Database Nodes

```bash
apt install -y python3-pip python3-psycopg2
pip3 install patroni[etcd] --break-system-packages
```

### 7.3 Create Patroni Configuration

> **Important:** Before starting Patroni, stop any running PostgreSQL instances. Patroni will manage PostgreSQL from now on.

```bash
pg_ctlcluster 16 main stop
systemctl disable postgresql
```

Create the Patroni config. This example is for `pg-primary` — adjust `name` and `connect_address` for each node.

**On pg-primary:**

```bash
mkdir -p /etc/patroni

cat > /etc/patroni/config.yml << 'EOF'
scope: pg-cluster
name: pg-primary

restapi:
  listen: 0.0.0.0:8008
  connect_address: 10.114.0.10:8008

etcd:
  hosts: 10.114.0.10:2379,10.114.0.11:2379,10.114.0.12:2379

bootstrap:
  dcs:
    ttl: 30
    loop_wait: 10
    retry_timeout: 10
    maximum_lag_on_failover: 1048576    # 1MB — only promote replicas within this lag
    postgresql:
      use_pg_rewind: true
      use_slots: true
      parameters:
        wal_level: replica
        max_wal_senders: 10
        max_replication_slots: 10
        hot_standby: "on"
        wal_keep_size: "2GB"
        max_connections: 200
        shared_buffers: "2GB"
        effective_cache_size: "6GB"
        work_mem: "64MB"
        maintenance_work_mem: "512MB"
        random_page_cost: 1.1
        effective_io_concurrency: 200
        checkpoint_completion_target: 0.9
        max_wal_size: "4GB"
        logging_collector: "on"
        log_directory: "log"
        log_min_duration_statement: 500
        shared_preload_libraries: "pg_stat_statements"
  initdb:
    - encoding: UTF8
    - data-checksums
  pg_hba:
    - local   all             postgres                       peer
    - local   all             all                            scram-sha-256
    - host    all             all            10.114.0.0/24   scram-sha-256
    - host    replication     replicator     10.114.0.0/24   scram-sha-256
  users:
    admin_user:
      password: "YOUR_STRONG_ADMIN_PASSWORD"
      options:
        - createrole
        - createdb
        - superuser
    replicator:
      password: "YOUR_STRONG_REPL_PASSWORD"
      options:
        - replication
    app_user:
      password: "YOUR_STRONG_APP_PASSWORD"
      options: []

postgresql:
  listen: 0.0.0.0:5432
  connect_address: 10.114.0.10:5432
  data_dir: /var/lib/postgresql/16/main
  bin_dir: /usr/lib/postgresql/16/bin
  pgpass: /tmp/pgpass0
  authentication:
    superuser:
      username: postgres
      password: "YOUR_POSTGRES_PASSWORD"
    replication:
      username: replicator
      password: "YOUR_STRONG_REPL_PASSWORD"
    rewind:
      username: admin_user
      password: "YOUR_STRONG_ADMIN_PASSWORD"

tags:
  nofailover: false
  noloadbalance: false
  clonefrom: false
  nosync: false
EOF

chown postgres:postgres /etc/patroni/config.yml
chmod 600 /etc/patroni/config.yml
```

**On pg-replica1** — same file but change:

```yaml
name: pg-replica1
restapi:
  connect_address: 10.114.0.11:8008
postgresql:
  connect_address: 10.114.0.11:5432
```

**On pg-replica2** — same file but change:

```yaml
name: pg-replica2
restapi:
  connect_address: 10.114.0.12:8008
postgresql:
  connect_address: 10.114.0.12:5432
```

### 7.4 Create a Systemd Service for Patroni

On all three nodes:

```bash
cat > /etc/systemd/system/patroni.service << 'EOF'
[Unit]
Description=Patroni - PostgreSQL High Availability
After=syslog.target network.target etcd.service
Wants=etcd.service

[Service]
Type=simple
User=postgres
Group=postgres
ExecStart=/usr/local/bin/patroni /etc/patroni/config.yml
ExecReload=/bin/kill -s HUP $MAINPID
KillMode=process
TimeoutSec=30
Restart=on-failure
RestartSec=5s

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
```

### 7.5 Start Patroni

**Important:** Start the intended primary first, let it initialize, then start the replicas.

```bash
# On pg-primary FIRST:
systemctl enable patroni
systemctl start patroni
journalctl -u patroni -f    # Watch logs until you see "is the leader"

# Then on pg-replica1 and pg-replica2:
systemctl enable patroni
systemctl start patroni
journalctl -u patroni -f    # Watch until you see "replica has been created"
```

### 7.6 Verify the Patroni Cluster

```bash
# Install patronictl alias (on any node)
patronictl -c /etc/patroni/config.yml list
```

Expected output:

```
+-----------+--------------+---------+---------+----+-----------+
| Member    | Host         | Role    | State   | TL | Lag in MB |
+-----------+--------------+---------+---------+----+-----------+
| pg-primary| 10.114.0.10  | Leader  | running |  1 |           |
| pg-replica1|10.114.0.11  | Replica | running |  1 |         0 |
| pg-replica2|10.114.0.12  | Replica | running |  1 |         0 |
+-----------+--------------+---------+---------+----+-----------+
```

### 7.7 Update HAProxy to Use Patroni's REST API

Now replace the xinetd health checks with Patroni's built-in REST API, which is much more reliable. On `pg-proxy`, update HAProxy:

```cfg
listen pg_read_replicas
    bind *:5433
    mode tcp
    option httpchk GET /replica
    http-check expect status 200
    balance roundrobin
    default-server inter 5s fall 3 rise 2 on-marked-down shutdown-sessions

    server pg-primary  pg-primary:5432  check port 8008
    server pg-replica1 pg-replica1:5432 check port 8008
    server pg-replica2 pg-replica2:5432 check port 8008

listen pg_primary_write
    bind *:5432
    mode tcp
    option httpchk GET /primary
    http-check expect status 200
    default-server inter 5s fall 3 rise 2

    server pg-primary  pg-primary:5432  check port 8008
    server pg-replica1 pg-replica1:5432 check port 8008
    server pg-replica2 pg-replica2:5432 check port 8008
```

> **Key insight:** All three servers are listed in **both** backends. Patroni's REST API on port 8008 returns:
> - `GET /primary` → 200 only on the current leader, 503 on replicas
> - `GET /replica` → 200 only on replicas, 503 on the leader
>
> This means after a failover, HAProxy **automatically** routes writes to the new leader without any DNS or config changes.

```bash
systemctl restart haproxy
```

---

## Phase 8 — Monitoring with Prometheus + Grafana

### 8.1 Install postgres_exporter on All Three Database Nodes

```bash
# Download the latest release
cd /tmp
curl -LO https://github.com/prometheus-community/postgres_exporter/releases/download/v0.15.0/postgres_exporter-0.15.0.linux-amd64.tar.gz
tar xzf postgres_exporter-0.15.0.linux-amd64.tar.gz
cp postgres_exporter-0.15.0.linux-amd64/postgres_exporter /usr/local/bin/

# Create environment file
cat > /etc/default/postgres_exporter << 'EOF'
DATA_SOURCE_NAME="postgresql://admin_user:YOUR_STRONG_ADMIN_PASSWORD@localhost:5432/myapp?sslmode=disable"
EOF

# Create systemd service
cat > /etc/systemd/system/postgres_exporter.service << 'EOF'
[Unit]
Description=Prometheus PostgreSQL Exporter
After=postgresql.service patroni.service

[Service]
User=postgres
EnvironmentFile=/etc/default/postgres_exporter
ExecStart=/usr/local/bin/postgres_exporter \
    --collector.stat_statements \
    --collector.replication \
    --collector.replication_slot
Restart=on-failure
RestartSec=5s

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable postgres_exporter
systemctl start postgres_exporter

# Verify
curl http://localhost:9187/metrics | grep pg_replication
```

### 8.2 Install Prometheus on pg-proxy

```bash
apt install -y prometheus

cat > /etc/prometheus/prometheus.yml << 'EOF'
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'postgresql'
    static_configs:
      - targets:
          - 'pg-primary:9187'
          - 'pg-replica1:9187'
          - 'pg-replica2:9187'
        labels:
          cluster: 'pg-cluster'

  - job_name: 'haproxy'
    static_configs:
      - targets: ['localhost:7000']

  - job_name: 'patroni'
    static_configs:
      - targets:
          - 'pg-primary:8008'
          - 'pg-replica1:8008'
          - 'pg-replica2:8008'
EOF

systemctl restart prometheus
```

### 8.3 Install Grafana on pg-proxy

```bash
apt install -y apt-transport-https software-properties-common
curl -fsSL https://apt.grafana.com/gpg.key | gpg --dearmor -o /usr/share/keyrings/grafana-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/grafana-keyring.gpg] https://apt.grafana.com stable main" > /etc/apt/sources.list.d/grafana.list
apt update && apt install -y grafana

systemctl enable grafana-server
systemctl start grafana-server
```

Access Grafana at `http://pg-proxy-public-ip:3000` (default login: admin/admin).

1. Add Prometheus as a data source: `http://localhost:9090`
2. Import the **PostgreSQL dashboard** (Grafana ID: `9628`)
3. Import the **HAProxy dashboard** (Grafana ID: `2428`)

### 8.4 Critical Alerts to Configure

Create these Prometheus alerting rules in `/etc/prometheus/alerts.yml`:

```yaml
groups:
  - name: postgresql_alerts
    rules:
      # Replication lag over 10 seconds for 2 minutes
      - alert: PostgreSQLReplicationLagHigh
        expr: pg_replication_lag > 10
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "High replication lag on {{ $labels.instance }}"
          description: "Replication lag is {{ $value }}s"

      # Replication lag over 30 seconds — critical
      - alert: PostgreSQLReplicationLagCritical
        expr: pg_replication_lag > 30
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "CRITICAL replication lag on {{ $labels.instance }}"

      # Replica disconnected
      - alert: PostgreSQLReplicaDown
        expr: pg_stat_replication_pid == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Replica disconnected from primary"

      # Connections approaching limit
      - alert: PostgreSQLConnectionsHigh
        expr: sum(pg_stat_activity_count) by (instance) / pg_settings_max_connections * 100 > 80
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Connections at {{ $value }}% capacity"

      # Disk space on WAL
      - alert: PostgreSQLDiskSpaceLow
        expr: node_filesystem_avail_bytes{mountpoint="/var/lib/postgresql"} / node_filesystem_size_bytes{mountpoint="/var/lib/postgresql"} * 100 < 20
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Database disk space below 20%"
```

---

## Phase 9 — Application-Level Read/Write Routing

Your application connects to **two PgBouncer pools** on `pg-proxy`:

| Purpose | Host | Port | Database |
|---------|------|------|----------|
| Writes (INSERT, UPDATE, DELETE) | pg-proxy | 6432 | `myapp_write` |
| Reads (SELECT) | pg-proxy | 6432 | `myapp_read` |

### 9.1 Example: Python with SQLAlchemy

```python
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
import time

# Two separate engines
WRITE_ENGINE = create_engine(
    "postgresql://app_user:PASSWORD@pg-proxy:6432/myapp_write",
    pool_size=20,
    max_overflow=10,
    pool_pre_ping=True,        # Detect stale connections
    pool_recycle=300,           # Recycle connections every 5 min
)

READ_ENGINE = create_engine(
    "postgresql://app_user:PASSWORD@pg-proxy:6432/myapp_read",
    pool_size=50,              # More read connections (read-heavy app)
    max_overflow=20,
    pool_pre_ping=True,
    pool_recycle=300,
)

WriteSession = sessionmaker(bind=WRITE_ENGINE)
ReadSession = sessionmaker(bind=READ_ENGINE)


class DatabaseRouter:
    """Routes queries to the correct database based on operation type."""

    def __init__(self):
        self._write_timestamps = {}   # user_id → last write time

    def get_read_session(self, user_id=None):
        """
        Returns a read session. If the user recently wrote data,
        routes to the primary to guarantee read-your-own-writes.
        """
        if user_id and user_id in self._write_timestamps:
            elapsed = time.time() - self._write_timestamps[user_id]
            if elapsed < 5.0:   # 5-second consistency window
                return WriteSession()   # Read from primary
        return ReadSession()

    def get_write_session(self, user_id=None):
        """Returns a write session and records the write timestamp."""
        if user_id:
            self._write_timestamps[user_id] = time.time()
        return WriteSession()


# Usage
router = DatabaseRouter()

# Writing
with router.get_write_session(user_id=42) as session:
    session.execute("INSERT INTO orders ...")
    session.commit()

# Reading — automatically routes to primary for 5 seconds after write
with router.get_read_session(user_id=42) as session:
    orders = session.execute("SELECT * FROM orders WHERE user_id = 42").fetchall()
```

### 9.2 Example: Node.js with pg (node-postgres)

```javascript
const { Pool } = require('pg');

const writePool = new Pool({
  host: 'pg-proxy',
  port: 6432,
  database: 'myapp_write',
  user: 'app_user',
  password: 'PASSWORD',
  max: 20,
  idleTimeoutMillis: 30000,
  connectionTimeoutMillis: 5000,
});

const readPool = new Pool({
  host: 'pg-proxy',
  port: 6432,
  database: 'myapp_read',
  user: 'app_user',
  password: 'PASSWORD',
  max: 50,
  idleTimeoutMillis: 30000,
  connectionTimeoutMillis: 5000,
});

// Read-your-own-writes tracking
const writeTimestamps = new Map();
const CONSISTENCY_WINDOW_MS = 5000;

async function query(sql, params, { userId, isWrite = false } = {}) {
  if (isWrite) {
    if (userId) writeTimestamps.set(userId, Date.now());
    return writePool.query(sql, params);
  }

  // Check if user recently wrote — route to primary if so
  if (userId && writeTimestamps.has(userId)) {
    const elapsed = Date.now() - writeTimestamps.get(userId);
    if (elapsed < CONSISTENCY_WINDOW_MS) {
      return writePool.query(sql, params);
    }
    writeTimestamps.delete(userId);
  }

  return readPool.query(sql, params);
}

// Usage
await query('INSERT INTO orders (user_id, total) VALUES ($1, $2)', [42, 99.99],
            { userId: 42, isWrite: true });

const result = await query('SELECT * FROM orders WHERE user_id = $1', [42],
                           { userId: 42 });
```

---

## Phase 10 — Operational Runbook

### Test a Manual Failover

```bash
# On any node with patronictl
patronictl -c /etc/patroni/config.yml switchover

# Follow the prompts:
# Current leader: pg-primary
# Candidate: pg-replica1
# Scheduled: now

# Verify
patronictl -c /etc/patroni/config.yml list
# pg-replica1 should now be Leader
```

### Add a Third Replica Later

```bash
# 1. Create new droplet (pg-replica3, 10.114.0.13)
# 2. Install PostgreSQL 16, etcd, Patroni (same as Phase 2 + 7)
# 3. Add to etcd cluster
# 4. Create Patroni config with name=pg-replica3, connect_address=10.114.0.13
# 5. Start Patroni — it will automatically clone from the leader
# 6. Add to HAProxy backend:
#    server pg-replica3 pg-replica3:5432 check port 8008
# 7. Reload HAProxy: systemctl reload haproxy
```

### Emergency: Replica Is Lagging Badly

```bash
# Check lag
patronictl -c /etc/patroni/config.yml list

# If lag > threshold, temporarily remove from HAProxy
# HAProxy does this automatically via health checks, but you can force it:
echo "disable server pg_read_replicas/replica1" | socat stdio /var/run/haproxy/admin.sock

# Investigate on the replica
sudo -u postgres psql -c "SELECT * FROM pg_stat_wal_receiver;"

# After resolving, re-enable
echo "enable server pg_read_replicas/replica1" | socat stdio /var/run/haproxy/admin.sock
```

### Regular Maintenance Checklist

| Task | Frequency | Command |
|------|-----------|---------|
| Check replication lag | Continuous (Grafana) | `patronictl list` |
| Review slow queries | Weekly | Query `pg_stat_statements` |
| VACUUM ANALYZE | Daily (auto) | Verify `autovacuum` is running |
| Check disk usage | Daily (alert) | `df -h /var/lib/postgresql` |
| Test failover | Monthly | `patronictl switchover` |
| Update PostgreSQL | Monthly/as needed | Rolling restart via Patroni |
| Rotate credentials | Quarterly | Update PgBouncer userlist + Patroni config |
| Review pg_hba.conf | Quarterly | Audit access rules |

---

## Final Architecture Summary

```
                           ┌────────────────┐
                           │  APPLICATION   │
                           └───────┬────────┘
                                   │
                          ┌────────▼────────┐
                          │   PgBouncer     │
                          │   (port 6432)   │
                          │                 │
                          │ myapp_write ──────────► port 5432 (HAProxy)
                          │ myapp_read  ──────────► port 5433 (HAProxy)
                          └─────────────────┘
                                   │
                    ┌──────────────┼──────────────┐
                    │              │              │
            ┌───────▼───────┐ ┌───▼──────┐ ┌────▼─────┐
            │  pg-primary   │ │pg-replica1│ │pg-replica2│
            │  (Leader)     │ │ (Replica) │ │ (Replica) │
            │  Patroni      │ │ Patroni   │ │ Patroni   │
            │  etcd         │ │ etcd      │ │ etcd      │
            │  PG Exporter  │ │ PG Export │ │ PG Export │
            └───────────────┘ └──────────┘ └──────────┘
                    │              ▲              ▲
                    │   Streaming  │   Streaming  │
                    └──────────────┴──────────────┘

            ┌─────────────────────────────────┐
            │  Prometheus → Grafana           │
            │  (Monitoring & Alerting)        │
            └─────────────────────────────────┘
```

**Connection endpoints your app uses:**
- **Writes:** `postgresql://app_user:PASS@pg-proxy:6432/myapp_write`
- **Reads:** `postgresql://app_user:PASS@pg-proxy:6432/myapp_read`

That's it. The entire stack — from bare droplets to a production-grade, self-healing, monitored PostgreSQL cluster with automatic failover and read scaling.
