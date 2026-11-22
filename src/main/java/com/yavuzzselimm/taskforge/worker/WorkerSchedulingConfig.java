package com.yavuzzselimm.taskforge.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "taskforge.worker", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class WorkerSchedulingConfig {
}