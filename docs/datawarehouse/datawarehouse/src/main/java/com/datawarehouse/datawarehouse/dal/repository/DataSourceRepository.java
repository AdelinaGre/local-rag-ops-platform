package com.datawarehouse.datawarehouse.dal.repository;

import com.datawarehouse.datawarehouse.dal.partitionKey.DataSourceKey;
import com.datawarehouse.datawarehouse.domain.DataSource;
import lombok.Data;

import java.util.List;

public interface DataSourceRepository extends WarehouseRepository<DataSource, DataSourceKey> {
    List<String> findDataSourceIds(int offset, int limit);
    long countDataSources();
    DataSource findLatestById(String dataSourceId);
}
