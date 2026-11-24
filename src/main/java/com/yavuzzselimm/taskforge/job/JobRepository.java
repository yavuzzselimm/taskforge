package com.yavuzzselimm.taskforge.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    Optional<Job> findByIdempotencyKey(String idempotencyKey);

    // Kontrol ve yazma tek ifadede: worker işi araya girip claim ettiyse 0 döner.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Job j SET j.status = :cancelled, j.updatedAt = :now
            WHERE j.id = :id AND j.status IN :cancellable
            """)
    int cancelIfWaiting(@Param("id") UUID id,
                        @Param("cancellable") Collection<JobStatus> cancellable,
                        @Param("cancelled") JobStatus cancelled,
                        @Param("now") Instant now);
}
