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
    
    /**
     * Three-Stage Smart Topic Discovery + Subreddit Redirection:
     * Stage 1: Broad search to understand the topic
     * Stage 2: Analyze top posts to identify most relevant subreddit
     * Stage 3: Search that subreddit for deeper, focused context
     */
    @Cacheable(
        value = "vectorSearchResults",
        key = "T(java.util.Objects).hash(#query, #userSubreddit)",
        unless = "#result == null || #result.getPostsFound() == 0"
    )
    public SearchResult answerQueryWithDetails(String query, String userSubreddit) {
        try {
            // rate limiting 
            if (appConfig.getRateLimitDelayMs() > 0) {
                Thread.sleep(appConfig.getRateLimitDelayMs());
            }
            
            // STAGE 1: Broad search to understand the topic
            System.out.println("\n STAGE 1: BROAD DISCOVERY ");
            System.out.println("Query: " + query);
            System.out.println("User-specified subreddit: " + userSubreddit);
            
            String initialSearchSubreddit = (userSubreddit != null && !userSubreddit.trim().isEmpty()) 
                ? userSubreddit.trim().replaceAll("^r/", "")
                : "all";
            
            List<RedditPost> initialPosts = redditScraperService.searchRedditPosts(query, 20, initialSearchSubreddit);
            
            // Filter Stage 1 results to remove completely off-topic posts
            // A post is relevant if it mentions multiple key terms from the query
            List<RedditPost> filteredPosts = filterStage1Posts(initialPosts, query);
            System.out.println("[Stage 1] Filtered from " + initialPosts.size() + " to " + filteredPosts.size() + " relevant posts");
            
            if (filteredPosts.isEmpty()) {
                String fallbackMessage = "Couldn't find any Reddit discussions about this topic. Try rephrasing your question or specify a subreddit.";
                System.out.println("[Stage 1] No relevant posts found - returning fallback");
                return new SearchResult(fallbackMessage, 0);
            }
            
            System.out.println("[Stage 1] Found " + filteredPosts.size() + " relevant posts from broad search");
            
            // STAGE 2: Analyze top posts to identify most relevant subreddit
            System.out.println("\n STAGE 2: SUBREDDIT REDIRECTION ");
            String identifiedSubreddit = analyzePostsForBestSubreddit(filteredPosts, query);
            System.out.println("[Stage 2] Identified most relevant subreddit: r/" + identifiedSubreddit);
            
            // STAGE 3: Search the identified subreddit for deeper, focused context
            System.out.println("\n STAGE 3: FOCUSED DEEP SEARCH ");
            List<RedditPost> focusedPosts = redditScraperService.searchRedditPosts(query, 15, identifiedSubreddit);
            System.out.println("[Stage 3] Found " + focusedPosts.size() + " posts from r/" + identifiedSubreddit);
            
            // Use focused posts if found, otherwise use filtered initial posts
            List<RedditPost> posts = focusedPosts.isEmpty() ? filteredPosts : focusedPosts;
            
            // Build vocabulary and vectorize
            buildVocabulary(posts, query);
            Map<String, Double> queryVector = vectorizeText(query);
            List<DocumentVector> docVectors = new ArrayList<>();
            
            for (RedditPost post : posts) {
                // Try to get cached vector first to avoid recomputation
                Map<String, Double> postVector = postVectorCache.getVector(post.getSubreddit(), post.getTitle());
                
                if (postVector == null) {
                    // Vector not in cache, compute it
                    postVector = vectorizeText(post.getCombinedText());
                    // Store in cache for future use
                    postVectorCache.cacheVector(post.getSubreddit(), post.getTitle(), postVector);
                }
                
                double magnitude = calculateMagnitude(postVector);
                docVectors.add(new DocumentVector(post, postVector, magnitude));
            }
            
            // Filter and sort documents based on cosine similarity
            List<DocumentVector> relevantDocs = docVectors.stream()
                .filter(doc -> doc.getMagnitude() > 0)
                .sorted((a, b) -> Double.compare(
                    cosineSimilarity(queryVector, b.getVector(), b.getMagnitude()),
                    cosineSimilarity(queryVector, a.getVector(), a.getMagnitude())))
                .limit(5)  // Keep top 5 for context, will use best 3 for final answer
                .collect(Collectors.toList());
            
            if (relevantDocs.isEmpty()) {
                return new SearchResult("Found posts but couldn't match them well to your query. Try rephrasing.", posts.size());
            }
            
            // Build context from top 3 most relevant posts for the LLM
            // (reduces token usage while maintaining answer quality)
            List<DocumentVector> topForAnswer = relevantDocs.stream()
                .limit(3)
                .collect(Collectors.toList());
            
            String context = topForAnswer.stream()
                .map(doc -> String.format("Post from r/%s (Score: %d, Comments: %d):\nTitle: %s\nContent: %s\n---",
                    doc.getPost().getSubreddit(),
                    doc.getPost().getScore(),
                    doc.getPost().getComments(),
                    doc.getPost().getTitle(), 
                    doc.getPost().getContent().length() > 400 ? 
                        doc.getPost().getContent().substring(0, 400) + "..." : 
                        doc.getPost().getContent()))
                .collect(Collectors.joining("\n\n"));
            
            System.out.println("[Answer] Using top 3 of " + relevantDocs.size() + " relevant posts for LLM context");
            
            String answer = generateAnswerWithGemini(query, context);
            
            // If LLM failed to generate, use intelligent fallback that synthesizes the top posts
            if (answer == null || answer.isEmpty() ||
                answer.contains("couldn't generate a comprehensive answer") || 
                answer.contains("couldn't connect to the AI service") ||
                answer.contains("Quota exhausted")) {
                System.out.println("[Fallback] LLM unavailable (quota exhausted or error), synthesizing answer from top posts...");
                answer = synthesizeFallbackAnswer(query, topForAnswer);
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

    /**
     * Stage 2: Analyze the top posts from broad search to identify the best subreddit.
     * Uses ONLY frequency analysis (no LLM) to minimize API quota usage.
     * LLM calls are reserved for final answer generation only.
     */
    private String analyzePostsForBestSubreddit(List<RedditPost> posts, String query) {
        // Count subreddit frequency in results
        Map<String, Integer> subredditCount = new HashMap<>();
        for (RedditPost post : posts) {
            subredditCount.put(post.getSubreddit(), subredditCount.getOrDefault(post.getSubreddit(), 0) + 1);
        }
        
        // Find most common subreddit
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
        
        // NO LLM CALL HERE - frequency analysis is sufficient and more efficient
        // This saves ~1 LLM call per query, allowing more quota for final answer generation
        System.out.println("[Stage 2] Identified subreddit using frequency analysis (no LLM call)");
        return mostCommonSubreddit;
    }

    public String generateGeminiResponse(String prompt, double temperature, int maxTokens) {
        return generateOllamaResponse(prompt, temperature, maxTokens);
    }

    /**
     * Generate response using local Ollama (no API key, no quotas, no rate limits!)
     * Ollama runs locally on your machine at localhost:11434
     * 
     * Setup:
     * 1. Install: brew install ollama
     * 2. Download model: ollama pull mistral
     * 3. Start server: ollama serve
     */
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
            
            // Ollama API request format
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("prompt", prompt);
            requestBody.put("temperature", temperature);
            requestBody.put("num_predict", maxTokens);
            requestBody.put("stream", false); // Get complete response at once
            
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
            System.err.println("[Ollama] Get Ollama at: https://ollama.ai");
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

    /**
     * Built-in subreddit knowledge base for common topics.
     * Used as fallback when LLM fails or as hints for better CoT.
     */
    private static final Map<String, List<String>> SUBREDDIT_KNOWLEDGE_BASE = new HashMap<String, List<String>>() {{
        put("beyblade", Arrays.asList("Beyblade", "beybladememes", "anime"));
        put("kyoya", Arrays.asList("Beyblade", "beybladememes", "anime"));
        put("gingka", Arrays.asList("Beyblade", "beybladememes", "anime"));
        put("anime", Arrays.asList("anime", "Animemes", "Manga"));
        put("manga", Arrays.asList("manga", "Manhua", "Manhwa"));
        put("pokemon", Arrays.asList("pokemon", "Pokémon", "PokemonGO"));
        put("gaming", Arrays.asList("gaming", "Games", "VideoGames"));
        put("programming", Arrays.asList("learnprogramming", "programming", "coding"));
        put("python", Arrays.asList("learnprogramming", "Python", "django"));
        put("javascript", Arrays.asList("learnprogramming", "javascript", "webdev"));
        put("health", Arrays.asList("Health", "fitness", "AskDocs"));
        put("science", Arrays.asList("explainlikeimfive", "science", "AskScience"));
        put("history", Arrays.asList("history", "AskHistorians", "todayilearned"));
        put("music", Arrays.asList("Music", "hiphopheads", "metal"));
        put("movies", Arrays.asList("movies", "MovieDetails", "TrueFilm"));
        put("tv", Arrays.asList("television", "TV_Critics", "BingeWatching"));
        put("books", Arrays.asList("books", "literature", "writing"));
        put("advice", Arrays.asList("relationship_advice", "AmItheAsshole", "LifeAdvice"));
    }};

    /**
     * Stage 1: Use Chain-of-Thought prompting to identify the best subreddit and refine the search query.
     * The LLM reasons through the problem step-by-step.
     * Results are cached in Redis.
     */
    private SubredditRecommendation identifyBestSubredditWithCoT(String query, String userSubreddit) {
        try {
            // If user already specified a subreddit, use it but still refine the query
            if (userSubreddit != null && !userSubreddit.trim().isEmpty()) {
                String refinedQuery = refineSearchQuery(query);
                return new SubredditRecommendation(userSubreddit.trim().replaceAll("^r/", ""), refinedQuery, "User-specified subreddit");
            }
            
            // First, try to match with knowledge base
            String suggestedSubreddit = findSuggestedSubreddit(query);
            
            // CoT Prompt for subreddit and query recommendation
            String cotPrompt = String.format(
                "You are an expert at identifying the most relevant Reddit communities (subreddits) for questions. " +
                "Use Chain-of-Thought reasoning to identify the best subreddit and refine the search query.\n\n" +
                
                "Question: %s\n\n" +
                
                "Think step-by-step:\n" +
                "1. Analyze the main topic of the question\n" +
                "2. Identify 2-3 relevant subreddits that would discuss this topic\n" +
                "3. Rank them by relevance and audience expertise\n" +
                "4. Select the SINGLE BEST subreddit (this is CRITICAL)\n" +
                "5. Refine the search query to be VERY specific and discoverable on Reddit\n\n" +
                
                "IMPORTANT: You must respond ONLY with a valid JSON object (no markdown, no extra text, no code blocks):\n" +
                "{\n" +
                "  \"subreddit\": \"name_without_r_prefix\",\n" +
                "  \"refined_query\": \"improved specific search terms\",\n" +
                "  \"reasoning\": \"brief explanation\"\n" +
                "}\n\n" +
                
                (suggestedSubreddit != null ? String.format("Hint: Consider r/%s based on keywords\n\n", suggestedSubreddit) : "") +
                
                "Examples:\n" +
                "Question: \"How do I learn Python?\"\n" +
                "Answer: {\"subreddit\": \"learnprogramming\", \"refined_query\": \"Python tutorial for beginners\", \"reasoning\": \"Most active community for programming education\"}\n\n" +
                
                "Question: \"Who would win Kyoya vs Gingka Beyblade?\"\n" +
                "Answer: {\"subreddit\": \"Beyblade\", \"refined_query\": \"Kyoya vs Gingka battle comparison\", \"reasoning\": \"Dedicated Beyblade community with fans discussing character matchups\"}\n\n",
                
                query
            );
            
            String response = generateGeminiResponse(cotPrompt, 0.2, 250);
            
            if (response != null && !response.trim().isEmpty()) {
                // Parse the JSON response
                SubredditRecommendation rec = parseSubredditRecommendation(response);
                if (rec != null && rec.getSubreddit() != null && !rec.getSubreddit().isEmpty()) {
                    System.out.println("[Stage 1 CoT] Successfully identified subreddit: " + rec.getSubreddit());
                    return rec;
                }
            }
            
            // Fallback: if LLM fails, use knowledge base match
            if (suggestedSubreddit != null) {
                System.out.println("[Stage 1 Fallback] Using knowledge base suggestion: " + suggestedSubreddit);
                String refinedQuery = refineSearchQuery(query);
                return new SubredditRecommendation(suggestedSubreddit, refinedQuery, "Knowledge base match");
            }
            
        } catch (Exception e) {
            System.err.println("Error in CoT subreddit identification: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Final fallback: return null to use user input or "all"
        System.out.println("[Stage 1 Final Fallback] Could not identify subreddit, using defaults");
        return null;
    }

    /**
     * Find a suggested subreddit from knowledge base by matching query keywords
     */
    private String findSuggestedSubreddit(String query) {
        String lowerQuery = query.toLowerCase();
        
        for (Map.Entry<String, List<String>> entry : SUBREDDIT_KNOWLEDGE_BASE.entrySet()) {
            if (lowerQuery.contains(entry.getKey())) {
                // Return the first (primary) subreddit for this topic
                return entry.getValue().get(0);
            }
        }
        
        return null;
    }

    /**
     * Parse the JSON response from the CoT prompt
     */
    private SubredditRecommendation parseSubredditRecommendation(String jsonResponse) {
        try {
            // Clean the response: remove markdown code blocks if present
            String cleanedResponse = jsonResponse
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();
            
            JsonNode jsonNode = objectMapper.readTree(cleanedResponse);
            
            String subreddit = jsonNode.has("subreddit") ? jsonNode.get("subreddit").asText("").trim() : null;
            String refinedQuery = jsonNode.has("refined_query") ? jsonNode.get("refined_query").asText("").trim() : null;
            String reasoning = jsonNode.has("reasoning") ? jsonNode.get("reasoning").asText("") : "";
            
            if (subreddit != null && !subreddit.isEmpty() && refinedQuery != null && !refinedQuery.isEmpty()) {
                // Clean subreddit name: remove r/ prefix if present, lowercase
                subreddit = subreddit.replaceAll("^r/", "").toLowerCase();
                return new SubredditRecommendation(subreddit, refinedQuery, reasoning);
            }
            
        } catch (Exception e) {
            System.err.println("Error parsing subreddit recommendation JSON: " + e.getMessage());
        }
        
        return null;
    }

    /**
     * Refine the search query to be more specific and discoverable
     */
    private String refineSearchQuery(String originalQuery) {
        try {
            String refinePrompt = String.format(
                "Refine this search query to be more specific and find better Reddit discussions. " +
                "Only respond with the refined query, nothing else:\n\n" +
                "Original: %s\n\n" +
                "Refined:",
                originalQuery
            );
            
            String refined = generateGeminiResponse(refinePrompt, 0.3, 100);
            
            if (refined != null && !refined.trim().isEmpty()) {
                return refined.trim();
            }
            
        } catch (Exception e) {
            System.err.println("Error refining search query: " + e.getMessage());
        }
        
        return originalQuery;
    }

    /**
     * Generate answer with caching based on query and context hash.
     * This prevents multiple LLM calls for the same query/context combination.
     * 
     * @param query The user's original question
     * @param context The Reddit context to base the answer on
     * @return The LLM-generated answer or fallback if generation fails
     */
    @Cacheable(
        value = "generatedAnswers",
        key = "T(java.util.Objects).hash(#query, #context)",
        unless = "#result == null || #result.isEmpty()"
    )
    private String generateAnswerWithGemini(String query, String context) {
        try {
            System.out.println("[LLM] Generating answer (not cached)...");
            String prompt = String.format(
                "You are a helpful assistant that answers questions based on Reddit discussions in natural human language" +
                "Use the provided Reddit posts to answer the user's question. Be conversational and do not mention upvotes " +
                "prefer information with higher upvotes & use all relevant info to provide high level conversational insight in plain text. Try and at the least provide an answer that is one paragraph. " +
                "If the context doesn't contain enough information, say so politely. If unsure but containing context please try and give some answer instead of saying unsure but if no idea say to try a different subreddit.\n\n" +
                "Question: %s\n\nRelevant Reddit posts:\n%s\n\n" +
                "Please provide a helpful answer based on this Reddit content:",
                query, context
            );

            String geminiResponse = generateGeminiResponse(prompt, 0.7, 500);
            
            if (geminiResponse != null && !geminiResponse.trim().isEmpty()) {
                System.out.println("[LLM] Answer generation successful");
                return geminiResponse;
            } else {
                // Fallback response when Gemini fails
                System.out.println("[LLM] Gemini returned null/empty, using fallback");
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

    /**
     * Filter Stage 1 broad search results to remove completely off-topic posts.
     * Keeps posts that mention multiple key terms from the query.
     * 
     * @param posts Raw posts from broad Reddit search
     * @param query The user's original query
     * @return Filtered posts that are likely relevant to the query
     */
    private List<RedditPost> filterStage1Posts(List<RedditPost> posts, String query) {
        if (query == null || query.trim().isEmpty()) {
            return posts;
        }
        
        String queryLower = query.toLowerCase();
        // Extract main topic words (longer than 3 chars, not stopwords)
        String[] topicWords = queryLower
            .replaceAll("[^a-z0-9\\s]", " ")
            .split("\\s+");
        
        List<String> significantWords = new ArrayList<>();
        for (String word : topicWords) {
            if (word.length() > 3 && !stopWords.contains(word)) {
                significantWords.add(word);
            }
        }
        
        // Filter posts: keep if title OR content contains at least 2 significant words from query
        return posts.stream()
            .filter(post -> {
                String combined = (post.getTitle() + " " + post.getContent()).toLowerCase();
                long matchCount = significantWords.stream()
                    .filter(combined::contains)
                    .count();
                return matchCount >= Math.max(1, significantWords.size() / 2); // At least 50% of key words
            })
            .collect(Collectors.toList());
    }

    private String synthesizeFallbackAnswer(String query, List<DocumentVector> relevantDocs) {
        if (relevantDocs.isEmpty()) {
            return "No relevant discussions found. Try rephrasing your question or specifying a subreddit.";
        }
        
        // Sort by score descending to prioritize high-quality posts
        List<DocumentVector> topByScore = relevantDocs.stream()
            .sorted((a, b) -> Integer.compare(b.getPost().getScore(), a.getPost().getScore()))
            .limit(3)
            .collect(Collectors.toList());
        
        // Extract substantive content from top posts
        List<String> insights = new ArrayList<>();
        StringBuilder answer = new StringBuilder();
        
        for (DocumentVector doc : topByScore) {
            RedditPost post = doc.getPost();
            String content = post.getContent();
            
            // Get a meaningful excerpt from the content (first 300 chars that contain substance)
            String excerpt = content;
            if (excerpt.length() > 300) {
                excerpt = excerpt.substring(0, 300);
                int lastSpace = excerpt.lastIndexOf(" ");
                if (lastSpace > 100) {
                    excerpt = excerpt.substring(0, lastSpace);
                }
                excerpt = excerpt.replaceAll("\\s+$", "") + "...";
            }
            
            // Clean up common Reddit artifacts
            excerpt = excerpt.replaceAll("(?i)(link to post:|source:|more info:).*", "")
                            .replaceAll("\\[.+?\\]\\(.+?\\)", "") // Remove markdown links
                            .trim();
            
            if (!excerpt.isEmpty()) {
                insights.add(excerpt);
            }
        }
        
        // Build a proper answer from the insights
        answer.append("Based on Reddit discussions about this topic:\n\n");
        
        if (insights.isEmpty()) {
            answer.append("The community discusses this topic across multiple posts, but no clear consensus emerges from the available data. ");
            answer.append("For more detailed analysis, check the relevant subreddit directly.");
        } else {
            // Combine insights into a coherent answer
            answer.append("The community discusses several perspectives on this:\n\n");
            
            for (int i = 0; i < insights.size(); i++) {
                answer.append("• ").append(insights.get(i)).append("\n\n");
            }
            
            // Add closing statement that's topic-agnostic but helpful
            answer.append("These discussions represent the community's analysis. For the most accurate answer, ")
                  .append("consider checking the specific posts and comments in the relevant subreddit, as community ")
                  .append("opinions may vary and evolve based on new content or episodes.");
        }
        
        System.out.println("[Fallback] Generated synthesis from " + topByScore.size() + " top posts");
        return answer.toString();
    }
}