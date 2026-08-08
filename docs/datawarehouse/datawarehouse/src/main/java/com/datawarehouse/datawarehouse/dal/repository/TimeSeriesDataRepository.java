package com.datawarehouse.datawarehouse.dal.repository;

import com.datawarehouse.datawarehouse.dal.partitionKey.TimeSeriesPartitionKey;
import com.datawarehouse.datawarehouse.domain.TimeSeriesData;

import java.time.Instant;
import java.util.List;

public interface TimeSeriesDataRepository extends WarehouseRepository<TimeSeriesData, TimeSeriesPartitionKey >{

//same generic operations
// time-series partition handled by a typed key
    // later implementation can optimize around partition fields and ordering
     Iterable<TimeSeriesData> findByBusinessDateRange(TimeSeriesPartitionKey key, Instant from, Instant to);

     Iterable<TimeSeriesData> findByBusinessDate(TimeSeriesPartitionKey key, Instant businessDate);

     List<TimeSeriesData> findLatestByBusinessDateRange(String assetId,
                                                        String dataSourceId,
                                                        Instant startBusinessDate,
                                                        Instant endBusinessDate,
                                                        int offset,
                                                        int limit);
     TimeSeriesData findLatestByBusinessDate(TimeSeriesPartitionKey key, Instant businessDate);

     TimeSeriesData markDeleted(TimeSeriesPartitionKey key, Instant businessDate);

}
