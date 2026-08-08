package com.datawarehouse.datawarehouse.ingestion.loader;

import com.datawarehouse.datawarehouse.dal.partitionKey.AssetKey;
import com.datawarehouse.datawarehouse.dal.partitionKey.DataSourceKey;
import com.datawarehouse.datawarehouse.dal.partitionKey.TimeSeriesPartitionKey;
import com.datawarehouse.datawarehouse.dal.repository.AssetRepository;
import com.datawarehouse.datawarehouse.dal.repository.DataSourceRepository;
import com.datawarehouse.datawarehouse.dal.repository.TimeSeriesDataRepository;
import com.datawarehouse.datawarehouse.domain.Asset;
import com.datawarehouse.datawarehouse.domain.DataSource;
import com.datawarehouse.datawarehouse.domain.TimeSeriesData;
import com.datawarehouse.datawarehouse.ingestion.model.CanonicalMarketData;
import com.datawarehouse.datawarehouse.ingestion.model.IngestionResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultMarketDataLoaderTest {

    @Test
    void loadStoresNewWarehouseEntitiesAndSkipsIdenticalReruns() {
        InMemoryAssetRepository assetRepository = new InMemoryAssetRepository();
        InMemoryDataSourceRepository dataSourceRepository = new InMemoryDataSourceRepository();
        InMemoryTimeSeriesDataRepository timeSeriesRepository = new InMemoryTimeSeriesDataRepository();
        DefaultMarketDataLoader loader = new DefaultMarketDataLoader(assetRepository, dataSourceRepository, timeSeriesRepository);
        CanonicalMarketData canonical = canonicalMarketData(
                timeSeries("A-2026-05-30", "asset-a", "source-a", "2026-05-30T00:00:00Z", Map.of("close", 100.0), "hash-100"),
                timeSeries("A-2026-05-31", "asset-a", "source-a", "2026-05-31T00:00:00Z", Map.of("close", 110.0), "hash-110")
        );

        IngestionResult firstRun = loader.load(canonical);

        assertEquals(2, firstRun.getFetchedRecords());
        assertEquals(4, firstRun.getStoredRecords());
        assertEquals(0, firstRun.getSkippedRecords());
        assertEquals(0, firstRun.getFailedRecords());
        assertEquals(1, assetRepository.saved.size());
        assertEquals(1, dataSourceRepository.saved.size());
        assertEquals(2, timeSeriesRepository.saved.size());

        IngestionResult secondRun = loader.load(canonical);

        assertEquals(0, secondRun.getStoredRecords());
        assertEquals(4, secondRun.getSkippedRecords());
        assertEquals(0, secondRun.getFailedRecords());
        assertEquals(2, timeSeriesRepository.saved.size());
    }

    @Test
    void loadCreatesNewTemporalVersionWhenPayloadHashChanges() {
        InMemoryAssetRepository assetRepository = new InMemoryAssetRepository();
        InMemoryDataSourceRepository dataSourceRepository = new InMemoryDataSourceRepository();
        InMemoryTimeSeriesDataRepository timeSeriesRepository = new InMemoryTimeSeriesDataRepository();
        DefaultMarketDataLoader loader = new DefaultMarketDataLoader(assetRepository, dataSourceRepository, timeSeriesRepository);

        loader.load(canonicalMarketData(
                timeSeries("A-2026-05-31", "asset-a", "source-a", "2026-05-31T00:00:00Z", Map.of("close", 100.0), "hash-100")
        ));

        IngestionResult changedRun = loader.load(canonicalMarketData(
                timeSeries("A-2026-05-31-v2", "asset-a", "source-a", "2026-05-31T00:00:00Z", Map.of("close", 101.0), "hash-101")
        ));

        assertEquals(1, changedRun.getStoredRecords());
        assertEquals(2, changedRun.getSkippedRecords());
        assertEquals(0, changedRun.getFailedRecords());
        assertEquals(2, timeSeriesRepository.saved.size());
    }

    private CanonicalMarketData canonicalMarketData(TimeSeriesData... records) {
        Asset asset = new Asset(
                "asset-a",
                "Asset A",
                "Test asset",
                "A",
                "MARKET",
                Instant.parse("2026-05-31T10:00:00Z"),
                Map.of("provider", "test")
        );
        DataSource dataSource = new DataSource(
                "source-a",
                "Source A",
                "Test source",
                Instant.parse("2026-05-31T10:00:00Z"),
                "TestProvider",
                "TEST/DATASET",
                Map.of("sourceType", "test"),
                Set.of("date", "close")
        );
        return new CanonicalMarketData(asset, dataSource, List.of(records));
    }

    private TimeSeriesData timeSeries(String id, String assetId, String dataSourceId, String businessDate, Map<String, Object> payload, String hash) {
        return new TimeSeriesData(
                id,
                assetId,
                dataSourceId,
                Instant.parse(businessDate),
                Instant.now(),
                new HashMap<>(payload),
                false,
                2026,
                "TestProvider",
                "TEST/DATASET",
                Map.of("code", "A"),
                hash,
                "test/" + id,
                "run-1"
        );
    }

    private static final class InMemoryAssetRepository implements AssetRepository {
        private final List<Asset> saved = new ArrayList<>();

        @Override
        public Asset save(Asset entity) {
            saved.add(entity);
            return entity;
        }

        @Override
        public void delete(Asset entity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAll(AssetKey partitionKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Asset findLatest(AssetKey partitionKey) {
            return saved.stream()
                    .filter(asset -> asset.getId().equals(partitionKey.getId()))
                    .max(Comparator.comparing(Asset::getSystemDate))
                    .orElse(null);
        }

        @Override
        public Iterable<Asset> findAll(AssetKey partitionKey) {
            return saved.stream().filter(asset -> asset.getId().equals(partitionKey.getId())).toList();
        }

        @Override
        public List<String> findAssetIds(int offset, int limit) {
            return saved.stream().map(Asset::getId).distinct().skip(offset).limit(limit).toList();
        }

        @Override
        public long countAssets() {
            return saved.stream().map(Asset::getId).distinct().count();
        }

        @Override
        public Asset findLatestById(String assetId) {
            return findLatest(new AssetKey(assetId));
        }
    }

    private static final class InMemoryDataSourceRepository implements DataSourceRepository {
        private final List<DataSource> saved = new ArrayList<>();

        @Override
        public DataSource save(DataSource entity) {
            saved.add(entity);
            return entity;
        }

        @Override
        public void delete(DataSource entity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAll(DataSourceKey partitionKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DataSource findLatest(DataSourceKey partitionKey) {
            return saved.stream()
                    .filter(dataSource -> dataSource.getId().equals(partitionKey.getId()))
                    .max(Comparator.comparing(DataSource::getSystemDate))
                    .orElse(null);
        }

        @Override
        public Iterable<DataSource> findAll(DataSourceKey partitionKey) {
            return saved.stream().filter(dataSource -> dataSource.getId().equals(partitionKey.getId())).toList();
        }

        @Override
        public List<String> findDataSourceIds(int offset, int limit) {
            return saved.stream().map(DataSource::getId).distinct().skip(offset).limit(limit).toList();
        }

        @Override
        public long countDataSources() {
            return saved.stream().map(DataSource::getId).distinct().count();
        }

        @Override
        public DataSource findLatestById(String dataSourceId) {
            return findLatest(new DataSourceKey(dataSourceId));
        }
    }

    private static final class InMemoryTimeSeriesDataRepository implements TimeSeriesDataRepository {
        private final List<TimeSeriesData> saved = new ArrayList<>();

        @Override
        public TimeSeriesData save(TimeSeriesData entity) {
            saved.add(entity);
            return entity;
        }

        @Override
        public void delete(TimeSeriesData entity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteAll(TimeSeriesPartitionKey partitionKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TimeSeriesData findLatest(TimeSeriesPartitionKey partitionKey) {
            return saved.stream()
                    .filter(record -> matches(partitionKey, record))
                    .max(Comparator.comparing(TimeSeriesData::getSystemDate))
                    .orElse(null);
        }

        @Override
        public Iterable<TimeSeriesData> findAll(TimeSeriesPartitionKey partitionKey) {
            return saved.stream().filter(record -> matches(partitionKey, record)).toList();
        }

        @Override
        public Iterable<TimeSeriesData> findByBusinessDateRange(TimeSeriesPartitionKey key, Instant from, Instant to) {
            return saved.stream()
                    .filter(record -> matches(key, record))
                    .filter(record -> !record.getBusinessDate().isBefore(from) && !record.getBusinessDate().isAfter(to))
                    .toList();
        }

        @Override
        public Iterable<TimeSeriesData> findByBusinessDate(TimeSeriesPartitionKey key, Instant businessDate) {
            return saved.stream()
                    .filter(record -> matches(key, record))
                    .filter(record -> record.getBusinessDate().equals(businessDate))
                    .toList();
        }

        @Override
        public List<TimeSeriesData> findLatestByBusinessDateRange(String assetId, String dataSourceId, Instant startBusinessDate, Instant endBusinessDate, int offset, int limit) {
            return saved.stream()
                    .filter(record -> record.getAssetId().equals(assetId))
                    .filter(record -> record.getDataSourceId().equals(dataSourceId))
                    .filter(record -> !record.getBusinessDate().isBefore(startBusinessDate) && record.getBusinessDate().isBefore(endBusinessDate))
                    .skip(offset)
                    .limit(limit)
                    .toList();
        }

        @Override
        public TimeSeriesData findLatestByBusinessDate(TimeSeriesPartitionKey key, Instant businessDate) {
            return saved.stream()
                    .filter(record -> matches(key, record))
                    .filter(record -> record.getBusinessDate().equals(businessDate))
                    .max(Comparator.comparing(TimeSeriesData::getSystemDate))
                    .orElse(null);
        }

        @Override
        public TimeSeriesData markDeleted(TimeSeriesPartitionKey key, Instant businessDate) {
            TimeSeriesData marker = new TimeSeriesData(
                    key.getAssetId() + "-" + businessDate + "-deleted",
                    key.getAssetId(),
                    key.getDataSourceId(),
                    businessDate,
                    Instant.now(),
                    Map.of(),
                    true,
                    key.getBusinessYear(),
                    null,
                    null,
                    Map.of("operation", "delete-marker")
            );
            return save(marker);
        }

        private boolean matches(TimeSeriesPartitionKey key, TimeSeriesData record) {
            return record.getAssetId().equals(key.getAssetId())
                    && record.getDataSourceId().equals(key.getDataSourceId())
                    && (key.getBusinessYear() == null || key.getBusinessYear().equals(record.getBusinessYear()));
        }
    }
}
