package com.datawarehouse.datawarehouse.dal.partitionKey;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TimeSeriesPartitionKey {
    private String assetId;
    private String dataSourceId;
    private Integer businessYear;
}
