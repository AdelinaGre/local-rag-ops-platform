package com.datawarehouse.datawarehouse.dal.repository;

import com.datawarehouse.datawarehouse.dal.partitionKey.AssetKey;
import com.datawarehouse.datawarehouse.domain.Asset;

import java.util.List;

public interface AssetRepository extends WarehouseRepository<Asset, AssetKey> {
    List<String> findAssetIds(int offset, int limit);
    long countAssets();
    Asset findLatestById(String assetId);

}
