package com.datawarehouse.datawarehouse.service;

import com.datawarehouse.datawarehouse.dal.repository.AssetRepository;
import com.datawarehouse.datawarehouse.dal.repository.DataSourceRepository;
import com.datawarehouse.datawarehouse.dal.repository.TimeSeriesDataRepository;
import com.datawarehouse.datawarehouse.domain.Asset;
import com.datawarehouse.datawarehouse.domain.DataSource;
import com.datawarehouse.datawarehouse.domain.TimeSeriesData;
import com.datawarehouse.datawarehouse.web.dataTransferObject.PagedIdsResponse;
import com.datawarehouse.datawarehouse.web.dataTransferObject.TimeSeriesPointResponse;
import com.datawarehouse.datawarehouse.web.dataTransferObject.TimeSeriesQueryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WarehouseReadService {

    private final AssetRepository assetRepository;
    private final DataSourceRepository dataSourceRepository;
    private final TimeSeriesDataRepository timeSeriesDataRepository;

    public PagedIdsResponse listAssets(int offset, int limit) {
        List<String> items = assetRepository.findAssetIds(offset, limit);
        long total = assetRepository.countAssets();
        boolean hasNext = offset + limit < total;
        return new PagedIdsResponse(items, offset, limit, hasNext);
    }

    public Asset getAsset(String assetId) {
        return assetRepository.findLatestById(assetId);
    }

    public PagedIdsResponse listDataSources(int offset, int limit) {
        List<String> items = dataSourceRepository.findDataSourceIds(offset, limit);
        long total = dataSourceRepository.countDataSources();
        boolean hasNext = offset + limit < total;
        return new PagedIdsResponse(items, offset, limit, hasNext);
    }

    public DataSource getDataSource(String dataSourceId) {
        return dataSourceRepository.findLatestById(dataSourceId);
    }

    public TimeSeriesQueryResponse getTimeSeries(
            String assetId,
            String dataSourceId,
            Instant startBusinessDate,
            Instant endBusinessDate,
            boolean includeAttributes,
            int offset,
            int limit
    ) {
        List<TimeSeriesData> rows = timeSeriesDataRepository.findLatestByBusinessDateRange(
                assetId, dataSourceId, startBusinessDate, endBusinessDate, offset, limit
        );

        List<TimeSeriesPointResponse> data = rows.stream()
                .map(row -> new TimeSeriesPointResponse(
                        row.getBusinessDate(),
                        row.getSystemDate(),
                        row.getPayload()
                ))
                .toList();

        Set<String> attributes = includeAttributes
                ? rows.stream().flatMap(r -> r.getPayload().keySet().stream()).collect(java.util.stream.Collectors.toSet())
                : null;

        boolean hasNext = rows.size() == limit;

        return new TimeSeriesQueryResponse(
                assetId,
                dataSourceId,
                data,
                attributes,
                offset,
                limit,
                hasNext
        );
    }
}
