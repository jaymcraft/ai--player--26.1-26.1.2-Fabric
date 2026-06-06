// Kudos to this guy, Matt Williams, https://www.youtube.com/watch?v=IdPdwQdM9lA, for opening my eyes on function calling.

package net.shasankp000.FunctionCaller;

import com.google.gson.*;

import com.google.gson.stream.JsonReader;

import io.github.amithkoujalgi.ollama4j.core.OllamaAPI;

import io.github.amithkoujalgi.ollama4j.core.models.chat.*;

import java.io.IOException;
import java.io.StringReader;

import java.util.*;

import java.util.concurrent.*;

import java.time.format.DateTimeFormatter;

import java.time.LocalDateTime;

import java.util.regex.Matcher;

import java.util.regex.Pattern;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.shasankp000.AIPlayer;

import net.shasankp000.ChatUtils.ChatContextManager;

import net.shasankp000.ChatUtils.ChatUtils;

import net.shasankp000.Entity.EntityDetails;

import net.shasankp000.GameAI.BotEventHandler;

import net.shasankp000.GameAI.State;

import net.shasankp000.Database.SQLiteDB;

import net.shasankp000.Entity.AutoFaceEntity;

import net.shasankp000.Entity.LookController;

import net.shasankp000.Overlay.ThinkingStateManager;

import net.shasankp000.PathFinding.ChartPathToBlock;

import net.shasankp000.PathFinding.GoTo;

import net.shasankp000.PathFinding.PathTracer;

import net.shasankp000.PlayerUtils.*;
import net.shasankp000.ServiceLLMClients.LLMClient;

import net.shasankp000.WebSearch.WebSearchTool;

import net.shasankp000.GameAI.planner.*;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import static net.shasankp000.ChatUtils.Helper.JsonUtils.cleanJsonString;

public class FunctionCallerV2 {

    private static final Logger logger = LoggerFactory.getLogger("function-caller");

    private static CommandSourceStack botSource = null;

    private static final String DB_URL = "jdbc:sqlite:" + "./sqlite_databases/" + "memory_agent.db";

    private static final String host = "http://localhost:11434/";

    private static final OllamaAPI ollamaAPI = new OllamaAPI(host);

    private static volatile String functionOutput = null;

    private static final ExecutorService executor = Executors.newFixedThreadPool(4);

    private static final Map<String, Object> sharedState = new ConcurrentHashMap<>();  // Updated to Map<String, Object>

    private static UUID playerUUID;

    private static final Pattern THINK_BLOCK = Pattern.compile("([\\s\\S]*?)", Pattern.DOTALL);

    private static final String selectedLM = AIPlayer.CONFIG.getSelectedLanguageModel();

    private static final Set<String> CRAFT_FILLER_WORDS = Set.of(
            "a", "an", "the", "some", "one", "me", "for", "please", "item", "items", "batch", "batches"
    );

    // Markov Planner components (initialized on first use)
    private static Planner planner = null;
    private static ActionLogWriter actionLogWriter = null;
    private static MarkovChain2 markovChain = null;
    private static final double SAFE_THRESHOLD = 50.0;

    // Hybrid Planner components (advanced goal-oriented planning)
    private static HybridPlanner hybridPlanner = null;
    private static boolean useHybridPlanner = true; // Toggle between planners

    public FunctionCallerV2(CommandSourceStack botSource, UUID playerUUID) {
        FunctionCallerV2.botSource = botSource;
        ollamaAPI.setRequestTimeoutSeconds(90);
        FunctionCallerV2.playerUUID = playerUUID;
    }

    /**
     * Initialize the Markov planner system.
     * Should be called once during bot initialization.
     */
    public static void initializePlanner(ServerPlayer bot, net.shasankp000.GameAI.RLAgent rlAgent) {
        if (markovChain == null) {
            logger.info("[planner] Initializing Markov-based planner system...");

            // Ensure ActionRegistry is populated from ToolRegistry
            ActionRegistry.refreshFromToolRegistry();

            markovChain = new MarkovChain2();
            planner = new Planner(markovChain, rlAgent);
            actionLogWriter = new ActionLogWriter(markovChain, bot);
            logger.info("[planner] ✓ Planner system initialized");
        }

        // Initialize hybrid planner if enabled
        if (useHybridPlanner && hybridPlanner == null) {
            try {
                logger.info("[hybrid-planner] Initializing hybrid goal-oriented planner...");

                // Initialize goal vector system (uses simple TF-IDF-like approach)
                GoalVector goalVector = new GoalVector();

                // Build action graph from ActionRegistry
                ActionGraph actionGraph = new ActionGraph(goalVector);
                actionGraph.buildFromRegistry();

                // Initialize hybrid planner
                SequenceRiskAnalyzer riskAnalyzer = new SequenceRiskAnalyzer(rlAgent);
                hybridPlanner = new HybridPlanner(actionGraph, goalVector, markovChain, rlAgent, riskAnalyzer);

                logger.info("[hybrid-planner] ✓ Hybrid planner system initialized");
            } catch (Exception e) {
                logger.error("[hybrid-planner] Failed to initialize hybrid planner, falling back to Markov", e);
                useHybridPlanner = false;
            }
        }
    }

    /**
     * Handle a natural language goal using the Markov planner.
     * Falls back to LLM-based planning if the Markov planner fails.
     *
     * @param naturalLanguageGoal User's goal in natural language (e.g., "get some wood")
     * @param currentState Current bot state
     * @param bot Bot entity
     * @param rlAgent RL agent for state management
     * @param botSource Bot's command source (required for function execution)
     * @return CompletableFuture that completes when plan execution finishes
     */
    public static CompletableFuture<Boolean> handleUserGoal(
            String naturalLanguageGoal,
            State currentState,
            ServerPlayer bot,
            net.shasankp000.GameAI.RLAgent rlAgent,
            CommandSourceStack botSource) {

        // ✅ Store botSource for function execution
        FunctionCallerV2.botSource = botSource;

        // Ensure planner is initialized
        if (planner == null) {
            initializePlanner(bot, rlAgent);
        }

        // Parse goal using GoalMapper
        short goalId = GoalMapper.parseGoal(naturalLanguageGoal);

        if (goalId == 0) {
            logger.warn("[planner] Unknown goal: '{}', falling back to LLM", naturalLanguageGoal);
            return fallbackToLLM(naturalLanguageGoal, currentState);
        }

        String goalName = GoalMapper.getGoalName(goalId);
        logger.info("[planner] Parsed goal '{}' → ID {} ({})", naturalLanguageGoal, goalId, goalName);

        Plan plan = null;

        // Try hybrid planner first if enabled
        if (useHybridPlanner && hybridPlanner != null) {
            try {
                logger.info("[hybrid-planner] Attempting hybrid planning for goal '{}'", goalName);
                plan = hybridPlanner.buildPlan(currentState, naturalLanguageGoal, goalId);

                if (plan != null) {
                    logger.info("[hybrid-planner] ✓ Using hybrid planner (score: {})", plan.getTotalScore());
                    return executePlan(plan, actionLogWriter, currentState);
                }
            } catch (Exception e) {
                logger.error("[hybrid-planner] Hybrid planning failed, falling back to Markov", e);
            }
        }

        // Fall back to Markov planner
        if (plan == null) {
            logger.info("[planner] Attempting Markov planning for goal '{}'", goalName);
            plan = planner.buildPlan(currentState, goalId);
        }

        if (plan != null && plan.getTotalScore() < SAFE_THRESHOLD * 4) {
            logger.info("[planner] ✓ Using Markov planner for goal '{}' (score: {})",
                    goalName, plan.getTotalScore());
            return executePlan(plan, actionLogWriter, currentState);
        } else {
            if (plan == null) {
                logger.warn("[planner] All planners returned null for goal '{}', falling back to LLM", goalName);
            } else {
                logger.warn("[planner] Planner score too high ({}) for goal '{}', falling back to LLM",
                        plan.getTotalScore(), goalName);
            }
            return fallbackToLLM(naturalLanguageGoal, currentState);
        }
    }

    /**
     * Fallback to LLM-based planning when Markov planner fails.
     * Generates pipeline using language model and executes it.
     */
    private static CompletableFuture<Boolean> fallbackToLLM(String goal, State currentState) {
        logger.info("[planner] 🤖 LLM fallback for goal: '{}'", goal);

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Build context for LLM
                InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1);
                map.updateMap();
                Map<String, String> surroundingsStr = map.summarizeSurroundings();
                Map<String, Object> surroundings = new HashMap<>();
                surroundings.putAll(surroundingsStr);

                String botContext = buildLLMBotContext(currentState, sharedState, surroundings);
                String systemPrompt = buildPrompt(toolBuilder());
                String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;

                // Construct user prompt
                String userPrompt = "Goal: " + goal + "\n\n" +
                    "The automated planning systems (Hybrid and Markov) were unable to generate a safe plan for this goal. " +
                    "Please analyze the situation and generate an appropriate action pipeline. " +
                    "Consider the bot's current state and surroundings carefully.";

                String response;

                // Use Ollama for LLM fallback
                List<OllamaChatMessage> messages = new ArrayList<>();
                messages.add(new OllamaChatMessage(OllamaChatMessageRole.SYSTEM, fullSystemPrompt));
                messages.add(new OllamaChatMessage(OllamaChatMessageRole.USER, userPrompt));

                net.shasankp000.OllamaClient.OllamaThinkingResponse thinkingResponse =
                        net.shasankp000.OllamaClient.OllamaAPIHelper.smartChat(
                                ollamaAPI,
                                "http://localhost:11434",
                                net.shasankp000.AIPlayer.CONFIG.getSelectedLanguageModel(),
                                messages
                        );
                response = thinkingResponse.getContent();

                logger.info("[planner] Raw LLM response: {}", response);

                // Parse and execute LLM response
                String cleanedResponse = stripThinkBlock(response);
                String jsonPart = extractJson(cleanedResponse);
                logger.info("[planner] Extracted JSON: {}", jsonPart);

                JsonObject jsonObject = JsonParser.parseString(jsonPart).getAsJsonObject();

                if (jsonObject.has("pipeline")) {
                    JsonArray pipeline = jsonObject.getAsJsonArray("pipeline");
                    logger.info("[planner] ✓ LLM generated pipeline with {} steps", pipeline.size());

                    // Execute pipeline using existing runPipelineLoop
                    runPipelineLoop(pipeline);
                    return true;

                } else if (jsonObject.has("functionName")) {
                    // Single function call
                    String fnName = jsonObject.get("functionName").getAsString();
                    Map<String, String> paramMap = parseParameterMap(jsonObject.get("parameters"), sharedState);

                    logger.info("[planner] ✓ LLM generated single function: {} with {}", fnName, paramMap);
                    callFunction(fnName, paramMap, sharedState).join();
                    return true;

                } else if (jsonObject.has("clarification")) {
                    String clarification = jsonObject.get("clarification").getAsString();
                    logger.warn("[planner] ⚠ LLM requested clarification: {}", clarification);
                    ChatContextManager.setPendingClarification(
                        playerUUID,
                        goal,
                        clarification,
                        botSource.getTextName()
                    );
                    sendMessageToPlayer(clarification);
                    return false;
                } else {
                    logger.error("[planner] ❌ Invalid LLM response format");
                    return false;
                }

            } catch (Exception e) {
                logger.error("[planner] ❌ LLM fallback error: {}", e.getMessage(), e);
                return false;
            }
        });
    }

    private static class ExecutionRecord {
        String timestamp;
        String command;
        List<Double> eventEmbedding;
        List<Double> eventContextEmbedding;
        List<Double> eventResultEmbedding;
        String result;
        String context;

        private ExecutionRecord(String Timestamp, String command, String context, String result, List<Double> eventEmbedding, List<Double> eventContextEmbedding, List<Double> eventResultEmbedding) {
            this.context = context;
            this.timestamp = Timestamp;
            this.command = command;
            this.eventEmbedding = eventEmbedding;
            this.eventContextEmbedding = eventContextEmbedding;
            this.eventResultEmbedding = eventResultEmbedding;
            this.result = result;
        }

        private void updateRecords() {
            try {
                // Store command as memory with prompt=command, response=result
                SQLiteDB.storeMemory("function_call", this.command, this.result, this.eventEmbedding);
            } catch (Exception e) {
                logger.error("Caught exception: {} ", (Object) e.getStackTrace());
                throw new RuntimeException(e);
            }
        }
    }

    private static String getCurrentDateandTime() {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
        LocalDateTime now = LocalDateTime.now();
        return dtf.format(now);
    }

    private static void getFunctionOutput(String method) {
        functionOutput = String.valueOf(method);
    }

    private static class Tools {
        /** goTo tool: path finder + tracer **/
        private static void goTo(int x, int y, int z, boolean sprint) {
            System.out.println("Going to coordinates: " + x + ", " + y + ", " + z + " | Sprint: " + sprint);
            if (botSource == null || botSource.getPlayer() == null) {
                System.out.println("Bot not found.");
                getFunctionOutput("Bot not found.");
                return;
            }
            try {
                String result = startPreciseCoordinateMove(x, y, z, sprint).get(120, TimeUnit.SECONDS);
                // ✅ Ensure we have a valid result for parsing
                if (result == null || result.trim().isEmpty()) {
                    // Fallback: get current bot position
                    ServerPlayer bot = botSource.getPlayer();
                    if (bot != null) {
                        BlockPos pos = bot.blockPosition();
                        result = String.format("Bot position - x: %d y: %d z: %d",
                                pos.getX(), pos.getY(), pos.getZ());
                    }
                }
                getFunctionOutput(result);
            } catch (Exception e) {
                logger.error("Error in goTo: ", e);
                getFunctionOutput("Failed to navigate to coordinates: " + e.getMessage());
            }
        }

        /** chartPathToBlock: final positioning **/
        private static void chartPathToBlock(int targetX, int targetY, int targetZ, String blockType) {
            System.out.println("Charting path to block at: " + targetX + ", " + targetY + ", " + targetZ + " | BlockType: " + blockType);
            getFunctionOutput(ChartPathToBlock.chart(Objects.requireNonNull(botSource.getPlayer()), new BlockPos(targetX, targetY, targetZ), blockType));
        }

        /** faceBlock: look at block **/
        private static void faceBlock(int targetX, int targetY, int targetZ) {
            System.out.println("Facing block at: " + targetX + ", " + targetY + ", " + targetZ);
            getFunctionOutput(LookController.faceBlock(Objects.requireNonNull(botSource.getPlayer()), new BlockPos(targetX, targetY, targetZ)));
        }

        /** faceEntity: look at entity **/
        private static void faceEntity(int targetX, int targetY, int targetZ) {
            System.out.println("Facing entity at: " + targetX + ", " + targetY + ", " + targetZ);
            ServerPlayer bot = Objects.requireNonNull(botSource.getPlayer());
            // Get the world
            var world = bot.level();
            // Create a small bounding box around the coordinates to find nearby entities
            var box = new AABB(
                    targetX - 1, targetY - 1, targetZ - 1,
                    targetX + 1, targetY + 1, targetZ + 1
            );
            // Get all entities except the bot itself
            var entities = world.getEntities(bot, box);
            if (entities.isEmpty()) {
                System.out.println("No entity found at given coordinates.");
                getFunctionOutput("No entity found at given coordinates.");
                return;
            }
            // Take the first entity found
            var target = entities.getFirst();
            LookController.faceEntity(bot, target);
            getFunctionOutput("Facing entity: " + target.getName().getString());
        }

        /** detectBlocks: raycast with block type filter **/
        private static void detectBlocks(String blockType) {
            System.out.println("Detecting blocks of type: " + blockType);
            if (botSource == null || botSource.getPlayer() == null) {
                getFunctionOutput("Bot not found.");
                return;
            }
            try {
                BlockPos outputPos = blockDetectionUnit.detectBlocks(
                        Objects.requireNonNull(botSource.getPlayer()), blockType);
                String output;
                if (outputPos == null) {
                    output = "Block not found!";
                } else {
                    output = "Block found at " + outputPos.getX() + " " + outputPos.getY() + " " + outputPos.getZ();
                }
                getFunctionOutput(output);
            } catch (Exception e) {
                logger.error("Error in detectBlocks: ", e);
                getFunctionOutput("Failed to detect blocks: " + e.getMessage());
            }
        }

        /** turn: change torso facing direction **/
        private static void turn(String direction) {
            System.out.println("Turning to: " + direction);
            MinecraftServer server = botSource.getServer();
            String botName = botSource.getTextName();
            server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " turn " + direction); // choosing the command route instead of calling function to check if the bug still exists.
            getFunctionOutput("Now facing " + direction + " which is in " + Objects.requireNonNull(botSource.getPlayer()).getNearestViewDirection().getName() + " in " + Objects.requireNonNull(botSource.getPlayer()).getNearestViewDirection().getAxis().getSerializedName() + " axis.");
        }

        /** look: change head facing direction **/
        private static void look(String cardinalDirection) {
            System.out.println("Looking at: " + cardinalDirection);
            MinecraftServer server = botSource.getServer();
            String botName = botSource.getTextName();
            server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " look " + cardinalDirection); // choosing the command route instead of calling function to check if the bug still exists.
            getFunctionOutput("Now facing cardinal direction: " + Objects.requireNonNull(botSource.getPlayer()).getNearestViewDirection().getName() + " which is in " + Objects.requireNonNull(botSource.getPlayer()).getNearestViewDirection().getAxis().getSerializedName() + " axis.");
        }

        /** walk: move in a direction for a short duration **/
        private static void walk(int seconds) {
            walk(seconds, "forward");
        }

        private static void walk(int seconds, String direction) {
            String movementDirection = normalizeWalkDirection(direction);
            System.out.println("Walking " + movementDirection + " for: " + seconds + " seconds");
            if (botSource == null || botSource.getPlayer() == null) {
                getFunctionOutput("Bot not found.");
                return;
            }

            MinecraftServer server = botSource.getServer();
            String botName = botSource.getPlayer().getName().getString();
            int clampedSeconds = Math.max(1, Math.min(seconds, 30));

            logger.info("Executing walk command for {} moving {}", botName, movementDirection);
            server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " move " + movementDirection);
            AutoFaceEntity.isBotMoving = true;
            AutoFaceEntity.setBotExecutingTask(true);

            executor.submit(() -> {
                try {
                    Thread.sleep(clampedSeconds * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                server.execute(() -> {
                    logger.info("Executing walk stop command for {}", botName);
                    server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " stop");
                    AutoFaceEntity.isBotMoving = false;
                    AutoFaceEntity.setBotExecutingTask(false);
                });
            });

            getFunctionOutput("Walking " + movementDirection + " for " + clampedSeconds + " seconds.");
        }

        /** hit: swing/attack once **/
        private static void hit() {
            System.out.println("Hitting with current item");
            if (botSource == null || botSource.getPlayer() == null) {
                getFunctionOutput("Bot not found.");
                return;
            }

            MinecraftServer server = botSource.getServer();
            String botName = botSource.getTextName();
            server.getCommands().performPrefixedCommand(botSource, "/player " + botName + " attack");
            getFunctionOutput("Attack executed.");
        }

        /** mineBlock: break block **/
        private static void mineBlock(int targetX, int targetY, int targetZ) {
            System.out.println("Mining block at: " + targetX + ", " + targetY + ", " + targetZ);
            if (botSource == null || botSource.getPlayer() == null) {
                getFunctionOutput("Bot not found.");
                return;
            }
            try {
                // ✅ Use CompletableFuture.get() to wait for completion
                CompletableFuture<String> miningFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        return MiningTool.mineBlock(
                                Objects.requireNonNull(botSource.getPlayer()),
                                new BlockPos(targetX, targetY, targetZ)
                        ).get();
                    } catch (Exception e) {
                        logger.error("Failed to mine block: {}", e.getMessage(), e);
                        return "⚠️ Failed to mine block: " + e.getMessage();
                    }
                });
                // Wait for result with timeout
                String result = miningFuture.get(10, TimeUnit.SECONDS);
                getFunctionOutput(result);
            } catch (Exception e) {
                logger.error("Error in mineBlock: ", e);
                getFunctionOutput("⚠️ Failed to mine block: " + e.getMessage());
            }
        }

        /** placeBlock: place block at coordinates **/
        private static void placeBlock(int targetX, int targetY, int targetZ, String blockType) {
            System.out.println("Placing block " + blockType + " at: " + targetX + ", " + targetY + ", " + targetZ);
            if (botSource == null || botSource.getPlayer() == null) {
                getFunctionOutput("Bot not found.");
                return;
            }
            try {
                // ✅ Use CompletableFuture.get() to wait for completion
                CompletableFuture<String> placementFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        return BlockPlacementTool.placeBlock(
                                Objects.requireNonNull(botSource.getPlayer()),
                                new BlockPos(targetX, targetY, targetZ),
                                blockType
                        ).get();
                    } catch (Exception e) {
                        logger.error("Failed to place block: {}", e.getMessage(), e);
                        return "⚠️ Failed to place block: " + e.getMessage();
                    }
                });
                // Wait for result with timeout
                String result = placementFuture.get(10, TimeUnit.SECONDS);
                getFunctionOutput(result);
            } catch (Exception e) {
                logger.error("Error in placeBlock: ", e);
                getFunctionOutput("⚠️ Failed to place block: " + e.getMessage());
            }
        }

        /** getOxygenLevel: report air level **/
        private static void getOxygenLevel() {
            System.out.println("Getting oxygen level...");
            getFunctionOutput("Oxygen Level: " + getPlayerOxygen.getBotOxygenLevel(Objects.requireNonNull(botSource.getPlayer())));
        }

        /** getHungerLevel: report hunger **/
        private static void getHungerLevel() {
            System.out.println("Getting hunger level...");
            getFunctionOutput("Hunger Level: " + getPlayerHunger.getBotHungerLevel(Objects.requireNonNull(botSource.getPlayer())));
        }

        /** getHealthLevel: report health **/
        private static void getHealthLevel() {
            System.out.println("Getting health level...");
            getFunctionOutput("Remaining hearts: " + getHealth.getBotHealthLevel(Objects.requireNonNull(botSource.getPlayer())));
        }

        private static void webSearch(String query) {
            System.out.println("Running web search...");
            getFunctionOutput("Web search result: " + WebSearchTool.search(query));
        }

        /** searchBlocks: efficiently search for blocks in expanding radius **/
        private static void searchBlocks(String blockType, int initialRadius, int maxRadius, int radiusIncrement) {
            System.out.println("Searching for " + blockType + " in radius " + initialRadius + "-" + maxRadius);
            if (botSource == null || botSource.getPlayer() == null) {
                getFunctionOutput("Bot not found.");
                return;
            }
            try {
                ServerPlayer bot = botSource.getPlayer();
                BlockPos result = net.shasankp000.Tools.SearchBlocks.searchBlock(
                        bot,
                        blockType,
                        initialRadius,
                        maxRadius,
                        radiusIncrement
                );

                if (result != null) {
                    getFunctionOutput(String.format("Found %s at x: %d y: %d z: %d (distance: %d blocks)",
                            blockType,
                            result.getX(),
                            result.getY(),
                            result.getZ(),
                            bot.blockPosition().distManhattan(result)));
                } else {
                    getFunctionOutput(String.format("No %s found within %d blocks", blockType, maxRadius));
                }
            } catch (Exception e) {
                logger.error("Error in searchBlocks: ", e);
                getFunctionOutput("Failed to search for blocks: " + e.getMessage());
            }
        }

        private static void sendMessageToChat(String message) {
            System.out.println("Sending message to chat...");
            ChatUtils.sendChatMessages(botSource, message);
        }
    }

    private static String toolBuilder() {
        var gson = new Gson();
        List<Map<String, Object>> functions = ToolRegistry.TOOLS.stream().map(tool -> Map.of(
                    "name", tool.name(),
                    "description", tool.description(),
                    "parameters", tool.parameters().stream().map(param -> Map.of(
                            "name", param.name(),
                            "description", param.description(),
                            "required", true
                    )).toList()
            )).toList();
        return gson.toJson(Map.of("functions", functions));
    }

    // This code right here is pure EUREKA moment.
    private static String buildPrompt(String toolString) {
        String recentMemory = buildRecentMemoryContext();
        return """
            You are a first-principles reasoning **function-caller AI agent** for a Minecraft bot.
            
            You will be provided with additional context information of the minecraft bot you are controlling. Use that information well to carefully plan your approach.
            You also have recent chat/action memory. Use it to maintain continuity, avoid repeating failed actions, and understand follow-up requests.

            Your role is to analyze player prompts carefully and decide which tool or sequence of tools best accomplishes the task.
            You must output your decision strictly as JSON, following the required schema.
            Return a complete JSON object in one response. Never stop after an opening brace.
            
            ---
            
            Key Principles
            
            1. **Use only the tools you have.**
            Do not hallucinate new tools. Each tool has clear parameters, a purpose, and trade-offs.
            
            2. **Use the fewest tools possible.**
            When a single tool is enough, use it.
            When multiple tools must be chained, output them as a pipeline in the correct order.
            
            3. **Focus on action verbs.** 
            Player prompts contain action verbs that reveal intent: go, walk, navigate, check, search, mine, approach, align, harvest, etcetera. 
            Always match these to the most relevant tools.
            
            4. **Use $placeholders for shared state.** 
            If a step depends on output from a previous step, use `$lastDetectedBlock.x` (etc.). 
            The runtime will substitute these dynamically.
            
            ---
            
            Execution Loop
            
            After each step, you will be given the output of the previous function.
            You must decide what to do next:
            - Continue with the remaining steps in the pipeline.
            - Retry the same function with adjusted parameters.
            - Abandon the current pipeline and create a completely new pipeline.
            
            ---
            
            ### **Examples**
            
            ✅ **Continue:**
            If you are satisfied, do nothing — the next step will execute automatically.
            
            ✅ **Retry:**
            If the previous function failed or needs adjustments, output:
            {
              "functionName": "goTo",
              "parameters": [
                { "parameterName": "x", "parameterValue": "12" },
                { "parameterName": "y", "parameterValue": "65" },
                { "parameterName": "z", "parameterValue": "-20" },
                { "parameterName": "sprint", "parameterValue": "false" }
              ]
            }
            
            ✅ Rebuild pipeline:
            If the plan must change completely, output:
            {
              "pipeline": [
                {
                  "functionName": "detectBlocks",
                  "parameters": [
                    { "parameterName": "blockType", "parameterValue": "stone" }
                  ]
                },
                {
                  "functionName": "goTo",
                  "parameters": [
                    { "parameterName": "x", "parameterValue": "$lastDetectedBlock.x" },
                    { "parameterName": "y", "parameterValue": "$lastDetectedBlock.y" },
                    { "parameterName": "z", "parameterValue": "$lastDetectedBlock.z" },
                    { "parameterName": "sprint", "parameterValue": "true" }
                  ]
                }
              ]
            }
            
            ---
            
            When to chain tools:
            
            For example if the player outputs: "Can you fetch me some wood?" or "Can you mine some iron?" or "Can you plant the wheat seeds from the chest?"
            
            These type of requests are requests which involve multi-step actions, each action chained in a particular order.
            
            To fulfill these type of requests you need to chain the tools you have at your disposal in a specific order by understanding what each tool does and how each tool works.
            
            ---
            
            How you must output:
            
            If you only need to use one tool, output in this JSON format.
            
            {
              "functionName": "searchBlocks",
              "parameters": [
                { "parameterName": "blockType", "parameterValue": "minecraft:oak_log" },
                { "parameterName": "initialRadius", "parameterValue": "10" },
                { "parameterName": "maxRadius", "parameterValue": "100" },
                { "parameterName": "radiusIncrement", "parameterValue": "20" }
              ]
            }
            
            If you need multiple tools in a sequence, output as follows:
            
            {
              "pipeline": [
                {
                  "functionName": "searchBlocks",
                  "parameters": [
                    { "parameterName": "blockType", "parameterValue": "minecraft:oak_log" },
                    { "parameterName": "initialRadius", "parameterValue": "10" },
                    { "parameterName": "maxRadius", "parameterValue": "100" },
                    { "parameterName": "radiusIncrement", "parameterValue": "20" }
                  ]
                },
                {
                  "functionName": "goTo",
                  "parameters": [
                    { "parameterName": "x", "parameterValue": "$lastDetectedBlock.x" },
                    { "parameterName": "y", "parameterValue": "$lastDetectedBlock.y" },
                    { "parameterName": "z", "parameterValue": "$lastDetectedBlock.z" }
                  ]
                }
              ]
            }
            
            ---
            
            REMEMBER:
            
            ✅ Always use $placeholders when a step depends on values returned by a previous step. The pipeline executor will resolve these placeholders dynamically with the correct output from the previous step.
            
            ✅ Always use the "parameterName" and "parameterValue" fields exactly — do not rename them.
            
            ✅ Do not output any extra words, explanations, or formatting — only valid JSON.
            
            ===
            
            Final reminders:
            
            ✅ Only output valid JSON.
            
            ✅ Do not output any other text.
            
            ✅ Do not change field names.
            
            ✅ Be logical — always use the simplest pipeline that fully achieves the goal.
            
            ✅ The runtime will parse your JSON exactly as you return it.
            
            ---
            
            If the prompt is ambiguous, choose the minimal safe path or ask for clarification.
            
            If you cannot confidently select the correct tools because the prompt is ambiguous or incomplete,
            do NOT guess.
            
            Instead, output JSON like this:
            
            {
              "clarification": "Could you please clarify which type of block I should search for?"
            }
            
            Your clarification should be concise, specific, and related to Minecraft context.
            
            ✅ Never output any other words — only valid JSON.
            
            ✅ The runtime will deliver your clarification question to the player and wait for their answer.
            
            ✅ After receiving the answer, decide on how you should continue things from there onwards. If you again need more clarification, ask again.
            
            ---
            
            Available Tools
            
            Below is your list of tools, each with its name, description, required parameters and the key names:
            
            """ + toolString +
                """
                
                And the correct placeholders to use per tool: \n
                
                """ + functionStateKeyMap + "\n Do remember to add a placeholder symbol: $ in front of each parameter name when designing the pipeline json."
                + "\n\nRecent memory:\n" + recentMemory;
    }

    private static String buildRecentMemoryContext() {
        List<SQLiteDB.Memory> memories = SQLiteDB.fetchRecentMemories(List.of("chat", "action"), 10);
        if (memories.isEmpty()) {
            return "No recent chat or action memory.";
        }

        StringBuilder builder = new StringBuilder();
        for (SQLiteDB.Memory memory : memories) {
            builder.append("- [").append(memory.type()).append("] ");
            builder.append(memory.prompt());
            if (memory.response() != null && !memory.response().isBlank()) {
                builder.append(" -> ").append(memory.response());
            }
            builder.append("\n");
        }
        return builder.toString().trim();
    }

    private static String generatePromptContext(String userPrompt) {
        String contextOutput = "";
        String sysPrompt = """
        You are a context generation AI agent in terms of minecraft. \n
        This means that you will have a prompt from a user, who is the player and you need to analyze the context of the player's prompts, i.e what the player means by the prompt. \n
        This context information will then be used by a minecraft bot to understand what the user is trying to say. \n
        \n
        Here are some example player prompts you may receive: \n
        1. Could you check if there is a block in front of you? \n
        2. Look around for any hostile mobs, and report to me if you find any. \n
        3. Could you mine some stone and bring them to me? \n
        4. Craft a set of iron armor. \n
        5. Please go to coordinates 10 -60 20. \n
        \n
        A few more variations of the prompts may be: \n
        "Could you search for blocks in front of you?"
        "Do you see if there is a block in front of you?"
        "Can you mine some stone and bring them to me?
        "Please move to 10 -60 20." or "Please go to the coords 10 -60 20" or "Please go to 10 -60 20" and so on... \n
        \n
        Here are some examples of the format in which you MUST answer.
        \n
        1. The player asked you to check whether there is a block in front of you or not. \n
        2. The player asked you to search for hostile mobs around you, and to report to the player if you find any such hostile mob. \n
        3. The player asked you to mine some stone and then bring the stone to the player. \n
        4. The player asked you to craft a set of iron armor. \n
        5. The player asked you to go to coordinates 10 -60 20. You followed the instructions and began movement to the coordinates. \n
        Remember that all the context you generate should be in the past tense, sense it is being recorded after the deed has been done.
        \n
        "Remember that when dealing with prompts that ask the bot to go to a specific set of x y z coordinates, you MUST NOT alter the coordinates, they SHOULD BE the exact same as in the prompt given by the player.
        \n
        Now,remember that you must only generate the context as stated in the examples, nothing more, nothing less. DO NOT add your own opinions/statements/thinking to the context you generate. \n
        Remember that if you generate incorrect context then the bot will not be able to understand what the user has asked of it.
        \s
        \s""";
        try {
            List<OllamaChatMessage> messages = new java.util.ArrayList<>();
            messages.add(new OllamaChatMessage(OllamaChatMessageRole.SYSTEM, sysPrompt));
            messages.add(new OllamaChatMessage(OllamaChatMessageRole.USER, "Player prompt: " + userPrompt));

            net.shasankp000.OllamaClient.OllamaThinkingResponse thinkingResponse =
                    net.shasankp000.OllamaClient.OllamaAPIHelper.smartChat(
                            ollamaAPI,
                            "http://localhost:11434",
                            net.shasankp000.AIPlayer.CONFIG.getSelectedLanguageModel(),
                            messages
                    );

            contextOutput = thinkingResponse.getContent();
        } catch (IOException | InterruptedException | JsonSyntaxException e) {
            logger.error("{}", (Object) e.getStackTrace());
        }
        return contextOutput;
    }

    public static String buildLLMBotContext(State state, Map<String, Object> sharedState, Map<String, Object> surroundingsSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("Bot current state:\n");
        sb.append("- Position: (").append(state.getBotX()).append(", ").append(state.getBotY()).append(", ").append(state.getBotZ()).append(")\n");

        Object direction = SharedStateUtils.getValue(sharedState, "facing.direction");
        if (direction != null) {
            sb.append("- Facing: ").append(direction);
            Object facing = SharedStateUtils.getValue(sharedState, "facing.facing");
            if (facing != null) {
                sb.append(" (").append(facing);
                Object axis = SharedStateUtils.getValue(sharedState, "facing.axis");
                if (axis != null) {
                    sb.append(", axis: ").append(axis);
                }
                sb.append(")");
            }
            sb.append("\n");
        } else {
            // first time call.
            assert botSource.getPlayer() != null;
            Direction facingDir = botSource.getPlayer().getNearestViewDirection();
            sb.append("- Facing: ").append(facingDir.getName());
            sb.append(" (axis: ").append(facingDir.getAxis().getSerializedName()).append(")");
        }

        sb.append("- Selected Item: ").append(state.getSelectedItem()).append("\n");
        if (!state.getNearbyBlocks().isEmpty()) {
            sb.append("- Nearby Blocks: ").append(state.getNearbyBlocks().stream().limit(3).toList()).append("\n");
        }

        if (!state.getNearbyEntities().isEmpty()) {
            List<String> nearbyHostiles = state.getNearbyEntities().stream()
                    .filter(EntityDetails::isHostile)
                    .map(EntityDetails::getName)
                    .limit(2)
                    .toList();
            if (!nearbyHostiles.isEmpty()) {
                sb.append("- Nearby Hostile Entities: ").append(nearbyHostiles).append("\n");
            }
        }

        sb.append("- Health: ").append(state.getBotHealth()).append("\n");
        sb.append("- Hunger: ").append(state.getBotHungerLevel()).append("\n");
        sb.append("- Oxygen: ").append(state.getBotOxygenLevel()).append("\n");
        if (state.getDistanceToDangerZone() > 0) {
            sb.append("- Bot is close to a danger zone\n");
        }

        // Inject summarized surroundings
        if (surroundingsSummary != null && !surroundingsSummary.isEmpty()) {
            sb.append("- Immediate surroundings:\n");
            for (Map.Entry<String, Object> entry : surroundingsSummary.entrySet()) {
                sb.append(" • ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }

        return sb.toString();
    }

    public static void run(String userPrompt) {
        ollamaAPI.setRequestTimeoutSeconds(600);
        if (tryHandleDirectDrop(userPrompt)) {
            return;
        }
        if (tryHandleDirectHouseBuild(userPrompt)) {
            return;
        }
        if (tryHandleDirectCraft(userPrompt)) {
            return;
        }
        if (tryHandleDirectWalk(userPrompt)) {
            return;
        }

        String systemPrompt = FunctionCallerV2.buildPrompt(toolBuilder());
        String response;
        State initialState = BotEventHandler.createInitialState(botSource.getPlayer());
        InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1); // 1-block radius in all directions
        map.updateMap();
        // If method returns Map<String, String>
        Map<String, String> surroundingsStr = map.summarizeSurroundings();
        Map<String, Object> surroundings = new HashMap<>(surroundingsStr);

        String botContext = buildLLMBotContext(initialState, sharedState, surroundings);
        String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;
        try {
            List<OllamaChatMessage> messages = new java.util.ArrayList<>();
            messages.add(new OllamaChatMessage(OllamaChatMessageRole.SYSTEM, fullSystemPrompt));
            messages.add(new OllamaChatMessage(OllamaChatMessageRole.USER, userPrompt));

            net.shasankp000.OllamaClient.OllamaThinkingResponse thinkingResponse =
                    net.shasankp000.OllamaClient.OllamaAPIHelper.smartChat(
                            ollamaAPI,
                            "http://localhost:11434",
                            net.shasankp000.AIPlayer.CONFIG.getSelectedLanguageModel(),
                            messages
                    );

            response = thinkingResponse.getContent();
            logger.info("Raw LLM Response: {}", response);
            String cleanedResponse = stripThinkBlock(response);
            String jsonPart = extractJson(cleanedResponse);
            logger.info("Extracted JSON: {}", jsonPart);
            executeFunction(userPrompt, jsonPart);
        } catch (Exception e) {
            logger.error("Error in Function Caller: {}", e.getMessage());
        }
    }

    public static void run(String userPrompt, LLMClient client) {
        if (tryHandleDirectDrop(userPrompt)) {
            return;
        }
        if (tryHandleDirectHouseBuild(userPrompt)) {
            return;
        }
        if (tryHandleDirectCraft(userPrompt)) {
            return;
        }
        if (tryHandleDirectWalk(userPrompt)) {
            return;
        }

        String systemPrompt = FunctionCallerV2.buildPrompt(toolBuilder());
        String response;
        State initialState = BotEventHandler.createInitialState(botSource.getPlayer());
        InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1); // 1-block radius in all directions
        map.updateMap();
        // If method returns Map<String, String>
        Map<String, String> surroundingsStr = map.summarizeSurroundings();
        Map<String, Object> surroundings = new HashMap<>();
        surroundings.putAll(surroundingsStr);

        String botContext = buildLLMBotContext(initialState, sharedState, surroundings);
        String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;
        try {
            response = client.sendPrompt(fullSystemPrompt, userPrompt);
            logger.info("Raw LLM Response: {}", response);
            String cleanedResponse = stripThinkBlock(response);
            String jsonPart = extractJson(cleanedResponse);
            logger.info("Extracted JSON: {}", jsonPart);
            executeFunction(userPrompt, jsonPart, client);
        } catch (Exception e) {
            logger.error("Error in Function Caller: {}", e.getMessage(), e);
        }
    }

    private static String extractJson(String response) {
        String stripped = stripThinkBlock(response); // Use fix 1 here
        // Try to locate either a JSON object or array
        int objStart = stripped.indexOf("{");
        int objEnd = stripped.lastIndexOf("}") + 1;
        int arrStart = stripped.indexOf("[");
        int arrEnd = stripped.lastIndexOf("]") + 1;
        // Try full JSON object (most likely case)
        if (objStart != -1 && objEnd != -1 && objEnd > objStart) {
            String candidate = stripped.substring(objStart, objEnd);
            if (isValidJson(candidate)) return candidate;
        }
        // Try JSON array (secondary fallback)
        if (arrStart != -1 && arrEnd != -1 && arrEnd > arrStart) {
            String candidate = stripped.substring(arrStart, arrEnd);
            if (isValidJson(candidate)) return candidate;
        }
        logger.error("❌ Could not extract valid JSON from response:\n{}", response);
        return "";
    }

    private static boolean isValidJson(String json) {
        try {
            JsonParser.parseString(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripThinkBlock(String response) {
        Matcher matcher = THINK_BLOCK.matcher(response);
        if (matcher.find()) {
            return response.replace(matcher.group(0), "").trim(); // Strip the full ...
        }
        return response.trim();
    }

    private static void executeFunction(String userInput, String response) {
        String executionDateTime = getCurrentDateandTime();
        executor.submit(() -> {
            try {
                if (response == null || response.isBlank()) {
                    throw new IllegalStateException("LLM returned malformed or incomplete JSON.");
                }
                String cleanedResponse = cleanJsonString(response);
                cleanedResponse = wrapBareParameterArray(userInput, cleanedResponse);
                logger.info("Cleaned JSON Response: {}", cleanedResponse);
                JsonReader reader = new JsonReader(new StringReader(cleanedResponse));
                reader.setLenient(true);
                JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                if (jsonObject.has("pipeline")) {
                    AutoFaceEntity.setBotExecutingTask(true);
                    JsonArray pipeline = jsonObject.getAsJsonArray("pipeline");
                    runPipelineLoop(pipeline);
                } else if (jsonObject.has("functionName")) {
                    AutoFaceEntity.setBotExecutingTask(true);
                    String fnName = jsonObject.get("functionName").getAsString();
                    Map<String, String> paramMap = new ConcurrentHashMap<>(
                            parseParameterMap(jsonObject.get("parameters"), sharedState)
                    );
                    logger.info("Executing: {} with {}", fnName, paramMap);
                    callFunction(fnName, paramMap, sharedState).join();
                } else if (jsonObject.has("clarification")) {
                    System.out.println("Detected clarification");
                    String clarification = jsonObject.get("clarification").getAsString();
                    // Save the clarification state
                    ChatContextManager.setPendingClarification(playerUUID, userInput, clarification, botSource.getTextName());
                    // Relay to player in-game
                    sendMessageToPlayer(clarification);
                } else {
                    throw new IllegalStateException("Response must have either functionName or pipeline.");
                }
                // getFunctionResultAndSave(userInput, executionDateTime);
            } catch (JsonSyntaxException | NullPointerException | IllegalStateException e) {
                logger.error("Error processing JSON response: {}", e.getMessage(), e);
                ChatUtils.sendChatMessages(botSource, "I couldn't understand the action response from the language model. Please try that action again.");
            }
        });
    }

    private static void executeFunction(String userInput, String response, LLMClient client) {
        String executionDateTime = getCurrentDateandTime();
        executor.submit(() -> {
            try {
                if (response == null || response.isBlank()) {
                    throw new IllegalStateException("LLM returned malformed or incomplete JSON.");
                }
                String cleanedResponse = cleanJsonString(response);
                cleanedResponse = wrapBareParameterArray(userInput, cleanedResponse);
                logger.info("Cleaned JSON Response: {}", cleanedResponse);
                JsonReader reader = new JsonReader(new StringReader(cleanedResponse));
                reader.setLenient(true);
                JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
                if (jsonObject.has("pipeline")) {
                    AutoFaceEntity.setBotExecutingTask(true);
                    JsonArray pipeline = jsonObject.getAsJsonArray("pipeline");
                    runPipelineLoop(pipeline, client);
                } else if (jsonObject.has("functionName")) {
                    AutoFaceEntity.setBotExecutingTask(true);
                    String fnName = jsonObject.get("functionName").getAsString();
                    Map<String, String> paramMap = new ConcurrentHashMap<>(
                            parseParameterMap(jsonObject.get("parameters"), sharedState)
                    );
                    logger.info("Executing: {} with {}", fnName, paramMap);
                    callFunction(fnName, paramMap, sharedState).join();
                } else if (jsonObject.has("clarification")) {
                    String clarification = jsonObject.get("clarification").getAsString();
                    // Save the clarification state
                    ChatContextManager.setPendingClarification(playerUUID, userInput, clarification, botSource.getTextName());
                    // Relay to player in-game
                    sendMessageToPlayer(clarification);
                } else {
                    throw new IllegalStateException("Response must have either functionName or pipeline.");
                }
                // getFunctionResultAndSave(userInput, executionDateTime);
            } catch (JsonSyntaxException | NullPointerException | IllegalStateException e) {
                logger.error("Error processing JSON response: {}", e.getMessage(), e);
                ChatUtils.sendChatMessages(botSource, "I couldn't understand the action response from the language model. Please try that action again.");
            }
        });
    }

    private static String wrapBareParameterArray(String userInput, String cleanedResponse) {
        if (cleanedResponse == null || !cleanedResponse.trim().startsWith("[")) {
            return cleanedResponse;
        }

        String inferredFunction = inferFunctionFromUserInput(userInput);
        if (inferredFunction == null) {
            return cleanedResponse;
        }

        JsonElement parsed = JsonParser.parseString(cleanedResponse);
        if (!parsed.isJsonArray()) {
            return cleanedResponse;
        }

        JsonObject wrapped = new JsonObject();
        wrapped.addProperty("functionName", inferredFunction);
        wrapped.add("parameters", parsed.getAsJsonArray());
        logger.warn("Wrapped bare parameter array as function '{}': {}", inferredFunction, wrapped);
        return wrapped.toString();
    }

    private static String inferFunctionFromUserInput(String userInput) {
        if (userInput == null) {
            return null;
        }

        String normalized = userInput.toLowerCase(Locale.ROOT);
        if (normalized.contains("walk") || normalized.contains("move forward") || normalized.contains("forward")) {
            return "walk";
        }
        if (normalized.contains("hit") || normalized.contains("attack")) {
            return "hit";
        }
        return null;
    }

    private static boolean tryHandleDirectWalk(String userInput) {
        WalkRequest walkRequest = parseDirectWalkRequest(userInput);
        if (walkRequest == null) {
            return false;
        }

        if (botSource == null || botSource.getPlayer() == null) {
            logger.warn("Direct walk request ignored because botSource/player is null");
            return false;
        }

        logger.info("Direct walk request detected: {} blocks {} -> {} seconds",
                walkRequest.blocks, walkRequest.direction, walkRequest.seconds);
        startRelativeWalk(walkRequest);
        return true;
    }

    private static void startRelativeWalk(WalkRequest walkRequest) {
        MinecraftServer server = botSource.getServer();
        ServerPlayer bot = botSource.getPlayer();
        String botName = bot.getName().getString();
        int ticks = Math.max(1, walkRequest.seconds * 20);
        int multiplier = "backward".equals(walkRequest.direction) ? -1 : 1;
        Vec3 start = bot.position();
        Vec3 look = bot.getViewVector(1.0F);
        Vec3 horizontal = new Vec3(look.x, 0.0, look.z);
        if (horizontal.lengthSqr() < 1.0E-6) {
            Direction facing = bot.getDirection();
            horizontal = new Vec3(facing.getStepX(), 0.0, facing.getStepZ());
        }
        Vec3 offset = horizontal.normalize().scale(walkRequest.blocks * multiplier);
        Vec3 target = start.add(offset);

        AutoFaceEntity.isBotMoving = true;
        AutoFaceEntity.setBotExecutingTask(true);

        executor.submit(() -> {
            logger.info("Executing precise direct walk for {} from {} to {} over {} ticks",
                    botName, start, target, ticks);
            for (int tick = 0; tick < ticks; tick++) {
                double progress = (tick + 1) / (double) ticks;
                Vec3 next = start.lerp(target, progress);
                server.execute(() -> bot.teleportTo(
                        bot.level(),
                        next.x,
                        next.y,
                        next.z,
                        Set.of(),
                        bot.getYRot(),
                        bot.getXRot(),
                        false
                ));

                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            server.execute(() -> {
                bot.teleportTo(
                        bot.level(),
                        target.x,
                        target.y,
                        target.z,
                        Set.of(),
                        bot.getYRot(),
                        bot.getXRot(),
                        false
                );
                AutoFaceEntity.isBotMoving = false;
                AutoFaceEntity.setBotExecutingTask(false);
                String result = "Walked " + walkRequest.blocks + " blocks " + walkRequest.direction + ".";
                getFunctionOutput(result);
                storeActionMemory("directWalk", Map.of(
                        "blocks", String.valueOf(walkRequest.blocks),
                        "direction", walkRequest.direction,
                        "seconds", String.valueOf(walkRequest.seconds)
                ), result);
            });
        });
    }

    private static WalkRequest parseDirectWalkRequest(String userInput) {
        if (userInput == null) {
            return null;
        }

        String normalized = userInput.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        boolean mentionsWalk = normalized.matches(".*\\b(walk|move|go)\\b.*");
        boolean mentionsDirection = normalized.matches(".*\\b(forward|forwards|backward|backwards|back)\\b.*");
        if (!mentionsWalk || !mentionsDirection) {
            return null;
        }

        String direction = normalized.matches(".*\\b(backward|backwards|back)\\b.*") ? "backward" : "forward";
        int blocks = 1;
        Matcher blocksMatcher = Pattern.compile("\\b(\\d+)\\s*(?:blocks?|meters?|m)\\b").matcher(normalized);
        if (blocksMatcher.find()) {
            blocks = Integer.parseInt(blocksMatcher.group(1));
        }

        int seconds = blocksToWalkSeconds(blocks);
        return new WalkRequest(direction, blocks, seconds);
    }

    private static int blocksToWalkSeconds(int blocks) {
        return Math.max(1, Math.min(30, (int) Math.ceil(blocks / 4.3)));
    }

    private static String normalizeWalkDirection(String direction) {
        if (direction == null) {
            return "forward";
        }

        String normalized = direction.toLowerCase(Locale.ROOT).trim();
        if (normalized.equals("back") || normalized.equals("backward") || normalized.equals("backwards")) {
            return "backward";
        }
        return "forward";
    }

    private record WalkRequest(String direction, int blocks, int seconds) {}

    private static boolean tryHandleDirectDrop(String userInput) {
        DropRequest dropRequest = parseDirectDropRequest(userInput);
        if (dropRequest == null) {
            return false;
        }

        if (botSource == null || botSource.getPlayer() == null) {
            logger.warn("Direct drop request ignored because botSource/player is null");
            return false;
        }

        dropItem(dropRequest.itemName(), dropRequest.quantity());
        return true;
    }

    private static DropRequest parseDirectDropRequest(String userInput) {
        if (userInput == null) {
            return null;
        }

        Optional<String> clarificationAnswer = extractClarificationAnswer(userInput);
        if (clarificationAnswer.isPresent() && userInput.toLowerCase(Locale.ROOT).contains("original:")
                && userInput.toLowerCase(Locale.ROOT).contains("drop")) {
            return new DropRequest(cleanupCraftItemName(clarificationAnswer.get()), -1);
        }

        Matcher matcher = Pattern.compile(
                "(?i)\\bdrop\\b\\s+(?:it\\s+|item\\s+)?(?:(all|\\d+)\\s+)?(.+)$"
        ).matcher(userInput);
        if (!matcher.find()) {
            return null;
        }

        String quantityText = matcher.group(1);
        String rawItemName = matcher.group(2)
                .replaceAll("(?i)\\b(?:please|now|for me)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        String itemName = cleanupCraftItemName(rawItemName);
        if (itemName.isBlank()) {
            return null;
        }

        int quantity = -1;
        if (quantityText != null && !quantityText.equalsIgnoreCase("all")) {
            try {
                quantity = Math.max(1, Integer.parseInt(quantityText));
            } catch (NumberFormatException ignored) {
                quantity = -1;
            }
        }

        return new DropRequest(itemName, quantity);
        }

        private record DropRequest(String itemName, int quantity) {}

    private static CompletableFuture<String> startPreciseCoordinateMove(int x, int y, int z, boolean sprint) {
        CompletableFuture<String> future = new CompletableFuture<>();
        MinecraftServer server = botSource.getServer();
        ServerPlayer bot = botSource.getPlayer();
        Vec3 start = bot.position();
        Vec3 target = new Vec3(x + 0.5, y, z + 0.5);
        double distance = start.distanceTo(target);
        double blocksPerSecond = sprint ? 5.612 : 4.317;
        int ticks = Math.max(1, Math.min(2400, (int) Math.ceil((distance / blocksPerSecond) * 20.0)));

        AutoFaceEntity.isBotMoving = true;
        AutoFaceEntity.setBotExecutingTask(true);

        executor.submit(() -> {
            logger.info("Executing precise coordinate move from {} to {} over {} ticks", start, target, ticks);
            try {
                for (int tick = 0; tick < ticks; tick++) {
                    double progress = (tick + 1) / (double) ticks;
                    Vec3 next = start.lerp(target, progress);
                    server.execute(() -> bot.teleportTo(
                            bot.level(),
                            next.x,
                            next.y,
                            next.z,
                            Set.of(),
                            bot.getYRot(),
                            bot.getXRot(),
                            false
                    ));
                    Thread.sleep(50L);
                }

                server.execute(() -> {
                    bot.teleportTo(
                            bot.level(),
                            target.x,
                            target.y,
                            target.z,
                            Set.of(),
                            bot.getYRot(),
                            bot.getXRot(),
                            false
                    );
                    AutoFaceEntity.isBotMoving = false;
                    AutoFaceEntity.setBotExecutingTask(false);
                    BlockPos pos = bot.blockPosition();
                    String result = String.format("Bot moved to position - x: %d y: %d z: %d",
                            pos.getX(), pos.getY(), pos.getZ());
                    storeActionMemory("goTo", Map.of(
                            "x", String.valueOf(x),
                            "y", String.valueOf(y),
                            "z", String.valueOf(z),
                            "sprint", String.valueOf(sprint)
                    ), result);
                    future.complete(result);
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                server.execute(() -> {
                    AutoFaceEntity.isBotMoving = false;
                    AutoFaceEntity.setBotExecutingTask(false);
                });
                future.completeExceptionally(e);
            } catch (Exception e) {
                server.execute(() -> {
                    AutoFaceEntity.isBotMoving = false;
                    AutoFaceEntity.setBotExecutingTask(false);
                });
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private static boolean tryHandleDirectCraft(String userInput) {
        if (!isCraftRequest(userInput)) {
            return false;
        }

        if (botSource == null || botSource.getPlayer() == null) {
            logger.warn("Direct craft request ignored because botSource/player is null");
            return false;
        }

        String itemName = extractCraftItemName(userInput);
        if (itemName.isBlank()) {
            String clarification = "What item should I craft?";
            ChatContextManager.setPendingClarification(playerUUID, "craft an item", clarification, botSource.getTextName());
            sendMessageToPlayer(clarification);
            return true;
        }

        int quantity = extractCraftQuantity(userInput);
        craftItem(itemName, quantity);
        return true;
    }

    private static boolean isCraftRequest(String userInput) {
        if (userInput == null) {
            return false;
        }

        String normalized = userInput.toLowerCase(Locale.ROOT);
        if (normalized.contains("original:") && normalized.contains("craft")) {
            return true;
        }

        return normalized.matches(".*\\b(craft|make)\\b.*");
    }

    private static String extractCraftItemName(String userInput) {
        Optional<String> clarificationAnswer = extractClarificationAnswer(userInput);
        if (clarificationAnswer.isPresent()) {
            return cleanupCraftItemName(clarificationAnswer.get());
        }

        Matcher matcher = Pattern.compile("(?i)\\b(?:craft|make)\\b\\s+(.+)$").matcher(userInput);
        if (!matcher.find()) {
            return "";
        }

        String rawItem = matcher.group(1)
                .replaceAll("(?i)\\b\\d+\\b", " ")
                .replaceAll("(?i)\\b(?:using|with|from|out of)\\b.*$", " ")
                .replaceAll("(?i)\\b(?:please|now)\\b", " ");
        return cleanupCraftItemName(rawItem);
    }

    private static String cleanupCraftItemName(String rawItem) {
        String cleaned = rawItem.toLowerCase(Locale.ROOT)
                .replace("minecraft:", "")
                .replaceAll("[^a-z0-9_\\-\\s]", " ")
                .replaceAll("[\\-_]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        List<String> keptWords = new ArrayList<>();
        for (String word : cleaned.split(" ")) {
            if (!word.isBlank() && !CRAFT_FILLER_WORDS.contains(word)) {
                keptWords.add(word);
            }
        }
        return String.join("_", keptWords);
    }

    private static int extractCraftQuantity(String userInput) {
        Matcher matcher = Pattern.compile("\\b(\\d+)\\b").matcher(userInput == null ? "" : userInput);
        if (!matcher.find()) {
            return 1;
        }

        try {
            return Math.max(1, Math.min(64, Integer.parseInt(matcher.group(1))));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static boolean tryHandleDirectHouseBuild(String userInput) {
        BuildingType buildingType = extractBuildingType(userInput);
        if (buildingType == null) {
            return false;
        }

        if (botSource == null || botSource.getPlayer() == null) {
            logger.warn("Direct build request ignored because botSource/player is null");
            return false;
        }

        Optional<Block> requestedBlock = extractHouseBlock(userInput);
        if (requestedBlock.isEmpty()) {
            String clarification = "What type of block should I use for the " + buildingType.displayName() + "?";
            ChatContextManager.setPendingClarification(playerUUID, userInput, clarification, botSource.getTextName());
            sendMessageToPlayer(clarification);
            return true;
        }

        buildSimpleHouseAtBot(requestedBlock.get(), extractHouseSize(userInput, buildingType), buildingType);
        return true;
    }

    private static BuildingType extractBuildingType(String userInput) {
        if (userInput == null) {
            return null;
        }

        String normalized = userInput.toLowerCase(Locale.ROOT);
        boolean buildIntent = normalized.matches(".*\\b(build|make|construct)\\b.*")
                || (normalized.contains("original:") && normalized.matches(".*\\b(build|make|construct)\\b.*"));
        if (!buildIntent) {
            return null;
        }

        if (normalized.matches(".*\\b(sky\\s*scraper|skyscraper)\\b.*")) {
            return BuildingType.SKYSCRAPER;
        }
        if (normalized.matches(".*\\b(castle|fort|fortress)\\b.*")) {
            return BuildingType.CASTLE;
        }
        if (normalized.matches(".*\\b(tower|watchtower)\\b.*")) {
            return BuildingType.TOWER;
        }
        if (normalized.matches(".*\\b(house|home|hut|base)\\b.*")) {
            return BuildingType.HOUSE;
        }
        if (normalized.matches(".*\\b(building|structure|shop|store|office|school|barn|temple|church|cabin)\\b.*")) {
            return BuildingType.BUILDING;
        }
        return null;
    }

    private static Optional<Block> extractHouseBlock(String userInput) {
        String materialText = sanitizeBuildingMaterial(
                extractClarificationAnswer(userInput).orElseGet(() -> extractInlineHouseMaterial(userInput))
        );
        if (materialText == null || materialText.isBlank()) {
            return Optional.empty();
        }

        String normalizedBlockName = BlockNameNormalizer.normalizeBlockName(materialText);
        Identifier blockId = Identifier.tryParse(normalizedBlockName);
        if (blockId == null) {
            return Optional.empty();
        }

        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(blockId);
        if (block.isEmpty() || block.get() == Blocks.AIR || block.get() == Blocks.CAVE_AIR || block.get() == Blocks.VOID_AIR) {
            logger.warn("Invalid house block requested: {}", materialText);
            return Optional.empty();
        }

        return block;
    }

    private static String sanitizeBuildingMaterial(String materialText) {
        if (materialText == null) {
            return "";
        }

        return materialText
                .replaceAll("(?i)\\b(?:size|sized)\\s+\\d{1,2}\\b", " ")
                .replaceAll("(?i)\\b\\d{1,2}\\s*(?:x|by)\\s*\\d{1,2}\\b", " ")
                .replaceAll("(?i)\\b(?:small|medium|normal|big|large|huge|giant|very big|very large)\\b", " ")
                .replaceAll("(?i)\\b(?:house|home|hut|base|castle|fort|fortress|tower|watchtower|sky\\s*scraper|skyscraper|building|structure|shop|store|office|school|barn|temple|church|cabin)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static Optional<String> extractClarificationAnswer(String userInput) {
        Matcher matcher = Pattern.compile("(?is)player answer:\\s*(.+)$").matcher(userInput);
        if (!matcher.find()) {
            return Optional.empty();
        }

        String answer = matcher.group(1)
                .replaceAll("[\\r\\n].*", "")
                .replaceAll("[^a-zA-Z0-9:_\\-\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return answer.isBlank() ? Optional.empty() : Optional.of(answer);
    }

    private static String extractInlineHouseMaterial(String userInput) {
        if (userInput == null) {
            return "";
        }

        Matcher matcher = Pattern.compile(
                "(?i)\\b(?:out of|with|using|from|made of)\\s+([a-z0-9:_\\-\\s]+?)(?:\\s+(?:at|where|near|please|now)\\b|[,.!?]|$)"
        ).matcher(userInput);
        if (!matcher.find()) {
            return "";
        }

        return matcher.group(1).replaceAll("\\s+", " ").trim();
    }

    private static HouseSize extractHouseSize(String userInput, BuildingType buildingType) {
        if (userInput == null) {
            return HouseSize.defaultFor(buildingType);
        }

        String normalized = userInput.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        Matcher rectangle = Pattern.compile("\\b(\\d{1,2})\\s*(?:x|by)\\s*(\\d{1,2})\\b").matcher(normalized);
        if (rectangle.find()) {
            return HouseSize.of(Integer.parseInt(rectangle.group(1)), Integer.parseInt(rectangle.group(2)), buildingType);
        }

        Matcher sizeNumber = Pattern.compile("\\b(?:size|sized|big|large)\\s+(\\d{1,2})\\b").matcher(normalized);
        if (sizeNumber.find()) {
            int size = Integer.parseInt(sizeNumber.group(1));
            return HouseSize.of(size, size, buildingType);
        }

        if (normalized.matches(".*\\b(huge|giant|very big|very large)\\b.*")) {
            return HouseSize.of(9, 9, buildingType);
        }
        if (normalized.matches(".*\\b(big|large)\\b.*")) {
            return HouseSize.of(7, 7, buildingType);
        }
        if (normalized.matches(".*\\b(medium|normal)\\b.*")) {
            return HouseSize.of(5, 5, buildingType);
        }

        return HouseSize.defaultFor(buildingType);
    }

    private static void buildSimpleHouseAtBot(Block wallBlock, HouseSize houseSize, BuildingType buildingType) {
        ServerPlayer bot = botSource.getPlayer();
        BlockPos origin = bot.blockPosition();
        Direction doorwayDirection = bot.getDirection();
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(wallBlock);
        List<BlockPos> blueprint = buildManualStructureBlueprint(origin, doorwayDirection, houseSize, buildingType);

        AutoFaceEntity.setBotExecutingTask(true);
        ChatUtils.sendChatMessages(botSource, "Building a " + houseSize.width() + "x" + houseSize.depth()
                + " " + buildingType.displayName() + " using " + blockId + ".");
        executor.submit(() -> {
            int placed = 0;
            try {
                for (BlockPos pos : blueprint) {
                    String placementResult = placeManualHouseBlock(bot, pos, wallBlock).get(10, TimeUnit.SECONDS);
                    if (placementResult.startsWith("❌") || placementResult.startsWith("⚠️")) {
                        String failure = "I had to stop building the " + buildingType.displayName() + ": " + placementResult;
                        getFunctionOutput(failure);
                        ChatUtils.sendChatMessages(botSource, failure);
                        storeActionMemory("buildStructure", Map.of(
                                "type", buildingType.displayName(),
                                "blockType", blockId.toString(),
                                "x", String.valueOf(origin.getX()),
                                "y", String.valueOf(origin.getY()),
                                "z", String.valueOf(origin.getZ())
                        ), failure);
                        return;
                    }
                    placed++;
                    Thread.sleep(150L);
                }

                String result = "Manually built a " + houseSize.width() + "x" + houseSize.depth()
                        + " " + buildingType.displayName() + " using " + blockId + " at x:" + origin.getX()
                        + " y:" + origin.getY() + " z:" + origin.getZ() + ".";
                getFunctionOutput(result);
                storeActionMemory("buildStructure", Map.of(
                        "type", buildingType.displayName(),
                        "blockType", blockId.toString(),
                        "x", String.valueOf(origin.getX()),
                        "y", String.valueOf(origin.getY()),
                        "z", String.valueOf(origin.getZ()),
                        "width", String.valueOf(houseSize.width()),
                        "depth", String.valueOf(houseSize.depth()),
                        "placedBlocks", String.valueOf(placed)
                ), result);
                ChatUtils.sendChatMessages(botSource, result);
            } catch (Exception e) {
                logger.error("Failed to build {}", buildingType.displayName(), e);
                ChatUtils.sendChatMessages(botSource, "I couldn't build the " + buildingType.displayName() + ": " + e.getMessage());
            } finally {
                AutoFaceEntity.setBotExecutingTask(false);
            }
        });
    }

    private static CompletableFuture<String> placeManualHouseBlock(ServerPlayer bot, BlockPos pos, Block wallBlock) {
        CompletableFuture<String> future = new CompletableFuture<>();
        MinecraftServer server = ((ServerLevel) bot.level()).getServer();
        server.execute(() -> {
            try {
                ServerLevel world = (ServerLevel) bot.level();
                BlockState targetState = world.getBlockState(pos);
                if (!targetState.isAir() && !targetState.canBeReplaced()) {
                    future.complete("❌ Target position is already occupied by: " + targetState.getBlock().getName().getString());
                    return;
                }

                Item wallItem = Item.byBlock(wallBlock);
                if (wallItem == Items.AIR || !consumeInventoryItem(bot.getInventory(), wallItem, 1)) {
                    future.complete("❌ Block not found in inventory: " + BuiltInRegistries.BLOCK.getKey(wallBlock));
                    return;
                }

                world.setBlock(pos, wallBlock.defaultBlockState(), 3);
                if (world.getBlockState(pos).getBlock() == wallBlock) {
                    future.complete("✅ Placed " + BuiltInRegistries.BLOCK.getKey(wallBlock) + " at x:"
                            + pos.getX() + " y:" + pos.getY() + " z:" + pos.getZ());
                } else {
                    future.complete("⚠️ Block was consumed but did not appear at target position");
                }
            } catch (Exception e) {
                future.complete("❌ Failed to place block: " + e.getMessage());
            }
        });
        return future;
    }

    private static boolean consumeInventoryItem(Inventory inventory, Item item, int count) {
        int remaining = count;
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.getItem() != item) {
                continue;
            }

            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            if (stack.isEmpty()) {
                inventory.setItem(slot, ItemStack.EMPTY);
            }
            remaining -= removed;
        }
        inventory.setChanged();
        return remaining == 0;
    }

    private static List<BlockPos> buildManualStructureBlueprint(BlockPos origin, Direction doorwayDirection, HouseSize houseSize, BuildingType buildingType) {
        return switch (buildingType) {
            case CASTLE -> buildCastleBlueprint(origin, doorwayDirection, houseSize);
            case SKYSCRAPER -> buildSkyscraperBlueprint(origin, doorwayDirection, houseSize);
            case TOWER -> buildTowerBlueprint(origin, doorwayDirection, houseSize);
            case BUILDING -> buildRectangularBuildingBlueprint(origin, doorwayDirection, houseSize);
            case HOUSE -> buildHouseBlueprint(origin, doorwayDirection, houseSize);
        };
    }

    private static List<BlockPos> buildHouseBlueprint(BlockPos origin, Direction doorwayDirection, HouseSize houseSize) {
        return buildRectangularShellBlueprint(origin, doorwayDirection, houseSize, true, false);
    }

    private static List<BlockPos> buildRectangularBuildingBlueprint(BlockPos origin, Direction doorwayDirection, HouseSize houseSize) {
        return buildRectangularShellBlueprint(origin, doorwayDirection, houseSize.withHeight(Math.max(3, houseSize.wallHeight() + 1)), true, true);
    }

    private static List<BlockPos> buildRectangularShellBlueprint(BlockPos origin, Direction doorwayDirection, HouseSize houseSize, boolean roof, boolean floors) {
        List<BlockPos> blocks = new ArrayList<>();
        int minX = -houseSize.width() / 2;
        int maxX = minX + houseSize.width() - 1;
        int minZ = -houseSize.depth() / 2;
        int maxZ = minZ + houseSize.depth() - 1;
        int wallTopY = houseSize.wallHeight() - 1;
        int roofY = houseSize.wallHeight();
        BlockPos doorway = switch (doorwayDirection) {
            case NORTH -> origin.offset(0, 0, minZ);
            case SOUTH -> origin.offset(0, 0, maxZ);
            case EAST -> origin.offset(maxX, 0, 0);
            case WEST -> origin.offset(minX, 0, 0);
            default -> origin.offset(0, 0, minZ);
        };

        for (int dy = 0; dy <= wallTopY; dy++) {
            for (int dx = minX; dx <= maxX; dx++) {
                for (int dz = minZ; dz <= maxZ; dz++) {
                    boolean perimeter = dx == minX || dx == maxX || dz == minZ || dz == maxZ;
                    if (perimeter) {
                        addHouseBlock(blocks, origin.offset(dx, dy, dz), doorway, doorway.above());
                    } else if (floors && dy > 0 && dy % 3 == 0) {
                        blocks.add(origin.offset(dx, dy, dz));
                    }
                }
            }
        }

        if (roof) {
            for (int dx = minX; dx <= maxX; dx++) {
                for (int dz = minZ; dz <= maxZ; dz++) {
                    blocks.add(origin.offset(dx, roofY, dz));
                }
            }
        }

        return blocks;
    }

    private static List<BlockPos> buildCastleBlueprint(BlockPos origin, Direction doorwayDirection, HouseSize houseSize) {
        List<BlockPos> blocks = buildRectangularShellBlueprint(origin, doorwayDirection, houseSize.withHeight(3), false, false);
        int minX = -houseSize.width() / 2;
        int maxX = minX + houseSize.width() - 1;
        int minZ = -houseSize.depth() / 2;
        int maxZ = minZ + houseSize.depth() - 1;
        List<BlockPos> corners = List.of(
                origin.offset(minX, 0, minZ),
                origin.offset(minX, 0, maxZ),
                origin.offset(maxX, 0, minZ),
                origin.offset(maxX, 0, maxZ)
        );

        for (BlockPos corner : corners) {
            for (int dy = 0; dy <= houseSize.wallHeight() + 2; dy++) {
                blocks.add(corner.above(dy));
            }
        }

        int battlementY = houseSize.wallHeight();
        for (int dx = minX; dx <= maxX; dx += 2) {
            blocks.add(origin.offset(dx, battlementY, minZ));
            blocks.add(origin.offset(dx, battlementY, maxZ));
        }
        for (int dz = minZ; dz <= maxZ; dz += 2) {
            blocks.add(origin.offset(minX, battlementY, dz));
            blocks.add(origin.offset(maxX, battlementY, dz));
        }

        return uniqueBlockList(blocks);
    }

    private static List<BlockPos> buildTowerBlueprint(BlockPos origin, Direction doorwayDirection, HouseSize houseSize) {
        HouseSize towerSize = HouseSize.of(Math.min(houseSize.width(), 5), Math.min(houseSize.depth(), 5), BuildingType.TOWER);
        return buildRectangularShellBlueprint(origin, doorwayDirection, towerSize.withHeight(Math.max(6, houseSize.wallHeight())), true, true);
    }

    private static List<BlockPos> buildSkyscraperBlueprint(BlockPos origin, Direction doorwayDirection, HouseSize houseSize) {
        HouseSize skyscraperSize = houseSize.withHeight(Math.max(8, houseSize.wallHeight()));
        return buildRectangularShellBlueprint(origin, doorwayDirection, skyscraperSize, true, true);
    }

    private static List<BlockPos> uniqueBlockList(List<BlockPos> blocks) {
        return new ArrayList<>(new LinkedHashSet<>(blocks));
    }

    private static void addHouseBlock(List<BlockPos> blocks, BlockPos pos, BlockPos... excludedDoorwayPositions) {
        for (BlockPos excludedDoorwayPos : excludedDoorwayPositions) {
            if (pos.equals(excludedDoorwayPos)) {
                return;
            }
        }
        blocks.add(pos);
    }

    private enum BuildingType {
        HOUSE("house"),
        BUILDING("building"),
        CASTLE("castle"),
        SKYSCRAPER("skyscraper"),
        TOWER("tower");

        private final String displayName;

        BuildingType(String displayName) {
            this.displayName = displayName;
        }

        private String displayName() {
            return displayName;
        }
    }

    private record HouseSize(int width, int depth, int wallHeight) {
        private static HouseSize small() {
            return new HouseSize(3, 3, 2);
        }

        private static HouseSize defaultFor(BuildingType buildingType) {
            return switch (buildingType) {
                case CASTLE -> of(7, 7, buildingType);
                case SKYSCRAPER -> of(5, 5, buildingType);
                case TOWER -> of(5, 5, buildingType);
                case BUILDING -> of(5, 5, buildingType);
                case HOUSE -> small();
            };
        }

        private static HouseSize of(int requestedWidth, int requestedDepth, BuildingType buildingType) {
            int width = clampHouseDimension(requestedWidth);
            int depth = clampHouseDimension(requestedDepth);
            int wallHeight = switch (buildingType) {
                case SKYSCRAPER -> Math.max(8, Math.min(14, Math.max(width, depth) + 3));
                case TOWER -> Math.max(6, Math.min(12, Math.max(width, depth) + 2));
                case CASTLE -> Math.max(3, Math.min(6, Math.max(width, depth) / 2));
                case BUILDING -> Math.max(3, Math.min(8, Math.max(width, depth)));
                case HOUSE -> Math.max(2, Math.min(4, Math.max(width, depth) / 2));
            };
            return new HouseSize(width, depth, wallHeight);
        }

        private HouseSize withHeight(int newWallHeight) {
            return new HouseSize(width, depth, Math.max(2, Math.min(16, newWallHeight)));
        }

        private static int clampHouseDimension(int requested) {
            return Math.max(3, Math.min(32, requested));
        }
    }

    private static void craftItem(String itemName, int batches) {
        MinecraftServer server = botSource.getServer();
        server.execute(() -> {
            try {
                String result = craftItemOnServerThread(botSource.getPlayer(), itemName, batches);
                getFunctionOutput(result);
                storeActionMemory("craftItem", Map.of(
                        "itemName", itemName,
                        "quantity", String.valueOf(batches)
                ), result);
                ChatUtils.sendChatMessages(botSource, result);
            } catch (Exception e) {
                logger.error("Failed to craft item {}", itemName, e);
                ChatUtils.sendChatMessages(botSource, "I couldn't craft that: " + e.getMessage());
            }
        });
    }

    private static String craftItemOnServerThread(ServerPlayer bot, String itemName, int batches) {
        Optional<Item> outputItem = resolveCraftOutputItem(itemName);
        if (outputItem.isEmpty()) {
            return "I don't know how to craft '" + itemName + "' yet.";
        }

        CraftRecipe recipe = findCraftRecipe(outputItem.get());
        if (recipe == null) {
            return "I know that item, but I don't have a crafting recipe for " + itemId(outputItem.get()) + " yet.";
        }

        int safeBatches = Math.max(1, Math.min(64, batches));
        if (!hasIngredients(bot.getInventory(), recipe.ingredients(), safeBatches)) {
            return "I don't have the ingredients to craft " + itemId(recipe.output()) + ". Need: "
                    + describeIngredients(recipe.ingredients(), safeBatches) + ".";
        }

        consumeIngredients(bot.getInventory(), recipe.ingredients(), safeBatches);
        ItemStack result = new ItemStack(recipe.output(), recipe.outputCount() * safeBatches);
        boolean added = bot.getInventory().add(result);
        bot.getInventory().setChanged();

        if (!added && !result.isEmpty()) {
            bot.drop(result, false);
            return "Crafted " + recipe.outputCount() * safeBatches + " " + itemId(recipe.output())
                    + ", but my inventory was full so I dropped it.";
        }

        return "Crafted " + recipe.outputCount() * safeBatches + " " + itemId(recipe.output()) + ".";
    }

    private static Optional<Item> resolveCraftOutputItem(String itemName) {
        String cleaned = cleanupCraftItemName(itemName);
        if (cleaned.isBlank()) {
            return Optional.empty();
        }

        String normalized = normalizeCraftAlias(cleaned);
        Identifier directId = Identifier.tryParse(normalized.contains(":") ? normalized : "minecraft:" + normalized);
        if (directId != null) {
            Optional<Item> direct = BuiltInRegistries.ITEM.getOptional(directId);
            if (direct.isPresent() && direct.get() != Items.AIR) {
                return direct;
            }
        }

        Item bestMatch = null;
        int bestScore = Integer.MIN_VALUE;
        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            String path = id.getPath();
            int score = getItemMatchScore(normalized, path);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            }
        }

        return bestScore > 0 && bestMatch != null && bestMatch != Items.AIR
                ? Optional.of(bestMatch)
                : Optional.empty();
    }

    private static String normalizeCraftAlias(String itemName) {
        String singular = singularizeCraftItemName(itemName);
        return switch (singular) {
            case "wood", "wood_planks", "plank" -> "oak_planks";
            case "table", "workbench", "crafting" -> "crafting_table";
            case "torchlight" -> "torch";
            case "pick", "pickaxe" -> "wooden_pickaxe";
            case "axe" -> "wooden_axe";
            case "shovel" -> "wooden_shovel";
            case "hoe" -> "wooden_hoe";
            case "sword" -> "wooden_sword";
            default -> singular;
        };
    }

    private static String singularizeCraftItemName(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return "";
        }

        if (itemName.endsWith("ches") || itemName.endsWith("shes") || itemName.endsWith("xes")) {
            return itemName.substring(0, itemName.length() - 2);
        }
        if (itemName.endsWith("ies") && itemName.length() > 3) {
            return itemName.substring(0, itemName.length() - 3) + "y";
        }
        if (itemName.endsWith("s") && !itemName.endsWith("ss") && itemName.length() > 1) {
            return itemName.substring(0, itemName.length() - 1);
        }
        return itemName;
    }

    private static int getItemMatchScore(String input, String target) {
        int score = 0;
        if (target.equals(input)) score += 1000;
        else if (target.startsWith(input)) score += 500;
        else if (target.contains(input)) score += 100;
        score -= target.length();
        return score;
    }

    private static CraftRecipe findCraftRecipe(Item output) {
        String outputPath = itemId(output).getPath();

        if (outputPath.endsWith("_planks")) {
            String wood = outputPath.substring(0, outputPath.length() - "_planks".length());
            return new CraftRecipe(output, 4, List.of(new CraftIngredient(wood + " log/wood", 1, item -> {
                String path = itemId(item).getPath();
                return path.equals(wood + "_log")
                        || path.equals("stripped_" + wood + "_log")
                        || path.equals(wood + "_wood")
                        || path.equals("stripped_" + wood + "_wood")
                        || path.equals(wood + "_stem")
                        || path.equals("stripped_" + wood + "_stem")
                        || path.equals(wood + "_hyphae")
                        || path.equals("stripped_" + wood + "_hyphae");
            })));
        }

        Map<Item, CraftRecipe> recipes = new HashMap<>();
        recipes.put(Items.STICK, new CraftRecipe(Items.STICK, 4, List.of(planks(2))));
        recipes.put(Items.CRAFTING_TABLE, new CraftRecipe(Items.CRAFTING_TABLE, 1, List.of(planks(4))));
        recipes.put(Items.CHEST, new CraftRecipe(Items.CHEST, 1, List.of(planks(8))));
        recipes.put(Items.FURNACE, new CraftRecipe(Items.FURNACE, 1, List.of(exact(Items.COBBLESTONE, 8))));
        recipes.put(Items.TORCH, new CraftRecipe(Items.TORCH, 4, List.of(coalLike(1), exact(Items.STICK, 1))));
        recipes.put(Items.BREAD, new CraftRecipe(Items.BREAD, 1, List.of(exact(Items.WHEAT, 3))));

        addToolRecipes(recipes, "wooden", planks(0));
        addToolRecipes(recipes, "stone", stoneToolMaterial(0));

        return recipes.get(output);
    }

    private static void addToolRecipes(Map<Item, CraftRecipe> recipes, String material, CraftIngredient baseMaterial) {
        Item pickaxe = itemByPath(material + "_pickaxe");
        Item axe = itemByPath(material + "_axe");
        Item shovel = itemByPath(material + "_shovel");
        Item hoe = itemByPath(material + "_hoe");
        Item sword = itemByPath(material + "_sword");

        recipes.put(pickaxe, new CraftRecipe(pickaxe, 1, List.of(baseMaterial.withCount(3), exact(Items.STICK, 2))));
        recipes.put(axe, new CraftRecipe(axe, 1, List.of(baseMaterial.withCount(3), exact(Items.STICK, 2))));
        recipes.put(shovel, new CraftRecipe(shovel, 1, List.of(baseMaterial.withCount(1), exact(Items.STICK, 2))));
        recipes.put(hoe, new CraftRecipe(hoe, 1, List.of(baseMaterial.withCount(2), exact(Items.STICK, 2))));
        recipes.put(sword, new CraftRecipe(sword, 1, List.of(baseMaterial.withCount(2), exact(Items.STICK, 1))));
    }

    private static Item itemByPath(String path) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.tryParse("minecraft:" + path)).orElse(Items.AIR);
    }

    private static CraftIngredient exact(Item item, int count) {
        return new CraftIngredient(itemId(item).getPath(), count, candidate -> candidate == item);
    }

    private static CraftIngredient planks(int count) {
        return new CraftIngredient("any planks", count, item -> itemId(item).getPath().endsWith("_planks"));
    }

    private static CraftIngredient coalLike(int count) {
        return new CraftIngredient("coal or charcoal", count, item -> item == Items.COAL || item == Items.CHARCOAL);
    }

    private static CraftIngredient stoneToolMaterial(int count) {
        return new CraftIngredient("cobblestone or cobbled deepslate", count,
                item -> item == Items.COBBLESTONE || item == Items.COBBLED_DEEPSLATE);
    }

    private static boolean hasIngredients(Inventory inventory, List<CraftIngredient> ingredients, int batches) {
        for (CraftIngredient ingredient : ingredients) {
            if (countMatchingItems(inventory, ingredient) < ingredient.count() * batches) {
                return false;
            }
        }
        return true;
    }

    private static int countMatchingItems(Inventory inventory, CraftIngredient ingredient) {
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && ingredient.matches(stack.getItem())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void consumeIngredients(Inventory inventory, List<CraftIngredient> ingredients, int batches) {
        for (CraftIngredient ingredient : ingredients) {
            int remaining = ingredient.count() * batches;
            for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty() || !ingredient.matches(stack.getItem())) {
                    continue;
                }

                int removed = Math.min(remaining, stack.getCount());
                stack.shrink(removed);
                if (stack.isEmpty()) {
                    inventory.setItem(i, ItemStack.EMPTY);
                }
                remaining -= removed;
            }
        }
    }

    private static String describeIngredients(List<CraftIngredient> ingredients, int batches) {
        List<String> parts = new ArrayList<>();
        for (CraftIngredient ingredient : ingredients) {
            parts.add((ingredient.count() * batches) + " " + ingredient.label());
        }
        return String.join(", ", parts);
    }

    private static Identifier itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    private record CraftRecipe(Item output, int outputCount, List<CraftIngredient> ingredients) {}

    private record CraftIngredient(String label, int count, java.util.function.Predicate<Item> matcher) {
        private boolean matches(Item item) {
            return matcher.test(item);
        }

        private CraftIngredient withCount(int newCount) {
            return new CraftIngredient(label, newCount, matcher);
        }
    }

    private static void dropItem(String itemName, int quantity) {
        MinecraftServer server = botSource.getServer();
        server.execute(() -> {
            try {
                String result = dropItemOnServerThread(botSource.getPlayer(), itemName, quantity);
                getFunctionOutput(result);
                storeActionMemory("dropItem", Map.of(
                        "itemName", itemName,
                        "quantity", quantity < 0 ? "all" : String.valueOf(quantity)
                ), result);
                ChatUtils.sendChatMessages(botSource, result);
            } catch (Exception e) {
                logger.error("Failed to drop item {}", itemName, e);
                ChatUtils.sendChatMessages(botSource, "I couldn't drop that: " + e.getMessage());
            }
        });
    }

    private static String dropItemOnServerThread(ServerPlayer bot, String itemName, int quantity) {
        Optional<Item> requestedItem = resolveCraftOutputItem(itemName);
        if (requestedItem.isEmpty()) {
            return "I don't recognize the item '" + itemName + "'.";
        }

        Inventory inventory = bot.getInventory();
        Item item = requestedItem.get();
        int toDrop = quantity < 0 ? Integer.MAX_VALUE : Math.max(1, quantity);
        int dropped = 0;

        for (int slot = 0; slot < inventory.getContainerSize() && dropped < toDrop; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.getItem() != item) {
                continue;
            }

            int amount = Math.min(stack.getCount(), toDrop - dropped);
            ItemStack droppedStack = stack.split(amount);
            if (stack.isEmpty()) {
                inventory.setItem(slot, ItemStack.EMPTY);
            }
            bot.drop(droppedStack, false);
            dropped += amount;
        }

        inventory.setChanged();
        if (dropped == 0) {
            return "I don't have any " + itemId(item) + " to drop.";
        }

        return "Dropped " + dropped + " " + itemId(item) + ".";
    }

    private static void storeActionMemory(String functionName, Map<String, String> params, String result) {
        try {
            String botName = botSource != null ? botSource.getTextName() : "unknown";
            String prompt = "Bot " + botName + " executed " + functionName + " with " + params;
            SQLiteDB.storeMemory("action", prompt, result == null ? "" : result);
            SQLiteDB.storeMemory("function_call", prompt, result == null ? "" : result);
            logger.info("Stored action memory: {}", prompt);
        } catch (Exception e) {
            logger.warn("Could not store action memory for {}: {}", functionName, e.getMessage());
        }
    }

    private static void processLLMOutput(String fullResponse, String botName, CommandSourceStack botSource) {
        logger.info("processLLMOutput called with response: '{}', botName: '{}'", fullResponse, botName);
        if (fullResponse == null || fullResponse.trim().isEmpty()) {
            logger.warn("fullResponse is null or empty");
            return;
        }
        Matcher matcher = THINK_BLOCK.matcher(fullResponse);
        if (matcher.find()) {
            logger.info("Think block found");
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
                logger.info("Sending remainder: '{}'", remainder);
                ChatUtils.sendChatMessages(botSource, botName + ": " + remainder);
            } else {
                logger.info("Remainder is empty");
            }
        } else {
            logger.info("No think block found, sending full response: '{}'", fullResponse);
            ChatUtils.sendChatMessages(botSource, fullResponse);
        }
    }

    private static void sendMessageToPlayer(String message) {
        processLLMOutput(message, botSource.getTextName(), botSource);
    }

    private static void runPipelineLoop(JsonArray pipeline) {
        List<JsonObject> steps = new ArrayList<>();
        List<String> executedSteps = new ArrayList<>();
        for (JsonElement step : pipeline) {
            steps.add(step.getAsJsonObject());
        }

        Deque<JsonObject> pipelineStack = new ArrayDeque<>(steps); // Keep FIFO order
        logger.info("Pipeline stack: {}", pipelineStack);
        OllamaChatRequestBuilder builder = OllamaChatRequestBuilder.getInstance(selectedLM);
        String systemPrompt = FunctionCallerV2.buildPrompt(toolBuilder());
        final int maxRetries = 3;
        int retryCount = 0;

        while (!pipelineStack.isEmpty()) {
            JsonObject step = pipelineStack.pop();
            String functionName = step.get("functionName").getAsString();
            Map<String, String> paramMap = parseParameterMap(step.get("parameters"), sharedState);

            boolean hasUnresolved = paramMap.values().stream().anyMatch(v -> v.equals("__UNRESOLVED__"));
            if (hasUnresolved) {
                logger.warn("⚠️ One or more parameters in step '{}' are unresolved. Triggering LLM fallback.", functionName);
                if (retryCount >= maxRetries) {
                    logger.error("❌ Max LLM fallback retries reached due to unresolved parameters. Aborting.");
                    break;
                }
                String newPrompt = "The following steps in the pipeline were successfully executed:\n"
                        + String.join("\n", executedSteps)
                        + "\n\nExecution failed at step: " + functionName
                        + "\nCause: One or more placeholders could not be resolved from shared state.";
                try {
                    State initialState = BotEventHandler.createInitialState(botSource.getPlayer());
                    InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1);
                    map.updateMap();
                    // If method returns Map<String, String>
                    Map<String, String> surroundingsStr = map.summarizeSurroundings();
                    Map<String, Object> surroundings = new HashMap<>();
                    surroundings.putAll(surroundingsStr);

                    String botContext = buildLLMBotContext(initialState, sharedState, surroundings);
                    String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;

                    List<OllamaChatMessage> messages = new java.util.ArrayList<>();
                    messages.add(new OllamaChatMessage(OllamaChatMessageRole.SYSTEM, fullSystemPrompt));
                    messages.add(new OllamaChatMessage(OllamaChatMessageRole.USER, newPrompt));

                    net.shasankp000.OllamaClient.OllamaThinkingResponse thinkingResponse =
                            net.shasankp000.OllamaClient.OllamaAPIHelper.smartChat(
                                    ollamaAPI,
                                    "http://localhost:11434",
                                    net.shasankp000.AIPlayer.CONFIG.getSelectedLanguageModel(),
                                    messages
                            );

                    String llmResponse = thinkingResponse.getContent();
                    logger.info("Raw LLM response: {}", llmResponse);
                    String cleanedResponse = stripThinkBlock(llmResponse);
                    String jsonPart = extractJson(cleanedResponse);
                    logger.info("Extracted JSON: {}", jsonPart);
                    JsonObject llmResponseObj = JsonParser.parseString(jsonPart).getAsJsonObject();

                    if (llmResponseObj.has("pipeline")) {
                        logger.info("LLM provided NEW pipeline. Rebuilding stack.");
                        retryCount = 0;
                        JsonArray newPipeline = llmResponseObj.getAsJsonArray("pipeline");
                        pipelineStack.clear();
                        List<JsonObject> newSteps = new ArrayList<>();
                        for (JsonElement e : newPipeline) {
                            newSteps.add(e.getAsJsonObject());
                        }
                        pipelineStack.addAll(newSteps);
                        continue;
                    } else if (llmResponseObj.has("clarification")) {
                        logger.info("LLM requested clarification. Relaying to player.");
                        String clarification = llmResponseObj.get("clarification").getAsString();
                        ChatContextManager.setPendingClarification(playerUUID, "A recent action failed.", clarification, botSource.getTextName());
                        sendMessageToPlayer(clarification);
                        break;
                    } else {
                        logger.warn("LLM did not return a pipeline or clarification. Exiting.");
                        break;
                    }
                } catch (Exception e) {
                    logger.error("❌ Error in LLM fallback after unresolved parameters: {}", e.getMessage(), e);
                    retryCount++;
                    continue;
                }
            }

            logger.info("Running function: " + functionName + " with " + paramMap);
            callFunction(functionName, paramMap, sharedState).join(); // Sync call
            logger.info("Function output: {}", functionOutput);
            parseOutputValues(functionName, functionOutput);

            // Short wait for state to settle (e.g., for movement tools)
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                logger.warn("Interrupted during state settle wait");
            }

            // Get bot entity
            ServerPlayer bot = botSource.getPlayer();
            if (bot == null) {
                logger.error("Bot entity not found for verification");
                continue;
            }

            // Use state-based verifier
            ToolVerifiers.StateVerifier verifier = ToolVerifiers.VERIFIER_REGISTRY.get(functionName);
            ToolVerifiers.VerificationResult result = (verifier == null)
                    ? new ToolVerifiers.VerificationResult(true, null)
                    : verifier.verify(paramMap, sharedState, bot);

            if (result.success) {
                logger.info("✅ Verifier passed for {} with data: {}", functionName, result.data);
                executedSteps.add(functionName + ", Output: " + functionOutput);
            } else {
                logger.warn("❌ Verifier failed for {} with data: {}", functionName, result.data);
                if (retryCount >= maxRetries) {
                    logger.error("❌ Max LLM fallback retries reached due to verifier failures. Aborting.");
                    break;
                }
                String newPrompt = "The following steps were executed successfully:\n"
                        + String.join("\n", executedSteps)
                        + "\n\nExecution failed at step: " + functionName
                        + "\nFunction output: " + functionOutput
                        + "\nVerification details: " + result.data;
                try {
                    State initialState = BotEventHandler.createInitialState(botSource.getPlayer());
                    InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1);
                    map.updateMap();
                    // If method returns Map<String, String>
                    Map<String, String> surroundingsStr = map.summarizeSurroundings();
                    Map<String, Object> surroundings = new HashMap<>();
                    surroundings.putAll(surroundingsStr);

                    String botContext = buildLLMBotContext(initialState, sharedState, surroundings);
                    String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;
                    OllamaChatRequestModel requestModel = builder
                            .withMessage(OllamaChatMessageRole.SYSTEM, fullSystemPrompt)
                            .withMessage(OllamaChatMessageRole.USER, newPrompt)
                            .build();
                    OllamaChatResult llmResult = ollamaAPI.chat(requestModel);
                    String llmResponse = llmResult.getResponse();
                    logger.info("Raw LLM response: {}", llmResponse);
                    String cleanedResponse = stripThinkBlock(llmResponse);
                    String jsonPart = extractJson(cleanedResponse);
                    logger.info("Extracted JSON: {}", jsonPart);
                    JsonObject llmResponseObj = JsonParser.parseString(jsonPart).getAsJsonObject();

                    if (llmResponseObj.has("pipeline")) {
                        logger.info("LLM provided NEW pipeline. Rebuilding stack.");
                        retryCount = 0;
                        JsonArray newPipeline = llmResponseObj.getAsJsonArray("pipeline");
                        pipelineStack.clear();
                        List<JsonObject> newSteps = new ArrayList<>();
                        for (JsonElement e : newPipeline) {
                            newSteps.add(e.getAsJsonObject());
                        }
                        pipelineStack.addAll(newSteps);
                    } else if (llmResponseObj.has("clarification")) {
                        logger.info("LLM requested clarification. Relaying to player.");
                        String clarification = llmResponseObj.get("clarification").getAsString();
                        ChatContextManager.setPendingClarification(playerUUID, "A recent action failed.", clarification, botSource.getTextName());
                        sendMessageToPlayer(clarification);
                        break;
                    } else {
                        logger.warn("LLM did not return a pipeline or clarification. Exiting.");
                        break;
                    }
                } catch (Exception e) {
                    logger.error("❌ Error in LLM fallback after verifier failure: {}", e.getMessage(), e);
                    retryCount++;
                }
            }
        }
        blockDetectionUnit.setIsBlockDetectionActive(false);
        PathTracer.flushAllMovementTasks();
        AutoFaceEntity.setBotExecutingTask(false);
        AutoFaceEntity.isBotMoving = false;
        logger.info("✔️ Autoface module has been reset.");
    }

    // overloaded method to handle the other LLM providers
    private static void runPipelineLoop(JsonArray pipeline, LLMClient client) {
        List<JsonObject> steps = new ArrayList<>();
        List<String> executedSteps = new ArrayList<>();
        for (JsonElement step : pipeline) {
            steps.add(step.getAsJsonObject());
        }

        Deque<JsonObject> pipelineStack = new ArrayDeque<>(steps);
        logger.info("Pipeline stack: {}", pipelineStack);
        String systemPrompt = FunctionCallerV2.buildPrompt(toolBuilder());
        final int maxRetries = 3;
        int retryCount = 0;

        while (!pipelineStack.isEmpty()) {
            JsonObject step = pipelineStack.pop();
            String functionName = step.get("functionName").getAsString();
            Map<String, String> paramMap = parseParameterMap(step.get("parameters"), sharedState);

            boolean hasUnresolved = paramMap.values().stream().anyMatch(v -> v.equals("__UNRESOLVED__"));
            if (hasUnresolved) {
                logger.warn("⚠️ One or more parameters in step '{}' are unresolved. Triggering LLM fallback.", functionName);
                if (retryCount >= maxRetries) {
                    logger.error("❌ Max LLM fallback retries reached due to unresolved parameters. Aborting.");
                    break;
                }
                String newPrompt = "The following steps in the pipeline were successfully executed:\n"
                        + String.join("\n", executedSteps)
                        + "\n\nExecution failed at step: " + functionName
                        + "\nCause: One or more placeholders could not be resolved from shared state.";
                try {
                    State initialState = BotEventHandler.createInitialState(botSource.getPlayer());
                    InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1);
                    map.updateMap();
                    // If method returns Map<String, String>
                    Map<String, String> surroundingsStr = map.summarizeSurroundings();
                    Map<String, Object> surroundings = new HashMap<>();
                    surroundings.putAll(surroundingsStr);

                    String botContext = buildLLMBotContext(initialState, sharedState, surroundings);
                    String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;
                    String llmResponse = client.sendPrompt(fullSystemPrompt, newPrompt);
                    logger.info("Raw LLM response: {}", llmResponse);
                    String cleanedResponse = stripThinkBlock(llmResponse);
                    String jsonPart = extractJson(cleanedResponse);
                    logger.info("Extracted JSON: {}", jsonPart);
                    JsonObject llmResponseObj = JsonParser.parseString(jsonPart).getAsJsonObject();

                    if (llmResponseObj.has("pipeline")) {
                        logger.info("LLM provided NEW pipeline. Rebuilding stack.");
                        retryCount = 0;
                        JsonArray newPipeline = llmResponseObj.getAsJsonArray("pipeline");
                        pipelineStack.clear();
                        List<JsonObject> newSteps = new ArrayList<>();
                        for (JsonElement e : newPipeline) {
                            newSteps.add(e.getAsJsonObject());
                        }
                        pipelineStack.addAll(newSteps);
                        continue;
                    } else if (llmResponseObj.has("clarification")) {
                        logger.info("LLM requested clarification. Relaying to player.");
                        String clarification = llmResponseObj.get("clarification").getAsString();
                        ChatContextManager.setPendingClarification(playerUUID, "A recent action failed.", clarification, botSource.getTextName());
                        sendMessageToPlayer(clarification);
                        break;
                    } else {
                        logger.warn("LLM did not return a pipeline or clarification. Exiting.");
                        break;
                    }
                } catch (Exception e) {
                    logger.error("❌ Error in LLM fallback after unresolved parameters: {}", e.getMessage());
                    retryCount++;
                    continue;
                }
            }

            logger.info("Running function: " + functionName + " with " + paramMap);
            callFunction(functionName, paramMap, sharedState).join();
            logger.info("Function output: {}", functionOutput);
            parseOutputValues(functionName, functionOutput);

            // Short wait for state to settle (e.g., for movement tools)
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                logger.warn("Interrupted during state settle wait");
            }

            // Get bot entity
            ServerPlayer bot = botSource.getPlayer();
            if (bot == null) {
                logger.error("Bot entity not found for verification");
                continue;
            }

            // Use state-based verifier
            ToolVerifiers.StateVerifier verifier = ToolVerifiers.VERIFIER_REGISTRY.get(functionName);
            ToolVerifiers.VerificationResult result = (verifier == null)
                    ? new ToolVerifiers.VerificationResult(true, null)
                    : verifier.verify(paramMap, sharedState, bot);

            if (result.success) {
                logger.info("✅ Verifier passed for {} with data: {}", functionName, result.data);
                executedSteps.add(functionName + ", Output: " + functionOutput);
            } else {
                logger.warn("❌ Verifier failed for {} with data: {}", functionName, result.data);
                if (retryCount >= maxRetries) {
                    logger.error("❌ Max LLM fallback retries reached due to verifier failures. Aborting.");
                    break;
                }
                String newPrompt = "The following steps were executed successfully:\n"
                        + String.join("\n", executedSteps)
                        + "\n\nExecution failed at step: " + functionName
                        + "\nFunction output: " + functionOutput
                        + "\nVerification details: " + result.data;
                try {
                    State initialState = BotEventHandler.createInitialState(botSource.getPlayer());
                    InternalMap map = new InternalMap(botSource.getPlayer(), 1, 1);
                    map.updateMap();
                    // If method returns Map<String, String>
                    Map<String, String> surroundingsStr = map.summarizeSurroundings();
                    Map<String, Object> surroundings = new HashMap<>();
                    surroundings.putAll(surroundingsStr);

                    String botContext = buildLLMBotContext(initialState, sharedState, surroundings);
                    String fullSystemPrompt = systemPrompt + "\n\nBot's context information:\n" + botContext;
                    String llmResponse = client.sendPrompt(fullSystemPrompt, newPrompt);
                    logger.info("Raw LLM response: {}", llmResponse);
                    String cleanedResponse = stripThinkBlock(llmResponse);
                    String jsonPart = extractJson(cleanedResponse);
                    logger.info("Extracted JSON: {}", jsonPart);
                    JsonObject llmResponseObj = JsonParser.parseString(jsonPart).getAsJsonObject();

                    if (llmResponseObj.has("pipeline")) {
                        logger.info("LLM provided NEW pipeline. Rebuilding stack.");
                        retryCount = 0;
                        JsonArray newPipeline = llmResponseObj.getAsJsonArray("pipeline");
                        pipelineStack.clear();
                        List<JsonObject> newSteps = new ArrayList<>();
                        for (JsonElement e : newPipeline) {
                            newSteps.add(e.getAsJsonObject());
                        }
                        pipelineStack.addAll(newSteps);
                    } else if (llmResponseObj.has("clarification")) {
                        logger.info("LLM requested clarification. Relaying to player.");
                        String clarification = llmResponseObj.get("clarification").getAsString();
                        ChatContextManager.setPendingClarification(playerUUID, "A recent action failed.", clarification, botSource.getTextName());
                        sendMessageToPlayer(clarification);
                        break;
                    } else {
                        logger.warn("LLM did not return a pipeline or clarification. Exiting.");
                        break;
                    }
                } catch (Exception e) {
                    logger.error("❌ Error in LLM fallback after verifier failure: {}", e.getMessage(), e);
                    retryCount++;
                }
            }
        }
        blockDetectionUnit.setIsBlockDetectionActive(false);
        PathTracer.flushAllMovementTasks();
        AutoFaceEntity.setBotExecutingTask(false);
        AutoFaceEntity.isBotMoving = false;
        logger.info("✔️ Autoface module has been reset.");
    }

    private static final Map<String, List<String>> functionStateKeyMap = Map.ofEntries(
            Map.entry("detectBlocks", List.of("lastDetectedBlock.x", "lastDetectedBlock.y", "lastDetectedBlock.z")),
            Map.entry("goTo", List.of("botPosition.x", "botPosition.y", "botPosition.z")),
            Map.entry("chartPathToBlock", List.of("finalBlockPos.x", "finalBlockPos.y", "finalBlockPos.z")),
            Map.entry("faceBlock", List.of("facing.yaw", "facing.pitch")),
            Map.entry("faceEntity", List.of("facing.entityName")),
            Map.entry("turn", List.of("facing.direction", "facing.facing", "facing.axis")),
            Map.entry("look", List.of("facing.direction", "facing.facing", "facing.axis")),
            Map.entry("mineBlock", List.of("lastMineStatus")),
            Map.entry("getOxygenLevel", List.of("oxygenLevel")),
            Map.entry("getHungerLevel", List.of("hungerLevel")),
            Map.entry("getHealthLevel", List.of("healthLevel"))
    );

    private static void parseOutputValues(String functionName, String output) {
        List<String> keys = functionStateKeyMap.get(functionName);
        if (keys == null || keys.isEmpty()) return;

        List<Object> values = new ArrayList<>();
        switch (functionName) {
            case "goTo" -> {
                Matcher matcher = Pattern.compile("x[:=]\\s*(-?\\d+)\\s*y[:=]\\s*(-?\\d+)\\s*z[:=]\\s*(-?\\d+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) {
                    values.add(Integer.parseInt(matcher.group(1)));
                    values.add(Integer.parseInt(matcher.group(2)));
                    values.add(Integer.parseInt(matcher.group(3)));
                }
            }
            case "detectBlocks" -> {
                Matcher matcher = Pattern.compile(".*found at (-?\\d+) (-?\\d+) (-?\\d+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) {
                    values.add(Integer.parseInt(matcher.group(1)));
                    values.add(Integer.parseInt(matcher.group(2)));
                    values.add(Integer.parseInt(matcher.group(3)));
                }
            }
            case "turn" -> {
                Matcher matcher = Pattern.compile("Now facing (\\w+) which is in (\\w+).*in (\\w+) axis", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) {
                    values.add(matcher.group(1));
                    values.add(matcher.group(2));
                    values.add(matcher.group(3));
                }
            }
            case "look" -> {
                Matcher matcher = Pattern.compile("Now facing cardinal direction (\\w+) which is in (\\w+).*in (\\w+) axis", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) {
                    values.add(matcher.group(1));
                    values.add(matcher.group(2));
                    values.add(matcher.group(3));
                }
            }
            case "mineBlock" -> {
                if (output.contains("Mining complete!")) {
                    values.add("success");
                } else if (output.contains("⚠️ Failed to mine block")) {
                    values.add("failed");
                }
            }
            case "getOxygenLevel" -> {
                Matcher matcher = Pattern.compile("Oxygen Level[:=]\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) values.add(Integer.parseInt(matcher.group(1)));
            }
            case "getHungerLevel" -> {
                Matcher matcher = Pattern.compile("Hunger Level[:=]\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) values.add(Integer.parseInt(matcher.group(1)));
            }
            case "getHealthLevel" -> {
                Matcher matcher = Pattern.compile("Remaining hearts[:=]\\s*([\\d.]+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) values.add(Double.parseDouble(matcher.group(1)));
            }
            case "faceBlock" -> {
                Matcher matcher = Pattern.compile("Yaw[:=]\\s*([\\d.-]+).*Pitch[:=]\\s*([\\d.-]+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) {
                    values.add(Double.parseDouble(matcher.group(1)));
                    values.add(Double.parseDouble(matcher.group(2)));
                }
            }
            case "faceEntity" -> {
                Matcher matcher = Pattern.compile("Facing entity[:=]\\s*(.+)", Pattern.CASE_INSENSITIVE).matcher(output);
                if (matcher.find()) values.add(matcher.group(1));
            }
            case "chartPathToBlock" -> {
                Matcher matcher = Pattern.compile("Bot is at (-?\\d+) (-?\\d+) (-?\\d+)").matcher(output);
                if (matcher.find()) {
                    values.add(Integer.parseInt(matcher.group(1)));
                    values.add(Integer.parseInt(matcher.group(2)));
                    values.add(Integer.parseInt(matcher.group(3)));
                }
            }
        }

        if (values.size() == keys.size()) {
            updateState(keys, values, sharedState);
        } else {
            logger.warn("❌ Mismatch in keys/values for {} → keys: {}, values: {}", functionName, keys, values);
        }
    }

    private static void getFunctionResultAndSave(String userInput, String executionDateTime) {
        try {
            // Generate context synchronously
            String eventContext = generatePromptContext(userInput);
            // Get embedding client
            net.shasankp000.ServiceLLMClients.EmbeddingClient embeddingClient =
                    net.shasankp000.FilingSystem.EmbeddingClientFactory.createClient();
            // Generate event embedding synchronously
            List<Double> eventEmbedding;
            List<Double> eventContextEmbedding;
            try {
                eventEmbedding = embeddingClient.generateEmbedding(userInput);
                // Generate event context embedding synchronously
                eventContextEmbedding = embeddingClient.generateEmbedding(eventContext);
            } catch (Exception e) {
                logger.error("Failed to generate embeddings", e);
                throw new RuntimeException(e);
            }
            // Wait until functionOutput is a valid string
            while (functionOutput == null || !(functionOutput instanceof String)) {
                try {
                    Thread.sleep(500L); // Check every 500ms
                } catch (InterruptedException e) {
                    logger.error("Couldn't get function call output");
                    throw new RuntimeException(e);
                }
            }
            System.out.println("Received output: " + functionOutput);
            // Generate result embedding based on the function output
            List<Double> resultEmbedding;
            try {
                resultEmbedding = embeddingClient.generateEmbedding(functionOutput);
            } catch (Exception e) {
                logger.error("Failed to generate result embedding", e);
                throw new RuntimeException(e);
            }
            // Create execution record and save it
            ExecutionRecord executionRecord = new ExecutionRecord(executionDateTime, userInput, eventContext, functionOutput, eventEmbedding, eventContextEmbedding, resultEmbedding);
            executionRecord.updateRecords();
            // Clear the functionOutput to reset state
            functionOutput = null;
            System.out.println("Event data saved successfully.");
        } catch (Exception e) {
            // Log or handle the exception
            logger.error("Error occurred while processing the function result: ", e);
            throw new RuntimeException(e);
        }
    }

    private static String resolvePlaceholder(String value, Map<String, Object> state) {
        if (value == null) return "0";
        if (value.startsWith("$")) {
            String key = value.substring(1);
            Object resolvedObj = SharedStateUtils.getValue(state, key);
            if (resolvedObj == null) {
                logger.warn("⚠️ Placeholder '{}' not found in sharedState. Using fallback value '0'", key);
                return "0";
            }
            String resolved = resolvedObj.toString();
            logger.debug("🔁 Resolved placeholder {} → {}", key, resolved);
            return resolved;
        }
        return value;
    }

    private static Map<String, String> parseParameterMap(JsonElement parametersElement, Map<String, Object> state) {
        Map<String, String> paramMap = new HashMap<>();
        if (parametersElement == null || parametersElement.isJsonNull()) {
            return paramMap;
        }

        if (parametersElement.isJsonArray()) {
            for (JsonElement parameter : parametersElement.getAsJsonArray()) {
                if (!parameter.isJsonObject()) {
                    logger.warn("Skipping non-object parameter entry: {}", parameter);
                    continue;
                }

                JsonObject paramObj = parameter.getAsJsonObject();
                JsonElement nameElement = paramObj.has("parameterName")
                        ? paramObj.get("parameterName")
                        : paramObj.get("name");
                JsonElement valueElement = paramObj.has("parameterValue")
                        ? paramObj.get("parameterValue")
                        : paramObj.get("value");

                if (nameElement == null || valueElement == null || nameElement.isJsonNull()) {
                    logger.warn("Skipping malformed parameter entry: {}", paramObj);
                    continue;
                }

                paramMap.put(nameElement.getAsString(), resolvePlaceholder(jsonValueAsString(valueElement), state));
            }
            return paramMap;
        }

        if (parametersElement.isJsonObject()) {
            JsonObject paramsObject = parametersElement.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : paramsObject.entrySet()) {
                paramMap.put(entry.getKey(), resolvePlaceholder(jsonValueAsString(entry.getValue()), state));
            }
            return paramMap;
        }

        logger.warn("Unsupported parameters format: {}", parametersElement);
        return paramMap;
    }

    private static String jsonValueAsString(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        return element.toString();
    }

    private static void updateState(List<String> keys, List<Object> values, Map<String, Object> state) {
        for (int i = 0; i < keys.size(); i++) {
            SharedStateUtils.setValue(state, keys.get(i), values.get(i));
            logger.info("📌 Updated sharedState: {} → {}", keys.get(i), values.get(i));
        }
    }

    private static CompletableFuture<Void> callFunction(String functionName, Map<String, String> paramMap, Map<String, Object> state) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                callFunctionOnCurrentThread(functionName, paramMap, state);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };

        MinecraftServer functionServer = botSource != null ? botSource.getServer() : null;
        if ("goTo".equals(functionName)) {
            executor.submit(task);
        } else if (functionServer != null && functionServer.isSameThread()) {
            task.run();
        } else if (functionServer != null) {
            functionServer.execute(task);
        } else {
            executor.submit(task);
        }

        return future;
    }

    private static void callFunctionOnCurrentThread(String functionName, Map<String, String> paramMap, Map<String, Object> state) {
        logger.info("🔧 callFunction: {} with params: {}", functionName, paramMap);

            switch (functionName) {
                case "goTo" -> {
                    int x = Integer.parseInt(resolvePlaceholder(paramMap.get("x"), state));
                    int y = Integer.parseInt(resolvePlaceholder(paramMap.get("y"), state));
                    int z = Integer.parseInt(resolvePlaceholder(paramMap.get("z"), state));
                    boolean sprint = Boolean.parseBoolean(resolvePlaceholder(paramMap.get("sprint"), state));
                    logger.info("Calling method: goTo with x={} y={} z={} sprint={}", x, y, z, sprint);
                    Tools.goTo(x, y, z, sprint);
                }
                case "chartPathToBlock" -> {
                    int targetX = Integer.parseInt(resolvePlaceholder(paramMap.get("targetX"), state));
                    int targetY = Integer.parseInt(resolvePlaceholder(paramMap.get("targetY"), state));
                    int targetZ = Integer.parseInt(resolvePlaceholder(paramMap.get("targetZ"), state));
                    String blockType = resolvePlaceholder(paramMap.get("blockType"), state);
                    logger.info("Calling method: chartPathToBlock with targetX={} targetY={} targetZ={} blockType={}", targetX, targetY, targetZ, blockType);
                    Tools.chartPathToBlock(targetX, targetY, targetZ, blockType);
                }
                case "faceBlock" -> {
                    int targetX = Integer.parseInt(resolvePlaceholder(paramMap.get("targetX"), state));
                    int targetY = Integer.parseInt(resolvePlaceholder(paramMap.get("targetY"), state));
                    int targetZ = Integer.parseInt(resolvePlaceholder(paramMap.get("targetZ"), state));
                    logger.info("Calling method: faceBlock with targetX={} targetY={} targetZ={}", targetX, targetY, targetZ);
                    Tools.faceBlock(targetX, targetY, targetZ);
                }
                case "faceEntity" -> {
                    int targetX = Integer.parseInt(resolvePlaceholder(paramMap.get("targetX"), state));
                    int targetY = Integer.parseInt(resolvePlaceholder(paramMap.get("targetY"), state));
                    int targetZ = Integer.parseInt(resolvePlaceholder(paramMap.get("targetZ"), state));
                    logger.info("Calling method: faceEntity with targetX={} targetY={} targetZ={}", targetX, targetY, targetZ);
                    Tools.faceEntity(targetX, targetY, targetZ);
                }
                case "detectBlocks" -> {
                    String blockType = resolvePlaceholder(paramMap.get("blockType"), state);
                    logger.info("Calling method: detectBlocks with blockType={}", blockType);
                    Tools.detectBlocks(blockType);
                }
                case "turn" -> {
                    String direction = resolvePlaceholder(paramMap.get("direction"), state);
                    logger.info("Calling method: turn with direction={}", direction);
                    Tools.turn(direction);
                }
                case "look" -> {
                    String cardinalDirection = resolvePlaceholder(paramMap.get("cardinalDirection"), state);
                    logger.info("Calling method: look with cardinal direction={}", cardinalDirection);
                    Tools.look(cardinalDirection);
                }
                case "walk", "moveForward" -> {
                    String duration = paramMap.getOrDefault("seconds",
                            paramMap.getOrDefault("duration", paramMap.getOrDefault("till", "1")));
                    String direction = resolvePlaceholder(paramMap.getOrDefault("direction", "forward"), state);
                    int seconds = parseDurationSeconds(resolvePlaceholder(duration, state));
                    logger.info("Calling method: walk with seconds={} direction={}", seconds, direction);
                    Tools.walk(seconds, direction);
                }
                case "hit", "attack" -> {
                    logger.info("Calling method: {}", functionName);
                    Tools.hit();
                }
                case "action" -> {
                    String actionName = resolvePlaceholder(paramMap.getOrDefault("action", paramMap.get("name")), state);
                    logger.info("Calling generic action: {}", actionName);
                    if ("walk".equalsIgnoreCase(actionName) || "moveForward".equalsIgnoreCase(actionName)) {
                        String duration = paramMap.getOrDefault("seconds",
                                paramMap.getOrDefault("duration", paramMap.getOrDefault("till", "1")));
                        String direction = resolvePlaceholder(paramMap.getOrDefault("direction", "forward"), state);
                        Tools.walk(parseDurationSeconds(resolvePlaceholder(duration, state)), direction);
                    } else if ("hit".equalsIgnoreCase(actionName) || "attack".equalsIgnoreCase(actionName)) {
                        Tools.hit();
                    } else {
                        logger.warn("Unknown generic action: {}", actionName);
                        getFunctionOutput("Unknown action: " + actionName);
                    }
                }
                case "mineBlock" -> {
                    int targetX = Integer.parseInt(resolvePlaceholder(paramMap.get("targetX"), state));
                    int targetY = Integer.parseInt(resolvePlaceholder(paramMap.get("targetY"), state));
                    int targetZ = Integer.parseInt(resolvePlaceholder(paramMap.get("targetZ"), state));
                    logger.info("Calling method: mineBlock with targetX={} targetY={} targetZ={}", targetX, targetY, targetZ);
                    Tools.mineBlock(targetX, targetY, targetZ);
                }
                case "placeBlock" -> {
                    int targetX = Integer.parseInt(resolvePlaceholder(paramMap.get("targetX"), state));
                    int targetY = Integer.parseInt(resolvePlaceholder(paramMap.get("targetY"), state));
                    int targetZ = Integer.parseInt(resolvePlaceholder(paramMap.get("targetZ"), state));
                    String blockType = resolvePlaceholder(paramMap.get("blockType"), state);
                    logger.info("Calling method: placeBlock with targetX={} targetY={} targetZ={} blockType={}",
                            targetX, targetY, targetZ, blockType);
                    Tools.placeBlock(targetX, targetY, targetZ, blockType);
                }
                case "craftItem" -> {
                    String itemName = resolvePlaceholder(paramMap.get("itemName"), state);
                    int quantity = parseDurationSeconds(resolvePlaceholder(paramMap.getOrDefault("quantity", "1"), state));
                    logger.info("Calling method: craftItem with itemName={} quantity={}", itemName, quantity);
                    craftItem(itemName, quantity);
                }
                case "dropItem" -> {
                    String itemName = resolvePlaceholder(paramMap.get("itemName"), state);
                    String quantityValue = resolvePlaceholder(paramMap.getOrDefault("quantity", "all"), state);
                    int quantity = "all".equalsIgnoreCase(quantityValue) ? -1 : parseDurationSeconds(quantityValue);
                    logger.info("Calling method: dropItem with itemName={} quantity={}", itemName, quantityValue);
                    dropItem(itemName, quantity);
                }
                case "getOxygenLevel" -> {
                    logger.info("Calling method: getOxygenLevel");
                    Tools.getOxygenLevel();
                }
                case "getHungerLevel" -> {
                    logger.info("Calling method: getHungerLevel");
                    Tools.getHungerLevel();
                }
                case "getHealthLevel" -> {
                    logger.info("Calling method: getHealthLevel");
                    Tools.getHealthLevel();
                }
                case "updateState" -> {
                    String keysRaw = paramMap.get("keys");
                    String valuesRaw = paramMap.get("values");
                    List<String> keys = List.of(keysRaw.split(","));
                    List<String> valueStrings = List.of(valuesRaw.split(","));
                    List<Object> values = new ArrayList<>();
                    for (String v : valueStrings) {
                        try {
                            values.add(Integer.parseInt(v));
                        } catch (NumberFormatException e1) {
                            try {
                                values.add(Double.parseDouble(v));
                            } catch (NumberFormatException e2) {
                                values.add(v);  // Fallback to string
                            }
                        }
                    }
                    updateState(keys, values, state);
                    logger.info("Called updateState with keys={} and values={}", keys, values);
                }
                // Add this new case to your existing switch statement inside the callFunction method.
                case "webSearch" -> {
                    String query = resolvePlaceholder(paramMap.get("query"), state);
                    logger.info("Calling method: webSearch with query='{}'", query);
                    Tools.webSearch(query);
                }
                case "searchBlocks" -> {
                    String blockType = resolvePlaceholder(paramMap.get("blockType"), state);
                    int initialRadius = Integer.parseInt(resolvePlaceholder(paramMap.get("initialRadius"), state));
                    int maxRadius = Integer.parseInt(resolvePlaceholder(paramMap.get("maxRadius"), state));
                    int radiusIncrement = Integer.parseInt(resolvePlaceholder(paramMap.get("radiusIncrement"), state));
                    logger.info("Calling method: searchBlocks with blockType={} initialRadius={} maxRadius={} increment={}",
                            blockType, initialRadius, maxRadius, radiusIncrement);

                    // Call searchBlocks and capture the result
                    if (botSource != null && botSource.getPlayer() != null) {
                        ServerPlayer bot = botSource.getPlayer();
                        BlockPos result = net.shasankp000.Tools.SearchBlocks.searchBlock(
                            bot,
                            blockType,
                            initialRadius,
                            maxRadius,
                            radiusIncrement
                        );

                        // Store result in SharedState for next steps
                        if (result != null) {
                            SharedStateUtils.setValue(state, "found_block_x", result.getX());
                            SharedStateUtils.setValue(state, "found_block_y", result.getY());
                            SharedStateUtils.setValue(state, "found_block_z", result.getZ());
                            SharedStateUtils.setValue(state, "found_block_type", blockType);
                            SharedStateUtils.setValue(state, "search_success", true);
                            logger.info("✓ searchBlocks found {} at ({}, {}, {})", blockType, result.getX(), result.getY(), result.getZ());
                        } else {
                            SharedStateUtils.setValue(state, "search_success", false);
                            logger.warn("searchBlocks did not find {} within radius {}", blockType, maxRadius);
                        }
                    } else {
                        logger.error("Cannot execute searchBlocks: bot is null");
                    }
                }
                default -> logger.warn("Unknown function: {}", functionName);
            }

            logger.info("✓ Function {} execution completed", functionName);
            storeActionMemory(functionName, paramMap, functionOutput);
    }

    private static int parseDurationSeconds(String value) {
        try {
            return Math.max(1, (int) Math.ceil(Double.parseDouble(value)));
        } catch (NumberFormatException e) {
            logger.warn("Invalid walk duration '{}', defaulting to 1 second", value);
            return 1;
        }
    }

    /**
     * Execute a Plan generated by the Planner.
     * Converts planned steps into sequential function calls.
     */
    public static CompletableFuture<Boolean> executePlan(Plan plan, ActionLogWriter logWriter, State initialState) {
        if (plan == null || plan.isEmpty()) {
            logger.warn("Cannot execute empty plan");
            return CompletableFuture.completedFuture(false);
        }

        if (botSource == null) {
            logger.error("Cannot execute plan: botSource is null! Bot not initialized properly.");
            return CompletableFuture.completedFuture(false);
        }

        logger.info("Executing plan: {}", plan.planId);
        logger.info("Plan has {} steps with total score: {}", plan.length(), plan.getTotalScore());

        // Create SharedState for inter-step communication
        Map<String, Object> sharedState = new java.util.concurrent.ConcurrentHashMap<>();

        // Debug: Log all step names
        for (int i = 0; i < plan.steps.size(); i++) {
            PlannedStep step = plan.steps.get(i);
            logger.debug("  Step {}: {} (byte: {})", i + 1, step.actionName, step.actionId & 0xFF);
        }

        // ✅ Execute steps SEQUENTIALLY with blocking and state verification
        CompletableFuture<Void> sequentialExecution = CompletableFuture.completedFuture(null);

        for (int i = 0; i < plan.steps.size(); i++) {
            PlannedStep step = plan.steps.get(i);
            final int stepIndex = i;

            sequentialExecution = sequentialExecution.thenCompose(_void -> {
                // Get state BEFORE action
                State stateBefore = initialState; // TODO: Could update this per step

                // Convert PlannedStep to function call, resolving params from SharedState
                Map<String, String> params = convertStepToParams(step, sharedState);

                logger.info("🔧 Step {}/{}: Executing {} with params: {}",
                    stepIndex + 1, plan.steps.size(), step.actionName, params);

                return callFunction(step.actionName, params, sharedState)
                    .thenCompose(result -> {
                        // Add delay for game state to update (CRITICAL for sequential execution)
                        return CompletableFuture.runAsync(() -> {
                            try {
                                Thread.sleep(500); // Wait 500ms for game state update
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
                    })
                    .thenApply(_void2 -> {
                        // Verify the step actually achieved its goal
                        boolean verified = verifyStepOutcome(step, sharedState);

                        // Log execution
                        if (logWriter != null) {
                            logWriter.logStep(
                                plan.planId,
                                plan.goalId,
                                stateBefore,
                                stepIndex,
                                step,
                                verified ? "success" : "failed_verification",
                                verified ? 10.0 : -5.0,
                                false
                            );
                        }

                        if (verified) {
                            logger.info("✓ Step {}/{}: {} completed and verified",
                                       stepIndex + 1, plan.steps.size(), step.actionName);
                        } else {
                            logger.warn("⚠ Step {}/{}: {} completed but verification failed",
                                       stepIndex + 1, plan.steps.size(), step.actionName);
                            // If verification fails for critical action, abort
                            if (isCriticalAction(step.actionName)) {
                                throw new RuntimeException("Critical action verification failed: " + step.actionName);
                            }
                        }

                        return (Void) null;
                    })
                    .exceptionally(ex -> {
                        // Log failure
                        if (logWriter != null) {
                            logWriter.logStep(
                                plan.planId,
                                plan.goalId,
                                stateBefore,
                                stepIndex,
                                step,
                                "failed: " + ex.getMessage(),
                                -10.0, // Penalty for failure
                                false
                            );
                        }
                        logger.error("✗ Step {}/{}: {} failed: {}",
                                    stepIndex + 1, plan.steps.size(), step.actionName, ex.getMessage());

                        // For critical actions, throw to abort plan
                        if (isCriticalAction(step.actionName)) {
                            logger.error("✗ Critical action failed, aborting plan");
                            throw new RuntimeException("Critical action failed: " + step.actionName);
                        }

                        return null;
                    });
            });
        }

        return sequentialExecution.handle((result, ex) -> {
            if (ex != null) {
                logger.error("Plan execution failed: {}", ex.getMessage());
                return false;
            } else {
                logger.info("✓ Plan {} executed successfully", plan.planId);
                return true;
            }
        });
    }

    /**
     * Convert a PlannedStep to parameter map for function calling WITH SharedState resolution.
     */
    private static Map<String, String> convertStepToParams(PlannedStep step, Map<String, Object> state) {
        Map<String, String> params = new HashMap<>();

        // Map action to parameters based on action name
        String actionName = step.actionName.toLowerCase();

        // Parse params string into array (comma-separated or JSON array)
        String[] paramArray = new String[0];
        if (step.params != null && !step.params.trim().isEmpty()) {
            String trimmedParams = step.params.trim();
            if (trimmedParams.startsWith("[") && trimmedParams.endsWith("]")) {
                // JSON array format: ["x", "y", "z"]
                String content = trimmedParams.substring(1, trimmedParams.length() - 1);
                if (!content.trim().isEmpty()) {
                    paramArray = content.split(",");
                    for (int i = 0; i < paramArray.length; i++) {
                        paramArray[i] = paramArray[i].trim().replaceAll("^\"|\"$", "");
                    }
                }
            } else {
                // Simple comma-separated: x,y,z
                paramArray = trimmedParams.split(",");
                for (int i = 0; i < paramArray.length; i++) {
                    paramArray[i] = paramArray[i].trim();
                }
            }
        }

        // Normalize action name for comparison (case-insensitive)
        switch (actionName) {
            case "goto":
            case "movetocoordinates":
                // Try to get coords from SharedState first (if previous searchBlocks found something)
                if (SharedStateUtils.getValue(state, "found_block_x") != null) {
                    int blockX = (int) SharedStateUtils.getValue(state, "found_block_x");
                    int blockY = (int) SharedStateUtils.getValue(state, "found_block_y");
                    int blockZ = (int) SharedStateUtils.getValue(state, "found_block_z");

                    // Navigate to adjacent position (not ON the block)
                    params.put("x", String.valueOf(blockX + 1));
                    params.put("y", String.valueOf(blockY));
                    params.put("z", String.valueOf(blockZ));
                    params.put("sprint", "true");
                    logger.info("🔗 Resolved goTo params from SharedState: ({}, {}, {})", blockX+1, blockY, blockZ);
                } else if (paramArray.length >= 3) {
                    params.put("x", paramArray[0]);
                    params.put("y", paramArray[1]);
                    params.put("z", paramArray[2]);
                    params.put("sprint", paramArray.length >= 4 ? paramArray[3] : "true");
                }
                break;

            case "mineblock":
            case "breakblock":
                // Try to get coords from SharedState first
                if (SharedStateUtils.getValue(state, "found_block_x") != null) {
                    int blockX = (int) SharedStateUtils.getValue(state, "found_block_x");
                    int blockY = (int) SharedStateUtils.getValue(state, "found_block_y");
                    int blockZ = (int) SharedStateUtils.getValue(state, "found_block_z");
                    params.put("targetX", String.valueOf(blockX));
                    params.put("targetY", String.valueOf(blockY));
                    params.put("targetZ", String.valueOf(blockZ));
                    logger.info("🔗 Resolved mineBlock params from SharedState: ({}, {}, {})", blockX, blockY, blockZ);
                } else if (paramArray.length >= 3) {
                    params.put("targetX", paramArray[0]);
                    params.put("targetY", paramArray[1]);
                    params.put("targetZ", paramArray[2]);
                }
                break;

            case "placeblock":
                if (paramArray.length >= 4) {
                    params.put("targetX", paramArray[0]);
                    params.put("targetY", paramArray[1]);
                    params.put("targetZ", paramArray[2]);
                    params.put("blockType", paramArray[3]);
                } else if (paramArray.length >= 1) {
                    params.put("blockType", paramArray[0]);
                }
                break;

            case "detectblocks":
                // Use found_block_type from SharedState if available
                if (SharedStateUtils.getValue(state, "found_block_type") != null) {
                    params.put("blockType", (String) SharedStateUtils.getValue(state, "found_block_type"));
                    logger.info("🔗 Resolved detectBlocks blockType from SharedState");
                } else if (paramArray.length >= 1) {
                    params.put("blockType", paramArray[0]);
                }
                break;

            case "searchblocks":
                if (paramArray.length >= 4) {
                    params.put("blockType", paramArray[0]);
                    params.put("initialRadius", paramArray[1]);
                    params.put("maxRadius", paramArray[2]);
                    params.put("radiusIncrement", paramArray[3]);
                } else if (paramArray.length >= 1) {
                    params.put("blockType", paramArray[0]);
                    // Set defaults
                    params.put("initialRadius", "10");
                    params.put("maxRadius", "100");
                    params.put("radiusIncrement", "20");
                }
                break;

            case "turn":
                if (paramArray.length >= 1) {
                    params.put("direction", paramArray[0]);
                }
                break;

            case "look":
                if (paramArray.length >= 1) {
                    params.put("cardinalDirection", paramArray[0]);
                }
                break;

            case "websearch":
                if (paramArray.length >= 1) {
                    params.put("query", paramArray[0]);
                }
                break;

            default:
                logger.debug("No special parameter mapping for action: {}", actionName);
        }

        return params;
    }

    /**
     * Verify that a step achieved its expected outcome based on SharedState changes.
     */
    private static boolean verifyStepOutcome(PlannedStep step, Map<String, Object> sharedState) {
        String actionName = step.actionName.toLowerCase();

        switch (actionName) {
            case "searchblocks":
                // Verify searchBlocks actually found something
                Boolean searchSuccess = (Boolean) SharedStateUtils.getValue(sharedState, "search_success");
                if (searchSuccess != null && searchSuccess) {
                    logger.info("✓ searchBlocks verification: block found in SharedState");
                    return true;
                } else {
                    logger.warn("✗ searchBlocks verification failed: no block found");
                    return false;
                }

            case "goto":
            case "movetocoordinates":
                // Verify goTo moved the bot close to the target
                Integer targetX = (Integer) SharedStateUtils.getValue(sharedState, "found_block_x");
                Integer targetZ = (Integer) SharedStateUtils.getValue(sharedState, "found_block_z");

                if (targetX != null && targetZ != null && botSource != null && botSource.getPlayer() != null) {
                    BlockPos botPos = botSource.getPlayer().blockPosition();
                    double distance = Math.sqrt(Math.pow(botPos.getX() - (targetX + 1), 2) +
                                               Math.pow(botPos.getZ() - targetZ, 2));

                    if (distance <= 3.0) { // Within 3 blocks is good enough
                        logger.info("✓ goTo verification: bot within {} blocks of target", String.format("%.1f", distance));
                        return true;
                    } else {
                        logger.warn("✗ goTo verification failed: bot still {} blocks away from target", String.format("%.1f", distance));
                        return false;
                    }
                }
                // If no target in state, assume success (might be standalone goTo)
                logger.info("✓ goTo verification: no target in SharedState, assuming success");
                return true;

            case "mineblock":
            case "breakblock":
                // Verify block was actually broken by checking if it still exists
                Integer blockX = (Integer) SharedStateUtils.getValue(sharedState, "found_block_x");
                Integer blockY = (Integer) SharedStateUtils.getValue(sharedState, "found_block_y");
                Integer blockZ = (Integer) SharedStateUtils.getValue(sharedState, "found_block_z");

                if (blockX != null && blockY != null && blockZ != null && botSource != null) {
                    MinecraftServer server = botSource.getServer();
                    if (server != null) {
                        ServerLevel world = server.overworld();
                        BlockPos blockPos = new BlockPos(blockX, blockY, blockZ);
                        Block block = world.getBlockState(blockPos).getBlock();

                        // Check if block is now air (successfully mined)
                        if (block == Blocks.AIR) {
                            logger.info("✓ mineBlock verification: block successfully removed");
                            return true;
                        } else {
                            logger.warn("✗ mineBlock verification failed: block still exists at position");
                            return false;
                        }
                    }
                }
                logger.info("✓ mineBlock verification: no target in SharedState, assuming success");
                return true;

            case "detectblocks":
                // DetectBlocks can succeed or fail - assume success for now
                // TODO: Could check if detectBlocks actually detected the target
                logger.info("✓ detectBlocks verification: assuming success (TODO: verify detection)");
                return true;

            default:
                // For other actions, assume success if no exception was thrown
                logger.debug("✓ {} verification: default success", actionName);
                return true;
        }
    }

    /**
     * Convert a PlannedStep to parameter map for function calling (legacy method without SharedState).
     */
    private static Map<String, String> convertStepToParams(PlannedStep step) {
        Map<String, String> params = new HashMap<>();
        
        // Map action to parameters based on action name
        String actionName = step.actionName.toLowerCase();
        
        // Parse params string into array (comma-separated or JSON array)
        String[] paramArray = new String[0];
        if (step.params != null && !step.params.trim().isEmpty()) {
            String trimmedParams = step.params.trim();
            if (trimmedParams.startsWith("[") && trimmedParams.endsWith("]")) {
                // JSON array format: ["x", "y", "z"]
                String content = trimmedParams.substring(1, trimmedParams.length() - 1);
                if (!content.trim().isEmpty()) {
                    paramArray = content.split(",");
                    for (int i = 0; i < paramArray.length; i++) {
                        paramArray[i] = paramArray[i].trim().replaceAll("^\"|\"$", "");
                    }
                }
            } else {
                // Simple comma-separated: x,y,z
                paramArray = trimmedParams.split(",");
                for (int i = 0; i < paramArray.length; i++) {
                    paramArray[i] = paramArray[i].trim();
                }
            }
        }

        // Normalize action name for comparison (case-insensitive)
        switch (actionName) {
            case "goto":
            case "movetocoordinates":
                if (paramArray.length >= 3) {
                    params.put("x", paramArray[0]);
                    params.put("y", paramArray[1]);
                    params.put("z", paramArray[2]);
                    params.put("sprint", paramArray.length >= 4 ? paramArray[3] : "true");
                }
                break;
                
            case "mineblock":
            case "breakblock":
                if (paramArray.length >= 3) {
                    params.put("targetX", paramArray[0]);
                    params.put("targetY", paramArray[1]);
                    params.put("targetZ", paramArray[2]);
                }
                break;
                
            case "placeblock":
                if (paramArray.length >= 4) {
                    params.put("targetX", paramArray[0]);
                    params.put("targetY", paramArray[1]);
                    params.put("targetZ", paramArray[2]);
                    params.put("blockType", paramArray[3]);
                } else if (paramArray.length >= 1) {
                    params.put("blockType", paramArray[0]);
                }
                break;
                
            case "detectblocks":
                if (paramArray.length >= 1) {
                    params.put("blockType", paramArray[0]);
                }
                break;
                
            case "searchblocks":
                if (paramArray.length >= 4) {
                    params.put("blockType", paramArray[0]);
                    params.put("initialRadius", paramArray[1]);
                    params.put("maxRadius", paramArray[2]);
                    params.put("radiusIncrement", paramArray[3]);
                } else if (paramArray.length >= 1) {
                    params.put("blockType", paramArray[0]);
                    // Set defaults
                    params.put("initialRadius", "10");
                    params.put("maxRadius", "100");
                    params.put("radiusIncrement", "20");
                }
                break;

            case "turn":
                if (paramArray.length >= 1) {
                    params.put("direction", paramArray[0]);
                } else {
                    params.put("direction", "left"); // Default
                }
                break;
                
            case "look":
                if (paramArray.length >= 1) {
                    params.put("cardinalDirection", paramArray[0]);
                } else {
                    params.put("cardinalDirection", "north"); // Default
                }
                break;
                
            case "websearch":
                if (paramArray.length >= 1) {
                    params.put("query", paramArray[0]);
                }
                break;
                
            // Actions with no parameters (handled by bot commands)
            case "eat":
            case "attack":
            case "shoot":
            case "evade":
            case "retreat":
            case "shield":
            case "gethealthlevel":
            case "gethungerlevel":
            case "getoxygenlevel":
                // No parameters needed - these are simple bot commands
                break;
                
            default:
                // If action not recognized, log warning but don't fail
                // The action might still be valid in the function caller
                logger.debug("No parameter mapping for action: {}", actionName);
        }
        
        return params;
    }

    /**
     * Determine if an action is critical (plan should abort if it fails).
     * Critical actions are those that subsequent steps depend on.
     */
    private static boolean isCriticalAction(String actionName) {
        if (actionName == null) return false;

        return switch (actionName.toLowerCase()) {
            case "searchblocks", "goto", "navigateto" -> true;
            default -> false;
        };
    }
}
