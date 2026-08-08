package com.datawarehouse.datawarehouse.dal.repository.impl;

import com.datawarehouse.datawarehouse.dal.partitionKey.DataSourceKey;
import com.datawarehouse.datawarehouse.dal.repository.DataSourceRepository;
import com.datawarehouse.datawarehouse.domain.DataSource;
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

import java.util.List;

@Repository
@RequiredArgsConstructor
public class DataSourceRepositoryImpl implements DataSourceRepository {
    private final MongoTemplate mongoTemplate;

    @Override
    public DataSource save(DataSource entity){
        return mongoTemplate.save(entity);
    }

    @Override
    public void delete(DataSource entity){
        throw new UnsupportedOperationException("Physical delete is disabled for temporal storage");
    }

    @Override
    public void deleteAll(DataSourceKey partitionKey) {
        throw new UnsupportedOperationException("Physical delete is disabled for temporal storage");
    }

    @Override
    public DataSource findLatest(DataSourceKey partitionKey) {
        Query query=new Query(new Criteria().andOperator(
                Criteria.where("id").is(partitionKey.getId()),
                Criteria.where("deleted").ne(true)
        ));
        query.with(Sort.by(Sort.Direction.DESC, "systemDate"));
        return mongoTemplate.findOne(query, DataSource.class);
    }

    @Override
    public Iterable<DataSource> findAll(DataSourceKey partitionKey) {
        Query query=new Query(new Criteria().andOperator(
                Criteria.where("id").is(partitionKey.getId()),
                Criteria.where("deleted").ne(true)
        ));
        query.with(Sort.by(Sort.Direction.DESC, "systemDate"));
        return mongoTemplate.find(query, DataSource.class);
    }

    @Override
    public List<String> findDataSourceIds(int offset, int limit) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.sort(Sort.by(Sort.Order.asc("id"), Sort.Order.desc("systemDate"))),
                latestDocumentById(),
                replaceRootWithLatestDocument(),
                Aggregation.match(Criteria.where("deleted").ne(true)),
                Aggregation.sort(Sort.by(Sort.Order.asc("id"))),
                Aggregation.skip(offset),
                Aggregation.limit(limit)
        );

        return mongoTemplate.aggregate(aggregation, "data_sources", DataSource.class)
                .getMappedResults()
                .stream()
                .map(DataSource::getId)
                .toList();
    }

    @Override
    public long countDataSources() {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.sort(Sort.by(Sort.Order.asc("id"), Sort.Order.desc("systemDate"))),
                latestDocumentById(),
                replaceRootWithLatestDocument(),
                Aggregation.match(Criteria.where("deleted").ne(true)),
                Aggregation.count().as("total")
        );

        AggregationResults<Document> result = mongoTemplate.aggregate(aggregation, "data_sources", Document.class);
        Document first = result.getUniqueMappedResult();
        return first == null ? 0 : first.get("total", Number.class).longValue();
    }

    @Override
    public DataSource findLatestById(String dataSourceId) {
        Query query= new Query(new Criteria().andOperator(
                Criteria.where("id").is(dataSourceId),
                Criteria.where("deleted").ne(true)
        ));
        query.with(Sort.by(Sort.Direction.DESC, "systemDate"));

        return mongoTemplate.findOne(query, DataSource.class);
    }

    private AggregationOperation latestDocumentById() {
        return context -> new Document("$group",
                new Document("_id", "$id")
                        .append("doc", new Document("$first", "$$ROOT"))
        );
    }

    private AggregationOperation replaceRootWithLatestDocument() {
        return context -> new Document("$replaceRoot",
                new Document("newRoot", "$doc")
        );
    }
}
