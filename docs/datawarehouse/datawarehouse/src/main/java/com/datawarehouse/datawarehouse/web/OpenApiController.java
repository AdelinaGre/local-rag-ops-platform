package com.datawarehouse.datawarehouse.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
public class OpenApiController {

    @GetMapping(value = "/api/v1/openapi.yaml", produces = "application/yaml")
    public String openApiYaml() throws IOException {
        ClassPathResource resource = new ClassPathResource("api/openapi.yaml");
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }

    @GetMapping(value = "/api/v1/openapi", produces = MediaType.TEXT_PLAIN_VALUE)
    public String openApiLocation() {
        return "/api/v1/openapi.yaml";
    }
}
