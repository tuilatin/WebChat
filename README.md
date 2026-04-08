# ChatAppRealTime

This project is a SparkJava-based real-time chat application with WebSocket support.

## What changed

- Added support for MySQL and PostgreSQL via `DB_URL`, `DB_USER` and `DB_PASSWORD`.
- Added a `Database` helper class with dialect-aware SQL.
- Added Maven Shade plugin to build a runnable fat jar.
- Added Dockerfile for container deployment.
- Added backup scripts for `chatapp.db` and `uploads/`.
- Added `PORT` environment support for cloud platforms.

## Build

```bash
mvn package
```

The fat jar is created in `target/ChatAppRealTime-1.0-SNAPSHOT.jar`.

## Run locally with SQLite

```bash
java -jar target/ChatAppRealTime-1.0-SNAPSHOT.jar
```

## Run locally with MySQL

```bash
export DB_URL="jdbc:mysql://localhost:3306/chatapp?useSSL=false&serverTimezone=UTC"
export DB_USER="your_user"
export DB_PASSWORD="your_password"
java -jar target/ChatAppRealTime-1.0-SNAPSHOT.jar
```

## Run locally with PostgreSQL

```bash
export DB_URL="jdbc:postgresql://localhost:5432/chatapp"
export DB_USER="your_user"
export DB_PASSWORD="your_password"
java -jar target/ChatAppRealTime-1.0-SNAPSHOT.jar
```

## Run in Docker

```bash
docker build -t chatapp-realtime .

docker run -p 4567:4567 \
  -e DB_URL="jdbc:sqlite:chatapp.db" \
  -v "$PWD/chatapp.db:/app/chatapp.db" \
  -v "$PWD/uploads:/app/uploads" \
  chatapp-realtime
```

For MySQL/PostgreSQL, pass `DB_URL`, `DB_USER` and `DB_PASSWORD` at runtime.

## Deploy on VPS / VM

1. Install Java 22.
2. Copy the fat jar and `uploads/` folder.
3. Run with `java -jar target/ChatAppRealTime-1.0-SNAPSHOT.jar`.
4. Use `nginx` as reverse proxy, forwarding `80/443` to `4567`.

## Deploy on cloud services

- Azure App Service: deploy the generated jar or container image.
- AWS Elastic Beanstalk: deploy the jar or Docker image.
- Google Cloud Run: build the Docker image and deploy.

### Important env vars

- `DB_URL` (default: `jdbc:sqlite:chatapp.db`)
- `DB_USER`
- `DB_PASSWORD`
- `PORT` (default: `4567`)

## HTTPS and custom domain

Use `nginx` or the cloud service managed TLS feature to terminate HTTPS.

Example nginx configuration:

```nginx
server {
    listen 80;
    server_name example.com;

    location / {
        proxy_pass http://127.0.0.1:4567;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_cache_bypass $http_upgrade;
    }
}
```

Then enable TLS with Certbot / cloud-managed certificate.

## Backup

- On Linux/macOS: `./backup.sh`
- On Windows PowerShell: `./backup.ps1`

These scripts copy `chatapp.db` and `uploads/` into a timestamped backup folder.
