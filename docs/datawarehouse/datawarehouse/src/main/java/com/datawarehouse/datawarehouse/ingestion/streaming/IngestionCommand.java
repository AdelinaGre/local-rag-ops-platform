package com.datawarehouse.datawarehouse.ingestion.streaming;

import java.time.Instant;

public record IngestionCommand(
    String jobId,
    String provider,
    String assetId,
    Instant requestedAt
) {}
