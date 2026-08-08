package com.datawarehouse.datawarehouse.ingestion.transformer;

import com.datawarehouse.datawarehouse.ingestion.model.CanonicalMarketData;
import com.datawarehouse.datawarehouse.ingestion.model.RawMarketDataPage;
// interface converts raw provided data into your canonical internal warehouse model
public interface MarketDataTransformer {
    CanonicalMarketData transform (String assetIdentifier, RawMarketDataPage rawPage); // takes one raw page from the provider and normalize it
// and creates internal objects: Asset, DataSource, TimeSeriesData
}
