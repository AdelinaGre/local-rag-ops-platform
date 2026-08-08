package com.datawarehouse.datawarehouse.dal.repository.impl;

import com.datawarehouse.datawarehouse.dal.partitionKey.AssetKey;
import com.datawarehouse.datawarehouse.dal.repository.AssetRepository;
import com.datawarehouse.datawarehouse.domain.Asset;
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
public class AssetRepositoryImpl implements AssetRepository {
    private final MongoTemplate mongoTemplate;

    @Override
    public Asset save(Asset entity){
        return mongoTemplate.save(entity);
    }

    @Override
    public void delete(Asset entity){
        throw new UnsupportedOperationException("Physical delete is disabled for temporal storage");
    }

    @Override
    public void deleteAll(AssetKey partitionKey) {
        throw new UnsupportedOperationException("Physical delete is disabled for temporal storage");
    }

    @Override
    public Asset findLatest(AssetKey partitionKey) {
        Query query=new Query(new Criteria().andOperator(
                Criteria.where("id").is(partitionKey.getId()),
                Criteria.where("deleted").ne(true)
        ));
        query.with(Sort.by(Sort.Direction.DESC, "systemDate"));
        return mongoTemplate.findOne(query, Asset.class);
    }

    @Override
    public Iterable<Asset> findAll(AssetKey partitionKey) {
        Query query=new Query(new Criteria().andOperator(
                Criteria.where("id").is(partitionKey.getId()),
                Criteria.where("deleted").ne(true)
        ));
        query.with(Sort.by(Sort.Direction.DESC, "systemDate"));
        return mongoTemplate.find(query, Asset.class);
    }

    @Override
    public List<String> findAssetIds(int offset, int limit) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.sort(Sort.by(Sort.Order.asc("id"), Sort.Order.desc("systemDate"))),
                latestDocumentById(),
                replaceRootWithLatestDocument(),
                Aggregation.match(Criteria.where("deleted").ne(true)),
                Aggregation.sort(Sort.by(Sort.Order.asc("id"))),
                Aggregation.skip(offset),
                Aggregation.limit(limit)
        );

        return mongoTemplate.aggregate(aggregation, "assets", Asset.class)
                .getMappedResults()
                .stream()
                .map(Asset::getId)
                .toList();
    }

    @Override
    public long countAssets() {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.sort(Sort.by(Sort.Order.asc("id"), Sort.Order.desc("systemDate"))),
                latestDocumentById(),
                replaceRootWithLatestDocument(),
                Aggregation.match(Criteria.where("deleted").ne(true)),
                Aggregation.count().as("total")
        );

        AggregationResults<Document> result = mongoTemplate.aggregate(aggregation, "assets", Document.class);
        Document first = result.getUniqueMappedResult();
        return first == null ? 0 : first.get("total", Number.class).longValue();
    }

    @Override
    public Asset findLatestById(String assetId) {
        Query query=new Query(new Criteria().andOperator(
                Criteria.where("id").is(assetId),
                Criteria.where("deleted").ne(true)
        ));
        query.with(Sort.by(Sort.Direction.DESC, "systemDate"));
        return mongoTemplate.findOne(query, Asset.class);
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
