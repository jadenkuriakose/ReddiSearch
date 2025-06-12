package com.example.reddisearch.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.example.reddisearch.VectorSearchService;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ReddisearchController {

    @Autowired
    private VectorSearchService vectorSearchService;

    public static class QueryRequest {
        private String query;
        private String subreddit;
        
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public String getSubreddit() { return subreddit; }
        public void setSubreddit(String subreddit) { this.subreddit = subreddit; }
    }

    public static class QueryResponse {
        private String answer;
        private String query;
        private long processingTimeMs;
        
        public QueryResponse(String query, String answer, long processingTimeMs) {
            this.query = query;
            this.answer = answer;
            this.processingTimeMs = processingTimeMs;
        }
        
        public String getAnswer() { return answer; }
        public String getQuery() { return query; }
        public long getProcessingTimeMs() { return processingTimeMs; }
    }

    @PostMapping("/search")
    public QueryResponse searchQuestion(@RequestBody QueryRequest request) {
        long startTime = System.currentTimeMillis();
        String answer = vectorSearchService.answerQuery(request.getQuery(), request.getSubreddit());
        long processingTime = System.currentTimeMillis() - startTime;
        return new QueryResponse(request.getQuery(), answer, processingTime);
    }
    
    @GetMapping("/search")
    public QueryResponse searchQuestionGet(@RequestParam String q, 
                                         @RequestParam(required = false) String subreddit) {
        long startTime = System.currentTimeMillis();
        String answer = vectorSearchService.answerQuery(q, subreddit);
        long processingTime = System.currentTimeMillis() - startTime;
        return new QueryResponse(q, answer, processingTime);
    }

    @GetMapping("/health")
    public String health() {
        return "ReddiSearch Application is running! 🚀";
    }
    
    @GetMapping("/")
    public String home() {
        return "<!DOCTYPE html>" +
               "<html><head><title>ReddiSearch</title></head>" +
               "<body style='font-family: Arial, sans-serif; max-width: 800px; margin: 50px auto; padding: 20px;'>" +
               "<h1>🔍 ReddiSearch</h1>" +
               "<p>Ask any question and get answers based on Reddit discussions!</p>" +
               "<form onsubmit='searchQuestion(event)'>" +
               "<input type='text' id='query' placeholder='Ask anything...' style='width: 70%; padding: 10px; font-size: 16px;'/>" +
               "<input type='text' id='subreddit' placeholder='Optional: subreddit' style='width: 20%; padding: 10px; font-size: 16px; margin-left: 10px;'/>" +
               "<button type='submit' style='padding: 10px 20px; font-size: 16px; margin-left: 10px;'>Search</button>" +
               "</form>" +
               "<div id='result' style='margin-top: 30px; padding: 20px; background: #f5f5f5; border-radius: 8px; display: none;'></div>" +
               "<script>" +
               "function searchQuestion(e) {" +
               "  e.preventDefault();" +
               "  const query = document.getElementById('query').value;" +
               "  const subreddit = document.getElementById('subreddit').value;" +
               "  if (!query) return;" +
               "  const resultDiv = document.getElementById('result');" +
               "  resultDiv.style.display = 'block';" +
               "  resultDiv.innerHTML = '<p>🔍 Searching Reddit...</p>';" +
               "  const url = subreddit ? '/api/search?q=' + encodeURIComponent(query) + '&subreddit=' + encodeURIComponent(subreddit) : '/api/search?q=' + encodeURIComponent(query);" +
               "  fetch(url)" +
               "    .then(r => r.json())" +
               "    .then(data => {" +
               "      resultDiv.innerHTML = '<h3>Question: ' + data.query + '</h3><p>' + data.answer.replace(/\\n/g, '<br>') + '</p><small>Processed in ' + data.processingTimeMs + 'ms</small>';" +
               "    })" +
               "    .catch(err => {" +
               "      resultDiv.innerHTML = '<p style=\"color: red;\">Error: ' + err.message + '</p>';" +
               "    });" +
               "}" +
               "</script>" +
               "</body></html>";
    }
}