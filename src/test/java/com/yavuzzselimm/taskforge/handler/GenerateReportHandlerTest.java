package com.yavuzzselimm.taskforge.handler;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerateReportHandlerTest {

    private final GenerateReportHandler handler = new GenerateReportHandler(JsonMapper.builder().build());

    @Test
    void missingReportType_isAPermanentFailure() {
        assertThatThrownBy(() -> handler.handle(context("{\"durationSeconds\": 1}")))
                .isInstanceOf(PermanentJobException.class)
                .hasMessageContaining("reportType");
    }

    @Test
    void durationOutOfRange_isAPermanentFailure() {
        assertThatThrownBy(() -> handler.handle(context("{\"reportType\": \"SALES\", \"durationSeconds\": 0}")))
                .isInstanceOf(PermanentJobException.class)
                .hasMessageContaining("durationSeconds");
    }

    @Test
    void validReport_isGenerated() {
        assertThatCode(() -> handler.handle(context("{\"reportType\": \"SALES\", \"durationSeconds\": 1}")))
                .doesNotThrowAnyException();
    }

    private static JobContext context(String payload) {
        return new JobContext(UUID.randomUUID(), "GENERATE_REPORT", payload, 1);
    }
}
