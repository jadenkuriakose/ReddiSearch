package com.example.reddisearch;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.reddisearch.ReddisearchApplication.AppConfig;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RedditScraperService {

    @Autowired
    private RestTemplate restTemplate;
    
    @Autowired
    private AppConfig appConfig;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String REDDIT_BASE_URL = "https://www.reddit.com";

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
    }

    public List<RedditPost> searchRedditPosts(String query, int limit, String userSubreddit) {
        try {
            String subreddit = (userSubreddit != null && !userSubreddit.trim().isEmpty()) ? 
                userSubreddit.trim().replaceAll("^r/", "") : "all";
            
            List<RedditPost> allPosts = new ArrayList<>();
            
            // Try both search and recent posts for better results
            if (query != null && !query.trim().isEmpty()) {
                // First try searching for the full query
                allPosts.addAll(searchRedditByQuery(query, subreddit, limit));
                
                System.out.println("[Scraper] Found " + allPosts.size() + " posts with full query in r/" + subreddit);
                
                // If we get very few results, try keyword-based search
                if (allPosts.size() < limit / 2) {
                    System.out.println("[Scraper] Low results, trying keyword extraction");
                    String[] keywords = extractKeywords(query);
                    for (String keyword : keywords) {
                        if (keyword.length() > 2) {
                            List<RedditPost> keywordPosts = searchRedditByQuery(keyword, subreddit, limit);
                            for (RedditPost post : keywordPosts) {
                                if (allPosts.stream().noneMatch(p -> p.getUrl().equals(post.getUrl()))) {
                                    allPosts.add(post);
                                }
                            }
                            if (allPosts.size() >= limit) break;
                        }
                    }
                }
                
                // If still low on results, get recent posts and filter
                if (allPosts.size() < limit) {
                    System.out.println("[Scraper] Still low, fetching and filtering recent posts");
                    List<RedditPost> recentPosts = fetchRecentPosts(subreddit, limit * 3);
                    List<RedditPost> filteredPosts = filterPostsByQuery(recentPosts, query);
                    
                    // Add posts that aren't already in our list
                    for (RedditPost post : filteredPosts) {
                        if (allPosts.stream().noneMatch(p -> p.getUrl().equals(post.getUrl()))) {
                            allPosts.add(post);
                        }
                    }
                }
                
                // Last resort: if subreddit search yielded nothing, try "all" subreddit
                if (allPosts.size() < 3 && !subreddit.equals("all")) {
                    System.out.println("[Scraper] Very low results in r/" + subreddit + ", falling back to r/all");
                    List<RedditPost> allSubredditPosts = searchRedditByQuery(query, "all", limit);
                    for (RedditPost post : allSubredditPosts) {
                        if (allPosts.stream().noneMatch(p -> p.getUrl().equals(post.getUrl()))) {
                            allPosts.add(post);
                        }
                    }
                }
            } else {
                // Just get recent posts if no query
                allPosts.addAll(fetchRecentPosts(subreddit, limit));
            }
            
            List<RedditPost> result = allPosts.stream()
                .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore()))
                .limit(limit)
                .collect(Collectors.toList());
            
            System.out.println("[Scraper] Final result: " + result.size() + " posts");
            return result;
            
        } catch (Exception e) {
            System.err.println("Error scraping Reddit data: " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }
    
    /**
     * Extract important keywords from a query for fallback searching
     */
    private String[] extractKeywords(String query) {
        // Remove common words and split
        String[] parts = query.toLowerCase()
            .replaceAll("[^a-z0-9\\s]", "")
            .split("\\s+");
        
        List<String> keywords = new ArrayList<>();
        for (String part : parts) {
            // Keep words longer than 3 chars that aren't common stop words
            if (part.length() > 3 && !isCommonStopWord(part)) {
                keywords.add(part);
            }
        }
        
        return keywords.toArray(new String[0]);
    }
    
    private boolean isCommonStopWord(String word) {
        Set<String> stopWords = Set.of("the", "and", "that", "with", "from", "have", "this", "what", "when", "where", "who", "why", "how");
        return stopWords.contains(word);
    }
    
    private List<RedditPost> searchRedditByQuery(String query, String subreddit, int limit) {
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String searchUrl = String.format("%s/r/%s/search.json?q=%s&restrict_sr=1&sort=relevance&limit=%d", 
                REDDIT_BASE_URL, subreddit, encodedQuery, Math.min(limit, appConfig.getMaxPostsPerRequest()));
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", appConfig.getUserAgent());
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            ResponseEntity<String> response = restTemplate.exchange(searchUrl, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                return parseRedditJson(response.getBody(), subreddit);
            }
            
        } catch (RestClientException e) {
            System.err.println("Network error searching Reddit: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Error searching Reddit by query: " + e.getMessage());
        }
        
        return Collections.emptyList();
    }
    
    private List<RedditPost> fetchRecentPosts(String subreddit, int limit) {
        try {
            String url = String.format("%s/r/%s/new.json?limit=%d", 
                REDDIT_BASE_URL, subreddit, Math.min(limit, appConfig.getMaxPostsPerRequest()));
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", appConfig.getUserAgent());
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                return parseRedditJson(response.getBody(), subreddit);
            }
            
        } catch (RestClientException e) {
            System.err.println("Network error fetching recent posts: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Error fetching recent posts: " + e.getMessage());
        }
        
        return Collections.emptyList();
    }
    
    private List<RedditPost> filterPostsByQuery(List<RedditPost> posts, String query) {
        if (query == null || query.trim().isEmpty()) {
            return posts;
        }
        
        String queryLower = query.toLowerCase();
        return posts.stream()
            .filter(post -> post.getTitle().toLowerCase().contains(queryLower) || 
                          post.getContent().toLowerCase().contains(queryLower))
            .collect(Collectors.toList());
    }

    private List<RedditPost> parseRedditJson(String jsonResponse, String subreddit) throws Exception {
        JsonNode root = objectMapper.readTree(jsonResponse);
        JsonNode posts = root.path("data").path("children");
        
        List<RedditPost> redditPosts = new ArrayList<>();
        
        for (JsonNode post : posts) {
            JsonNode data = post.path("data");
            String title = data.path("title").asText("");
            String content = data.path("selftext").asText("");
            String url = REDDIT_BASE_URL + data.path("permalink").asText("");
            String actualSubreddit = data.path("subreddit").asText(subreddit);
            int score = data.path("score").asInt(0);
            int comments = data.path("num_comments").asInt(0);
            
            // Skip deleted or removed posts
            if (title.equals("[deleted]") || title.equals("[removed]") || 
                content.equals("[deleted]") || content.equals("[removed]")) {
                continue;
            }
            
            // Only include posts with some content or reasonable engagement
            if (!title.trim().isEmpty() && (score > 0 || comments > 0 || !content.trim().isEmpty())) {
                redditPosts.add(new RedditPost(title, content, url, actualSubreddit, score, comments));
            }
        }
        
        return redditPosts;
    }
    
    /**
     * Get trending/hot posts from a subreddit
     */
    public List<RedditPost> getHotPosts(String subreddit, int limit) {
        try {
            String cleanSubreddit = (subreddit != null && !subreddit.trim().isEmpty()) ? 
                subreddit.trim().replaceAll("^r/", "") : "all";
            
            String url = String.format("%s/r/%s/hot.json?limit=%d", 
                REDDIT_BASE_URL, cleanSubreddit, Math.min(limit, appConfig.getMaxPostsPerRequest()));
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", appConfig.getUserAgent());
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                return parseRedditJson(response.getBody(), cleanSubreddit);
            }
            
        } catch (RestClientException e) {
            System.err.println("Network error fetching hot posts: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Error fetching hot posts: " + e.getMessage());
        }
        
        return Collections.emptyList();
    }
    
    /**
     * Get top posts from a subreddit with time filter
     */
    public List<RedditPost> getTopPosts(String subreddit, int limit, String timeFilter) {
        try {
            String cleanSubreddit = (subreddit != null && !subreddit.trim().isEmpty()) ? 
                subreddit.trim().replaceAll("^r/", "") : "all";
            
            // Valid time filters: hour, day, week, month, year, all
            String validTimeFilter = (timeFilter != null && 
                Arrays.asList("hour", "day", "week", "month", "year", "all").contains(timeFilter.toLowerCase())) 
                ? timeFilter.toLowerCase() : "day";
            
            String url = String.format("%s/r/%s/top.json?t=%s&limit=%d", 
                REDDIT_BASE_URL, cleanSubreddit, validTimeFilter, Math.min(limit, appConfig.getMaxPostsPerRequest()));
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", appConfig.getUserAgent());
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                return parseRedditJson(response.getBody(), cleanSubreddit);
            }
            
        } catch (RestClientException e) {
            System.err.println("Network error fetching top posts: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Error fetching top posts: " + e.getMessage());
        }
        
        return Collections.emptyList();
    }
    
    /**
     * Search across multiple subreddits
     */
    public List<RedditPost> searchMultipleSubreddits(String query, List<String> subreddits, int limitPerSubreddit) {
        List<RedditPost> allPosts = new ArrayList<>();
        
        for (String subreddit : subreddits) {
            List<RedditPost> posts = searchRedditPosts(query, limitPerSubreddit, subreddit);
            allPosts.addAll(posts);
        }
        
        // Sort by score and remove duplicates
        return allPosts.stream()
            .collect(Collectors.toMap(
                RedditPost::getUrl,
                post -> post,
                (existing, replacement) -> existing.getScore() > replacement.getScore() ? existing : replacement
            ))
            .values()
            .stream()
            .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore()))
            .collect(Collectors.toList());
    }
}