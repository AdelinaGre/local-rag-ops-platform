package com.datawarehouse.datawarehouse.ingestion.streaming;

import com.datawarehouse.datawarehouse.domain.Asset;
import com.datawarehouse.datawarehouse.domain.DataSource;
import com.datawarehouse.datawarehouse.domain.TimeSeriesData;
import com.datawarehouse.datawarehouse.ingestion.loader.MarketDataLoader;
import com.datawarehouse.datawarehouse.ingestion.model.CanonicalMarketData;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class StreamingMarketDataConsumer {

    private final MarketDataLoader marketDataLoader;

    @KafkaListener(
            topics = "${app.kafka.topics.marketdata-raw}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(RawMarketDataEvent event) {
        Instant systemDate = Instant.now();
        Instant businessDate = event.eventTime();
        Map<String, Object> payload = new LinkedHashMap<>(event.payload());

        Asset asset = new Asset(
                event.assetId(),
                event.symbol(),
                "Streaming asset imported from " + event.provider(),
                event.symbol(),
                "CRYPTO",
                systemDate,
                Map.of(
                        "provider", event.provider(),
                        "dataSourceId", event.dataSourceId(),
                        "streaming", "true"
                )
        );

        DataSource dataSource = new DataSource(
                event.dataSourceId(),
                event.provider(),
                "Streaming market data from " + event.provider(),
                systemDate,
                event.provider(),
                event.dataSourceId(),
                Map.of(
                        "eventType", event.eventType(),
                        "streaming", "true"
                ),
                Set.of("open", "high", "low", "close", "volume", "isClosed")
        );

        String sourceRecordKey = event.provider()
                + "/"
                + event.assetId()
                + "/"
                + event.eventType()
                + "/"
                + businessDate;

        TimeSeriesData point = new TimeSeriesData(
                event.assetId() + "-" + businessDate,
                event.assetId(),
                event.dataSourceId(),
                businessDate,
                systemDate,
                payload,
                false,
                businessDate.atZone(ZoneOffset.UTC).getYear(),
                event.provider(),
                event.dataSourceId(),
                Map.of(
                        "symbol", event.symbol(),
                        "eventType", event.eventType()
                ),
                sha256(payload.toString()),
                sourceRecordKey,
                "streaming"
        );

        marketDataLoader.load(new CanonicalMarketData(
                asset,
                dataSource,
                List.of(point)
        ));
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot compute payload hash", exception);
        }
    }
}
