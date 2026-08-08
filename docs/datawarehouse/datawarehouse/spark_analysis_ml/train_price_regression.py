import argparse
import os
from functools import reduce

from pyspark.ml.evaluation import RegressionEvaluator
from pyspark.ml.feature import StandardScaler, VectorAssembler
from pyspark.ml.regression import LinearRegression
from pyspark.sql import SparkSession
from pyspark.sql.functions import coalesce, col, current_timestamp, date_format, lit, unix_timestamp
from pyspark.sql.types import DoubleType, StringType, StructField, StructType

MONGO_URI = os.getenv(
    "MONGO_URI",
    "mongodb://admin:password@127.0.0.1:27017/datawarehouse?authSource=admin",
)

MIN_ROWS_PER_ASSET = 10
FEATURE_COLUMNS = ["seconds", "open", "high", "low", "volume"]

parser = argparse.ArgumentParser(description="Train Spark ML price regression models.")
parser.add_argument("--asset-id", default="", help="Optional warehouse assetId filter.")
args = parser.parse_args()


spark = (
    SparkSession.builder
    .appName("Financial Market DWH - Spark ML Price Regression")
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

training_df = (
    raw_df
    .filter((col("deleted") != True) | col("deleted").isNull())
    .select(
        col("assetId"),
        col("dataSourceId"),
        date_format(col("businessDate"), "yyyy-MM-dd").alias("date"),
        unix_timestamp(col("businessDate")).cast("double").alias("seconds"),
        coalesce(
            col("payload.close").cast("double"),
            col("payload.last").cast("double"),
            col("payload.mid").cast("double"),
            col("payload.open").cast("double"),
        ).alias("actualClose"),
        coalesce(
            col("payload.open").cast("double"),
            col("payload.close").cast("double"),
            col("payload.last").cast("double"),
            col("payload.mid").cast("double"),
        ).alias("open"),
        coalesce(
            col("payload.high").cast("double"),
            col("payload.close").cast("double"),
            col("payload.last").cast("double"),
            col("payload.mid").cast("double"),
        ).alias("high"),
        coalesce(
            col("payload.low").cast("double"),
            col("payload.close").cast("double"),
            col("payload.last").cast("double"),
            col("payload.mid").cast("double"),
        ).alias("low"),
        coalesce(col("payload.volume").cast("double"), lit(0.0)).alias("volume"),
    )
    .filter(col("assetId").isNotNull())
    .filter(col("dataSourceId").isNotNull())
    .filter(col("date").isNotNull())
    .filter(col("seconds").isNotNull())
    .filter(col("actualClose").isNotNull())
    .filter(col("open").isNotNull())
    .filter(col("high").isNotNull())
    .filter(col("low").isNotNull())
)

if args.asset_id:
    training_df = training_df.filter(col("assetId") == args.asset_id)

eligible_pairs = (
    training_df
    .groupBy("assetId", "dataSourceId")
    .count()
    .filter(col("count") >= MIN_ROWS_PER_ASSET)
    .collect()
)

assembler = VectorAssembler(
    inputCols=FEATURE_COLUMNS,
    outputCol="features",
    handleInvalid="skip",
)

rmse_evaluator = RegressionEvaluator(
    labelCol="actualClose",
    predictionCol="predictedClose",
    metricName="rmse",
)

r2_evaluator = RegressionEvaluator(
    labelCol="actualClose",
    predictionCol="predictedClose",
    metricName="r2",
)

prediction_frames = []

for pair in eligible_pairs:
    asset_id = pair["assetId"]
    data_source_id = pair["dataSourceId"]

    asset_df = (
        training_df
        .filter(col("assetId") == asset_id)
        .filter(col("dataSourceId") == data_source_id)
    )

    train_df, test_df = asset_df.randomSplit([0.7, 0.3], seed=42)
    if train_df.count() < 2 or test_df.count() < 1:
        train_df = asset_df
        test_df = asset_df

    assembled_train_df = assembler.transform(train_df)
    scaler_model = (
        StandardScaler(
            inputCol="features",
            outputCol="scaledFeatures",
            withMean=True,
            withStd=True,
        )
        .fit(assembled_train_df)
    )

    scaled_train_df = scaler_model.transform(assembled_train_df)

    model = (
        LinearRegression(
            featuresCol="scaledFeatures",
            labelCol="actualClose",
            predictionCol="predictedClose",
            maxIter=50,
            regParam=0.01,
            elasticNetParam=0.0,
        )
        .fit(scaled_train_df)
    )

    scored_test_df = model.transform(scaler_model.transform(assembler.transform(test_df)))
    model_rmse = float(rmse_evaluator.evaluate(scored_test_df))
    model_r2 = float(r2_evaluator.evaluate(scored_test_df))

    scored_df = model.transform(scaler_model.transform(assembler.transform(asset_df)))

    prediction_frames.append(
        scored_df
        .select(
            col("assetId"),
            col("dataSourceId"),
            col("date"),
            col("actualClose"),
            col("predictedClose"),
            (col("actualClose") - col("predictedClose")).alias("residual"),
            lit(model_r2).cast("double").alias("modelR2"),
            lit(model_rmse).cast("double").alias("modelRMSE"),
        )
        .withColumn("computedAt", current_timestamp())
        .withColumn("jobName", lit("train_price_regression"))
    )

if prediction_frames:
    output_df = reduce(lambda left, right: left.unionByName(right), prediction_frames)
else:
    output_schema = StructType(
        [
            StructField("assetId", StringType(), True),
            StructField("dataSourceId", StringType(), True),
            StructField("date", StringType(), True),
            StructField("actualClose", DoubleType(), True),
            StructField("predictedClose", DoubleType(), True),
            StructField("residual", DoubleType(), True),
            StructField("modelR2", DoubleType(), True),
            StructField("modelRMSE", DoubleType(), True),
        ]
    )
    output_df = (
        spark.createDataFrame([], output_schema)
        .withColumn("computedAt", current_timestamp())
        .withColumn("jobName", lit("train_price_regression"))
    )

output_df.show(50, truncate=False)

(
    output_df.write
    .format("mongodb")
    .mode("overwrite")
    .option("database", "datawarehouse")
    .option("collection", "analytics_price_predictions")
    .save()
)

spark.stop()
