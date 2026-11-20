CREATE TABLE jobs (
                      id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                      job_type         VARCHAR(100) NOT NULL,
                      payload          JSONB        NOT NULL,
                      status           VARCHAR(24)  NOT NULL DEFAULT 'READY',
                      priority         SMALLINT     NOT NULL DEFAULT 0,
                      scheduled_at     TIMESTAMPTZ,
                      attempt_count    INTEGER      NOT NULL DEFAULT 0,
                      max_attempts     INTEGER      NOT NULL DEFAULT 5,
                      available_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
                      locked_by        VARCHAR(120),
                      lease_until      TIMESTAMPTZ,
                      heartbeat_at     TIMESTAMPTZ,
                      idempotency_key  VARCHAR(200),
                      created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
                      updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
                      last_error       TEXT,

                      CONSTRAINT chk_jobs_status CHECK (
                          status IN ('READY', 'RUNNING', 'RETRY_WAIT', 'COMPLETED', 'DEAD', 'CANCELLED')
                          ),
                      CONSTRAINT chk_jobs_attempt_count CHECK (attempt_count >= 0),
                      CONSTRAINT chk_jobs_max_attempts  CHECK (max_attempts > 0),

    -- RUNNING bir işin mutlaka bir sahibi ve lease süresi olmalı
                      CONSTRAINT chk_jobs_running_has_lease CHECK (
                          status <> 'RUNNING' OR (locked_by IS NOT NULL AND lease_until IS NOT NULL)
                          ),

                      CONSTRAINT uq_jobs_idempotency_key UNIQUE (idempotency_key)
);

-- Worker'ın "sıradaki uygun iş" sorgusu için
CREATE INDEX idx_jobs_claimable
    ON jobs (priority DESC, available_at)
    WHERE status IN ('READY', 'RETRY_WAIT');

-- Recovery'nin "lease'i dolmuş RUNNING işler" sorgusu için
CREATE INDEX idx_jobs_running_lease
    ON jobs (lease_until)
    WHERE status = 'RUNNING';