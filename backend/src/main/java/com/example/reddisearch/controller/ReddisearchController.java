package com.example.reddisearch.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
        private String error;
        private int postsFound;
        
        public QueryResponse(String query, String answer, long processingTimeMs) {
            this.query = query;
            this.answer = answer;
            this.processingTimeMs = processingTimeMs;
        }
        
        public QueryResponse(String query, String error) {
            this.query = query;
            this.error = error;
            this.processingTimeMs = 0;
        }
        
        public String getAnswer() { return answer; }
        public String getQuery() { return query; }
        public long getProcessingTimeMs() { return processingTimeMs; }
        public String getError() { return error; }
        public int getPostsFound() { return postsFound; }
        
        public void setPostsFound(int postsFound) { this.postsFound = postsFound; }
    }

    @PostMapping("/search")
    public ResponseEntity<QueryResponse> searchQuestion(@RequestBody QueryRequest request) {
        // Validate input
        if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
            return ResponseEntity.badRequest()
                .body(new QueryResponse("", "Query cannot be empty"));
        }
        
        long startTime = System.currentTimeMillis();
        
        try {
            VectorSearchService.SearchResult result = vectorSearchService.answerQueryWithDetails(
                request.getQuery(), request.getSubreddit());
            
            long processingTime = System.currentTimeMillis() - startTime;
            QueryResponse response = new QueryResponse(request.getQuery(), result.getAnswer(), processingTime);
            response.setPostsFound(result.getPostsFound());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            long processingTime = System.currentTimeMillis() - startTime;
            System.err.println("Error in searchQuestion: " + e.getMessage());
            
            QueryResponse errorResponse = new QueryResponse(request.getQuery(), 
                "Sorry, I encountered an error while processing your query. Please try again.");
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorResponse);
        }
    }
    
    @GetMapping("/search")
    public ResponseEntity<QueryResponse> searchQuestionGet(@RequestParam String q, @RequestParam(required = false) String subreddit) {
        // Validate input
        if (q == null || q.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                .body(new QueryResponse("", "Query parameter 'q' cannot be empty"));
        }
        
        long startTime = System.currentTimeMillis();
        
        try {
            VectorSearchService.SearchResult result = vectorSearchService.answerQueryWithDetails(q, subreddit);
            
            long processingTime = System.currentTimeMillis() - startTime;
            QueryResponse response = new QueryResponse(q, result.getAnswer(), processingTime);
            response.setPostsFound(result.getPostsFound());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            long processingTime = System.currentTimeMillis() - startTime;
            System.err.println("Error in searchQuestionGet: " + e.getMessage());
            
            QueryResponse errorResponse = new QueryResponse(q, 
                "Sorry, I encountered an error while processing your query. Please try again.");
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorResponse);
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("ReddiSearch Application is running! 🚀");
    }
    
    @GetMapping("/")
    public String home() {
        return "<!DOCTYPE html>" +
               "<html><head><title>ReddiSearch</title>" +
               "<meta charset='UTF-8'>" +
               "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
               "</head>" +
               "<body style='font-family: Arial, sans-serif; max-width: 800px; margin: 50px auto; padding: 20px; background-color: #f8f9fa;'>" +
               "<h1 style='color: #2c3e50;'>🔍 ReddiSearch</h1>" +
               "<p style='color: #6c757d; font-size: 16px;'>Ask any question and get answers based on Reddit discussions!</p>" +
               "<form onsubmit='searchQuestion(event)' style='margin: 30px 0;'>" +
               "<div style='margin-bottom: 15px;'>" +
               "<input type='text' id='query' placeholder='Ask anything...' " +
               "style='width: 100%; padding: 12px; font-size: 16px; border: 2px solid #dee2e6; border-radius: 6px; box-sizing: border-box;'/>" +
               "</div>" +
               "<div style='display: flex; gap: 10px; align-items: center;'>" +
               "<input type='text' id='subreddit' placeholder='Optional: subreddit (e.g., programming)' " +
               "style='flex: 1; padding: 12px; font-size: 16px; border: 2px solid #dee2e6; border-radius: 6px;'/>" +
               "<button type='submit' style='padding: 12px 24px; font-size: 16px; background-color: #007bff; color: white; border: none; border-radius: 6px; cursor: pointer;'>Search</button>" +
               "</div>" +
               "</form>" +
               "<div id='result' style='margin-top: 30px; padding: 20px; background: white; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); display: none;'></div>" +
               "<script>" +
               "function searchQuestion(e) {" +
               "  e.preventDefault();" +
               "  const query = document.getElementById('query').value.trim();" +
               "  const subreddit = document.getElementById('subreddit').value.trim();" +
               "  if (!query) {" +
               "    alert('Please enter a question!');" +
               "    return;" +
               "  }" +
               "  const resultDiv = document.getElementById('result');" +
               "  resultDiv.style.display = 'block';" +
               "  resultDiv.innerHTML = '<p style=\"color: #007bff;\">🔍 Searching Reddit...</p>';" +
               "  const url = subreddit ? '/api/search?q=' + encodeURIComponent(query) + '&subreddit=' + encodeURIComponent(subreddit) : '/api/search?q=' + encodeURIComponent(query);" +
               "  fetch(url)" +
               "    .then(r => r.json())" +
               "    .then(data => {" +
               "      if (data.error) {" +
               "        resultDiv.innerHTML = '<p style=\"color: red;\">Error: ' + data.error + '</p>';" +
               "      } else {" +
               "        const postsInfo = data.postsFound > 0 ? ' (Found ' + data.postsFound + ' relevant posts)' : '';" +
               "        resultDiv.innerHTML = '<h3 style=\"color: #2c3e50;\">Question: ' + data.query + '</h3>' +" +
               "                             '<div style=\"padding: 15px; background-color: #f8f9fa; border-left: 4px solid #007bff; margin: 15px 0;\">' +" +
               "                             data.answer.replace(/\\n/g, '<br>') + '</div>' +" +
               "                             '<small style=\"color: #6c757d;\">Processed in ' + data.processingTimeMs + 'ms' + postsInfo + '</small>';" +
               "      }" +
               "    })" +
               "    .catch(err => {" +
               "      console.error('Fetch error:', err);" +
               "      resultDiv.innerHTML = '<p style=\"color: red;\">Error: Unable to connect to the server. Please try again.</p>';" +
               "    });" +
               "}" +
               "</script>" +
               "</body></html>";
    }
}