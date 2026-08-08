package com.datawarehouse.datawarehouse.dal.repository;

public interface WarehouseRepository <E,K>{
    E save(E entity);
    void delete(E entity);
    void deleteAll(K partitionKey);
    E findLatest(K partitionKey);
    Iterable<E> findAll(K partitionKey);
}
