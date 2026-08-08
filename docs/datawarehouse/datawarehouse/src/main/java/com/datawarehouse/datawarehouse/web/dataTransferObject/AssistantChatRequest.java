package com.datawarehouse.datawarehouse.web.dataTransferObject;

import java.time.LocalDate;

public record AssistantChatRequest(
        String message,
        String assetId,
        String dataSourceId,
        LocalDate startBusinessDate,
        LocalDate endBusinessDate,
        Boolean includeAttributes
) {
}
