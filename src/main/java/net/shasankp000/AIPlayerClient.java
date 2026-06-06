package net.shasankp000;


import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.shasankp000.ChatUtils.ChatContextManager;
import net.shasankp000.ChatUtils.ClarificationState;
import net.shasankp000.FilingSystem.LLMClientFactory;
import net.shasankp000.FilingSystem.ManualConfig;
import net.shasankp000.LauncherDetection.LauncherEnvironment;
import net.shasankp000.Network.OpenConfigPayload;
import net.shasankp000.OllamaClient.ollamaClient;
import net.shasankp000.ServiceLLMClients.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AIPlayerClient implements ClientModInitializer {
    private static final Gson GSON = new Gson();
    private static final Path BOT_PROFILE_PATH = Paths.get(LauncherEnvironment.getStorageDirectory("config") + File.separator + "settings.json5");
    private static JsonObject botProfiles;
    public static final Logger LOGGER = LoggerFactory.getLogger("ai-player-client");
    public static final ManualConfig CONFIG = ManualConfig.load();


    static {
        reloadBotProfiles();
    }


    private static String getBotNameIfMentioned(String message) {
        reloadBotProfiles();
        JsonObject profiles = getBotProfiles();
        if (profiles == null) return null;

        String[] words = message.split("\\s+");
        for (String word : words) {
            String cleaned = word.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
            for (String botName : profiles.keySet()) {
                if (botName.equalsIgnoreCase(cleaned)) {
                    return botName;
                }
            }
        }
        return null;
    }

    private static void reloadBotProfiles() {
        try (java.io.Reader reader = Files.newBufferedReader(BOT_PROFILE_PATH)) {
            botProfiles = GSON.fromJson(reader, JsonObject.class);
        } catch (IOException e) {
            System.out.println("Bot profiles not found yet - continuing with no bots registered.");
            botProfiles = null;
        }
    }

    private static JsonObject getBotProfiles() {
        if (botProfiles == null || !botProfiles.has("botGameProfile") || botProfiles.get("botGameProfile").isJsonNull()) {
            return null;
        }
        return botProfiles.getAsJsonObject("botGameProfile");
    }



    private static boolean isMessageFromBot(String rawMessage) {
        reloadBotProfiles();
        JsonObject profiles = getBotProfiles();
        if (profiles == null) return false;

        for (String botName : profiles.keySet()) {
            if (rawMessage.startsWith("[" + botName + "]")) {
                return true;
            }
        }
        return false;
    }


    private static boolean isMessageFromServerContainsBotName(String rawMessage) {
        if (rawMessage.startsWith("[Server]")) {
            reloadBotProfiles();
            JsonObject profiles = getBotProfiles();
            if (profiles == null) return false;

            for (String botName : profiles.keySet()) {
                if (rawMessage.contains(botName)) {
                    return true;
                }
            }
        }
        return false;
    }




    @Override
    public void onInitializeClient() {


        LOGGER.debug("Running on environment type: {}", FabricLoader.getInstance().getEnvironmentType());

        String llmProvider = System.getProperty("aiplayer.llmMode", "ollama");

        LOGGER.debug("Using provider: {}", llmProvider);


        ClientPlayNetworking.registerGlobalReceiver(OpenConfigPayload.ID, (payload, context) -> {
            JsonObject config = GSON.fromJson(payload.configData(), JsonObject.class);
            List<String> models = new ArrayList<>();
            String selectedModel = null;
            if (config != null) {
                if (config.has("modelList") && config.get("modelList").isJsonArray()) {
                    config.getAsJsonArray("modelList").forEach(model -> models.add(model.getAsString()));
                }
                if (config.has("selectedLanguageModel") && !config.get("selectedLanguageModel").isJsonNull()) {
                    selectedModel = config.get("selectedLanguageModel").getAsString();
                }
            }

            net.minecraft.client.gui.screens.Screen currentScreen = net.minecraft.client.Minecraft.getInstance().screen;
            net.minecraft.client.Minecraft.getInstance().setScreen(
                    new net.shasankp000.GraphicalUserInterface.ConfigManager(net.minecraft.network.chat.Component.empty(), currentScreen, models, selectedModel)
            );
        });


        // Register real-time chat scanner with loop protection
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            Minecraft client = Minecraft.getInstance();

            String rawMessage = message.getString();
            UUID playerUUID = sender != null ? sender.id() : null;



            System.out.println("Raw message: " + rawMessage);

            // ✅ Loop protection layer 1: Ignore commands
            if (rawMessage.startsWith("/")) {
                System.out.println("Command detected, skipping....");
                return;
            }

            // ✅ Loop protection layer 2: Ignore if message has bot prefix
            if (isMessageFromBot(rawMessage)) {
                System.out.println("Bot's own message detected by prefix, skipping...");
                return;
            }

            // ✅ Loop protection layer 3: Ignore server system messages that mention a bot name
            if (isMessageFromServerContainsBotName(rawMessage)) {
                System.out.println("Server system message mentioning bot detected, skipping...");
                return;
            }


            if (playerUUID != null && ChatContextManager.isAwaitingClarification(playerUUID)) {
                ClarificationState clarification = ChatContextManager.getPendingClarification(playerUUID);

                String combinedContext = """
                    Original: %s
                    Clarification asked: %s
                    Player answer: %s
                  """.formatted(
                        clarification.originalMessage,
                        clarification.clarifyingQuestion,
                        rawMessage
                );

                String botName = clarification.botName; // ✅ Always store it when you first ask!
                if (botName == null || botName.isEmpty()) {
                    botName = getBotNameIfMentioned(clarification.clarifyingQuestion);
                }
                if (botName == null || botName.isEmpty()) {
                    botName = getBotNameIfMentioned(rawMessage);
                }
                System.out.println("[Debug] Using botName: " + botName);

                if (botName == null) {
                    System.out.println("No bot name resolved for clarification! Skipping NLP call to prevent crash.");
                } else {

                    switch (llmProvider) {
                        case "openai", "gpt", "google", "gemini", "anthropic", "claude", "xAI", "xai", "grok", "custom":
                            LLMClient llmClient = LLMClientFactory.createClient(llmProvider);

                            if (llmClient!=null) {
                                LLMServiceHandler.runFromChat(combinedContext, botName, playerUUID, llmClient);
                            }
                            else {
                                LOGGER.error("");
                                client.getToastManager().addToast(
                                        SystemToast.multiline(client, SystemToast.SystemToastId.CHUNK_LOAD_FAILURE, Component.nullToEmpty("LLM Client factory error."), Component.nullToEmpty("Error! Returned client is null! Cannot proceed!"))
                                );
                            }
                            break;
                        case "ollama":
                            ollamaClient.runFromChat(botName, combinedContext, playerUUID);
                            break;
                        default:
                            LOGGER.warn("Unsupported provider detected. Defaulting to Ollama client");
                            client.getToastManager().addToast(
                                    SystemToast.multiline(client, SystemToast.SystemToastId.NARRATOR_TOGGLE, Component.nullToEmpty("Invalid LLM Client."), Component.nullToEmpty("Unsupported provider detected. Defaulting to Ollama client"))
                            );
                            ollamaClient.runFromChat(botName, combinedContext, playerUUID);
                            break;
                    }

                }
                ChatContextManager.clearPendingClarification(playerUUID);
            }



        });

        // Listen to player send messages event with the same safeguards.
        ClientSendMessageEvents.CHAT.register((message) -> {
            Minecraft client = Minecraft.getInstance();

            System.out.println("Outgoing message: " + message);

            UUID playerUUID = net.minecraft.client.Minecraft.getInstance().player.getUUID();

            // Skip outgoing if it's a command
            if (message.startsWith("/")) {
                System.out.println("Outgoing command detected, skipping NLP...");
                return;
            }

            // If we’re waiting for clarification, don’t handle it here — let receive do it
            if (ChatContextManager.isAwaitingClarification(playerUUID)) {
                System.out.println("Awaiting clarification — skipping outgoing NLP trigger.");
                return;
            }

            // NEW: Prevent spoofing server messages
            if (isMessageFromServerContainsBotName(message)) {
                System.out.println("Outgoing message spoofing server with bot name detected — skipping NLP...");
                return;
            }


            if (isMessageFromBot(message)) {
                System.out.println("Bot's own message detected by prefix, skipping...");
                return;
            }

            // Normal mention-based flow
            String botName = getBotNameIfMentioned(message);
            System.out.println("Mentioned bot name: " + botName);
            if (botName != null) {

                switch (llmProvider) {
                    case "openai", "gpt", "google", "gemini", "anthropic", "claude", "xAI", "xai", "grok", "custom":
                        LLMClient llmClient = LLMClientFactory.createClient(llmProvider);

                        if (llmClient!=null) {
                            LLMServiceHandler.runFromChat(message, botName, playerUUID, llmClient);
                        }
                        else {
                            LOGGER.error("Error! Returned client is null! Cannot proceed!");
                            client.getToastManager().addToast(
                                    SystemToast.multiline(client, SystemToast.SystemToastId.CHUNK_LOAD_FAILURE, Component.nullToEmpty("LLM Client factory error."), Component.nullToEmpty("Error! Returned client is null! Cannot proceed!"))
                            );
                        }
                        break;

                    case "ollama":
                        ollamaClient.runFromChat(botName, message, playerUUID);
                        break;

                    default:
                        LOGGER.warn("Unsupported provider detected. Defaulting to Ollama client");
                        client.getToastManager().addToast(
                                SystemToast.multiline(client, SystemToast.SystemToastId.NARRATOR_TOGGLE, Component.nullToEmpty("Invalid LLM Client."), Component.nullToEmpty("Unsupported provider detected. Defaulting to Ollama client"))
                        );
                        ollamaClient.runFromChat(botName, message, playerUUID);
                        break;

                }

            }
        });




    }
}
