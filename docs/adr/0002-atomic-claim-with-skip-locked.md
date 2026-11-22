# ADR-0002: Claim jobs atomically with `FOR UPDATE SKIP LOCKED`

## Status

Accepted — November 2026

## Context

Several worker threads, and several worker processes, read from the same `jobs` table. A job must
never be claimed by two workers at the same time.

## Decision

A claim is a single statement in a short transaction:

```sql
UPDATE jobs SET status = 'RUNNING', locked_by = ?, lease_until = ..., attempt_count = attempt_count + 1
WHERE id = (
    SELECT id FROM jobs
    WHERE status IN ('READY', 'RETRY_WAIT') AND available_at <= now()
    ORDER BY priority DESC, available_at
    LIMIT 1
    FOR UPDATE SKIP LOCKED
)
RETURNING ...
```

The handler runs outside this transaction, so no row lock is held while the job is executing.
Results are written with a conditional update (`WHERE locked_by = ? AND attempt_count = ?`) so a
worker that lost ownership cannot overwrite the job.

## Alternatives considered

- **SELECT, then UPDATE without locking:** two workers can see the same row. We tested this by
  removing the `FOR UPDATE SKIP LOCKED` line: 8 workers made 387 claims for 200 jobs.
- **`FOR UPDATE` without `SKIP LOCKED`:** correct, but workers queue up behind the same row, and
  after the wait the subquery returns nothing, so a worker can wrongly think the queue is empty.
- **Optimistic locking with a version column:** works, but under contention most claims fail and
  have to be retried.

## Consequences

- Covered by `JobClaimConcurrencyTest` (8 concurrent workers, 200 jobs, each claimed exactly once)
  and `MultiWorkerIntegrationTest`.
- Works the same across threads and processes, because the database only sees separate
  connections.
- The conditional result update is not a full fencing token; it is enough at this scale.
