package com.yavuzzselimm.taskforge.job;

import org.springframework.data.domain.Page;

import java.util.List;

public record JobPageResponse(
        List<JobSummaryResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    static JobPageResponse from(Page<Job> page) {
        return new JobPageResponse(
                page.map(JobSummaryResponse::from).getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
