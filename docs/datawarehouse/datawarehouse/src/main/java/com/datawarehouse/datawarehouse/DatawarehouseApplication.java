package com.datawarehouse.datawarehouse;

import com.datawarehouse.datawarehouse.ingestion.config.AlphaVantageApiProperties;
import com.datawarehouse.datawarehouse.ingestion.config.MarketDataApiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@EnableKafka
@EnableConfigurationProperties({MarketDataApiProperties.class, AlphaVantageApiProperties.class})
public class DatawarehouseApplication {

	public static void main(String[] args) {
		SpringApplication.run(DatawarehouseApplication.class, args);
	}

}
