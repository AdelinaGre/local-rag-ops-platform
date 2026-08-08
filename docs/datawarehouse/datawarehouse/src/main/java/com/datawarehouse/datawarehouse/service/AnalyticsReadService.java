package com.datawarehouse.datawarehouse.service;

import com.datawarehouse.datawarehouse.domain.AnalyticsPricePrediction;
import com.datawarehouse.datawarehouse.domain.AnalyticsYearlySummary;
import com.datawarehouse.datawarehouse.web.dataTransferObject.SparkJobStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AnalyticsReadService {

    private final MongoTemplate mongoTemplate;

    public List<AnalyticsYearlySummary> getYearlySummaries(
            String assetId,
            String dataSourceId,
            Integer businessYear
    ) {
        List<Criteria> criteria = new ArrayList<>();

        if (assetId != null && !assetId.isBlank()) {
            criteria.add(Criteria.where("assetId").is(assetId));
        }

        if (dataSourceId != null && !dataSourceId.isBlank()) {
            criteria.add(Criteria.where("dataSourceId").is(dataSourceId));
        }

        if (businessYear != null) {
            criteria.add(Criteria.where("businessYear").is(businessYear));
        }

        Query query = new Query();
        if (!criteria.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(criteria.toArray(new Criteria[0])));
        }

        query.with(Sort.by(
                Sort.Order.asc("assetId"),
                Sort.Order.asc("dataSourceId"),
                Sort.Order.asc("businessYear")
        ));

        return mongoTemplate.find(query, AnalyticsYearlySummary.class);
    }

    public List<AnalyticsPricePrediction> getPricePredictions(
            String assetId,
            String dataSourceId
    ) {
        List<Criteria> criteria = new ArrayList<>();

        if (assetId != null && !assetId.isBlank()) {
            criteria.add(Criteria.where("assetId").is(assetId));
        }

        if (dataSourceId != null && !dataSourceId.isBlank()) {
            criteria.add(Criteria.where("dataSourceId").is(dataSourceId));
        }

        Query query = new Query();
        if (!criteria.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(criteria.toArray(new Criteria[0])));
        }

        query.with(Sort.by(
                Sort.Order.asc("assetId"),
                Sort.Order.asc("dataSourceId"),
                Sort.Order.asc("date")
        ));

        return mongoTemplate.find(query, AnalyticsPricePrediction.class);
    }

    public List<SparkJobStatusResponse> getSparkJobs() {
        return List.of(
                buildJobStatus(
                        "spark-aggregation-yearly-summaries",
                        "compute_yearly_summaries",
                        "aggregation",
                        "analytics_yearly_summaries",
                        AnalyticsYearlySummary.class
                ),
                buildJobStatus(
                        "spark-ml-price-regression",
                        "train_price_regression",
                        "ml_regression",
                        "analytics_price_predictions",
                        AnalyticsPricePrediction.class
                )
        );
    }

    private SparkJobStatusResponse buildJobStatus(
            String jobId,
            String name,
            String type,
            String outputCollection,
            Class<?> resultClass
    ) {
        long count = mongoTemplate.count(new Query(), resultClass);
        InstantWrapper latest = latestComputedAt(resultClass);
        String status = count > 0 ? "completed" : "pending";

        return new SparkJobStatusResponse(
                jobId,
                name,
                type,
                status,
                null,
                latest.value(),
                count,
                outputCollection,
                null
        );
    }

    private InstantWrapper latestComputedAt(Class<?> resultClass) {
        Query query = new Query();
        query.with(Sort.by(Sort.Direction.DESC, "computedAt"));
        query.limit(1);

        Object result = mongoTemplate.findOne(query, resultClass);
        if (result instanceof AnalyticsYearlySummary summary) {
            return new InstantWrapper(summary.getComputedAt());
        }
        if (result instanceof AnalyticsPricePrediction prediction) {
            return new InstantWrapper(prediction.getComputedAt());
        }
        return new InstantWrapper(null);
    }

    private record InstantWrapper(java.time.Instant value) {
    }
}
