package com.datawarehouse.datawarehouse.ingestion.streaming;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class BinanceStreamingClient {

    private static final String PROVIDER = "Binance";
    private static final String DATA_SOURCE_ID = "BINANCE/SPOT";
    private static final String EVENT_TYPE = "kline";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    private final ObjectMapper objectMapper;
    private final KafkaRawMarketDataProducer rawMarketDataProducer;

    @Value("${streaming.binance.symbols:btcusdt,ethusdt}")
    private String symbols;

    @Value("${streaming.binance.interval:1m}")
    private String interval;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private WebSocketSession session;
    private Instant startedAt;
    private Instant lastMessageAt;
    private String lastError;
    private String lastCloseStatus;

    public synchronized void start() {
        if (running.get()) {
            return;
        }

        lastError = null;
        lastCloseStatus = null;

        Exception lastException = null;
        for (URI uri : buildStreamUrls()) {
            try {
                log.info("Starting Binance WebSocket stream: {}", uri);
                this.session = connect(uri);

                running.set(true);
                startedAt = Instant.now();
                lastError = null;
                log.info("Binance WebSocket stream started. Session open: {}", session.isOpen());
                return;
            } catch (Exception exception) {
                running.set(false);
                session = null;
                lastException = exception;
                lastError = describe(exception);
                log.warn("Could not connect to Binance WebSocket endpoint {}: {}", uri, lastError);
            }
        }

        log.error("Failed to start Binance streaming client", lastException);
        throw new IllegalStateException(
                "Failed to start Binance streaming client: " + lastError,
                lastException
        );
    }

    public synchronized void stop() {
        running.set(false);

        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
        }

        session = null;
    }

    public boolean isRunning() {
        return running.get() && session != null && session.isOpen();
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getLastCloseStatus() {
        return lastCloseStatus;
    }

    @PreDestroy
    public void shutdown() {
        stop();
    }

    private WebSocketSession connect(URI uri) throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        client.getUserProperties().put(
                "org.apache.tomcat.websocket.IO_TIMEOUT_MS",
                String.valueOf(CONNECT_TIMEOUT.toMillis())
        );

        CompletableFuture<WebSocketSession> connection = client.execute(
                new BinanceWebSocketHandler(),
                new WebSocketHttpHeaders(),
                uri
        );

        try {
            return connection.get(CONNECT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception exception) {
            connection.cancel(true);
            throw exception;
        }
    }

    private List<URI> buildStreamUrls() {
        String streams = Arrays.stream(symbols.split(","))
                .map(String::trim)
                .filter(symbol -> !symbol.isBlank())
                .map(String::toLowerCase)
                .map(symbol -> symbol + "@kline_" + interval)
                .collect(Collectors.joining("/"));

        return List.of(
                URI.create("wss://stream.binance.com:9443/stream?streams=" + streams),
                URI.create("wss://stream.binance.com:443/stream?streams=" + streams),
                URI.create("wss://data-stream.binance.vision/stream?streams=" + streams)
        );
    }

    private void handleMessage(String payload) {
        try {
            lastMessageAt = Instant.now();
            lastError = null;
            JsonNode root = objectMapper.readTree(payload);
            JsonNode kline = root.path("data").path("k");

            if (kline.isMissingNode() || kline.isNull()) {
                return;
            }

            String symbol = kline.path("s").textValue();
            Instant eventTime = Instant.ofEpochMilli(root.path("data").path("E").asLong());

            Map<String, Object> values = new LinkedHashMap<>();
            values.put("open", kline.path("o").asDouble());
            values.put("high", kline.path("h").asDouble());
            values.put("low", kline.path("l").asDouble());
            values.put("close", kline.path("c").asDouble());
            values.put("volume", kline.path("v").asDouble());
            values.put("isClosed", kline.path("x").asBoolean());

            RawMarketDataEvent event = new RawMarketDataEvent(
                    PROVIDER,
                    DATA_SOURCE_ID,
                    DATA_SOURCE_ID + "/" + symbol,
                    symbol,
                    EVENT_TYPE,
                    eventTime,
                    Instant.now(),
                    values
            );

            rawMarketDataProducer.publish(event);
            log.info("Published Binance {} event for {} at {}", EVENT_TYPE, symbol, eventTime);
        } catch (Exception exception) {
            lastError = describe(exception);
            log.error("Failed to handle Binance WebSocket message", exception);
        }
    }

    private String describe(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }

        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }

        return current.getClass().getSimpleName() + ": " + message;
    }

    private class BinanceWebSocketHandler extends TextWebSocketHandler {

        @Override
        public void handleTextMessage(WebSocketSession session, TextMessage message) {
            BinanceStreamingClient.this.handleMessage(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(
                WebSocketSession session,
                CloseStatus status
        ) {
            running.set(false);
            lastCloseStatus = status.toString();
            log.warn("Binance WebSocket stream closed: {}", status);
        }

        @Override
        public void handleTransportError(
                WebSocketSession session,
                Throwable exception
        ) {
            running.set(false);
            lastError = describe(exception);
            log.error("Binance WebSocket transport error", exception);
            stop();
        }
    }
}
