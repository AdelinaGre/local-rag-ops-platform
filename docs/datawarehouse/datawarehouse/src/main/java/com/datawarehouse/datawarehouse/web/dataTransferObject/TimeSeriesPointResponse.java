package com.datawarehouse.datawarehouse.web.dataTransferObject;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
@Data
@AllArgsConstructor
public class TimeSeriesPointResponse { // raspuns pentru un singur punct de timp
    private Instant businessDate;
    private Instant systemDate;
    private Map<String, Object> payload;
}
