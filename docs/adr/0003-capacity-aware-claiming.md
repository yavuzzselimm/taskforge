# ADR-0003: One poller per worker, claim only when a thread is free

## Status

Accepted — November 2026

## Context

A worker process runs jobs on a fixed-size thread pool (`taskforge.worker.threads`, default 4).
Something has to decide when to claim the next job.

## Decision

Each worker has one poller thread and a `Semaphore` with as many permits as pool threads. The
poller takes a permit before claiming and the permit is released when the job finishes. If the
queue is empty the poller sleeps `poll-interval-ms` plus a random jitter.

The worker implements `SmartLifecycle`. On shutdown it stops claiming, waits up to
`shutdown-timeout-seconds` for running jobs, then interrupts them. Interrupted jobs stay `RUNNING`
and will be picked up by lease recovery (stage 4).

## Alternatives considered

- **Claim eagerly and queue jobs in the executor:** claimed jobs would wait in memory while their
  lease runs out. Recovery would then hand them to another worker even though this one still
  intends to run them.
- **Every pool thread polls on its own:** simpler, but N threads hit the database even when the
  queue is empty.
- **`@Scheduled` polling (stage 2):** single-threaded and no control over shutdown.

## Consequences

- A worker never holds more leases than it can run. `JobWorkerIntegrationTest` checks that at most
  `threads` jobs run at once.
- Database connections per worker are bounded: threads + 1 poller. With the default Hikari pool
  of 10 this leaves room for the API.
- Jitter keeps several workers that started together from polling at the same moment.
