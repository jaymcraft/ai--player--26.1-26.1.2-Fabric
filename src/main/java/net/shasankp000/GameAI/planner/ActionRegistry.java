package net.shasankp000.GameAI.planner;

import net.shasankp000.FunctionCaller.Tool;
import net.shasankp000.FunctionCaller.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps action byte IDs (0-255) to function names and vice versa.
 * Integrates with ToolRegistry to ensure consistency.
 */
public class ActionRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("ActionRegistry");

    // Bidirectional mappings
    private static final Map<Byte, String> BYTE_TO_FUNCTION = new ConcurrentHashMap<>();
    private static final Map<String, Byte> FUNCTION_TO_BYTE = new ConcurrentHashMap<>();

    // Special action IDs
    public static final byte ACTION_UNKNOWN = 0;

    static {
        // Note: refreshFromToolRegistry() will be called explicitly after ToolRegistry is initialized
        // Static initialization can run before ToolRegistry is ready
        LOGGER.info("ActionRegistry static block initialized (deferred refresh)");
    }

    /**
     * Ensure ActionRegistry is initialized. Called by MarkovChain2 and Planner.
     */
    public static void ensureInitialized() {
        if (FUNCTION_TO_BYTE.isEmpty()) {
            LOGGER.info("ActionRegistry not yet initialized, refreshing now...");
            refreshFromToolRegistry();
        }
    }

    /**
     * Refresh action mappings from ToolRegistry.
     * Should be called when new tools are registered.
     */
    public static synchronized void refreshFromToolRegistry() {
        LOGGER.info("Refreshing action registry from ToolRegistry...");

        // Clear existing mappings (except for special actions)
        BYTE_TO_FUNCTION.clear();
        FUNCTION_TO_BYTE.clear();
        byte nextByteId = 1;

        // Register unknown action
        BYTE_TO_FUNCTION.put(ACTION_UNKNOWN, "unknown");
        FUNCTION_TO_BYTE.put("unknown", ACTION_UNKNOWN);

        // Register all functions from ToolRegistry
        List<Tool> allFunctions = ToolRegistry.TOOLS;

        if (allFunctions == null || allFunctions.isEmpty()) {
            LOGGER.error("⚠ ToolRegistry has no functions registered! Action registry will be empty.");
            LOGGER.error("This is a critical error - planner cannot work without registered tools.");
            return;
        }

        LOGGER.info("Found {} tools in ToolRegistry", allFunctions.size());

        for (Tool func : allFunctions) {
            String functionName = func.name();

            if (functionName == null || functionName.isEmpty()) {
                LOGGER.warn("Skipping tool with null/empty name");
                continue;
            }

            if (!FUNCTION_TO_BYTE.containsKey(functionName)) {
                if (nextByteId >= 127) {
                    LOGGER.warn("Action registry full! Cannot register more than 127 actions.");
                    break;
                }

                byte byteId = nextByteId++;
                BYTE_TO_FUNCTION.put(byteId, functionName);
                FUNCTION_TO_BYTE.put(functionName, byteId);

                LOGGER.info("  Registered: {} → byte {}", functionName, byteId & 0xFF);
            }
        }

        LOGGER.info("✓ Action registry initialized with {} functions", FUNCTION_TO_BYTE.size() - 1);

        // Always log registered actions for debugging
        LOGGER.info("Registered actions:");
        int count = 0;
        for (Map.Entry<String, Byte> entry : FUNCTION_TO_BYTE.entrySet()) {
            if (!entry.getKey().equals("unknown")) {
                LOGGER.info("  {} → byte {}", entry.getKey(), entry.getValue() & 0xFF);
                count++;
            }
        }

        if (count == 0) {
            LOGGER.error("❌ NO ACTIONS REGISTERED! This will cause planning to fail.");
        }
    }

    /**
     * Get function name from byte ID.
     */
    public static String getFunctionName(byte actionByte) {
        return BYTE_TO_FUNCTION.getOrDefault(actionByte, "unknown");
    }

    /**
     * Get byte ID from function name.
     */
    public static byte getActionByte(String functionName) {
        return FUNCTION_TO_BYTE.getOrDefault(functionName, ACTION_UNKNOWN);
    }

    /**
     * Get all registered action bytes.
     */
    public static Set<Byte> getAllActionBytes() {
        return new HashSet<>(BYTE_TO_FUNCTION.keySet());
    }

    /**
     * Get all registered function names.
     */
    public static Set<String> getAllFunctionNames() {
        return new HashSet<>(FUNCTION_TO_BYTE.keySet());
    }

    /**
     * Check if a byte ID is valid.
     */
    public static boolean isValidAction(byte actionByte) {
        return BYTE_TO_FUNCTION.containsKey(actionByte);
    }

    /**
     * Check if a function name is registered.
     */
    public static boolean isValidFunction(String functionName) {
        return FUNCTION_TO_BYTE.containsKey(functionName);
    }

    /**
     * Get all actions relevant to a specific goal.
     * Simple keyword matching against function names and descriptions.
     */
    public static List<Byte> getRelevantActions(short goalId, String goalKeywords) {
        List<Byte> relevant = new ArrayList<>();
        String lowerKeywords = goalKeywords.toLowerCase();

        // Split keywords into individual words for better matching
        String[] keywords = lowerKeywords.split("\\s+");

        // Add goal-specific keywords based on goal ID
        Set<String> expandedKeywords = new HashSet<>(Arrays.asList(keywords));

        // Expand keywords based on goal type
        if (goalKeywords.contains("gather") || goalKeywords.contains("collect") ||
            goalKeywords.contains("fetch") || goalKeywords.contains("get")) {
            expandedKeywords.addAll(Arrays.asList("mine", "chop", "cut", "harvest", "break", "dig",
                "detect", "navigate", "look", "inventory", "block"));
        }
        if (goalKeywords.contains("wood") || goalKeywords.contains("tree") || goalKeywords.contains("log")) {
            expandedKeywords.addAll(Arrays.asList("mine", "block", "look", "navigate", "detect", "inventory"));
        }
        if (goalKeywords.contains("build") || goalKeywords.contains("place")) {
            expandedKeywords.addAll(Arrays.asList("place", "block", "navigate", "look"));
        }
        if (goalKeywords.contains("craft")) {
            expandedKeywords.addAll(Arrays.asList("craft", "make", "inventory"));
        }

        // Simple keyword matching against all tools
        for (Tool tool : ToolRegistry.TOOLS) {
            String toolName = tool.name().toLowerCase();
            String toolDesc = tool.description().toLowerCase();

            boolean matches = false;

            // Check if any keyword matches tool name or description
            for (String keyword : expandedKeywords) {
                if (keyword.length() < 2) continue; // Skip single letters

                // More flexible matching: check if keyword is part of tool name/description
                // or if tool name is part of keyword
                if (toolName.contains(keyword) ||
                    toolDesc.contains(keyword) ||
                    keyword.contains(toolName) ||
                    // Also check without underscores
                    toolName.replace("_", "").contains(keyword) ||
                    keyword.contains(toolName.replace("_", ""))) {
                    matches = true;
                    break;
                }
            }

            // Also check full keyword match
            if (toolName.contains(lowerKeywords) || toolDesc.contains(lowerKeywords) ||
                lowerKeywords.contains(toolName)) {
                matches = true;
            }

            if (matches) {
                byte actionByte = getActionByte(tool.name());
                if (actionByte != ACTION_UNKNOWN) {
                    relevant.add(actionByte);
                    LOGGER.debug("Matched action '{}' for goal '{}'", tool.name(), goalKeywords);
                }
            }
        }

        // If no relevant actions found, use goal-specific default set (not ALL)
        if (relevant.isEmpty()) {
            // Use goal-specific defaults instead of all actions
            String[] defaultActions = getDefaultActionsForGoal(goalId);

            for (String actionName : defaultActions) {
                byte actionByte = getActionByte(actionName);
                if (actionByte != ACTION_UNKNOWN) {
                    relevant.add(actionByte);
                }
            }

            if (!relevant.isEmpty()) {
                LOGGER.info("Using {} default actions for goal '{}' (ID: {})",
                    relevant.size(), goalKeywords, goalId);
            } else {
                LOGGER.warn("No relevant or default actions found for goal '{}'", goalKeywords);
            }
        } else {
            LOGGER.info("Found {} relevant actions for goal '{}'", relevant.size(), goalKeywords);
        }

        return relevant;
    }

    /**
     * Get default actions for a specific goal ID.
     */
    private static String[] getDefaultActionsForGoal(short goalId) {
        // Return goal-specific defaults instead of ALL actions
        return switch (goalId) {
            case 1 -> // MINE
                new String[]{"mineBlock", "look", "detectBlocks"};
            case 2 -> // BUILD
                new String[]{"placeBlock", "look", "goTo"};
            case 3 -> // CRAFT
                new String[]{"look", "detectBlocks"};
            case 4 -> // NAVIGATE
                new String[]{"goTo", "walk", "look"};
            case 5 -> // COMBAT
                new String[]{"attack", "hit", "getHealthLevel"};
            case 6 -> // GATHER (most common)
                new String[]{"mineBlock", "detectBlocks", "look", "goTo"};
            case 7 -> // EXPLORE
                new String[]{"goTo", "walk", "look", "detectBlocks", "webSearch"};
            case 8 -> // FARM
                new String[]{"placeBlock", "mineBlock", "look", "goTo"};
            case 9 -> // TRADE
                new String[]{"goTo", "walk", "look"};
            default -> // Unknown goal - minimal set
                new String[]{"look", "walk", "getHealthLevel"};
        };
    }

    /**
     * Get human-readable debug info for an action byte.
     */
    public static String getActionDebugInfo(byte actionByte) {
        String funcName = getFunctionName(actionByte);
        if (funcName.equals("unknown")) {
            return String.format("unknown_%d", actionByte & 0xFF);
        }

        // Check if function exists in ToolRegistry
        Tool func = ToolRegistry.TOOLS.stream()
            .filter(t -> t.name().equals(funcName))
            .findFirst()
            .orElse(null);

        if (func != null) {
            return String.format("%s (byte %d)", funcName, actionByte & 0xFF);
        }

        return funcName;
    }
}
