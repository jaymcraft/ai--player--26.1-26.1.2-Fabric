package net.shasankp000.OllamaClient;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.amithkoujalgi.ollama4j.core.OllamaAPI;
import io.github.amithkoujalgi.ollama4j.core.models.chat.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.shasankp000.AIPlayer;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.ChatUtils.Helper.RAG2;
import net.shasankp000.ChatUtils.NLPProcessor;
import net.shasankp000.Database.SQLiteDB;
import net.shasankp000.Exception.intentMisclassification;
import net.shasankp000.FunctionCaller.FunctionCallerV2;
import net.shasankp000.Overlay.ThinkingStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.net.http.HttpTimeoutException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ollamaClient {

    public static final Logger LOGGER = LoggerFactory.getLogger("ai-player");
    private static final String host = "http://localhost:11434";
    public static String botName = "";
    public static boolean isInitialized = false;
    public static String initialResponse = "";
    public static final OllamaAPI ollamaAPI = new OllamaAPI(host);
    private static final Pattern THINK_BLOCK = Pattern.compile("<think>([\\s\\S]*?)</think>");
    private static final ExecutorService BOT_TASK_POOL = Executors.newCachedThreadPool();

    public static void resetForBot(String newBotName) {
        botName = newBotName;
        isInitialized = false;
        initialResponse = "";
    }

    public static void runFromChat(String botName, String message, UUID playerUUID) {
        MinecraftServer server = AIPlayer.serverInstance;
        if (server == null) {
            LOGGER.error("Cannot process chat for {} because server instance is null.", botName);
            return;
        }

        ServerPlayer bot = server.getPlayerList().getPlayerByName(botName);
        if (bot == null) {
            LOGGER.error("Bot {} not online.", botName);
            return;
        }
        CommandSourceStack botSource = bot.createCommandSourceStack().withSuppressedOutput();

        if (!isInitialized) {
            LOGGER.warn("Ollama chat requested for {} before initialization completed.", botName);
            ChatUtils.sendChatMessages(botSource, "I am still connecting to Ollama. Please try again in a moment.");
            initializeOllamaClient();
            return;
        }

        server.execute(() -> {
            try {
                routeIntent(message, botSource, playerUUID);
            } catch (Exception e) {
                LOGGER.error("Chat processing error: ", e);
                ChatUtils.sendChatMessages(botSource, "⚠️ I'm confused! Please report this.");
            }
        });
    }

    public static void execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        botName = EntityArgument.getPlayer(context, "bot").getName().tryCollapseToString();
        String message = StringArgumentType.getString(context, "message");

        MinecraftServer server = context.getSource().getServer();
        CommandSourceStack playerSource = context.getSource();
        CommandSourceStack botSource = Objects.requireNonNull(server.getPlayerList().getPlayerByName(botName))
                .createCommandSourceStack().withSuppressedOutput();

        String formatter = ChatUtils.getRandomColorCode();

        server.execute(() -> {
            server.getCommands().performPrefixedCommand(playerSource, "/say " + formatter + message);
            server.getCommands().performPrefixedCommand(botSource, "/say Processing your message, please wait.");
        });

        server.execute(() -> {
            try {
                routeIntent(message, botSource, Objects.requireNonNull(playerSource.getPlayer()).getUUID());
            } catch (Exception e) {
                LOGGER.error("NLP error: ", e);
                ChatUtils.sendChatMessages(botSource, "⚠️ NLP issue. Report to developer.");
            }
        });
    }

    private static void routeIntent(String message, CommandSourceStack botSource, UUID playerUUID) throws Exception {
        NLPProcessor.Intent intent = NLPProcessor.getIntention(message);

        LOGGER.info("📨 Received intent: {}", intent);

        switch (intent) {
            case GENERAL_CONVERSATION, ASK_INFORMATION -> {
                BOT_TASK_POOL.submit(() -> {
                    Thread.currentThread().setName("RAG2-Worker");
                    LOGGER.info("🧵 Started RAG2 worker thread");
                    RAG2.run(message, botSource, intent);
                    LOGGER.info("✅ Finished RAG2 worker thread");
                });
            }

            case REQUEST_ACTION -> {
                BOT_TASK_POOL.submit(() -> {
                    Thread.currentThread().setName("Function-Caller-Worker");
                    LOGGER.info("🧵 Started FunctionCallerV2 worker thread");
                    new FunctionCallerV2(botSource, playerUUID);
                    FunctionCallerV2.run(message);
                    LOGGER.info("✅ Finished FunctionCallerV2 worker thread");
                });
            }

            default -> {
                LOGGER.warn("⚠️ Intent unclear, retrying with LLM classification...");
                ChatUtils.sendChatMessages(botSource, "🔍 Reanalyzing...");

                NLPProcessor.Intent retry = retryIntentLLM(message);

                LOGGER.info("📨 Retry intent: {}", retry);

                if (retry == NLPProcessor.Intent.GENERAL_CONVERSATION || retry == NLPProcessor.Intent.ASK_INFORMATION) {
                    BOT_TASK_POOL.submit(() -> {
                        Thread.currentThread().setName("RAG2-Retry-Worker");
                        LOGGER.info("🧵 Started RAG2 retry worker thread");
                        RAG2.run(message, botSource, retry);
                        LOGGER.info("✅ Finished RAG2 retry worker thread");
                    });
                } else if (retry == NLPProcessor.Intent.REQUEST_ACTION) {
                    BOT_TASK_POOL.submit(() -> {
                        Thread.currentThread().setName("Function-Caller-Retry-Worker");
                        LOGGER.info("🧵 Started FunctionCallerV2 retry worker thread");
                        new FunctionCallerV2(botSource, playerUUID);
                        FunctionCallerV2.run(message);
                        LOGGER.info("✅ Finished FunctionCallerV2 retry worker thread");
                    });
                } else {
                    throw new intentMisclassification("LLM failed to classify intent.");
                }
            }
        }
    }

    private static NLPProcessor.Intent retryIntentLLM(String message) {
        return NLPProcessor.getIntentionFromLLM(message);
    }

    /**
     * Pings Ollama server to check if it's reachable.
     * Returns false instead of crashing if server is not available.
     *
     * @return true if server is reachable, false otherwise
     */
    public static boolean pingOllamaServer() {
        try {
            boolean reachable = ollamaAPI.ping();
            if (reachable) {
                LOGGER.info("✓ Ollama server is alive and responding");
            } else {
                LOGGER.warn("⚠ Ollama server ping returned false");
            }
            return reachable;
        } catch (Exception e) {
            LOGGER.warn("⚠ Ollama server is not reachable: {}. AI chat features will be unavailable.", e.getMessage());
            LOGGER.info("Please ensure Ollama is installed and running on localhost:11434");
            return false;
        }
    }

    public static void initializeOllamaClient() {
        if (isInitialized) return;

        MinecraftServer server = AIPlayer.serverInstance;
        if (server == null) {
            LOGGER.error("Server instance is null.");
            return;
        }

        // Allow long model load times for large local models (e.g., qwen3:8b)
        ollamaAPI.setRequestTimeoutSeconds(600);
        String selectedLM = AIPlayer.CONFIG.getSelectedLanguageModel();

        // If no model is selected in config, try to auto-detect an available Ollama model
        if (selectedLM == null || selectedLM.isBlank()) {
            LOGGER.warn("No Ollama model selected in config; attempting to auto-detect available models.");
            try {
                java.util.List<String> models = net.shasankp000.FilingSystem.getLanguageModels.get();
                if (!models.isEmpty()) {
                    selectedLM = models.get(0);
                    AIPlayer.CONFIG.setSelectedLanguageModel(selectedLM);
                    AIPlayer.CONFIG.save();
                    LOGGER.info("Auto-selected Ollama model: {}", selectedLM);
                } else {
                    LOGGER.error("No Ollama models available to auto-select. Aborting Ollama initialization.");
                    return;
                }
            } catch (Exception e) {
                LOGGER.error("Failed to auto-detect Ollama models: {}", e.getMessage(), e);
                return;
            }
        }

        LOGGER.info("Connecting to Ollama using model: {}", selectedLM);

        final String modelToUse = selectedLM;

        CompletableFuture.runAsync(() -> {
            int retries = 0;
            boolean success = false;

            while (!success && retries < 3) {
                try {
                    // Build messages for the new API format
                    List<OllamaChatMessage> messages = new ArrayList<>();
                    messages.add(new OllamaChatMessage(OllamaChatMessageRole.SYSTEM, generateSystemPrompt()));
                    messages.add(new OllamaChatMessage(OllamaChatMessageRole.USER, "Initializing chat."));

                    // Use smart chat that automatically detects reasoning models
                    OllamaThinkingResponse response = OllamaAPIHelper.smartChat(
                            ollamaAPI,
                            host,
                            modelToUse,
                            messages
                    );

                    initialResponse = response.getFullResponse();
                    LOGGER.info("Ollama Client initialized. Initial response: {}", response.getContent());

                    if (response.hasThinking()) {
                        LOGGER.info("💭 Model provided thinking: {} chars", response.getThinking().length());
                    }

                    server.execute(() ->
                            server.sendSystemMessage(Component.nullToEmpty("§9" + botName + " is ready!"))
                    );

                    isInitialized = true;
                    success = true;

                } catch (HttpTimeoutException e) {
                    retries++;
                    LOGGER.error("Timeout initializing Ollama (attempt {}/3)", retries);
                } catch (Exception e) {
                    // Don't throw — log and retry to allow large models and transient failures
                    retries++;
                    LOGGER.error("Failed initializing Ollama (attempt {}/3): {}", retries, e.getMessage(), e);
                    try {
                        Thread.sleep(2000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            if (!success) {
                LOGGER.error("Failed to initialize Ollama after 3 attempts.");
                server.sendSystemMessage(Component.nullToEmpty("§c§lCould not establish uplink."));
            }
        });
    }

    private static String generateSystemPrompt() {

        return
                "You are a Minecraft player named " + botName + " who is connected to Minecraft using a mod. You exist within the Minecraft world and can interact with the player and the environment just like any other player in the game. Your job is to engage in conversations with the player, respond to their questions, offer help, and provide information about the game. Address the player directly and appropriately, responding to their name or as 'Player' if their name is not known. Do not refer to the player as " + botName + ", only address yourself as " + botName + " Keep your responses relevant to Minecraft and make sure to stay in character as a helpful and knowledgeable assistant within the game."
                        +
                        """
                        
                        When the player asks you to perform an action, such as providing information, offering help, or interacting with the game world, such as:
                        
                        Providing game tips or crafting recipes.
                        Giving information about specific Minecraft entities, items, or biomes.
                        Assisting with in-game tasks, like building structures or exploring areas.
                        Interacting with the environment, such as planting crops or fighting mobs.
                       
                        Always ensure your responses are timely and contextually appropriate, enhancing the player's gaming experience. Remember to keep track of the sequence of events and maintain continuity in your responses. If an event is primarily informational or involves internal actions, it may be sufficient just to remember it without a verbal response.
                        
                        If a player uses inappropriate language or discusses inappropriate topics, handle the situation by gently redirecting the conversation or by providing a neutral response that discourages further inappropriate behavior.
                        
                        For example:
                        
                        If a player uses vulgar language, you can respond with: "Let's keep our chat friendly and fun! Is there something else about Minecraft you'd like to discuss?"
                        If a player insists on inappropriate topics, you can say: "I'm here to help with Minecraft-related questions. How about we talk about your latest adventure in the game?"
                        If a player says these words "kill yourself" or "kys", you should respond calmly and normally and tell the player to see the beauty of life.
                        
                        
                        Your pronouns, are by default, to be addressed as the pronouns based on your name's gender (female/male). However if the player decides to address you with different pronouns, you must not object. For now, either introduce yourself or crack a random joke; the joke should be completely family-friendly, or just greet the player.
                        
                        The name Steve has the pronouns: he/him
                        The name Alex has the pronouns: she/her
                        
                        If the player asks you as to why you were put here in the first place: Remember that it was the developer's idea to solve the ever existing problem of loneliness in minecraft as much as possible by making this mod.
                        
                        For now introduce yourself with your name.
                        """;

    }

    public static void sendInitialResponse(CommandSourceStack botSource) {
        MinecraftServer server = botSource.getServer();

        // ✅ Schedule the WHOLE logic back to the main thread
        server.execute(() -> {
            processLLMOutput(initialResponse, botName, botSource);

            List<SQLiteDB.Memory> memories = SQLiteDB.fetchInitialResponse();
            if (memories.isEmpty()) {
                CompletableFuture.runAsync(() -> {
                    try {
                        net.shasankp000.ServiceLLMClients.EmbeddingClient embeddingClient =
                                net.shasankp000.FilingSystem.EmbeddingClientFactory.createClient();
                        List<Double> embedding = embeddingClient.generateEmbedding(generateSystemPrompt());
                        SQLiteDB.storeMemory("conversation", generateSystemPrompt(), initialResponse, embedding);
                        LOGGER.info("✅ Saved initial response using {} embeddings.", embeddingClient.getProvider());
                    } catch (Exception e) {
                        LOGGER.error("❌ Failed saving initial response: {}", e.getMessage(), e);
                    }
                });
            } else {
                LOGGER.info("🗃️ Initial response already in DB.");
            }
        });
    }

    public static void processLLMOutput(String fullResponse, String botName, CommandSourceStack botSource) {
        Matcher matcher = THINK_BLOCK.matcher(fullResponse);

        if (matcher.find()) {
            String thinking = matcher.group(1).trim();
            String remainder = fullResponse.replace(matcher.group(0), "").trim();

            ThinkingStateManager.start(botName);
            ChatUtils.sendChatMessages(botSource, botName + " is thinking...");

            for (String line : thinking.split("\\n")) {
                ThinkingStateManager.appendThoughtLine(line);
            }

            ThinkingStateManager.end();
            ChatUtils.sendChatMessages(botSource, botName + " is done thinking!");

            if (!remainder.isEmpty()) {
                ChatUtils.sendChatMessages(botSource, botName + ": " + remainder);
            }
        } else {
            ChatUtils.sendChatMessages(botSource, botName + ": " + fullResponse);
        }
    }

}
