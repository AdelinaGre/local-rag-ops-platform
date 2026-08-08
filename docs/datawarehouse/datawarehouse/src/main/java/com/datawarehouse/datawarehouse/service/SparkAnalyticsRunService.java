package com.datawarehouse.datawarehouse.service;

import com.datawarehouse.datawarehouse.domain.AnalyticsPricePrediction;
import com.datawarehouse.datawarehouse.domain.AnalyticsYearlySummary;
import com.datawarehouse.datawarehouse.web.dataTransferObject.SparkJobStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class SparkAnalyticsRunService {

    private static final long TIMEOUT_MINUTES = 10;

    private final MongoTemplate mongoTemplate;

    public SparkJobStatusResponse runYearlySummaries(String assetId) {
        Instant startedAt = Instant.now();
        runSparkScript("compute_yearly_summaries.py", assetId);
        return buildStatus(
                "spark-aggregation-yearly-summaries",
                "compute_yearly_summaries",
                "aggregation",
                startedAt,
                "analytics_yearly_summaries",
                AnalyticsYearlySummary.class,
                assetId
        );
    }

    public SparkJobStatusResponse runPriceRegression(String assetId) {
        Instant startedAt = Instant.now();
        runSparkScript("train_price_regression.py", assetId);
        return buildStatus(
                "spark-ml-price-regression",
                "train_price_regression",
                "ml_regression",
                startedAt,
                "analytics_price_predictions",
                AnalyticsPricePrediction.class,
                assetId
        );
    }

    public List<SparkJobStatusResponse> runAll(String assetId) {
        return List.of(
                runYearlySummaries(assetId),
                runPriceRegression(assetId)
        );
    }

    private void runSparkScript(String scriptName, String assetId) {
        Path sparkRoot = resolveSparkRoot();
        Path runner = sparkRoot.resolve("run_spark.ps1");
        Path script = sparkRoot.resolve(scriptName);

        if (!Files.exists(runner)) {
            throw new IllegalStateException("Spark runner not found: " + runner);
        }

        if (!Files.exists(script)) {
            throw new IllegalStateException("Spark script not found: " + script);
        }

        List<String> command = new ArrayList<>();
        command.add("powershell.exe");
        command.add("-NoProfile");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(runner.toString());
        command.add(script.toString());

        if (StringUtils.hasText(assetId)) {
            command.add("--asset-id");
            command.add(assetId);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(sparkRoot.toFile());
        processBuilder.redirectErrorStream(true);

        StringBuilder output = new StringBuilder();
        try {
            Process process = processBuilder.start();
            AtomicReference<Exception> readerException = new AtomicReference<>();

            Thread outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(),
                        StandardCharsets.UTF_8
                ))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append(System.lineSeparator());
                    }
                } catch (Exception exception) {
                    readerException.set(exception);
                }
            }, "spark-job-output-reader");
            outputReader.start();

            boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Spark job timed out after " + TIMEOUT_MINUTES + " minutes");
            }

            outputReader.join(TimeUnit.SECONDS.toMillis(5));
            if (readerException.get() != null) {
                throw new IllegalStateException("Could not read Spark job output", readerException.get());
            }

            if (process.exitValue() != 0) {
                throw new IllegalStateException("Spark job failed: " + summarize(output));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Spark job was interrupted", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException illegalStateException) {
                throw illegalStateException;
            }
            throw new IllegalStateException("Spark job could not be started: " + exception.getMessage(), exception);
        }
    }

    private Path resolveSparkRoot() {
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path direct = workingDirectory.resolve("spark_analysis_ml");
        if (Files.isDirectory(direct)) {
            return direct;
        }

        Path nested = workingDirectory.resolve("datawarehouse").resolve("spark_analysis_ml");
        if (Files.isDirectory(nested)) {
            return nested;
        }

        throw new IllegalStateException("spark_analysis_ml directory not found from " + workingDirectory);
    }

    private SparkJobStatusResponse buildStatus(
            String jobId,
            String name,
            String type,
            Instant startedAt,
            String outputCollection,
            Class<?> resultClass,
            String assetId
    ) {
        Query query = new Query();
        if (StringUtils.hasText(assetId)) {
            query.addCriteria(Criteria.where("assetId").is(assetId));
        }

        long count = mongoTemplate.count(query, resultClass);
        return new SparkJobStatusResponse(
                jobId,
                name,
                type,
                "completed",
                startedAt,
                Instant.now(),
                count,
                outputCollection,
                null
        );
    }

    private String summarize(StringBuilder output) {
        String text = output.toString().trim();
        if (text.length() <= 1200) {
            return text;
        }
        return text.substring(text.length() - 1200);
    }
}
