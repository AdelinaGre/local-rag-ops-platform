package com.datawarehouse.datawarehouse.ingestion.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
// one page returned by an external provider
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RawMarketDataPage {
    private List<Map<String, Object>> records; //the raw provider rows exactly as received
    private String nextCursor; //the token used to fetch the next page
    private boolean hasNextPage; //tells if more pages still exist
    private String provider; //source name, for example Nasdaq
    private String dataset; //dataset identifier/name
}
