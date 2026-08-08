package com.datawarehouse.datawarehouse.web;

import com.datawarehouse.datawarehouse.dal.partitionKey.TimeSeriesPartitionKey;
import com.datawarehouse.datawarehouse.dal.repository.TimeSeriesDataRepository;
import com.datawarehouse.datawarehouse.domain.TimeSeriesData;
import com.datawarehouse.datawarehouse.ingestion.MarketDataIngestionService;
import com.datawarehouse.datawarehouse.ingestion.model.IngestionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class WarehouseWriteController {
    private final MarketDataIngestionService marketDataIngestionService;
    private final TimeSeriesDataRepository timeSeriesDataRepository;

    @PostMapping("/ingestions/{assetId}")
    public IngestionResult ingestAsset(@PathVariable String assetId) {
        return marketDataIngestionService.ingest(assetId);
    }

    @PostMapping("/ingestions/{provider}/{assetId}")
    public IngestionResult ingestAsset(
            @PathVariable String provider,
            @PathVariable String assetId
    ) {
        return marketDataIngestionService.ingest(provider, assetId);
    }

    @PostMapping("/data/deletions")
    public TimeSeriesData markTimeSeriesDeleted(
            @RequestParam String assetId,
            @RequestParam String dataSourceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate
    ) {
        return timeSeriesDataRepository.markDeleted(
                new TimeSeriesPartitionKey(
                        assetId,
                        dataSourceId,
                        businessDate.getYear()
                ),
                businessDate.atStartOfDay().toInstant(ZoneOffset.UTC)
        );
    }
}
