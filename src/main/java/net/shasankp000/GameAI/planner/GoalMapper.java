package net.shasankp000.GameAI.planner;

import io.github.amithkoujalgi.ollama4j.core.OllamaAPI;
import io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatMessage;
import io.github.amithkoujalgi.ollama4j.core.models.chat.OllamaChatMessageRole;
import net.shasankp000.AIPlayer;
import net.shasankp000.FilingSystem.LLMClientFactory;
import net.shasankp000.FilingSystem.LLMProviderConfig;
import net.shasankp000.OllamaClient.OllamaAPIHelper;
import net.shasankp000.OllamaClient.OllamaThinkingResponse;
import net.shasankp000.ServiceLLMClients.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps natural language goals to numeric goal IDs for the Markov planner.
 * Uses fast LLM parsing with timeout fallback to keyword matching.
 */
public class GoalMapper {
    private static final Logger LOGGER = LoggerFactory.getLogger("GoalMapper");
    private static final Map<Short, String> GOAL_ID_TO_NAME = new HashMap<>();
    private static final Map<String, Short> GOAL_KEYWORD_TO_ID = new HashMap<>();

    // Fast LLM timeout (milliseconds)
    private static final long LLM_TIMEOUT_MS = 5000; // 5 seconds max
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    // Goal IDs (0-255 for now, expand later if needed)
    public static final short GOAL_MINE = 1;
    public static final short GOAL_BUILD = 2;
    public static final short GOAL_CRAFT = 3;
    public static final short GOAL_NAVIGATE = 4;
    public static final short GOAL_COMBAT = 5;
    public static final short GOAL_GATHER = 6;
    public static final short GOAL_EXPLORE = 7;
    public static final short GOAL_FARM = 8;
    public static final short GOAL_TRADE = 9;
    public static final short GOAL_UNKNOWN = 0;

    static {
        // Register goal mappings
        registerGoal(GOAL_MINE, "mine", "mining", "dig", "excavate", "extract");
        registerGoal(GOAL_BUILD, "build", "place", "construct", "create structure");
        registerGoal(GOAL_CRAFT, "craft", "make", "create", "assemble");
        registerGoal(GOAL_NAVIGATE, "go", "navigate", "move", "travel", "walk");
        registerGoal(GOAL_COMBAT, "fight", "attack", "combat", "kill", "defend");
        registerGoal(GOAL_GATHER, "gather", "collect", "fetch", "get", "obtain");
        registerGoal(GOAL_EXPLORE, "explore", "search", "find", "look for");
        registerGoal(GOAL_FARM, "farm", "harvest", "plant", "grow");
        registerGoal(GOAL_TRADE, "trade", "buy", "sell", "exchange");
    }

    /**
     * Register a goal with multiple keyword triggers.
     */
    private static void registerGoal(short goalId, String name, String... keywords) {
        GOAL_ID_TO_NAME.put(goalId, name);
        for (String keyword : keywords) {
            GOAL_KEYWORD_TO_ID.put(keyword.toLowerCase(), goalId);
        }
    }

    /**
     * Parse a natural language goal into a goal ID using fast LLM inference.
     * Falls back to keyword matching if LLM times out or fails.
     *
     * TEMPORARY: LLM parsing disabled for speed - using keywords only
     */
    public static short parseGoal(String naturalLanguageGoal) {
        if (naturalLanguageGoal == null || naturalLanguageGoal.isEmpty()) {
            return GOAL_UNKNOWN;
        }

        // TEMPORARY: Skip LLM parsing, go straight to keywords for speed
        // TODO: Re-enable LLM parsing once we have a faster model or caching
        return parseGoalWithKeywords(naturalLanguageGoal);

        /* LLM parsing disabled for now - too slow
        // Try LLM parsing first with timeout
        try {
            Future<Short> llmResult = EXECUTOR.submit(() -> parseGoalWithLLM(naturalLanguageGoal));
            short goalId = llmResult.get(LLM_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (goalId != GOAL_UNKNOWN) {
                LOGGER.info("✓ LLM parsed goal: '{}' → {} ({})",
                    naturalLanguageGoal, goalId, getGoalName(goalId));
                return goalId;
            }
        } catch (TimeoutException e) {
            LOGGER.warn("⏱ LLM timeout for goal parsing, falling back to keywords");
        } catch (Exception e) {
            LOGGER.warn("⚠ LLM parsing failed: {}, falling back to keywords", e.getMessage());
        }

        // Fallback to keyword matching
        return parseGoalWithKeywords(naturalLanguageGoal);
        */
    }

    /**
     * Parse goal using LLM with a very focused, fast prompt.
     */
    private static short parseGoalWithLLM(String naturalLanguageGoal) {
        String llmProvider = LLMProviderConfig.getConfiguredProvider();

        try {
            // Build goal list for prompt
            StringBuilder goalList = new StringBuilder();
            for (short id : getAllGoalIds()) {
                if (id != GOAL_UNKNOWN) {
                    goalList.append(id).append("=").append(getGoalName(id)).append(", ");
                }
            }

            String prompt = String.format(
                "Classify this Minecraft goal into ONE category. Reply ONLY with the number.\n\n" +
                "Categories: %s0=unknown\n\n" +
                "Goal: \"%s\"\n\n" +
                "Answer (number only):",
                goalList,
                naturalLanguageGoal
            );

            String ThirdPartyProviderSystemPrompt = """
                      You are an expert at classifying user intents into predefined categories.
                      Given a Minecraft goal described in natural language, classify it into one of the predefined categories by responding with ONLY the corresponding number.
                      Respond with ONLY the number corresponding to the category that best fits the goal.
                    """;

            switch (llmProvider) {

                case "ollama":
                    // Use Ollama LLM
                    // Build chat messages
                    List<OllamaChatMessage> messages = new ArrayList<>();
                    messages.add(new OllamaChatMessage(OllamaChatMessageRole.USER, prompt));

                    // Get configuration from AIPlayer
                    String host = "http://localhost:11434";
                    OllamaAPI ollamaAPI = new OllamaAPI(host);
                    String modelName = AIPlayer.CONFIG.getSelectedLanguageModel();

                    if (modelName == null || modelName.isEmpty()) {
                        LOGGER.warn("No model selected in configuration");
                        return GOAL_UNKNOWN;
                    }

                    // Use smartChat for fast inference (auto-detects if thinking is needed)
                    OllamaThinkingResponse response = OllamaAPIHelper.smartChat(
                            ollamaAPI,
                            host,
                            modelName,
                            messages
                    );

                    String content = response.getContent();

                    if (content == null || content.trim().isEmpty()) {
                        return GOAL_UNKNOWN;
                    }

                    // Extract first number from response
                    Pattern pattern = Pattern.compile("\\b([0-9])\\b");
                    Matcher matcher = pattern.matcher(content);

                    if (matcher.find()) {
                        short goalId = Short.parseShort(matcher.group(1));
                        if (isValidGoal(goalId) || goalId == GOAL_UNKNOWN) {
                            return goalId;
                        }
                    }

                    // Try parsing the whole response as a number
                    try {
                        short goalId = Short.parseShort(content.trim());
                        if (isValidGoal(goalId) || goalId == GOAL_UNKNOWN) {
                            return goalId;
                        }
                    } catch (NumberFormatException ignored) {}

                    return GOAL_UNKNOWN;


                case "openai", "gpt", "gemini", "claude", "grok", "custom":
                    LLMClient llmClient = LLMClientFactory.createClient(llmProvider);

                    if (llmClient == null) {
                        LOGGER.warn("LLM client creation failed for provider: {}", llmProvider);
                        return GOAL_UNKNOWN;
                    }
                    else {
                        LOGGER.info("Using LLM client for provider: {}", llmProvider);
                        String response1 = llmClient.sendPrompt(ThirdPartyProviderSystemPrompt, prompt);

                        if (response1 == null || response1.trim().isEmpty()) {
                            return GOAL_UNKNOWN;
                        }

                        // Extract first number from response
                        Pattern pattern1 = Pattern.compile("\\b([0-9])\\b");
                        Matcher matcher1 = pattern1.matcher(response1);
                        if (matcher1.find()) {
                            short goalId = Short.parseShort(matcher1.group(1));
                            if (isValidGoal(goalId) || goalId == GOAL_UNKNOWN) {
                                return goalId;
                            }
                        }
                        // Try parsing the whole response as a number
                        try {
                            short goalId = Short.parseShort(response1.trim());
                            if (isValidGoal(goalId) || goalId == GOAL_UNKNOWN) {
                                return goalId;
                            }
                        } catch (NumberFormatException ignored) {}

                        return GOAL_UNKNOWN;
                    }


                default:
                    LOGGER.warn("Unsupported LLM mode: {}", llmProvider);
                    return GOAL_UNKNOWN;
            }



        } catch (Exception e) {
            LOGGER.error("LLM goal parsing error: {}", e.getMessage());
            return GOAL_UNKNOWN;
        }
    }

    /**
     * Fallback keyword-based parsing.
     */
    private static short parseGoalWithKeywords(String naturalLanguageGoal) {
        String lower = naturalLanguageGoal.toLowerCase();

        // Try to find matching keywords
        for (Map.Entry<String, Short> entry : GOAL_KEYWORD_TO_ID.entrySet()) {
            if (lower.contains(entry.getKey())) {
                LOGGER.info("✓ Keyword matched goal: '{}' → {} ({})",
                    naturalLanguageGoal, entry.getValue(), getGoalName(entry.getValue()));
                return entry.getValue();
            }
        }

        LOGGER.warn("⚠ No goal match found for: '{}'", naturalLanguageGoal);
        return GOAL_UNKNOWN;
    }

    /**
     * Get the human-readable name for a goal ID.
     */
    public static String getGoalName(short goalId) {
        return GOAL_ID_TO_NAME.getOrDefault(goalId, "unknown");
    }

    /**
     * Get all registered goal IDs.
     */
    public static short[] getAllGoalIds() {
        List<Short> sortedIds = new ArrayList<>(GOAL_ID_TO_NAME.keySet());
        Collections.sort(sortedIds);
        short[] result = new short[sortedIds.size()];
        for (int i = 0; i < sortedIds.size(); i++) {
            result[i] = sortedIds.get(i);
        }
        return result;
    }


    /**
     * Check if a goal ID is valid.
     */
    public static boolean isValidGoal(short goalId) {
        return GOAL_ID_TO_NAME.containsKey(goalId);
    }
}
