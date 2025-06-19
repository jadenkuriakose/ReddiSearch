package com.example.reddisearch;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.example.reddisearch.ReddisearchApplication.AppConfig;
import com.example.reddisearch.RedditScraperService.RedditPost;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class VectorSearchService {

    @Autowired
    private RestTemplate restTemplate;
    
    @Autowired
    private AppConfig appConfig;
    
    @Autowired
    private RedditScraperService redditScraperService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private final Map<String, Integer> vocabulary = new HashMap<>();
    private final Set<String> stopWords = Set.of(
        "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by",
        "this", "that", "these", "those", "i", "you", "he", "she", "it", "we", "they", "is", "am", "are", "was", "were"
    );
    
    public static class DocumentVector {
        private final RedditPost post;
        private final Map<String, Double> vector;
        private final double magnitude;
        
        public DocumentVector(RedditPost post, Map<String, Double> vector, double magnitude) {
            this.post = post;
            this.vector = vector;
            this.magnitude = magnitude;
        }
        
        public RedditPost getPost() { return post; }
        public Map<String, Double> getVector() { return vector; }
        public double getMagnitude() { return magnitude; }
    }
    
    public static class SearchResult {
        private final String answer;
        private final int postsFound;
        
        public SearchResult(String answer, int postsFound) {
            this.answer = answer;
            this.postsFound = postsFound;
        }
        
        public String getAnswer() { return answer; }
        public int getPostsFound() { return postsFound; }
    }

    // Keep backward compatibility
    public String answerQuery(String query, String userSubreddit) {
        return answerQueryWithDetails(query, userSubreddit).getAnswer();
    }
    
    public SearchResult answerQueryWithDetails(String query, String userSubreddit) {
        try {
            // Add rate limiting
            if (appConfig.getRateLimitDelayMs() > 0) {
                Thread.sleep(appConfig.getRateLimitDelayMs());
            }
            
            List<RedditPost> posts = redditScraperService.searchRedditPosts(query, 15, userSubreddit);
            
            if (posts.isEmpty()) {
                String fallbackMessage = "I couldn't find relevant discussions. " + 
                       (userSubreddit != null ? "Try a different subreddit or " : "") +
                       "try searching r/learnprogramming or r/programming directly.";
                return new SearchResult(fallbackMessage, 0);
            }
            
            buildVocabulary(posts, query);
            Map<String, Double> queryVector = vectorizeText(query);
            List<DocumentVector> docVectors = new ArrayList<>();
            
            for (RedditPost post : posts) {
                Map<String, Double> postVector = vectorizeText(post.getCombinedText());
                double magnitude = calculateMagnitude(postVector);
                docVectors.add(new DocumentVector(post, postVector, magnitude));
            }
            
            List<DocumentVector> relevantDocs = docVectors.stream()
                .filter(doc -> doc.getMagnitude() > 0)
                .sorted((a, b) -> Double.compare(
                    cosineSimilarity(queryVector, b.getVector(), b.getMagnitude()),
                    cosineSimilarity(queryVector, a.getVector(), a.getMagnitude())))
                .limit(4)
                .collect(Collectors.toList());
            
            if (relevantDocs.isEmpty()) {
                return new SearchResult("I found some posts but couldn't match them well to your query. Try rephrasing your question.", posts.size());
            }
            
            String context = relevantDocs.stream()
                .map(doc -> String.format("Post from r/%s (Score: %d, Comments: %d):\nTitle: %s\nContent: %s\n---",
                    doc.getPost().getSubreddit(),
                    doc.getPost().getScore(),
                    doc.getPost().getComments(),
                    doc.getPost().getTitle(), 
                    doc.getPost().getContent().length() > 400 ? 
                        doc.getPost().getContent().substring(0, 400) + "..." : 
                        doc.getPost().getContent()))
                .collect(Collectors.joining("\n\n"));
            
            String answer = generateAnswerWithGemini(query, context);
            return new SearchResult(answer, posts.size());
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SearchResult("Search was interrupted. Please try again.", 0);
        } catch (Exception e) {
            System.err.println("Error in answerQuery: " + e.getMessage());
            e.printStackTrace();
            return new SearchResult("Sorry, I encountered an error while processing your query: " + e.getMessage(), 0);
        }
    }

    public String generateGeminiResponse(String prompt, double temperature, int maxTokens) {
        try {
            // Check if Gemini API key is configured
            if (appConfig.getGeminiApiKey() == null || appConfig.getGeminiApiKey().trim().isEmpty()) {
                System.err.println("Gemini API key not configured");
                return null;
            }
            
            String url = "https://generativelanguage.googleapis.com/v1/models/gemini-2.0-flash:generateContent?key=" + appConfig.getGeminiApiKey();

            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            ObjectNode requestBody = objectMapper.createObjectNode();
            ArrayNode contents = requestBody.putArray("contents");
            ObjectNode content = contents.addObject();
            ArrayNode parts = content.putArray("parts");
            ObjectNode part = parts.addObject();
            part.put("text", prompt);
            
            ObjectNode generationConfig = requestBody.putObject("generationConfig");
            generationConfig.put("temperature", temperature);
            generationConfig.put("maxOutputTokens", maxTokens);
            
            HttpEntity<String> entity = new HttpEntity<>(requestBody.toString(), headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            
            if (!response.getStatusCode().is2xxSuccessful()) {
                System.err.println("Gemini API returned status: " + response.getStatusCode());
                return null;
            }
            
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode candidates = root.path("candidates");
            
            if (candidates.isArray() && candidates.size() > 0) {
                JsonNode firstCandidate = candidates.get(0);
                JsonNode content_response = firstCandidate.path("content");
                JsonNode parts_response = content_response.path("parts");
                
                if (parts_response.isArray() && parts_response.size() > 0) {
                    return parts_response.get(0).path("text").asText();
                }
            }
            
            System.err.println("No valid response from Gemini API");
            return null;
            
        } catch (RestClientException e) {
            System.err.println("Network error calling Gemini API: " + e.getMessage());
            return null;
        } catch (Exception e) {
            System.err.println("Error calling Gemini API: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private void buildVocabulary(List<RedditPost> posts, String query) {
        vocabulary.clear();
        Set<String> allWords = new HashSet<>();
        
        allWords.addAll(extractWords(query));
        
        for (RedditPost post : posts) {
            allWords.addAll(extractWords(post.getCombinedText()));
        }
        
        int index = 0;
        for (String word : allWords) {
            if (!stopWords.contains(word) && word.length() > 2) {
                vocabulary.put(word, index++);
            }
        }
    }

    private Set<String> extractWords(String text) {
        if (text == null) return new HashSet<>();
        
        return Arrays.stream(text.toLowerCase()
                .replaceAll("[^a-zA-Z0-9\\s]", " ")
                .split("\\s+"))
                .filter(word -> !word.isEmpty())
                .collect(Collectors.toSet());
    }

    private Map<String, Double> vectorizeText(String text) {
        Map<String, Double> vector = new HashMap<>();
        Map<String, Integer> wordCount = new HashMap<>();
        
        Set<String> words = extractWords(text);
        for (String word : words) {
            if (vocabulary.containsKey(word)) {
                wordCount.put(word, wordCount.getOrDefault(word, 0) + 1);
            }
        }
        
        int totalWords = wordCount.values().stream().mapToInt(Integer::intValue).sum();
        if (totalWords == 0) return vector;
        
        for (Map.Entry<String, Integer> entry : wordCount.entrySet()) {
            double tf = (double) entry.getValue() / totalWords;
            vector.put(entry.getKey(), tf);
        }
        
        return vector;
    }

    private double calculateMagnitude(Map<String, Double> vector) {
        return Math.sqrt(vector.values().stream()
                .mapToDouble(val -> val * val)
                .sum());
    }

    private double cosineSimilarity(Map<String, Double> vectorA, Map<String, Double> vectorB, double magnitudeB) {
        if (vectorA.isEmpty() || vectorB.isEmpty()) return 0.0;
        
        double dotProduct = 0.0;
        for (Map.Entry<String, Double> entry : vectorA.entrySet()) {
            String word = entry.getKey();
            if (vectorB.containsKey(word)) {
                dotProduct += entry.getValue() * vectorB.get(word);
            }
        }
        
        double magnitudeA = calculateMagnitude(vectorA);
        if (magnitudeA == 0.0 || magnitudeB == 0.0) return 0.0;
        
        return dotProduct / (magnitudeA * magnitudeB);
    }

    private String generateAnswerWithGemini(String query, String context) {
        try {
            String prompt = String.format(
                "You are a helpful assistant that answers questions based on Reddit discussions in natural human language" +
                "Use the provided Reddit posts to answer the user's question. Be conversational and do not mention upvotes " +
                "prefer information with higher upvotes & use all relevant info to provide high level conversational insight in plain text. " +
                "If the context doesn't contain enough information, say so politely.\n\n" +
                "Question: %s\n\nRelevant Reddit posts:\n%s\n\n" +
                "Please provide a helpful answer based on this Reddit content:",
                query, context
            );

            String geminiResponse = generateGeminiResponse(prompt, 0.7, 500);
            
            if (geminiResponse != null && !geminiResponse.trim().isEmpty()) {
                return geminiResponse;
            } else {
                // Fallback response when Gemini fails
                return "I found some relevant Reddit discussions, but couldn't generate a comprehensive answer. " +
                       "Here's a summary of what I found: " + 
                       context.substring(0, Math.min(300, context.length())) + "...";
            }
            
        } catch (Exception e) {
            System.err.println("Error generating answer with Gemini: " + e.getMessage());
            return "I couldn't connect to the AI service to generate an answer. Here's what I found from Reddit: " + 
                   context.substring(0, Math.min(300, context.length())) + "...";
        }
    }
}