package com.datawarehouse.datawarehouse.web.dataTransferObject;

import java.time.Instant;

public record SparkJobStatusResponse(
        String jobId,
        String name,
        String type,
        String status,
        Instant startedAt,
        Instant completedAt,
        Long recordsProcessed,
        String outputCollection,
        String error
) {
}
