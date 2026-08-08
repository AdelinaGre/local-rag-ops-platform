package com.datawarehouse.datawarehouse.web;

import com.datawarehouse.datawarehouse.domain.AnalyticsPricePrediction;
import com.datawarehouse.datawarehouse.domain.AnalyticsYearlySummary;
import com.datawarehouse.datawarehouse.service.AnalyticsReadService;
import com.datawarehouse.datawarehouse.service.SparkAnalyticsRunService;
import com.datawarehouse.datawarehouse.web.dataTransferObject.SparkJobStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsReadController {

    private final AnalyticsReadService analyticsReadService;
    private final SparkAnalyticsRunService sparkAnalyticsRunService;

    @GetMapping("/yearly-summaries")
    public List<AnalyticsYearlySummary> getYearlySummaries(
            @RequestParam(required = false) String assetId,
            @RequestParam(required = false) String dataSourceId,
            @RequestParam(required = false) Integer businessYear
    ) {
        return analyticsReadService.getYearlySummaries(assetId, dataSourceId, businessYear);
    }

    @GetMapping("/predictions")
    public List<AnalyticsPricePrediction> getPricePredictions(
            @RequestParam(required = false) String assetId,
            @RequestParam(required = false) String dataSourceId
    ) {
        return analyticsReadService.getPricePredictions(assetId, dataSourceId);
    }

    @GetMapping("/jobs")
    public List<SparkJobStatusResponse> getSparkJobs() {
        return analyticsReadService.getSparkJobs();
    }

    @PostMapping("/run/yearly-summaries")
    public SparkJobStatusResponse runYearlySummaries(
            @RequestParam(required = false) String assetId
    ) {
        return sparkAnalyticsRunService.runYearlySummaries(assetId);
    }

    @PostMapping("/run/price-regression")
    public SparkJobStatusResponse runPriceRegression(
            @RequestParam(required = false) String assetId
    ) {
        return sparkAnalyticsRunService.runPriceRegression(assetId);
    }

    @PostMapping("/run/all")
    public List<SparkJobStatusResponse> runAll(
            @RequestParam(required = false) String assetId
    ) {
        return sparkAnalyticsRunService.runAll(assetId);
    }
}
