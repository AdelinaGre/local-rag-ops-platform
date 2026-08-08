package com.datawarehouse.datawarehouse.web;

import com.datawarehouse.datawarehouse.service.AssistantAgentService;
import com.datawarehouse.datawarehouse.web.dataTransferObject.AssistantChatRequest;
import com.datawarehouse.datawarehouse.web.dataTransferObject.AssistantChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantAgentService assistantAgentService;

    @PostMapping("/chat")
    public AssistantChatResponse chat(@RequestBody AssistantChatRequest request) {
        return assistantAgentService.chat(request);
    }
}
