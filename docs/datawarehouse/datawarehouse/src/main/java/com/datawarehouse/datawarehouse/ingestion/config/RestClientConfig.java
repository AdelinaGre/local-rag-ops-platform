package com.datawarehouse.datawarehouse.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
//marcheaza clasa ca una de configurare in Spring Boot
@Configuration // Spring o va scana si va folosi metodele din ea pentru a crea obiecte been-uri
public class RestClientConfig {
    @Bean
    public RestClient restClient() { // creeaza un obiect de tip RestClient si il inregistreaza in contextul Spring
        return RestClient.builder().build(); // este un client modern pentru apeluri HTTP, comunicare cu API externe
    }
}