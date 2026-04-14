# kfs-rss deploy

## Instalace (prvni nasazeni)

### 1. PostgreSQL

```bash
su - postgres -c "createuser kfs_rss"
su - postgres -c "createdb kfs_rss -O kfs_rss"
su - postgres -c "psql -c \"ALTER USER kfs_rss WITH PASSWORD 'HESLO';\""
```

V `/etc/postgresql/*/main/pg_hba.conf` pridat PRED radek `local all all peer`:
```
local   kfs_rss   kfs_rss   md5
```

Pak: `systemctl reload postgresql`

Pozn: Tabulky vytvori Flyway automaticky pri prvnim startu.

### 2. JAR + systemd

```bash
mkdir -p /opt/rss-backend
cp deploy/update-rss.sh /opt/rss-backend/
cp deploy/kfs-rss.service /etc/systemd/system/

# Upravit heslo:
vi /etc/systemd/system/kfs-rss.service
# Environment=DB_PASSWORD=...

systemctl daemon-reload
systemctl enable kfs-rss

# Stahnout JAR a spustit:
/opt/rss-backend/update-rss.sh v1.0.0
```

### 3. Apache

V OBOU VHostech (`k-server-local.conf` + `k-server-ssl.conf`) pridat:

```apache
    # RSS backend (Quarkus)
    ProxyPass /api/rss/ http://127.0.0.1:8180/
    ProxyPassReverse /api/rss/ http://127.0.0.1:8180/
    <Location /api/rss/>
        Header always unset WWW-Authenticate
    </Location>
```

A ODSTRANIT stary RSS WebDAV blok (pokud existuje):
```apache
    # SMAZAT:
    <Location /dav/rss>
        Header always unset WWW-Authenticate
    </Location>
```

```bash
a2enmod proxy proxy_http
systemctl reload apache2
```

### 4. Vytvorit uzivatele

```bash
curl -X POST http://localhost:8180/auth/setup \
  -H 'Content-Type: application/json' \
  -d '{"username":"kofis","password":"HESLO"}'
```

### 5. Deploy SPA + vypnout Python cron

SPA deploy pres updator (k-server-web).

V crontabu zakomentovat:
```bash
# RSS — nahrazeno Quarkus backendem
#*/30 * * * * /opt/rss/fetch-feeds.py > /opt/rss/last-fetch.log 2>&1
```

### 6. Overeni

```bash
curl -u kofis:HESLO https://k-server.local/api/rss/auth/check
curl -u kofis:HESLO https://k-server.local/api/rss/feeds
curl -u kofis:HESLO -X POST https://k-server.local/api/rss/feeds/refresh
```

---

## Migrace dat (jednorazova)

Import z legacy souboru (OPML, state.json, starred.json, cache.json) do PostgreSQL.

```bash
systemctl stop kfs-rss

DB_PASSWORD=HESLO java -jar /opt/rss-backend/kfs-rss-runner.jar \
  -Dkfs.rss.migration.enabled=true \
  -Dkfs.rss.migration.data-dir=/var/www/html/rss/data \
  -Dkfs.rss.migration.webdav-dir=/media/storage/webdav/rss \
  -Dkfs.rss.migration.cache-file=/opt/rss/cache.json

# Pockej na "DATA MIGRATION COMPLETE" v logu, pak Ctrl+C

systemctl start kfs-rss
```

---

## Aktualizace (rutinni)

### Na dev stroji

```bash
git tag v1.x.x
git push --tags
# Pockej na GitHub Actions → Release
```

### Na serveru

```bash
/opt/rss-backend/update-rss.sh v1.x.x
# Nebo bez verze (latest):
/opt/rss-backend/update-rss.sh
```

Script automaticky: stahne JAR → zastavi service → backup → nahradi → spusti → overi.
Pri selhani startu provede rollback z `.bak`.

---

## Rollback

```bash
systemctl stop kfs-rss
mv /opt/rss-backend/kfs-rss-runner.jar.bak /opt/rss-backend/kfs-rss-runner.jar
systemctl start kfs-rss
```
