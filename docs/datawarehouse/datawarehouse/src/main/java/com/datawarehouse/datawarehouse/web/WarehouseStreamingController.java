package com.datawarehouse.datawarehouse.web;

import com.datawarehouse.datawarehouse.service.WarehouseReadService;
import com.datawarehouse.datawarehouse.web.dataTransferObject.TimeSeriesQueryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.ObjectMapper;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.ZoneOffset;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class WarehouseStreamingController {

    private final WarehouseReadService warehouseReadService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping(value = "/data/stream", produces = "application/x-ndjson")
    public StreamingResponseBody streamData(
            @RequestParam String assetId,
            @RequestParam String dataSourceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startBusinessDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endBusinessDate,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return outputStream -> {
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream));
            int offset = 0;
            boolean hasNext = true;

            while (hasNext) {
                TimeSeriesQueryResponse page = warehouseReadService.getTimeSeries(
                        assetId,
                        dataSourceId,
                        startBusinessDate.atStartOfDay().toInstant(ZoneOffset.UTC),
                        endBusinessDate.atStartOfDay().toInstant(ZoneOffset.UTC),
                        false,
                        offset,
                        limit
                );

                for (var item : page.getData()) {
                    writer.println(objectMapper.writeValueAsString(item));
                    writer.flush();
                }

                hasNext = page.isHasNext();
                offset += limit;
            }
        };
    }
}
