package com.datawarehouse.datawarehouse.dal.repository.impl;

import com.datawarehouse.datawarehouse.dal.partitionKey.TimeSeriesPartitionKey;
import com.datawarehouse.datawarehouse.dal.repository.TimeSeriesDataRepository;
import com.datawarehouse.datawarehouse.domain.TimeSeriesData;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;

import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.match;


@Repository
@RequiredArgsConstructor
public class TimeSeriesDataRepositoryImpl implements TimeSeriesDataRepository {

    private final MongoTemplate mongoTemplate;

    @Override
    public TimeSeriesData save(TimeSeriesData entity) {
        return mongoTemplate.save(entity);
    }

    @Override
    public void delete(TimeSeriesData entity) {
        throw new UnsupportedOperationException("Physical delete is disabled for temporal storage");
    }

    @Override
    public void deleteAll(TimeSeriesPartitionKey partitionKey) {
        throw new UnsupportedOperationException("Physical delete is disabled for temporal storage");
    }

    @Override
    public TimeSeriesData findLatest(TimeSeriesPartitionKey partitionKey) {
        Query query = new Query(buildPartitionCriteria(partitionKey));
        query.with(Sort.by(Sort.Direction.DESC, "systemDate"));
        return mongoTemplate.findOne(query, TimeSeriesData.class);
    }

    @Override
    public Iterable<TimeSeriesData> findAll(TimeSeriesPartitionKey partitionKey) {
        Query query = new Query(buildPartitionCriteria(partitionKey));
        query.with(Sort.by(
                Sort.Order.asc("businessDate"),
                Sort.Order.desc("systemDate")
        ));
        return mongoTemplate.find(query, TimeSeriesData.class);
    }

    @Override
    public Iterable<TimeSeriesData> findByBusinessDateRange(TimeSeriesPartitionKey key, Instant from, Instant to) {
        List<Criteria> criteriaList = new ArrayList<>();
        criteriaList.add(buildPartitionCriteria(key));
        criteriaList.add(Criteria.where("businessDate").gte(from).lte(to));

        Query query = new Query(new Criteria().andOperator(criteriaList.toArray(new Criteria[0])));
        query.with(Sort.by(
                Sort.Order.asc("businessDate"),
                Sort.Order.desc("systemDate")
        ));

        query.addCriteria(Criteria.where("deleted").ne(true));

        return mongoTemplate.find(query, TimeSeriesData.class);
    }

    @Override
    public Iterable<TimeSeriesData> findByBusinessDate(TimeSeriesPartitionKey key, Instant businessDate) {
        List<Criteria> criteriaList = new ArrayList<>();
        criteriaList.add(buildPartitionCriteria(key));
        criteriaList.add(Criteria.where("businessDate").is(businessDate));

        Query query = new Query(new Criteria().andOperator(criteriaList.toArray(new Criteria[0])));
        query.with(Sort.by(Sort.Direction.DESC, "systemDate"));

        return mongoTemplate.find(query, TimeSeriesData.class);
    }

    private Criteria buildPartitionCriteria(TimeSeriesPartitionKey key) {
        List<Criteria> criteriaList = new ArrayList<>();
        criteriaList.add(Criteria.where("assetId").is(key.getAssetId()));
        criteriaList.add(Criteria.where("dataSourceId").is(key.getDataSourceId()));

        if (key.getBusinessYear() != null) {
            criteriaList.add(Criteria.where("businessYear").is(key.getBusinessYear()));
        }

        return new Criteria().andOperator(criteriaList.toArray(new Criteria[0]));
    }

    @Override
    public List<TimeSeriesData> findLatestByBusinessDateRange(String assetId, String dataSourceId, Instant startBusinessDate, Instant endBusinessDate, int offset, int limit) {
        AggregationOperation match= match(
                new Criteria().andOperator( // filtreaza dupa asset, dataSource si interval
                        Criteria.where("assetId").is(assetId),
                        Criteria.where("dataSourceId").is(dataSourceId),
                        Criteria.where("businessDate").gte(startBusinessDate).lt(endBusinessDate)
                )
        );

        AggregationOperation sortBeforeGroup = Aggregation.sort( // sorteaza desc business date ca sa aveam cea mai recenta zi
                Sort.by(Sort.Order.desc("businessDate"), Sort.Order.desc("systemDate"))
        );

        AggregationOperation group = context -> new Document("$group", // grupam dupa business date
                new Document("_id", "$businessDate")
                        .append("doc", new Document("$first", "$$ROOT"))
        );

        AggregationOperation replaceRoot = context -> new Document("$replaceRoot", // si pastram primul document din fiecare grup, adica versiunea cea mai recenta
                new Document("newRoot", "$doc")
        );

        AggregationOperation excludeDeleted = match(Criteria.where("deleted").ne(true));
        AggregationOperation sortAfterGroup = Aggregation.sort(Sort.by(Sort.Order.desc("businessDate")));
        AggregationOperation skip = Aggregation.skip(offset);
        AggregationOperation limitOp = Aggregation.limit(limit);

        Aggregation aggregation = Aggregation.newAggregation(
                match,
                sortBeforeGroup,
                group,
                replaceRoot,
                excludeDeleted,
                sortAfterGroup,
                skip,
                limitOp
        );
        AggregationResults<TimeSeriesData> results =
                mongoTemplate.aggregate(aggregation, "time_series_data", TimeSeriesData.class);

        return results.getMappedResults();
    }

    @Override
    public TimeSeriesData findLatestByBusinessDate(TimeSeriesPartitionKey key, Instant businessDate) {
        List<Criteria> criteriaList = new ArrayList<>();
        criteriaList.add(buildPartitionCriteria(key));
        criteriaList.add(Criteria.where("businessDate").is(businessDate));

        Query query = new Query(new Criteria().andOperator(criteriaList.toArray(new Criteria[0])));
        query.with(Sort.by(Sort.Direction.DESC, "systemDate"));
        query.limit(1);

        return mongoTemplate.findOne(query, TimeSeriesData.class);
    }

    @Override
    public TimeSeriesData markDeleted(TimeSeriesPartitionKey key, Instant businessDate) {
        Integer businessYear = key.getBusinessYear() != null
                ? key.getBusinessYear()
                : businessDate.atZone(ZoneOffset.UTC).getYear();

        TimeSeriesData marker = new TimeSeriesData(
                key.getAssetId() + "-" + key.getDataSourceId() + "-" + businessDate + "-deleted",
                key.getAssetId(),
                key.getDataSourceId(),
                businessDate,
                Instant.now(),
                Map.of(),
                true,
                businessYear,
                null,
                null,
                Map.of("operation", "delete-marker"),
                null,
                key.getAssetId() + "/" + key.getDataSourceId() + "/" + businessDate + "/DELETE",
                null
        );

        return mongoTemplate.save(marker);
    }
}
