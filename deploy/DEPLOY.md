# kfs-rss deploy instrukce

## 1. PostgreSQL — vytvorit DB + user

```bash
su - postgres -c "createuser kfs_rss"
su - postgres -c "createdb kfs_rss -O kfs_rss"
# Nastavit heslo
su - postgres -c "psql -c \"ALTER USER kfs_rss WITH PASSWORD 'HESLO';\""
```

V `/etc/postgresql/*/main/pg_hba.conf` pridat PRED radek `local all all peer`:
```
local   kfs_rss   kfs_rss   md5
```

Pak: `systemctl reload postgresql`

Pozn: Tabulky vytvori Flyway automaticky pri prvnim startu aplikace.

## 2. Deploy JAR

```bash
mkdir -p /opt/rss-backend
# Stahnout artifact z GitHub Actions, nebo buildit lokalne:
# ./mvnw package -Dquarkus.package.jar.type=uber-jar -DskipTests
cp target/*-runner.jar /opt/rss-backend/kfs-rss-runner.jar
```

## 3. systemd service

```bash
cp deploy/kfs-rss.service /etc/systemd/system/
# UPRAVIT heslo v Environment=DB_PASSWORD=...
vi /etc/systemd/system/kfs-rss.service
systemctl daemon-reload
systemctl enable kfs-rss
systemctl start kfs-rss
# Overit:
journalctl -u kfs-rss -f
```

## 4. Vytvorit uzivatele

```bash
curl -X POST http://localhost:8180/auth/setup \
  -H 'Content-Type: application/json' \
  -d '{"username":"kofis","password":"HESLO"}'
```

## 5. Migrace dat (jednorazove)

Zastavit service, upravit properties, spustit:
```bash
systemctl stop kfs-rss
# Spustit s migraci:
DB_PASSWORD=HESLO java -jar /opt/rss-backend/kfs-rss-runner.jar \
  -Dkfs.rss.migration.enabled=true \
  -Dkfs.rss.migration.data-dir=/var/www/html/rss/data \
  -Dkfs.rss.migration.webdav-dir=/media/storage/webdav/rss \
  -Dkfs.rss.migration.cache-file=/opt/rss/cache.json
# Pockej na "DATA MIGRATION COMPLETE" v logu
# Ctrl+C, pak:
systemctl start kfs-rss
```

## 6. Apache — pridat proxy, odstranit stary RSS

V OBOU VHostech (k-server-local.conf + k-server-ssl.conf) pridat:

```apache
    # RSS backend (Quarkus)
    ProxyPass /api/rss/ http://127.0.0.1:8180/
    ProxyPassReverse /api/rss/ http://127.0.0.1:8180/
    <Location /api/rss/>
        Header always unset WWW-Authenticate
    </Location>
```

A ODSTRANIT stary RSS WebDAV blok:
```apache
    # SMAZAT tento blok:
    <Location /dav/rss>
        Header always unset WWW-Authenticate
    </Location>
```

Pozn: WebDAV `/dav` blok zustava (pouziva ho Notes). Jen se odebere RSS-specificka Location.

Zapnout proxy modul (pokud jeste neni):
```bash
a2enmod proxy proxy_http
systemctl reload apache2
```

## 7. Deploy SPA

SPA uz je v k-server-web repu (updatovany index.html). Deploy jako obvykle pres updator.

## 8. Vypnout Python cron

V crontabu zakomentovat:
```bash
# RSS — nahrazeno Quarkus backendem
#*/30 * * * * /opt/rss/fetch-feeds.py > /opt/rss/last-fetch.log 2>&1
```

## 9. Overeni

```bash
# Auth
curl -u kofis:HESLO https://k-server.local/api/rss/auth/check
# Feedy
curl -u kofis:HESLO https://k-server.local/api/rss/feeds
# Refresh
curl -u kofis:HESLO -X POST https://k-server.local/api/rss/feeds/refresh
```

## Rollback

Kdyby neco selhalo:
1. `systemctl stop kfs-rss`
2. Odkomentovat Python cron
3. V Apache: odstranit ProxyPass, vratit `<Location /dav/rss>`
4. SPA: git revert v k-server-web
