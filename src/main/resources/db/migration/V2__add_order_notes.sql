-- Demonstrates that Flyway migrations run against the real Postgres in tests,
-- so we catch migration issues early.
ALTER TABLE orders ADD COLUMN notes TEXT;
