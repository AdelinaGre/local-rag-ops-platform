package com.datawarehouse.datawarehouse.service;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class LlmChatClient {

    private final RestClient restClient;
    private final String apiBase;
    private final String apiKey;
    @Getter
    private final String model;

    public LlmChatClient(
            RestClient.Builder restClientBuilder,
            @Value("${assistant.llm.api-base:https://api.openai.com/v1}") String apiBase,
            @Value("${assistant.llm.api-key:}") String apiKey,
            @Value("${assistant.llm.model:gpt-4o-mini}") String model
    ) {
        this.restClient = restClientBuilder.build();
        this.apiBase = trimTrailingSlash(apiBase);
        this.apiKey = apiKey;
        this.model = model;
    }

    public Optional<String> complete(String systemPrompt, String userPrompt) {
        if (!StringUtils.hasText(apiKey)) {
            return Optional.empty();
        }

        try {
            Map<String, Object> payload = Map.of(
                    "model", model,
                    "temperature", 0.2,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    )
            );

            Map<String, Object> response = restClient.post()
                    .uri(apiBase + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(payload)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });

            return readMessageContent(response);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<String> readMessageContent(Map<String, Object> response) {
        if (response == null || !(response.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            return Optional.empty();
        }

        Object firstChoice = choices.getFirst();
        if (!(firstChoice instanceof Map<?, ?> choice)) {
            return Optional.empty();
        }

        Object message = choice.get("message");
        if (!(message instanceof Map<?, ?> messageMap)) {
            return Optional.empty();
        }

        Object content = messageMap.get("content");
        if (content instanceof String text && StringUtils.hasText(text)) {
            return Optional.of(text.trim());
        }

        return Optional.empty();
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://api.openai.com/v1";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
