package com.datawarehouse.datawarehouse.web.dataTransferObject;

import java.util.Map;

public record AssistantToolCallResponse(
        String name,
        Map<String, Object> arguments,
        String status,
        String summary
) {
}
