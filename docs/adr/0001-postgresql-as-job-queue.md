# ADR-0001: Use PostgreSQL as the job queue

## Status

Accepted — November 2026

## Context

Jobs must be stored durably anyway: clients query their status, and pending work must survive a
restart. The question is whether to also put a message broker (RabbitMQ, Kafka) or Redis in front of
the workers.

## Decision

The `jobs` table is both the record and the queue. Workers poll it and claim jobs with SQL
(see ADR-0002). No broker or Redis in the first version.

## Alternatives considered

- **RabbitMQ / Kafka:** a second source of truth next to the database. Job state and queue state
  can drift apart (job saved but message lost, or the other way round), which then needs an outbox
  or similar pattern.
- **Redis list/stream:** fast, but durability depends on persistence settings, and the job row
  still lives in PostgreSQL.
- **Ready-made libraries (Quartz, JobRunr, db-scheduler):** the right choice for a real product,
  but the point of this project is to understand and build the claiming, lease and retry logic.

## Consequences

- One piece of infrastructure, one transaction model, easy to inspect with plain SQL.
- Workers poll, so pickup latency is up to `poll-interval-ms` (+ jitter) when the queue was empty.
- Every claim is a write on the database. At very high throughput the database becomes the
  bottleneck; that is far above this project's load and would be measured in the load tests first.
