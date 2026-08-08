package com.datawarehouse.datawarehouse.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "data_sources")
public class DataSource {
    @Id
    private String documentId = UUID.randomUUID().toString();
    private String id;
    private String name;
    private String description;
    private Instant systemDate;
    private boolean deleted;
    private String provider;
    private String dataset;
    private Map<String,String> requestContext;
    private Set<String> attributes;

    public DataSource(
            String id,
            String name,
            String description,
            Instant systemDate,
            String provider,
            String dataset,
            Map<String, String> requestContext,
            Set<String> attributes
    ) {
        this.documentId = UUID.randomUUID().toString();
        this.id = id;
        this.name = name;
        this.description = description;
        this.systemDate = systemDate;
        this.deleted = false;
        this.provider = provider;
        this.dataset = dataset;
        this.requestContext = requestContext;
        this.attributes = attributes;
    }
}
