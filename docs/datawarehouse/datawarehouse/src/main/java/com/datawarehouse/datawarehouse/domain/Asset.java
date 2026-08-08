package com.datawarehouse.datawarehouse.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "assets")
public class Asset {
    @Id
    private String documentId = UUID.randomUUID().toString();
    private String id;
    private String name;
    private String description;
    private String symbol;
    private String assetType;
    private Instant systemDate;
    private boolean deleted;

    private Map<String, String> attributes; // for optional descriptive metadata

    public Asset(
            String id,
            String name,
            String description,
            String symbol,
            String assetType,
            Instant systemDate,
            Map<String, String> attributes
    ) {
        this.documentId = UUID.randomUUID().toString();
        this.id = id;
        this.name = name;
        this.description = description;
        this.symbol = symbol;
        this.assetType = assetType;
        this.systemDate = systemDate;
        this.deleted = false;
        this.attributes = attributes;
    }
}
