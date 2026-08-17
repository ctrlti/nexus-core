CREATE TABLE reminders (
    id BIGSERIAL PRIMARY KEY,
    text TEXT NOT NULL,
    remind_at TIMESTAMP,
    repeat_interval VARCHAR(20) NOT NULL DEFAULT 'NONE',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);