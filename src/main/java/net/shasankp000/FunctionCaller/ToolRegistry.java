package net.shasankp000.FunctionCaller;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;

public class ToolRegistry {

    public static final List<Tool> TOOLS = List.of(

            new Tool(
                    "goTo",
                    """
                    Uses the path finder and path tracer to navigate to a given x y z coordinate.
                    Stops within ~3 blocks of the target due to inertia-based carpet bot control.
                    """,
                    List.of(
                            new Tool.Parameter("x", "X coordinate to go to."),
                            new Tool.Parameter("y", "Y coordinate to go to."),
                            new Tool.Parameter("z", "Z coordinate to go to."),
                            new Tool.Parameter("sprint", "Boolean flag for sprint mode.")
                    ),
                    Set.of("lastTarget.x", "lastTarget.y", "lastTarget.z"),
                    (sharedState, paramMap, result) -> {
                        sharedState.put("lastTarget.x", paramMap.get("x"));
                        sharedState.put("lastTarget.y", paramMap.get("y"));
                        sharedState.put("lastTarget.z", paramMap.get("z"));
                    }
            ),

            new Tool(
                    "detectBlocks",
                    """
                    Raycasts to find the first block of the given type in front of the bot.
                    If found, stores its coordinates in shared state for chaining.
                    """,
                    List.of(
                            new Tool.Parameter("blockType", "Target block's name.")
                    ),
                    Set.of("lastDetectedBlock.x", "lastDetectedBlock.y", "lastDetectedBlock.z"),
                    (sharedState, paramMap, result) -> {
                        if (result instanceof BlockPos pos) {
                            sharedState.put("lastDetectedBlock.x", pos.getX());
                            sharedState.put("lastDetectedBlock.y", pos.getY());
                            sharedState.put("lastDetectedBlock.z", pos.getZ());
                        }
                    }
            ),

            new Tool(
                    "turn",
                    "Turns the bot to face a new direction based on the parameters (a side of the bot). Unlike the look method, this method only rotates the bot to face 3 of it's sides, i.e the left, right or it's back.",
                    List.of(
                            new Tool.Parameter("direction", "Direction to turn. Valid: 'left', 'right', 'back'.")
                    ),
                    Set.of("facing.direction", "facing.facing", "facing.axis"),
                    (sharedState, paramMap, result) -> {
                        if (result instanceof String output) {
                            Matcher matcher = Pattern.compile("Now facing (\\\\w+) which is in (\\\\w+).*in (\\\\w+) axis", Pattern.CASE_INSENSITIVE).matcher(output);
                            if (matcher.find()) {
                                sharedState.put("facing.direction", matcher.group(1));
                                sharedState.put("facing.facing", matcher.group(2));
                                sharedState.put("facing.axis", matcher.group(3));
                            }
                        }
                    }
            ),

            new Tool(
                    "look",
                    """
                    Rotates the bot to look in a specified cardinal direction (north, south, east, west).
                    """,
                    List.of(
                            new Tool.Parameter("cardinalDirection", "Direction to look. Valid: 'north', 'south', 'east', 'west'.")
                    ),
                    Set.of("facing.direction", "facing.facing", "facing.axis"),
                    (sharedState, paramMap, result) -> {
                        if (result instanceof String output) {
                            Matcher matcher = Pattern.compile("Now facing (\\w+) which is in (\\w+).*in (\\w+) axis", Pattern.CASE_INSENSITIVE).matcher(output);
                            if (matcher.find()) {
                                sharedState.put("facing.direction", matcher.group(1));
                                sharedState.put("facing.facing", matcher.group(2));
                                sharedState.put("facing.axis", matcher.group(3));
                            }
                        }
                    }
            ),

            new Tool(
                    "walk",
                    """
                    Makes the bot walk forward for a short duration, then stops movement.
                    """,
                    List.of(
                            new Tool.Parameter("seconds", "How many seconds to walk. Defaults to 1."),
                            new Tool.Parameter("direction", "Direction to walk. Valid: 'forward' or 'backward'. Defaults to 'forward'.")
                    ),
                    Set.of("bot.walking"),
                    (sharedState, paramMap, result) -> sharedState.put("bot.walking", false)
            ),

            new Tool(
                    "hit",
                    """
                    Makes the bot swing or attack once using the currently held item.
                    Alias for simple melee interaction.
                    """,
                    List.of(
                            new Tool.Parameter("None", "No parameters needed.")
                    ),
                    Set.of("lastAttack.status"),
                    (sharedState, paramMap, result) -> sharedState.put("lastAttack.status", result)
            ),

            new Tool(
                    "attack",
                    """
                    Makes the bot attack the current target or swing once using the currently held item.
                    """,
                    List.of(
                            new Tool.Parameter("None", "No parameters needed.")
                    ),
                    Set.of("lastAttack.status"),
                    (sharedState, paramMap, result) -> sharedState.put("lastAttack.status", result)
            ),


            new Tool(
                    "mineBlock",
                    """
                    Mines the block at the given coordinates.
                    """,
                    List.of(
                            new Tool.Parameter("targetX", "Target block X coordinate."),
                            new Tool.Parameter("targetY", "Target block Y coordinate."),
                            new Tool.Parameter("targetZ", "Target block Z coordinate.")
                    ),
                    Set.of("lastMinedBlock.x", "lastMinedBlock.y", "lastMinedBlock.z"),
                    (sharedState, paramMap, result) -> {
                        sharedState.put("lastMinedBlock.x", paramMap.get("targetX"));
                        sharedState.put("lastMinedBlock.y", paramMap.get("targetY"));
                        sharedState.put("lastMinedBlock.z", paramMap.get("targetZ"));
                    }
            ),

            new Tool(
                    "getOxygenLevel",
                    """
                    Retrieves the bot's current oxygen (air) level.
                    """,
                    List.of(
                            new Tool.Parameter("None", "No parameters needed.")
                    ),
                    Set.of("bot.oxygenLevel"),
                    (sharedState, paramMap, result) -> {
                        if (result instanceof Number level) {
                            sharedState.put("bot.oxygenLevel", level);
                        } else if (result instanceof String s) {
                            sharedState.put("bot.oxygenLevel", s); // fallback
                        }
                    }
            ),

            // Add other tools below in same pattern...

            new Tool(
                    "getHungerLevel",
                    """
                    Gets the bot's hunger level.
                    """,
                    List.of(
                            new Tool.Parameter("None", "No parameters needed.")
                    ),
                    Set.of("bot.hungerLevel"),
                    (sharedState, paramMap, result) -> {
                        if (result instanceof Number level) {
                            sharedState.put("bot.hungerLevel", level);
                        } else if (result instanceof String s) {
                            sharedState.put("bot.hungerLevel", s);
                        }
                    }
            ),

            new Tool(
                    "getHealthLevel",
                    """
                    Gets the bot's health level (hearts).
                    """,
                    List.of(
                            new Tool.Parameter("None", "No parameters needed.")
                    ),
                    Set.of("bot.healthLevel"),
                    (sharedState, paramMap, result) -> {
                        if (result instanceof Number level) {
                            sharedState.put("bot.healthLevel", level);
                        } else if (result instanceof String s) {
                            sharedState.put("bot.healthLevel", s);
                        }
                    }
            ),

            new Tool(
                "webSearch",
                    """
                    Searches the web for the input query via an automatically pre-configured provider. Meant to be used as a standalone method and not in a pipeline.
                    """,
                    List.of(
                            new Tool.Parameter("Query", "You need to understand the user's input and put in a search query accordingly in here.")
                    ),
                    Set.of("webSearchQuery.result"),
                    ((sharedState, paramMap, searchResult) -> {
                        if (searchResult instanceof String result) {
                            sharedState.put("webSearchQuery", result);
                        }
                    })
            ),

            new Tool(
                    "placeBlock",
                    """
                    Places a block of the specified type at the given x, y, z coordinates.
                    The bot must have the block in its inventory and must be within 5 blocks of the target position.
                    Automatically handles inventory management (moves block to hotbar if needed).
                    Verifies that the target position is empty and finds a suitable adjacent block to place against.
                    """,
                    List.of(
                            new Tool.Parameter("targetX", "X coordinate where the block should be placed."),
                            new Tool.Parameter("targetY", "Y coordinate where the block should be placed."),
                            new Tool.Parameter("targetZ", "Z coordinate where the block should be placed."),
                            new Tool.Parameter("blockType", "Type of block to place (e.g., 'stone', 'dirt', 'minecraft:oak_planks').")
                    ),
                    Set.of("lastPlacedBlock.x", "lastPlacedBlock.y", "lastPlacedBlock.z", "lastPlacedBlock.type"),
                    (sharedState, paramMap, result) -> {
                        sharedState.put("lastPlacedBlock.x", paramMap.get("targetX"));
                        sharedState.put("lastPlacedBlock.y", paramMap.get("targetY"));
                        sharedState.put("lastPlacedBlock.z", paramMap.get("targetZ"));
                        sharedState.put("lastPlacedBlock.type", paramMap.get("blockType"));
                    }
            ),

            new Tool(
                    "craftItem",
                    """
                    Crafts a supported item from the bot's current inventory.
                    Consumes the required ingredients and adds the crafted output to inventory.
                    Supports common survival recipes such as planks, sticks, crafting tables, chests, furnaces, torches, bread, and basic wooden/stone tools.
                    """,
                    List.of(
                            new Tool.Parameter("itemName", "Item to craft, e.g. 'oak_planks', 'stick', 'crafting_table', 'stone_pickaxe'."),
                            new Tool.Parameter("quantity", "How many recipe batches to craft. Defaults to 1.")
                    ),
                    Set.of("lastCrafted.item", "lastCrafted.quantity"),
                    (sharedState, paramMap, result) -> {
                        sharedState.put("lastCrafted.item", paramMap.get("itemName"));
                        sharedState.put("lastCrafted.quantity", paramMap.get("quantity"));
                    }
            ),

            new Tool(
                    "dropItem",
                    """
                    Drops the requested item from the bot's inventory into the world.
                    Use this when the player says phrases like 'drop it sticks', 'drop oak planks', or 'drop 3 torches'.
                    If quantity is omitted or set to 'all', drops all matching items.
                    """,
                    List.of(
                            new Tool.Parameter("itemName", "Item to drop, e.g. 'stick', 'oak_planks', 'minecraft:torch'."),
                            new Tool.Parameter("quantity", "How many items to drop, or 'all'. Defaults to 'all'.")
                    ),
                    Set.of("lastDropped.item", "lastDropped.quantity"),
                    (sharedState, paramMap, result) -> {
                        sharedState.put("lastDropped.item", paramMap.get("itemName"));
                        sharedState.put("lastDropped.quantity", paramMap.get("quantity"));
                    }
            ),

            new Tool(
                    "speedrunDragon",
                    """
                    Starts or updates a player-like Ender Dragon speedrun route.
                    The bot checks its inventory and dimension, chooses the next speedrun stage, and reports what it needs next.
                    It must not use creative shortcuts, teleport-kill behavior, phasing, or impossible block placement.
                    """,
                    List.of(),
                    Set.of("dragonSpeedrun.stage", "dragonSpeedrun.nextAction"),
                    (sharedState, paramMap, result) -> sharedState.put("dragonSpeedrun.active", true)
            ),

            new Tool(
                    "searchBlocks",
                    """
                    Efficiently searches for blocks in an expanding radius around the bot.
                    Uses incremental search shells to avoid lag. Caches searched positions to prevent re-scanning.
                    Returns the nearest matching block's coordinates, which can be used with goTo and mineBlock.
                    """,
                    List.of(
                            new Tool.Parameter("blockType", "Target block type (e.g., 'oak_log', 'minecraft:iron_ore')."),
                            new Tool.Parameter("initialRadius", "Starting search radius in blocks (e.g., 10)."),
                            new Tool.Parameter("maxRadius", "Maximum search radius in blocks (e.g., 100)."),
                            new Tool.Parameter("radiusIncrement", "How much to expand each iteration (e.g., 20).")
                    ),
                    Set.of("foundBlock.x", "foundBlock.y", "foundBlock.z", "foundBlock.type"),
                    (sharedState, paramMap, result) -> {
                        if (result instanceof BlockPos pos) {
                            sharedState.put("foundBlock.x", pos.getX());
                            sharedState.put("foundBlock.y", pos.getY());
                            sharedState.put("foundBlock.z", pos.getZ());
                            sharedState.put("foundBlock.type", paramMap.get("blockType"));
                        }
                    }
            )
    );

}
