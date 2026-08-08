package com.datawarehouse.datawarehouse.ingestion.transformer;

import com.datawarehouse.datawarehouse.domain.TimeSeriesData;
import com.datawarehouse.datawarehouse.ingestion.model.CanonicalMarketData;
import com.datawarehouse.datawarehouse.ingestion.model.RawMarketDataPage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultMarketDataTransformerTest {

    private final DefaultMarketDataTransformer transformer = new DefaultMarketDataTransformer();

    @Test
    void transformsRawRowsIntoCanonicalWarehouseRecords() {
        RawMarketDataPage page = new RawMarketDataPage(
                List.of(Map.of(
                        "Date", "2026-05-31",
                        "Open", 100.25,
                        "Close", 105.75,
                        "Volume", 12500,
                        "Code", "IBM"
                )),
                null,
                false,
                "AlphaVantage",
                "ALPHAVANTAGE/TIME_SERIES_DAILY"
        );

        CanonicalMarketData canonical = transformer.transform("IBM", page);

        assertEquals("ALPHAVANTAGE/TIME_SERIES_DAILY/IBM", canonical.getAsset().getId());
        assertEquals("IBM", canonical.getAsset().getSymbol());
        assertEquals("STOCK", canonical.getAsset().getAssetType());
        assertEquals("ALPHAVANTAGE/TIME_SERIES_DAILY", canonical.getDataSource().getId());
        assertTrue(canonical.getDataSource().getAttributes().contains("Date"));

        assertEquals(1, canonical.getTimeSeriesRecords().size());
        TimeSeriesData record = canonical.getTimeSeriesRecords().getFirst();
        assertEquals("ALPHAVANTAGE/TIME_SERIES_DAILY/IBM", record.getAssetId());
        assertEquals("ALPHAVANTAGE/TIME_SERIES_DAILY", record.getDataSourceId());
        assertEquals(Instant.parse("2026-05-31T00:00:00Z"), record.getBusinessDate());
        assertEquals(2026, record.getBusinessYear());
        assertEquals("AlphaVantage", record.getProvider());
        assertEquals("AlphaVantage/ALPHAVANTAGE/TIME_SERIES_DAILY/IBM/2026-05-31", record.getSourceRecordKey());
        assertNotNull(record.getIngestionRunId());
        assertNotNull(record.getPayloadHash());
        assertEquals(100.25, record.getPayload().get("open"));
        assertEquals(105.75, record.getPayload().get("close"));
        assertFalse(record.getPayload().containsKey("date"));
        assertFalse(record.getPayload().containsKey("code"));
    }

    @Test
    void skipsInvalidRowsWithoutFailingTheWholePage() {
        RawMarketDataPage page = new RawMarketDataPage(
                List.of(
                        Map.of("Close", 99.5),
                        Map.of("Date", "2026-05-30", "Close", 101.0)
                ),
                null,
                false,
                "NasdaqDataLink",
                "QDL/BITFINEX"
        );

        CanonicalMarketData canonical = transformer.transform("ZRXUSD", page);

        assertEquals(1, canonical.getTimeSeriesRecords().size());
        assertEquals(Instant.parse("2026-05-30T00:00:00Z"), canonical.getTimeSeriesRecords().getFirst().getBusinessDate());
    }
}
