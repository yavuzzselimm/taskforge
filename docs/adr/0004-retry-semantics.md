# ADR-0004: Retry semantics, backoff and dead jobs

## Status

Accepted — November 2026

## Context

Handlers fail. Some failures go away on their own (a timeout, a service that is briefly down), others
never will (an invalid payload). Retrying everything forever wastes resources and can hammer a
service that is already struggling; retrying nothing loses work that would have succeeded a minute
later.

## Decision

- Every job has `max_attempts` (default 5, 1–20 per request). `attempt_count` is incremented at
  claim time, so a crash also uses up an attempt.
- A handler signals a permanent failure by throwing `PermanentJobException`. Any other exception is
  treated as transient.
- Transient failure with attempts left: the job goes to `RETRY_WAIT` and `available_at` is pushed
  back by `min(max-delay, base-delay * 2^(attempt-1))` plus up to 20% random jitter
  (defaults: 1 s base, 5 min max).
- Permanent failure, a missing handler, or no attempts left: the job goes to `DEAD`. `DEAD` is the
  dead letter queue; there is no separate table. `last_error` keeps the reason.
- Every attempt is recorded in `job_attempts` with its worker and outcome
  (`SUCCESS`, `TRANSIENT_FAILURE`, `PERMANENT_FAILURE`, `LEASE_EXPIRED`).

## Alternatives considered

- **Fixed delay between retries:** simple, but a downstream outage is hit at the same rate the
  whole time.
- **Backoff without jitter:** jobs that failed together are retried together, in waves.
- **Exception type lists in configuration (retry on X, not on Y):** more flexible, but the handler
  knows best whether its own error is permanent, so the decision stays in code.
- **Separate DLQ table:** moving rows between tables adds a second write path; a status plus an
  index is enough at this scale.

## Consequences

- Delivery is at-least-once. A handler can run again after a failure that happened *after* its side
  effect (e.g. the email was sent, then the worker crashed), so handlers with side effects must be
  idempotent (stage 5).
- `job.retries`, `jobs.failed` and `job_attempts` make retry behaviour visible.
- Recovered jobs (see ADR-0005) are made available immediately, without backoff; `max_attempts`
  still stops a job that keeps killing its worker.
