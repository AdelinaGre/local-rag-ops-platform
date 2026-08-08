package com.datawarehouse.datawarehouse.web;

import com.datawarehouse.datawarehouse.domain.Asset;
import com.datawarehouse.datawarehouse.domain.DataSource;
import com.datawarehouse.datawarehouse.service.WarehouseReadService;
import com.datawarehouse.datawarehouse.web.dataTransferObject.PagedIdsResponse;
import com.datawarehouse.datawarehouse.web.dataTransferObject.TimeSeriesQueryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneOffset;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class WarehouseReadController {

    private final WarehouseReadService warehouseReadService;

    @GetMapping("/assets")
    public PagedIdsResponse getAssets(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return warehouseReadService.listAssets(offset, limit);
    }

    @GetMapping("/assets/{*assetId}")
    public Asset getAsset(@PathVariable String assetId) {
        return warehouseReadService.getAsset(stripLeadingSlash(assetId));
    }

    @GetMapping("/data-sources")
    public PagedIdsResponse getDataSources(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return warehouseReadService.listDataSources(offset, limit);
    }

    @GetMapping("/data-sources/{*dataSourceId}")
    public DataSource getDataSource(@PathVariable String dataSourceId) {
        return warehouseReadService.getDataSource(stripLeadingSlash(dataSourceId));
    }

    @GetMapping("/data")
    public TimeSeriesQueryResponse getData(
            @RequestParam String assetId,
            @RequestParam String dataSourceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startBusinessDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endBusinessDate,
            @RequestParam(defaultValue = "false") boolean includeAttributes,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return warehouseReadService.getTimeSeries(
                assetId,
                dataSourceId,
                startBusinessDate.atStartOfDay().toInstant(ZoneOffset.UTC),
                endBusinessDate.atStartOfDay().toInstant(ZoneOffset.UTC),
                includeAttributes,
                offset,
                limit
        );
    }

    private String stripLeadingSlash(String value) {
        return value != null && value.startsWith("/") ? value.substring(1) : value;
    }
}
