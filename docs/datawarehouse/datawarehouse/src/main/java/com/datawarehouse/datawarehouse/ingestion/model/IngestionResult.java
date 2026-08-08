package com.datawarehouse.datawarehouse.ingestion.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
// ingestion statistic
@Data
@AllArgsConstructor
@NoArgsConstructor
public class IngestionResult {
    private int fetchedRecords; // how many raw records came from the provider
    private int transformedRecords;// how many records was successfully converted to internal objects
    private int storedRecords; // how many objects were sasved in the warehouse
    private int skippedRecords; // how many were intentionally ignored
    private int failedRecords;//how many failed because of bad data or runtime errors
    private String message;

}
