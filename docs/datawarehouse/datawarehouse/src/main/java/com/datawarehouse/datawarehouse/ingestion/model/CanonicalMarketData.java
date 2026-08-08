package com.datawarehouse.datawarehouse.ingestion.model;

import com.datawarehouse.datawarehouse.domain.Asset;
import com.datawarehouse.datawarehouse.domain.DataSource;
import com.datawarehouse.datawarehouse.domain.TimeSeriesData;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CanonicalMarketData {
private Asset asset;
private DataSource dataSource;
private List<TimeSeriesData> timeSeriesRecords;
}
