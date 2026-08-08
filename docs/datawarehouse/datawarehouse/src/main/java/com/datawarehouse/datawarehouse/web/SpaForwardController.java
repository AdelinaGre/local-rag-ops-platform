package com.datawarehouse.datawarehouse.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaForwardController {

    @GetMapping({
            "/dashboard",
            "/dashboard.html",
            "/instruments",
            "/timeseries",
            "/ingestion",
            "/analytics",
            "/assistant",
            "/analytics-spark.html",
            "/agentic-ai.html"
    })
    public String forwardReactRoutes() {
        return "forward:/index.html";
    }
}
