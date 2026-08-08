package com.datawarehouse.datawarehouse.config;

import com.datawarehouse.datawarehouse.domain.Asset;
import com.datawarehouse.datawarehouse.domain.DataSource;
import com.datawarehouse.datawarehouse.domain.TimeSeriesData;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

@Configuration
public class MongoIndexConfig {

    @Bean
    ApplicationRunner ensureMongoIndexes(MongoTemplate mongoTemplate) {
        return args -> {
            mongoTemplate.indexOps(Asset.class).ensureIndex(
                    new Index()
                            .on("id", Sort.Direction.ASC)
                            .on("systemDate", Sort.Direction.DESC)
                            .named("asset_latest_by_business_id")
            );

            mongoTemplate.indexOps(DataSource.class).ensureIndex(
                    new Index()
                            .on("id", Sort.Direction.ASC)
                            .on("systemDate", Sort.Direction.DESC)
                            .named("data_source_latest_by_business_id")
            );

            mongoTemplate.indexOps(TimeSeriesData.class).ensureIndex(
                    new Index()
                            .on("assetId", Sort.Direction.ASC)
                            .on("dataSourceId", Sort.Direction.ASC)
                            .on("businessYear", Sort.Direction.ASC)
                            .on("businessDate", Sort.Direction.DESC)
                            .on("systemDate", Sort.Direction.DESC)
                            .named("time_series_partition_latest")
            );

            mongoTemplate.indexOps(TimeSeriesData.class).ensureIndex(
                    new Index()
                            .on("sourceRecordKey", Sort.Direction.ASC)
                            .on("payloadHash", Sort.Direction.ASC)
                            .named("time_series_source_hash")
            );
        };
    }
}
