package com.yavuzzselimm.taskforge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = "taskforge.worker.enabled=false")
@Import(TestcontainersConfiguration.class)
class TaskforgeApplicationTests {

    @Test
    void contextLoads() {
    }
}