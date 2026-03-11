package com.apiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.apiagent.config.ApiAgentProperties;

@SpringBootApplication
@EnableConfigurationProperties(ApiAgentProperties.class)
public class ApiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiAgentApplication.class, args);
    }
}
