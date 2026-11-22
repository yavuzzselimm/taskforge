# ADR-0005: Leases, heartbeats and recovery

## Status

Accepted — November 2026

## Context

A worker can die while holding a job (crash, `kill -9`, lost network). Its job stays `RUNNING` and
nothing would ever pick it up again. At the same time, a healthy worker running a long job must not
lose that job just because it takes a while.

## Decision

- **Lease:** a claim sets `locked_by` and `lease_until = now() + lease-seconds` (default 30 s).
- **Heartbeat:** one thread per worker renews the leases of all its running jobs in a single
  `UPDATE` every `heartbeat-interval-ms` (default 10 s, must be shorter than the lease). It also
  refreshes the worker's row in the `workers` table. A job that can no longer be renewed is dropped
  from the worker's list.
- **Recovery:** every worker process runs a recovery loop (default every 5 s) that moves `RUNNING`
  jobs with an expired lease to `RETRY_WAIT`, or to `DEAD` when no attempts are left, and closes the
  attempt as `LEASE_EXPIRED`. It uses `FOR UPDATE SKIP LOCKED`, so several processes can run it at
  the same time without leader election.
- **Late worker protection:** results are written only if `status = 'RUNNING'`,
  `locked_by` and `attempt_count` still match. Rejected writes are counted in
  `stale.result.writes`.

## Alternatives considered

- **No lease, detect dead workers from the `workers` table:** needs a reliable "this worker is dead"
  decision, which is the same timeout problem in a different place.
- **One long lease (e.g. 1 hour) without heartbeat:** jobs of a dead worker wait up to an hour.
- **Short lease without heartbeat:** long jobs are taken away from healthy workers. We tested this
  by disabling the heartbeat: a 3.5 s job with a 2 s lease was recovered, restarted and discarded
  again and again until it ran out of attempts.
- **Leader-elected single recovery process:** avoids duplicate work, but needs ZooKeeper or an
  advisory-lock scheme; SKIP LOCKED already makes concurrent recovery safe.
- **Fencing tokens checked by downstream services:** stronger than the ownership check, but every
  external system would have to support them; out of scope.

## Consequences

- A killed worker's jobs are picked up after at most `lease-seconds + recovery interval`. Demo:
  worker-1 was killed with 4 jobs in hand; worker-2 recovered and completed all of them
  (attempt 2).
- Two heartbeats can be missed before a healthy job is lost (lease 30 s, heartbeat 10 s).
- The ownership check is not a full fencing token: a paused worker can still perform an external
  side effect after losing its lease. Its *result* is rejected, but handlers still need to be
  idempotent.
