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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Objects;


@Component
@RequiredArgsConstructor
public class DefaultMarketDataLoader implements MarketDataLoader{
    // p/u salvare/verificare existentei unui Asset,DataSource sau TimeSeriesData
    private final AssetRepository assetRepository;
    private final DataSourceRepository dataSourceRepository;
    private final TimeSeriesDataRepository timeSeriesDataRepository;


    @Override
    public IngestionResult load(CanonicalMarketData canonicalMarketData) {
        int stored=0; // initializare statistica
        int skipped =0;
        int failed=0;
            // AssetKey, DatasourceKey sunt chei de cuatare/partitionare folosite ca sa identifice daca un obiect exista deja.
        try{ // salveaza versiuni noi doar cand apar date noi sau metadata diferita
            Asset latestAsset = assetRepository.findLatest(new AssetKey(canonicalMarketData.getAsset().getId()));
            if (latestAsset == null || latestAsset.isDeleted() || !isSameAsset(latestAsset, canonicalMarketData.getAsset())) {
                assetRepository.save(canonicalMarketData.getAsset());
                stored++;
            }else{
                skipped++;
            }

            DataSource latestDataSource = dataSourceRepository.findLatest(new DataSourceKey(canonicalMarketData.getDataSource().getId()));
            if (latestDataSource == null || latestDataSource.isDeleted() || !isSameDataSource(latestDataSource, canonicalMarketData.getDataSource())) {
                dataSourceRepository.save(canonicalMarketData.getDataSource());
                stored++;
            } else {
                skipped++;
            }

            for (TimeSeriesData record : canonicalMarketData.getTimeSeriesRecords()) {
                TimeSeriesPartitionKey key = new TimeSeriesPartitionKey(
                        record.getAssetId(),
                        record.getDataSourceId(),
                        record.getBusinessYear()
                );

                TimeSeriesData latestExistingRecord =
                        timeSeriesDataRepository.findLatestByBusinessDate(key, record.getBusinessDate());

                if (latestExistingRecord != null && isSamePayload(latestExistingRecord, record)) {
                    skipped++;
                    continue;
                }

                timeSeriesDataRepository.save(record);
                stored++;
            }
        }catch(Exception e){
            failed++;
        }


        return new IngestionResult(
                canonicalMarketData.getTimeSeriesRecords().size(),
                canonicalMarketData.getTimeSeriesRecords().size(),
                stored,
                skipped,
                failed,
                "Load completed"
        );
    }

    private boolean isSameAsset(Asset existing, Asset incoming) {
        return Objects.equals(existing.getName(), incoming.getName())
                && Objects.equals(existing.getDescription(), incoming.getDescription())
                && Objects.equals(existing.getSymbol(), incoming.getSymbol())
                && Objects.equals(existing.getAssetType(), incoming.getAssetType())
                && Objects.equals(existing.getAttributes(), incoming.getAttributes());
    }

    private boolean isSameDataSource(DataSource existing, DataSource incoming) {
        return Objects.equals(existing.getName(), incoming.getName())
                && Objects.equals(existing.getDescription(), incoming.getDescription())
                && Objects.equals(existing.getProvider(), incoming.getProvider())
                && Objects.equals(existing.getDataset(), incoming.getDataset())
                && Objects.equals(existing.getRequestContext(), incoming.getRequestContext())
                && Objects.equals(existing.getAttributes(), incoming.getAttributes());
    }

    private boolean isSamePayload(TimeSeriesData existingRecord, TimeSeriesData incomingRecord) {
        if (Objects.equals(existingRecord.getPayloadHash(), incomingRecord.getPayloadHash())) {
            return true;
        }

        // Compatibil cu recordurile vechi din Mongo care nu au inca payloadHash.
        if (existingRecord.getPayloadHash() == null) {
            return Objects.equals(existingRecord.getPayload(), incomingRecord.getPayload());
        }

        return false;
    }

}
