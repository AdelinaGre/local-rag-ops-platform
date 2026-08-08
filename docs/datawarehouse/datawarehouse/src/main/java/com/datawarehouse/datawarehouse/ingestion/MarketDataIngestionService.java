package com.datawarehouse.datawarehouse.ingestion;

import com.datawarehouse.datawarehouse.ingestion.extractor.MarketDataExtractor;
import com.datawarehouse.datawarehouse.ingestion.loader.MarketDataLoader;
import com.datawarehouse.datawarehouse.ingestion.model.CanonicalMarketData;
import com.datawarehouse.datawarehouse.ingestion.model.IngestionResult;
import com.datawarehouse.datawarehouse.ingestion.model.RawMarketDataPage;
import com.datawarehouse.datawarehouse.ingestion.transformer.MarketDataTransformer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MarketDataIngestionService {

    private final List<MarketDataExtractor> extractors;
    private final MarketDataTransformer transformer;
    private final MarketDataLoader loader;

    public IngestionResult ingest(String assetIdentifier) {
        return ingest("nasdaq", assetIdentifier);
    }

    public IngestionResult ingest(String provider, String assetIdentifier) {
        MarketDataExtractor extractor = findExtractor(provider);
        IngestionResult totalResult = new IngestionResult(0, 0, 0, 0, 0, "Ingestion started");

        RawMarketDataPage currentPage = extractor.fetchFirstPage(assetIdentifier);

        while (currentPage != null) {
            int fetchedCount = currentPage.getRecords() != null ? currentPage.getRecords().size() : 0;
            totalResult.setFetchedRecords(totalResult.getFetchedRecords() + fetchedCount);

            CanonicalMarketData canonicalMarketData = transformer.transform(assetIdentifier, currentPage);

            int transformedCount = canonicalMarketData.getTimeSeriesRecords() != null
                    ? canonicalMarketData.getTimeSeriesRecords().size()
                    : 0;
            totalResult.setTransformedRecords(totalResult.getTransformedRecords() + transformedCount);

            IngestionResult pageResult = loader.load(canonicalMarketData);

            totalResult.setStoredRecords(totalResult.getStoredRecords() + pageResult.getStoredRecords());
            totalResult.setSkippedRecords(totalResult.getSkippedRecords() + pageResult.getSkippedRecords());
            totalResult.setFailedRecords(totalResult.getFailedRecords() + pageResult.getFailedRecords());

            if (!currentPage.isHasNextPage()) {
                break;
            }

            currentPage = extractor.fetchNextPage(assetIdentifier, currentPage.getNextCursor());
        }

        totalResult.setMessage("Ingestion completed");
        return totalResult;
    }

    private MarketDataExtractor findExtractor(String provider) {
        return extractors.stream()
                .filter(extractor -> extractor.providerId().equalsIgnoreCase(provider))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown market data provider: " + provider));
    }
}
