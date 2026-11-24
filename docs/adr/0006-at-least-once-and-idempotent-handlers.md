# ADR-0006: At-least-once execution and idempotent handlers

## Status

Accepted — November 2026

## Context

ADR-0005 makes sure a job held by a dead worker is run again. The flip side is that a job can be
executed more than once: a worker can finish the side effect (send the email, call the payment API)
and die before it writes `COMPLETED`. Recovery then hands the job to another worker. The ownership
check only protects our own `jobs` row; it cannot undo an email that was already sent.

Exactly-once execution is not a goal of TaskForge (see the roadmap). The question is where duplicate
side effects are stopped.

## Decision

- **Delivery semantics are at-least-once.** A job may run more than once; a result is recorded at
  most once.
- **Handlers with external side effects must be idempotent.** The reference implementation is
  `SendOrderEmailHandler`: it passes `order-email-<jobId>` as an idempotency key to the email
  provider. The key contains the job id and **not** the attempt number, so every re-execution sends
  the same request and the provider ignores the duplicate.
- **The duplicate check lives where the side effect happens** (the provider), not in our database.
- **Cancellation is a conditional update:** `status IN ('READY', 'RETRY_WAIT')` is checked and
  changed in one `UPDATE`, so cancel and claim can never both win. Running jobs are not cancelled
  (`409 Conflict`); stopping a running handler would need cooperative cancellation in every handler.

## Alternatives considered

- **Mark "sent" in our database before or after calling the provider:** the database write and the
  external call cannot share a transaction. Writing first and crashing loses the email; calling
  first and crashing sends it twice.
- **Use the attempt number in the key:** every retry gets a new key, so nothing is deduplicated. We
  tried this and the re-execution test failed with two emails.
- **Use the order id as the key:** would also block a deliberately created second job for the same
  order. Business-level deduplication belongs in the API `Idempotency-Key`, which the client controls.
- **Cancel by reading the job, checking the status in Java and saving:** a worker can claim the job
  between the read and the write. With this version the cancel/claim race test ended with 6 of 200
  jobs both running and `CANCELLED`.

## Consequences

- The acceptance test for Stage 5 kills a worker after it sent the email: the job is recovered,
  runs again, completes, and the provider sees two requests but sends one email. Its attempt history
  reads `LEASE_EXPIRED → SUCCESS`.
- Every new handler that talks to the outside world has to answer "what happens if this runs
  twice?". Handlers without side effects (pure computations) need nothing extra.
- `FakeEmailProvider` keeps its keys in memory, so it only catches duplicates inside one process. A
  real provider keeps them on its side for all clients.
