package com.example.reddisearch;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.reddisearch.ReddisearchApplication.AppConfig;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class RedditScraperService {

    @Autowired
    private RestTemplate restTemplate;
    
    @Autowired
    private AppConfig appConfig;
    
    @Autowired
    private VectorSearchService vectorSearchService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    public static class RedditPost {
        private String title;
        private String content;
        private String url;
        private String subreddit;
        private int score;
        private int comments;

        public RedditPost(String title, String content, String url, String subreddit, int score, int comments) {
            this.title = title;
            this.content = content;
            this.url = url;
            this.subreddit = subreddit;
            this.score = score;
            this.comments = comments;
        }

        public String getTitle() { return title; }
        public String getContent() { return content; }
        public String getUrl() { return url; }
        public String getSubreddit() { return subreddit; }
        public int getScore() { return score; }
        public int getComments() { return comments; }
        
        public String getCombinedText() {
            return title + "\n\n" + content;
        }
        
        public String getFullText() {
            return String.format("Subreddit: r/%s\nTitle: %s\nContent: %s\nScore: %d Comments: %d", 
                subreddit, title, content, score, comments);
        }
    }

    public List<RedditPost> searchRedditPosts(String query, int limit, String userSubreddit) {
        try {
            String subreddit;
            if (userSubreddit != null && !userSubreddit.trim().isEmpty()) {
                subreddit = userSubreddit.trim().replaceAll("^r/", "");
            } else {
                String geminiSuggestedSub = getOptimalSubredditFromGemini(query);
                subreddit = (geminiSuggestedSub != null && !geminiSuggestedSub.isEmpty()) 
                    ? geminiSuggestedSub 
                    : findRelevantSubreddit(query);
            }
            
            String url = String.format("https://www.reddit.com/r/%s/hot.json?limit=%d", subreddit, Math.min(limit, 25));
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", appConfig.getUserAgent());
            headers.set("Accept", "application/json");
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            
            List<RedditPost> posts = parseRedditJson(response.getBody(), subreddit);
            
            if (posts.size() < 3 && (userSubreddit == null || userSubreddit.trim().isEmpty())) {
                posts.addAll(searchBackupSubreddits(query, limit - posts.size()));
            }
            
            return posts.stream().limit(limit).collect(Collectors.toList());
            
        } catch (Exception e) {
            System.err.println("Error scraping Reddit data: " + e.getMessage());
            return searchBackupSubreddits(query, limit);
        }
    }

    private String getOptimalSubredditFromGemini(String query) {
        try {
            String prompt = String.format(
                "Suggest the single most relevant Reddit subreddit for this query: '%s'. " +
                "Return only the subreddit name without r/ prefix or any other text. " +
                "Choose from popular, active subreddits that would have good discussions.",
                query
            );

            String response = vectorSearchService.generateGeminiResponse(prompt, 0.3, 30);
            if (response != null) {
                // Clean and validate the response
                String cleaned = response.trim()
                    .replaceAll("^r/", "")
                    .replaceAll("[^a-zA-Z0-9]", "")
                    .toLowerCase();
                if (!cleaned.isEmpty() && cleaned.length() <= 20) {
                    return cleaned;
                }
            }
        } catch (Exception e) {
            System.err.println("Error getting subreddit suggestion from Gemini: " + e.getMessage());
        }
        return null;
    }

    private String findRelevantSubreddit(String query) {
        Map<String, String> keywordToSubreddit = new HashMap<>();
        keywordToSubreddit.put("programming", "programming");
        keywordToSubreddit.put("code", "programming");
        keywordToSubreddit.put("java", "java");
        keywordToSubreddit.put("python", "Python");
        keywordToSubreddit.put("javascript", "javascript");
        keywordToSubreddit.put("technology", "technology");
        keywordToSubreddit.put("tech", "technology");
        keywordToSubreddit.put("science", "science");
        keywordToSubreddit.put("cooking", "Cooking");
        keywordToSubreddit.put("recipe", "recipes");
        keywordToSubreddit.put("fitness", "Fitness");
        keywordToSubreddit.put("workout", "Fitness");
        keywordToSubreddit.put("travel", "travel");
        keywordToSubreddit.put("movies", "movies");
        keywordToSubreddit.put("film", "movies");
        keywordToSubreddit.put("books", "books");
        keywordToSubreddit.put("reading", "books");
        keywordToSubreddit.put("music", "Music");
        keywordToSubreddit.put("gaming", "gaming");
        keywordToSubreddit.put("game", "gaming");
        keywordToSubreddit.put("health", "Health");
        keywordToSubreddit.put("money", "personalfinance");
        keywordToSubreddit.put("finance", "personalfinance");
        keywordToSubreddit.put("career", "careerguidance");
        keywordToSubreddit.put("job", "jobs");
        
        String lowerQuery = query.toLowerCase();
        for (Map.Entry<String, String> entry : keywordToSubreddit.entrySet()) {
            if (lowerQuery.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "AskReddit";
    }

    private List<RedditPost> parseRedditJson(String jsonResponse, String subreddit) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode posts = root.path("data").path("children");
            
            List<RedditPost> redditPosts = new ArrayList<>();
            
            for (JsonNode post : posts) {
                JsonNode data = post.path("data");
                String title = data.path("title").asText("");
                String content = data.path("selftext").asText("");
                String url = "https://reddit.com" + data.path("permalink").asText("");
                int score = data.path("score").asInt(0);
                int comments = data.path("num_comments").asInt(0);
                
                content = cleanRedditText(content);
                
                if ((!content.isEmpty() && content.length() > 30) || (score > 10 && comments > 5)) {
                    if (content.isEmpty()) {
                        content = "This post had high engagement but no text content.";
                    }
                    redditPosts.add(new RedditPost(title, content, url, subreddit, score, comments));
                }
            }
            
            return redditPosts.stream()
                .sorted((a, b) -> Integer.compare(
                    (b.getScore() + b.getComments() * 2), 
                    (a.getScore() + a.getComments() * 2)))
                .collect(Collectors.toList());
            
        } catch (Exception e) {
            System.err.println("Error parsing Reddit JSON: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<RedditPost> searchBackupSubreddits(String query, int limit) {
        String[] backupSubs = {"AskReddit", "explainlikeimfive", "LifeProTips", "todayilearned"};
        List<RedditPost> allPosts = new ArrayList<>();
        
        for (String sub : backupSubs) {
            try {
                String url = String.format("https://www.reddit.com/r/%s/hot.json?limit=10", sub);
                HttpHeaders headers = new HttpHeaders();
                headers.set("User-Agent", appConfig.getUserAgent());
                HttpEntity<String> entity = new HttpEntity<>(headers);
                
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                allPosts.addAll(parseRedditJson(response.getBody(), sub));
                
                if (allPosts.size() >= limit) break;
                
                Thread.sleep(500);
                
            } catch (Exception e) {
                System.err.println("Error with backup subreddit " + sub + ": " + e.getMessage());
            }
        }
        
        return allPosts.stream().limit(limit).collect(Collectors.toList());
    }

    private String cleanRedditText(String text) {
        if (text == null) return "";
        
        text = text.replaceAll("\\*\\*(.*?)\\*\\*", "$1");
        text = text.replaceAll("\\*(.*?)\\*", "$1");
        text = text.replaceAll("~~(.*?)~~", "$1");
        text = text.replaceAll("\\[(.*?)\\]\\(.*?\\)", "$1");
        
        text = text.replaceAll("\\n{3,}", "\n\n");
        text = text.trim();
        
        if (text.length() > 1000) {
            text = text.substring(0, 1000) + "...";
        }
        
        return text;
    }
}