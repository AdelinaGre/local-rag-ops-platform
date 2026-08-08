package com.datawarehouse.datawarehouse.web.dataTransferObject;

import java.util.List;
import java.util.Map;

public record AssistantChatResponse(
        String answer,
        boolean llmUsed,
        String model,
        List<AssistantToolCallResponse> toolCalls,
        Map<String, Object> context
) {
}
