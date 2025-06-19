package com.example.reddisearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@SpringBootApplication
public class ReddisearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReddisearchApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @ConfigurationProperties(prefix = "app")
    @Component
    public static class AppConfig {
        private String userAgent = "ReddiSearch/1.0";
        private int maxPostsPerRequest = 25;
        private int rateLimitDelayMs = 1000;
        private String geminiApiKey; 
        
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
        
        public int getMaxPostsPerRequest() { return maxPostsPerRequest; }
        public void setMaxPostsPerRequest(int maxPostsPerRequest) { this.maxPostsPerRequest = maxPostsPerRequest; }
        
        public int getRateLimitDelayMs() { return rateLimitDelayMs; }
        public void setRateLimitDelayMs(int rateLimitDelayMs) { this.rateLimitDelayMs = rateLimitDelayMs; }
        
        public String getGeminiApiKey() { return geminiApiKey; }
        public void setGeminiApiKey(String geminiApiKey) { this.geminiApiKey = geminiApiKey; }
    }
}