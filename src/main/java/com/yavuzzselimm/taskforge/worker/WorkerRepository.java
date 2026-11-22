package com.yavuzzselimm.taskforge.worker;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WorkerRepository {

    private final JdbcClient jdbc;

    public WorkerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Aynı id ile yeniden başlayan worker (ör. worker-1 restart) eski kaydın üzerine yazar
    public void register(String workerId, int capacity) {
        jdbc.sql("""
                        INSERT INTO workers (worker_id, status, capacity)
                        VALUES (:workerId, 'RUNNING', :capacity)
                        ON CONFLICT (worker_id) DO UPDATE
                        SET status            = 'RUNNING',
                            capacity          = EXCLUDED.capacity,
                            started_at        = now(),
                            last_heartbeat_at = now()
                        """)
                .param("workerId", workerId)
                .param("capacity", capacity)
                .update();
    }

    public void heartbeat(String workerId) {
        jdbc.sql("UPDATE workers SET last_heartbeat_at = now() WHERE worker_id = :workerId")
                .param("workerId", workerId)
                .update();
    }

    public void markStopped(String workerId) {
        jdbc.sql("""
                        UPDATE workers
                        SET status = 'STOPPED', last_heartbeat_at = now()
                        WHERE worker_id = :workerId
                        """)
                .param("workerId", workerId)
                .update();
    }
}
