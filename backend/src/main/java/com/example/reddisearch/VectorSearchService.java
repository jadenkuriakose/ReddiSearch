package com.example.reddisearch;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.cache.annotation.Cacheable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.reddisearch.ReddisearchApplication.AppConfig;
import com.example.reddisearch.RedditScraperService.RedditPost;
import com.example.reddisearch.config.PostVectorCache;

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

    @Autowired
    private PostVectorCache postVectorCache;

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

    public static class SubredditRecommendation {
        private final String subreddit;
        private final String refinedQuery;
        private final String reasoning;

        public SubredditRecommendation(String subreddit, String refinedQuery, String reasoning) {
            this.subreddit = subreddit;
            this.refinedQuery = refinedQuery;
            this.reasoning = reasoning;
        }

        public String getSubreddit() { return subreddit; }
        public String getRefinedQuery() { return refinedQuery; }
        public String getReasoning() { return reasoning; }
    }

    public String answerQuery(String query, String userSubreddit) {
        return answerQueryWithDetails(query, userSubreddit).getAnswer();
    }

    @Cacheable(
        value = "vectorSearchResults",
        key = "T(java.util.Objects).hash(#query, #userSubreddit)",
        unless = "#result == null || #result.getPostsFound() == 0"
    )
    public SearchResult answerQueryWithDetails(String query, String userSubreddit) {
        try {
            if (appConfig.getRateLimitDelayMs() > 0) {
                Thread.sleep(appConfig.getRateLimitDelayMs());
            }

            System.out.println("\n STAGE 1: BROAD DISCOVERY ");
            System.out.println("Query: " + query);
            System.out.println("User-specified subreddit: " + userSubreddit);

            String initialSearchSubreddit = (userSubreddit != null && !userSubreddit.trim().isEmpty())
                ? userSubreddit.trim().replaceAll("^r/", "")
                : "all";

            List<RedditPost> initialPosts = redditScraperService.searchRedditPosts(query, 20, initialSearchSubreddit);

            List<RedditPost> filteredPosts = filterStage1Posts(initialPosts, query);
            System.out.println("[Stage 1] Filtered from " + initialPosts.size() + " to " + filteredPosts.size() + " relevant posts");

            if (filteredPosts.isEmpty()) {
                String fallbackMessage = "Couldn't find any Reddit discussions about this topic. Try rephrasing your question or specify a subreddit.";
                System.out.println("[Stage 1] No relevant posts found - returning fallback");
                return new SearchResult(fallbackMessage, 0);
            }

            System.out.println("[Stage 1] Found " + filteredPosts.size() + " relevant posts from broad search");

            System.out.println("\n STAGE 2: SUBREDDIT REDIRECTION ");
            String identifiedSubreddit = analyzePostsForBestSubreddit(filteredPosts, query);
            System.out.println("[Stage 2] Identified most relevant subreddit: r/" + identifiedSubreddit);

            System.out.println("\n STAGE 3: FOCUSED DEEP SEARCH ");
            List<RedditPost> focusedPosts = redditScraperService.searchRedditPosts(query, 15, identifiedSubreddit);
            System.out.println("[Stage 3] Found " + focusedPosts.size() + " posts from r/" + identifiedSubreddit);

            List<RedditPost> posts = focusedPosts.isEmpty() ? filteredPosts : focusedPosts;

            buildVocabulary(posts, query);
            Map<String, Double> queryVector = vectorizeText(query);
            List<DocumentVector> docVectors = new ArrayList<>();

            for (RedditPost post : posts) {
                Map<String, Double> postVector = postVectorCache.getVector(post.getSubreddit(), post.getTitle());

                if (postVector == null) {
                    postVector = vectorizeText(post.getCombinedText());
                    postVectorCache.cacheVector(post.getSubreddit(), post.getTitle(), postVector);
                }

                double magnitude = calculateMagnitude(postVector);
                docVectors.add(new DocumentVector(post, postVector, magnitude));
            }

            // Minimal change: keep only top 3 total (less prompt tokens -> faster Ollama)
            List<DocumentVector> relevantDocs = docVectors.stream()
                .filter(doc -> doc.getMagnitude() > 0)
                .sorted((a, b) -> Double.compare(
                    cosineSimilarity(queryVector, b.getVector(), b.getMagnitude()),
                    cosineSimilarity(queryVector, a.getVector(), a.getMagnitude())))
                .limit(3)
                .collect(Collectors.toList());

            if (relevantDocs.isEmpty()) {
                return new SearchResult("Found posts but couldn't match them well to your query. Try rephrasing.", posts.size());
            }

            // Minimal change: shorter snippets in context to reduce prompt size
            String context = relevantDocs.stream()
                .map(doc -> String.format(
                    "Post from r/%s (Score: %d, Comments: %d):\nTitle: %s\nContent: %s\n---",
                    doc.getPost().getSubreddit(),
                    doc.getPost().getScore(),
                    doc.getPost().getComments(),
                    doc.getPost().getTitle(),
                    doc.getPost().getContent().length() > 220
                        ? doc.getPost().getContent().substring(0, 220) + "..."
                        : doc.getPost().getContent()
                ))
                .collect(Collectors.joining("\n\n"));

            System.out.println("[Answer] Using top " + relevantDocs.size() + " relevant posts for LLM context");

            String answer = generateAnswerWithMistral(query, context);

            if (answer == null || answer.isEmpty() ||
                answer.contains("couldn't generate a comprehensive answer") ||
                answer.contains("couldn't connect to the AI service") ||
                answer.contains("Quota exhausted")) {
                System.out.println("[Fallback] LLM unavailable (quota exhausted or error), synthesizing answer from top posts...");
                answer = synthesizeFallbackAnswer(query, relevantDocs);
            }

            System.out.println("\n ANSWER GENERATED & CACHED \n");
            return new SearchResult(answer, posts.size());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SearchResult("Search was interrupted. Please try again.", 0);
        } catch (Exception e) {
            System.err.println("Error in answerQueryWithDetails: " + e.getMessage());
            e.printStackTrace();
            return new SearchResult("Sorry, I encountered an error while processing your query: " + e.getMessage(), 0);
        }
    }

    private String analyzePostsForBestSubreddit(List<RedditPost> posts, String query) {
        Map<String, Integer> subredditCount = new HashMap<>();
        for (RedditPost post : posts) {
            subredditCount.put(post.getSubreddit(), subredditCount.getOrDefault(post.getSubreddit(), 0) + 1);
        }

        String mostCommonSubreddit = subredditCount.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("all");

        int totalPosts = subredditCount.values().stream().mapToInt(Integer::intValue).sum();
        int topSubredditCount = subredditCount.getOrDefault(mostCommonSubreddit, 0);
        double percentageOfTopSubreddit = (double) topSubredditCount / totalPosts * 100;

        System.out.println("[Stage 2] Subreddit frequency analysis: " + subredditCount);
        System.out.println("[Stage 2] Most common subreddit: r/" + mostCommonSubreddit +
                          " (" + topSubredditCount + "/" + totalPosts + " posts = " +
                          String.format("%.1f%%", percentageOfTopSubreddit) + ")");

        System.out.println("[Stage 2] Identified subreddit using frequency analysis (no LLM call)");
        return mostCommonSubreddit;
    }

    public String generateMistralResponse(String prompt, double temperature, int maxTokens) {
        return generateOllamaResponse(prompt, temperature, maxTokens);
    }

    private String generateOllamaResponse(String prompt, double temperature, int maxTokens) {
        try {
            String baseUrl = appConfig.getOllamaBaseUrl();
            String model = appConfig.getOllamaModel();

            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                System.err.println("[Ollama] Base URL not configured");
                return null;
            }

            String url = baseUrl + "/api/generate";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("prompt", prompt);
            requestBody.put("temperature", temperature);

            // Minimal change: cap generation tokens (faster final query)
            requestBody.put("num_predict", Math.min(maxTokens, 180));

            requestBody.put("stream", false);

            HttpEntity<String> entity = new HttpEntity<>(requestBody.toString(), headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                int statusCode = response.getStatusCode().value();
                System.err.println("[Ollama] Error: Status " + statusCode);
                System.err.println("[Ollama] Make sure Ollama is running: ollama serve");
                System.err.println("[Ollama] Download model: ollama pull " + model);
                return null;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            String responseText = root.path("response").asText("");

            if (responseText.isEmpty()) {
                System.err.println("[Ollama] Empty response from model");
                return null;
            }

            System.out.println("[Ollama] Answer generated successfully (local, no quotas!)");
            return responseText;

        } catch (RestClientException e) {
            System.err.println("[Ollama] Connection error: " + e.getMessage());
            System.err.println("[Ollama] Make sure Ollama is running: ollama serve");
            return null;
        } catch (Exception e) {
            System.err.println("[Ollama] Error: " + e.getMessage());
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

    private List<RedditPost> filterStage1Posts(List<RedditPost> posts, String query) {
        if (query == null || query.trim().isEmpty()) {
            return posts;
        }

        String queryLower = query.toLowerCase();
        String[] topicWords = queryLower
            .replaceAll("[^a-z0-9\\s]", " ")
            .split("\\s+");

        List<String> significantWords = new ArrayList<>();
        for (String word : topicWords) {
            if (word.length() > 3 && !stopWords.contains(word)) {
                significantWords.add(word);
            }
        }

        return posts.stream()
            .filter(post -> {
                String combined = (post.getTitle() + " " + post.getContent()).toLowerCase();
                long matchCount = significantWords.stream()
                    .filter(combined::contains)
                    .count();
                return matchCount >= Math.max(1, significantWords.size() / 2);
            })
            .collect(Collectors.toList());
    }

    private String synthesizeFallbackAnswer(String query, List<DocumentVector> relevantDocs) {
        if (relevantDocs.isEmpty()) {
            return "No relevant discussions found. Try rephrasing your question or specifying a subreddit.";
        }

        List<DocumentVector> topByScore = relevantDocs.stream()
            .sorted((a, b) -> Integer.compare(b.getPost().getScore(), a.getPost().getScore()))
            .limit(3)
            .collect(Collectors.toList());

        List<String> insights = new ArrayList<>();
        StringBuilder answer = new StringBuilder();

        for (DocumentVector doc : topByScore) {
            RedditPost post = doc.getPost();
            String content = post.getContent();

            String excerpt = content;
            if (excerpt.length() > 300) {
                excerpt = excerpt.substring(0, 300);
                int lastSpace = excerpt.lastIndexOf(" ");
                if (lastSpace > 100) {
                    excerpt = excerpt.substring(0, lastSpace);
                }
                excerpt = excerpt.replaceAll("\\s+$", "") + "...";
            }

            excerpt = excerpt.replaceAll("(?i)(link to post:|source:|more info:).*", "")
                            .replaceAll("\\[.+?\\]\\(.+?\\)", "")
                            .trim();

            if (!excerpt.isEmpty()) {
                insights.add(excerpt);
            }
        }

        answer.append("Based on Reddit discussions about this topic:\n\n");

        if (insights.isEmpty()) {
            answer.append("The community discusses this topic across multiple posts, but no clear consensus emerges from the available data. ");
            answer.append("For more detailed analysis, check the relevant subreddit directly.");
        } else {
            answer.append("The community discusses several perspectives on this:\n\n");

            for (int i = 0; i < insights.size(); i++) {
                answer.append("• ").append(insights.get(i)).append("\n\n");
            }

            answer.append("These discussions represent the community's analysis. For the most accurate answer, ")
                  .append("consider checking the specific posts and comments in the relevant subreddit.");
        }

        System.out.println("[Fallback] Generated synthesis from " + topByScore.size() + " top posts");
        return answer.toString();
    }

    @Cacheable(
        value = "generatedAnswers",
        key = "T(java.util.Objects).hash(#query, #context)",
        unless = "#result == null || #result.isEmpty()"
    )
    private String generateAnswerWithMistral(String query, String context) {
        try {
            System.out.println("[LLM] Generating answer (not cached)...");
            String prompt = String.format(
                "You are a helpful assistant that answers questions based on Reddit discussions in natural human language. " +
                "Use the provided Reddit posts to answer the user's question. Be conversational and do not mention upvotes. " +
                "Based on the overall consensus across these Reddit discussions, give a direct answer and briefly explain why. If opinions differ, state which view is most commonly supported.\n" + 
                "If the context doesn't contain enough information, say so  but try and suggest some answer regardless.\n\n" +
                "Question: %s\n\nRelevant Reddit posts:\n%s\n\n" +
                "Provide a helpful answer based on this Reddit content:",
                query, context
            );
            System.out.println("[Timing] Prompt length: " + prompt.length());

            // Minimal change: fewer tokens for faster local generation
            String mistralResponse = generateMistralResponse(prompt, 0.7, 320);

            if (mistralResponse != null && !mistralResponse.trim().isEmpty()) {
                System.out.println("[LLM] Answer generation successful");
                return mistralResponse;
            } else {
                System.out.println("[LLM] Returned null/empty, using fallback");
                return "I found relevant Reddit discussions but couldn't generate a full answer. " +
                       "Here's a quick summary: " +
                       context.substring(0, Math.min(280, context.length())) + "...";
            }

        } catch (Exception e) {
            System.err.println("Error generating answer: " + e.getMessage());
            return "I couldn't generate an answer right now. Here's what I found: " +
                   context.substring(0, Math.min(280, context.length())) + "...";
        }
    }
}
