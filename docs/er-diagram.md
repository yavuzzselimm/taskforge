# TaskForge ER Diagram

GitHub renders the Mermaid block below automatically. Place this file at `docs/er-diagram.md`.

```mermaid
erDiagram
    JOBS ||--o{ JOB_ATTEMPTS : "has attempts"
    WORKERS |o--o{ JOB_ATTEMPTS : "executes"
    WORKERS |o--o{ JOBS : "currently holds (locked_by)"
    JOBS ||--o| IDEMPOTENCY_RECORDS : "deduplicated by"

    JOBS {
        uuid id PK
        varchar job_type
        jsonb payload
        varchar status "READY, RUNNING, RETRY_WAIT, COMPLETED, DEAD, CANCELLED"
        smallint priority
        timestamptz scheduled_at
        int attempt_count
        int max_attempts
        timestamptz available_at
        varchar locked_by "logical ref to WORKERS.worker_id"
        timestamptz lease_until
        timestamptz heartbeat_at
        varchar idempotency_key UK "unique in chosen scope"
        timestamptz created_at
        timestamptz updated_at
        text last_error
    }

    JOB_ATTEMPTS {
        bigint id PK
        uuid job_id FK
        int attempt_no
        varchar worker_id "logical ref to WORKERS.worker_id"
        timestamptz started_at
        timestamptz finished_at
        varchar outcome "SUCCESS, TRANSIENT_FAILURE, PERMANENT_FAILURE, LEASE_EXPIRED"
        text error_summary
    }

    WORKERS {
        varchar worker_id PK
        timestamptz started_at
        timestamptz last_heartbeat_at
        int capacity
        varchar status
    }

    IDEMPOTENCY_RECORDS {
        varchar idempotency_key PK
        uuid job_id FK "unique"
        timestamptz created_at
    }
```

## Notes

- `WORKERS` and `IDEMPOTENCY_RECORDS` are optional in v1. Heartbeat can live on `JOBS`, and a UNIQUE constraint on `jobs.idempotency_key` can replace `IDEMPOTENCY_RECORDS`.
- `locked_by` and `job_attempts.worker_id` are logical references, not hard foreign keys, so a job's history survives a worker row being removed.
- `UNIQUE (job_id, attempt_no)` on `JOB_ATTEMPTS` prevents duplicate attempt rows.
- Indexes: `(status, available_at, priority)` for claiming; `(status, lease_until)` for recovery.
- CHECK constraints: `attempt_count >= 0`, `max_attempts > 0`.
