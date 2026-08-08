package com.datawarehouse.datawarehouse.ingestion.extractor;

import com.datawarehouse.datawarehouse.ingestion.model.RawMarketDataPage;
// interfaces from extract just fetches raw external data
public interface MarketDataExtractor {
    String providerId();

    RawMarketDataPage fetchFirstPage(String assetIdentifier); //start ingestion for one asset idrntifier and return the first raw page from provider
    RawMarketDataPage fetchNextPage(String assetIdentifier, String nextCursor); // loads the next page using token/cursor returned by previous page
    // used when the provider API is paginated
}
