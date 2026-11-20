package com.yavuzzselimm.taskforge.job;

import com.yavuzzselimm.taskforge.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JobControllerIntegrationTest {

    private static final String VALID_BODY = """
            {"type":"SEND_ORDER_EMAIL","payload":{"orderId":"ORD-8421"},"priority":5,"maxAttempts":5}
            """;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JobRepository jobRepository;

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