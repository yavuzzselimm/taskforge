-- Çalışan worker'ların kaydı. kill -9 ile ölen worker STOPPED yazamaz; onu eskimiş
-- last_heartbeat_at'ten anlarız.
CREATE TABLE workers (
    worker_id          VARCHAR(120) PRIMARY KEY,
    status             VARCHAR(16)  NOT NULL,
    capacity           INTEGER      NOT NULL,
    started_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_heartbeat_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_workers_status   CHECK (status IN ('RUNNING', 'STOPPED')),
    CONSTRAINT chk_workers_capacity CHECK (capacity > 0)
);
