package com.datawarehouse.datawarehouse.web;

import com.datawarehouse.datawarehouse.ingestion.streaming.BinanceStreamingClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/streaming/binance")
public class StreamingController {

    private final BinanceStreamingClient binanceStreamingClient;

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start() {
        try {
            binanceStreamingClient.start();
            Map<String, Object> body = statusBody();
            body.put("message", "Binance streaming client started");
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
        } catch (IllegalStateException exception) {
            Map<String, Object> body = statusBody();
            body.put("message", "Binance streaming client could not connect");
            body.put("error", binanceStreamingClient.getLastError());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        binanceStreamingClient.stop();

        return Map.of(
                "provider", "Binance",
                "status", "STOPPED",
                "message", "Binance streaming client stopped",
                "stoppedAt", Instant.now()
        );
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return statusBody();
    }

    private Map<String, Object> statusBody() {
        boolean running = binanceStreamingClient.isRunning();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("provider", "Binance");
        response.put("status", running ? "RUNNING" : "STOPPED");
        response.put("running", running);
        response.put("checkedAt", Instant.now());
        response.put("startedAt", binanceStreamingClient.getStartedAt());
        response.put("lastMessageAt", binanceStreamingClient.getLastMessageAt());
        response.put("lastCloseStatus", binanceStreamingClient.getLastCloseStatus());
        response.put("lastError", binanceStreamingClient.getLastError());
        return response;
    }
}
