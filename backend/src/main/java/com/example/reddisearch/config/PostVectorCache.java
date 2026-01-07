package com.example.reddisearch.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed cache for post vectors to avoid recomputation.
 * Stores vectorized representations of Reddit posts with configurable TTL.
 */
@Component
public class PostVectorCache {
    
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String CACHE_PREFIX = "post_vector:";
    private static final long CACHE_TTL_HOURS = 24;
    
    /**
     * Generate a unique key for a post based on its subreddit and title
     */
    private String generatePostKey(String subreddit, String title) {
        int hash = Objects.hash(subreddit, title);
        return CACHE_PREFIX + Math.abs(hash);
    }
    
    /**
     * Store a post vector in Redis with TTL
     */
    @SuppressWarnings("null")
    public void cacheVector(String subreddit, String title, Map<String, Double> vector) {
        try {
            String key = generatePostKey(subreddit, title);
            String vectorJson = objectMapper.writeValueAsString(vector);
            if (redisTemplate.hasKey(key)) {
                redisTemplate.delete(key);
            }
            Boolean result = redisTemplate.opsForValue().setIfAbsent(key, vectorJson);
            if (Boolean.TRUE.equals(result)) {
                redisTemplate.expire(key, CACHE_TTL_HOURS, TimeUnit.HOURS);
            }
        } catch (Exception e) {
            System.err.println("Error caching vector: " + e.getMessage());
        }
    }
    
    /**
     * Retrieve a cached vector from Redis, returns null if not found
     */
    @SuppressWarnings("null")
    public Map<String, Double> getVector(String subreddit, String title) {
        try {
            String key = generatePostKey(subreddit, title);
            String vectorJson = redisTemplate.opsForValue().get(key);
            if (vectorJson != null && !vectorJson.isEmpty()) {
                TypeReference<Map<String, Double>> typeRef = new TypeReference<Map<String, Double>>() {};
                return objectMapper.readValue(vectorJson, typeRef);
            }
        } catch (Exception e) {
            System.err.println("Error retrieving cached vector: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Clear all cached vectors (useful for testing)
     */
    public void clearAll() {
        try {
            Set<String> keys = redisTemplate.keys(CACHE_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            System.err.println("Error clearing vector cache: " + e.getMessage());
        }
    }
    
    /**
     * Get cache statistics
     */
    public int getCacheSize() {
        try {
            Set<String> keys = redisTemplate.keys(CACHE_PREFIX + "*");
            return keys != null ? keys.size() : 0;
        } catch (Exception e) {
            System.err.println("Error getting cache size: " + e.getMessage());
            return 0;
        }
    }
}
