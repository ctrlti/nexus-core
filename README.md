# Nexus Core

Telegram bot for personal finance tracking and task management. Built with Java 21, Spring Boot 3, and PostgreSQL. Automated deployment to an Ubuntu VPS via GitHub Actions on push to `main`.

## Features

### Finances

- Multi-currency accounts (PLN, USD, EUR — cash and card).
- Net balance calculated in PLN via live NBP API exchange rates.
- Expense and income logging with optional notes.
- Transfers between accounts of the same currency via `/transfer` or interactive UI.
- Last 10 transactions history.

### Tasks and Reminders

- General text notes without a due date.
- Scheduled reminders with time/date parsing (e.g. `19:30 Doctor` or `25.08 14:00 Dentist`).
- Recurring reminders (daily, weekly, monthly) via `/repeat`.
- Snooze options (`+15m`, `+1h`, `+3h`, next morning at 09:00).

## Tech Stack

- Java 21, Spring Boot 3 (Data JPA, Scheduling)
- PostgreSQL, Flyway
- JUnit 5, Mockito, H2 (in-memory, PostgreSQL-compatible mode) for tests
- NBP Web API
- Docker, Docker Compose, GitHub Actions, Ubuntu VPS (Oracle Cloud)

## CI/CD Pipeline

The workflow (`.github/workflows/deploy.yml`) runs on every push to `main`:

1. **Test job** — checks out the code, sets up JDK 21, and runs `./mvnw clean test` against an in-memory H2 database (see `src/test/resources/application.properties`).
2. **Deploy job** (runs only if tests pass) — connects to the VPS over SSH, pulls the repository, rebuilds the jar with `MAVEN_OPTS="-Xmx512m"`, and restarts the containers with `docker compose down && docker compose up -d --build --remove-orphans`.

### Required GitHub Secrets

- `SERVER_IP` — public IP address of the VPS.
- `SERVER_USER` — SSH username.
- `SSH_PRIVATE_KEY` — private SSH key for server access.

## Configuration

### `.env` (server-side only, not committed to git)

```
BOT_USERNAME=your_bot_username
BOT_TOKEN=your_telegram_bot_token
AUTHORIZED_CHAT_ID=your_chat_id
```

These are read via Spring properties in `application.properties`:
```properties
nexus.telegram.bot-username=${BOT_USERNAME:bot}
nexus.telegram.bot-token=${BOT_TOKEN:BOT_TOKEN}
nexus.telegram.authorized-chat-id=${AUTHORIZED_CHAT_ID:TELEGRAM_ID}
```

### Database

PostgreSQL connection details are currently hardcoded in `application.properties` and `docker-compose.yml` (not environment-driven):
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/nexus_db
spring.datasource.username=postgres
spring.datasource.password=postgres_password
```
> Note: moving these to environment variables is a planned improvement.

## Local Development

```bash
# Start the database
docker compose up -d nexus-db

# Run tests (uses in-memory H2, no external DB needed)
./mvnw clean test

# Run the application
./mvnw spring-boot:run
```

## Infrastructure

Deployed on an Oracle Cloud Always Free VPS (`E2.1.Micro`, 1 OCPU / 1GB RAM, Ubuntu 24.04). A 2GB swap file is configured (and persisted via `/etc/fstab`) to keep Maven builds stable on limited RAM. Access is direct via public IP — no domain, since the bot uses Telegram Long Polling and makes only outbound requests.
