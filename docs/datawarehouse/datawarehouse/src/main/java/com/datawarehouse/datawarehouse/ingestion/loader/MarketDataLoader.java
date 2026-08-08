package com.datawarehouse.datawarehouse.ingestion.loader;

import com.datawarehouse.datawarehouse.ingestion.model.CanonicalMarketData;
import com.datawarehouse.datawarehouse.ingestion.model.IngestionResult;
// stores asset, data source if needed and   time-series records, return s statistics about ehat happened
// this layer use: AssetRepository, DataSourceRepository, TimeSeriesRepositoiry
public interface MarketDataLoader {
    IngestionResult load(CanonicalMarketData canonicalMarketData); // persist transformed data into the warehouse
}
