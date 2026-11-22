package org.example;

import org.example.config.NewsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(NewsProperties.class)
public class GossipAgentsApplication {
    public static void main(String[] args) {
        SpringApplication.run(GossipAgentsApplication.class, args);
    }
}