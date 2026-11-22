package com.yavuzzselimm.taskforge.handler;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class JobHandlerRegistry {

    private final Map<String, JobHandler> handlersByType;

    /** Spring, uygulamadaki tüm JobHandler bean'lerini bu listeye koyar. */
    public JobHandlerRegistry(List<JobHandler> handlers) {
        Map<String, JobHandler> map = new HashMap<>();
        for (JobHandler handler : handlers) {
            JobHandler previous = map.putIfAbsent(handler.jobType(), handler);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate handler for job type " + handler.jobType() + ": "
                                + previous.getClass().getSimpleName() + " and "
                                + handler.getClass().getSimpleName());
            }
        }
        this.handlersByType = Map.copyOf(map);
    }

    public Optional<JobHandler> find(String jobType) {
        return Optional.ofNullable(handlersByType.get(jobType));
    }

    public boolean supports(String jobType) {
        return handlersByType.containsKey(jobType);
    }

    public Set<String> supportedTypes() {
        return new TreeSet<>(handlersByType.keySet());
    }
}