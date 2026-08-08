package com.datawarehouse.datawarehouse.dal.repository;

import com.datawarehouse.datawarehouse.dal.partitionKey.AssetKey;
import com.datawarehouse.datawarehouse.dal.repository.impl.AssetRepositoryImpl;
import com.datawarehouse.datawarehouse.domain.Asset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssetRepositoryImplTest {

    @Test
    void saveFindLatestAndFindAllUseMongoTemplateBoundary() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        AssetRepositoryImpl repository = new AssetRepositoryImpl(mongoTemplate);
        Asset asset = new Asset(
                "asset-a",
                "Asset A",
                "Test asset",
                "A",
                "MARKET",
                Instant.parse("2026-05-31T10:00:00Z"),
                Map.of("provider", "test")
        );

        when(mongoTemplate.save(asset)).thenReturn(asset);
        when(mongoTemplate.findOne(any(Query.class), eq(Asset.class))).thenReturn(asset);
        when(mongoTemplate.find(any(Query.class), eq(Asset.class))).thenReturn(List.of(asset));

        assertSame(asset, repository.save(asset));
        assertSame(asset, repository.findLatest(new AssetKey("asset-a")));
        assertEquals(List.of(asset), repository.findAll(new AssetKey("asset-a")));

        verify(mongoTemplate).save(asset);
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).findOne(queryCaptor.capture(), eq(Asset.class));
        verify(mongoTemplate).find(any(Query.class), eq(Asset.class));
    }

    @Test
    void physicalDeleteIsDisabledForTemporalStorage() {
        AssetRepositoryImpl repository = new AssetRepositoryImpl(mock(MongoTemplate.class));
        AssetKey key = new AssetKey("asset-a");

        assertThrows(UnsupportedOperationException.class, () -> repository.deleteAll(key));
    }
}
