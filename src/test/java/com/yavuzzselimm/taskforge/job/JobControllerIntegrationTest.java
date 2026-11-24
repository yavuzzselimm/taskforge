package com.yavuzzselimm.taskforge.job;

import com.yavuzzselimm.taskforge.TestcontainersConfiguration;
import com.yavuzzselimm.taskforge.worker.JobClaimRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "taskforge.worker.enabled=false")
@Import(TestcontainersConfiguration.class)
class JobControllerIntegrationTest {

    private static final String VALID_BODY = """
            {"type":"SEND_ORDER_EMAIL","payload":{"orderId":"ORD-8421"},"priority":5,"maxAttempts":5}
            """;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobClaimRepository claimRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jobRepository.deleteAll();
    }

    @Test
    void createJob_returns202WithLocationAndReadyStatus() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", startsWith("/api/v1/jobs/")))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.jobId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("READY"));

        assertThat(jobRepository.count()).isEqualTo(1);
    }

    @Test
    void getJob_returnsStoredJobWithPayloadAsJson() throws Exception {
        String location = createJob(null);

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("SEND_ORDER_EMAIL"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.payload.orderId").value("ORD-8421"))
                .andExpect(jsonPath("$.priority").value(5))
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andExpect(jsonPath("$.maxAttempts").value(5));
    }

    @Test
    void sameIdempotencyKey_returnsSameJobWithoutCreatingAnother() throws Exception {
        String firstLocation = createJob("order-email-ORD-8421");

        String secondLocation = mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "order-email-ORD-8421")
                        .content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getHeader("Location");

        assertThat(secondLocation).isEqualTo(firstLocation);
        assertThat(jobRepository.count()).isEqualTo(1);
    }

    @Test
    void invalidType_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"send email","payload":{}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.type").exists());

        assertThat(jobRepository.count()).isZero();
    }

    @Test
    void missingPayload_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"SEND_ORDER_EMAIL"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.payload").exists());
    }

    @Test
    void payloadThatIsNotAnObject_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"SEND_ORDER_EMAIL","payload":"not-an-object"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("payload must be a JSON object"));
    }

    @Test
    void unknownJob_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/jobs/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Job not found"));
    }

    @Test
    void malformedJobId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/jobs/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedJobType_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"MAKE_COFFEE","payload":{}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", startsWith("Unsupported job type: MAKE_COFFEE")));

        assertThat(jobRepository.count()).isZero();
    }

    @Test
    void attempts_listsEveryAttemptInOrder() throws Exception {
        String location = createJob(null);

        var first = claimRepository.claimNext("worker-a", 30).orElseThrow();
        claimRepository.markRetry(first.id(), "worker-a", first.attempt(), "smtp timeout", Duration.ZERO);
        claimRepository.claimNext("worker-b", 30).orElseThrow();

        mockMvc.perform(get(location + "/attempts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].attemptNo").value(1))
                .andExpect(jsonPath("$[0].workerId").value("worker-a"))
                .andExpect(jsonPath("$[0].outcome").value("TRANSIENT_FAILURE"))
                .andExpect(jsonPath("$[0].errorSummary").value("smtp timeout"))
                .andExpect(jsonPath("$[0].finishedAt").isNotEmpty())
                .andExpect(jsonPath("$[1].attemptNo").value(2))
                .andExpect(jsonPath("$[1].workerId").value("worker-b"))
                .andExpect(jsonPath("$[1].outcome").doesNotExist())
                .andExpect(jsonPath("$[1].finishedAt").doesNotExist());
    }

    @Test
    void attempts_ofNewJobIsEmpty() throws Exception {
        String location = createJob(null);

        mockMvc.perform(get(location + "/attempts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void attempts_ofUnknownJobReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/jobs/00000000-0000-0000-0000-000000000000/attempts"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancel_readyJobIsCancelledAndNeverClaimed() throws Exception {
        String location = createJob(null);

        mockMvc.perform(post(location + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(claimRepository.claimNext("worker-a", 30)).isEmpty();
    }

    @Test
    void cancel_retryWaitJobIsCancelled() throws Exception {
        String location = createJob(null);
        var job = claimRepository.claimNext("worker-a", 30).orElseThrow();
        claimRepository.markRetry(job.id(), "worker-a", job.attempt(), "boom", Duration.ofMinutes(5));

        mockMvc.perform(post(location + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancel_twiceIsHarmless() throws Exception {
        String location = createJob(null);

        mockMvc.perform(post(location + "/cancel")).andExpect(status().isOk());
        mockMvc.perform(post(location + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancel_runningJobReturns409AndKeepsRunning() throws Exception {
        String location = createJob(null);
        claimRepository.claimNext("worker-a", 30).orElseThrow();

        mockMvc.perform(post(location + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Job cannot be cancelled"))
                .andExpect(jsonPath("$.status").value("RUNNING"));

        mockMvc.perform(get(location)).andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void cancel_completedJobReturns409() throws Exception {
        String location = createJob(null);
        var job = claimRepository.claimNext("worker-a", 30).orElseThrow();
        claimRepository.markCompleted(job.id(), "worker-a", job.attempt());

        mockMvc.perform(post(location + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void cancel_unknownJobReturns404() throws Exception {
        mockMvc.perform(post("/api/v1/jobs/00000000-0000-0000-0000-000000000000/cancel"))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_returnsNewestFirstWithoutPayload() throws Exception {
        createJobOfType("SEND_ORDER_EMAIL");
        createJobOfType("GENERATE_REPORT");

        mockMvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].type").value("GENERATE_REPORT"))
                .andExpect(jsonPath("$.items[1].type").value("SEND_ORDER_EMAIL"))
                .andExpect(jsonPath("$.items[0].payload").doesNotExist());
    }

    @Test
    void list_filtersByStatusAndType() throws Exception {
        String toCancel = createJobOfType("SEND_ORDER_EMAIL");
        createJobOfType("SEND_ORDER_EMAIL");
        createJobOfType("SEND_ORDER_EMAIL");
        createJobOfType("GENERATE_REPORT");
        mockMvc.perform(post(toCancel + "/cancel")).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/jobs").param("status", "READY").param("type", "SEND_ORDER_EMAIL"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/jobs").param("status", "CANCELLED"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].status").value("CANCELLED"));
        mockMvc.perform(get("/api/v1/jobs").param("status", "READY", "CANCELLED"))
                .andExpect(jsonPath("$.totalElements").value(4));
    }

    @Test
    void list_paginates() throws Exception {
        for (int i = 0; i < 5; i++) {
            createJobOfType("SEND_ORDER_EMAIL");
        }

        mockMvc.perform(get("/api/v1/jobs").param("size", "2").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    void list_rejectsBadParameters() throws Exception {
        mockMvc.perform(get("/api/v1/jobs").param("size", "0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/jobs").param("size", "101")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/jobs").param("page", "-1")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/jobs").param("status", "WAITING")).andExpect(status().isBadRequest());
    }

    private String createJobOfType(String type) throws Exception {
        return mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"" + type + "\",\"payload\":{}}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getHeader("Location");
    }

    /** İş oluşturur ve Location başlığını döndürür. */
    private String createJob(String idempotencyKey) throws Exception {
        var request = post("/api/v1/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getHeader("Location");
    }
}