import argparse
import os

from pyspark.sql import SparkSession
from pyspark.sql.functions import avg, coalesce, col, count, current_timestamp, lit, max, min

MONGO_URI = os.getenv(
    "MONGO_URI",
    "mongodb://admin:password@127.0.0.1:27017/datawarehouse?authSource=admin",
)

parser = argparse.ArgumentParser(description="Compute yearly price summaries with Spark.")
parser.add_argument("--asset-id", default="", help="Optional warehouse assetId filter.")
args = parser.parse_args()

spark = (
    SparkSession.builder
    .appName("Compute Yearly Summaries")
    .config("spark.mongodb.read.connection.uri", MONGO_URI)
    .config("spark.mongodb.write.connection.uri", MONGO_URI)
    .getOrCreate()
)

raw_df = (
    spark.read
    .format("mongodb")
    .option("database", "datawarehouse")
    .option("collection", "time_series_data")
    .load()
)

analytics_df = (
    raw_df
    .filter((col("deleted") != True) | col("deleted").isNull())
    .select(
        col("assetId"),
        col("dataSourceId"),
        col("businessYear"),
        coalesce(
            col("payload.close").cast("double"),
            col("payload.last").cast("double"),
            col("payload.mid").cast("double"),
            col("payload.open").cast("double"),
        ).alias("price"),
        col("payload.volume").cast("double").alias("volume"),
    )
    .filter(col("assetId").isNotNull())
    .filter(col("dataSourceId").isNotNull())
    .filter(col("businessYear").isNotNull())
    .filter(col("price").isNotNull())
)

if args.asset_id:
    analytics_df = analytics_df.filter(col("assetId") == args.asset_id)

summary_df = (
    analytics_df
    .groupBy("assetId", "dataSourceId", "businessYear")
    .agg(
        count("*").alias("count"),
        min("price").alias("minClose"),
        max("price").alias("maxClose"),
        avg("price").alias("avgClose"),
        avg("volume").alias("avgVolume"),
    )
    .withColumn("computedAt", current_timestamp())
    .withColumn("jobName", lit("compute_yearly_summaries"))
)

summary_df.show(50, truncate=False)

(
    summary_df.write
    .format("mongodb")
    .mode("overwrite")
    .option("database", "datawarehouse")
    .option("collection", "analytics_yearly_summaries")
    .save()
)

spark.stop()
