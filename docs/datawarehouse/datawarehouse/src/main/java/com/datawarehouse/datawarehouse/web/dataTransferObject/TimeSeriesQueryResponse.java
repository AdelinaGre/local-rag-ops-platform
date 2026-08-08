package com.datawarehouse.datawarehouse.web.dataTransferObject;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Set;

@Data
@AllArgsConstructor
public class TimeSeriesQueryResponse { // raspuns pentru data
        private String assetId;
        private String dataSourceId;
        private List<TimeSeriesPointResponse> data;
        private Set<String> attributes;
        private int offset;
        private int limit;
        private boolean hasNext;
}
