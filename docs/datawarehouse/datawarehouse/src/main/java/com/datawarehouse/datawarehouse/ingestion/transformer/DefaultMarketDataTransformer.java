package com.datawarehouse.datawarehouse.ingestion.transformer;

import com.datawarehouse.datawarehouse.domain.Asset;
import com.datawarehouse.datawarehouse.domain.DataSource;
import com.datawarehouse.datawarehouse.domain.TimeSeriesData;
import com.datawarehouse.datawarehouse.ingestion.model.CanonicalMarketData;
import com.datawarehouse.datawarehouse.ingestion.model.RawMarketDataPage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Component
public class DefaultMarketDataTransformer implements MarketDataTransformer {
    private static final ObjectMapper HASH_MAPPER = new ObjectMapper();


    @Override
    public CanonicalMarketData transform(String assetIdentifier, RawMarketDataPage rawPage) {
        Instant now = Instant.now();
        String dataSourceId = rawPage.getDataset(); // ex: QDL/BITFINEX
        String assetId = dataSourceId + "/" + assetIdentifier; // ex: QDL/BITFINEX/ZRXUSD
        String ingestionRunId = UUID.randomUUID().toString();

        //colecteaz toate numele de coloane din recordurile brute (este folosit pentru a descrie metadatele sursei de date)
        Set<String> attributeNames = rawPage.getRecords().stream()
                .flatMap(record -> record.keySet().stream())
                .collect(Collectors.toSet());

        Asset asset = new Asset( // creeaza obiectul asset, acesta va fi salvat separat in baza de date
                assetId,
                assetIdentifier,
                "Asset imported from " + rawPage.getProvider(),
                assetIdentifier,
                resolveAssetType(rawPage),
                now,
                Map.of("provider", rawPage.getProvider(), "dataSourceId", dataSourceId)
        );

        DataSource dataSource = new DataSource( // pentru a descrie surse din care au prevenit datele
                dataSourceId,
                rawPage.getProvider(),
                "Imported from " + rawPage.getProvider() + " API",
                now,
                rawPage.getProvider(),
                rawPage.getDataset(),
                Map.of("sourceType", "rest-api"),
                attributeNames
        );
        //fiecare record brut este transformat intr-un obiect TimeSeriesData
        List<TimeSeriesData> timeSeriesRecords = rawPage.getRecords().stream()
                .map(record -> safelyCreateRecord(assetId, assetIdentifier, dataSource, rawPage, record, now, ingestionRunId))
                .filter(java.util.Objects::nonNull) // in caz ca un record e invalid este returnat null si trece la urmatorul
                .toList();

        return new CanonicalMarketData(asset, dataSource, timeSeriesRecords); // clasa produce un obiect intern unificat gata de trimis la loader
    }

    private TimeSeriesData safelyCreateRecord(
            String assetId,
            String assetSymbol,
            DataSource dataSource,
            RawMarketDataPage rawPage,
            Map<String, Object> record,
            Instant now,
            String ingestionRunId
    ) {
        try {
            return toTimeSeriesRecord(assetId, assetSymbol, dataSource, rawPage, record, now, ingestionRunId);
        } catch (Exception ex) {
            return null; // ca sa nu pice ingestia pentru un record defect
        }
    }

    private TimeSeriesData toTimeSeriesRecord(
            String assetId,
            String assetSymbol,
            DataSource dataSource,
            RawMarketDataPage rawPage,
            Map<String, Object> record,
            Instant now,
            String ingestionRunId
    ) {
        //cauta businessDate, si transforma in Instant la inceputul zilei UTC
        String businessDateRaw = findBusinessDateValue(record);
        Instant businessDate = LocalDate.parse(businessDateRaw).atStartOfDay().toInstant(ZoneOffset.UTC);


        // sunt luate toate campurile din record
        Map<String, Object> payload = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            String normalizedKey = normalizeKey(entry.getKey()); // dar normalizeaza numele cheii
            if (!normalizedKey.equals("date") && !normalizedKey.equals("code")) { // si exclude date, code
                payload.put(normalizedKey, entry.getValue());
            }
        }

        String payloadHash = hashPayload(payload);
        String sourceRecordKey = rawPage.getProvider()
                + "/" + rawPage.getDataset()
                + "/" + assetSymbol
                + "/" + businessDateRaw;

        // date este folosit ca businessDate, code este folosit ca id al assetului
        // si restul este payload, adic adate efective ale seriei
        // si se creeaza TimeSeriesData
        return new TimeSeriesData(
                assetId + "-" + businessDate,
                assetId,
                dataSource.getId(),
                businessDate,
                now,
                payload,
                false,
                businessDate.atZone(ZoneOffset.UTC).getYear(),
                rawPage.getProvider(),
                rawPage.getDataset(),
                Map.of("code", assetSymbol),
                payloadHash,
                sourceRecordKey,
                ingestionRunId
        );
    }

    private String findBusinessDateValue(Map<String, Object> record) {
        for (String key : record.keySet()) {
            String normalized = normalizeKey(key);
            if (normalized.equals("date")) {
                Object value = record.get(key);
                if (value != null) {
                    return value.toString();
                }
            }
        }
        throw new IllegalArgumentException("No date column found");
    }

    private String normalizeKey(String key) {
        return key.trim().toLowerCase(Locale.ROOT).replace(" ", "_");
    }

    private String resolveAssetType(RawMarketDataPage rawPage) {
        String provider = String.valueOf(rawPage.getProvider()).toLowerCase(Locale.ROOT);
        String dataset = String.valueOf(rawPage.getDataset()).toLowerCase(Locale.ROOT);

        if (dataset.contains("bitfinex") || dataset.contains("crypto") || provider.contains("crypto")) {
            return "CRYPTO";
        }
        if (provider.contains("alpha") || dataset.contains("stock") || dataset.contains("equity")) {
            return "STOCK";
        }
        return "MARKET";
    }

    private String hashPayload(Map<String, Object> payload) {
        try {
            String canonicalJson = HASH_MAPPER.writeValueAsString(new TreeMap<>(payload));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not compute payload hash", ex);
        }
    }
}
