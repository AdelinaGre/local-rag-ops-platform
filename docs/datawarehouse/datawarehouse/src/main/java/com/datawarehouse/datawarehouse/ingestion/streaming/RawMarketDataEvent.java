package com.datawarehouse.datawarehouse.ingestion.streaming;

import java.time.Instant;
import java.util.Map;

public record RawMarketDataEvent (String provider,
                                 String dataSourceId,
                                 String assetId,
                                 String symbol,
                                 String eventType,
                                 Instant eventTime,
                                 Instant receivedAt,
                                 Map<String, Object> payload){

}
