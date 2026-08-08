package com.datawarehouse.datawarehouse.web;

import com.datawarehouse.datawarehouse.domain.IngestionJob;
import com.datawarehouse.datawarehouse.ingestion.streaming.KafkaIngestionJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ingestions/jobs")
public class IngestionJobController {

    private final KafkaIngestionJobService kafkaIngestionJobService;

    @PostMapping("/{provider}/{assetId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestionJob submitJob(
            @PathVariable String provider,
            @PathVariable String assetId
    ) {
        return kafkaIngestionJobService.submit(provider, assetId);
    }

    @GetMapping("/{jobId}")
    public IngestionJob getJob(@PathVariable String jobId) {
        IngestionJob job = kafkaIngestionJobService.findById(jobId);

        if (job == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Ingestion job not found: " + jobId
            );
        }

        return job;
    }

    @GetMapping
    public List<IngestionJob> getRecentJobs(
            @RequestParam(defaultValue = "20") int limit
    ) {
        return kafkaIngestionJobService.findRecent(limit);
    }
}
