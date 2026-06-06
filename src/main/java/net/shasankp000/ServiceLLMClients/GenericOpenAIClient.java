package net.shasankp000.ServiceLLMClients;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

/**
 * Generic OpenAI-compatible client that supports custom API base URLs.
 * This allows using alternative providers like OpenRouter that follow the OpenAI API standard.
 */
public class GenericOpenAIClient implements LLMClient {
    private static final int MAX_OUTPUT_TOKENS = 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
    private final String apiKey;
    private final String modelName;
    private final String baseUrl;
    private final HttpClient client;
    public static final Logger LOGGER = LoggerFactory.getLogger("GenericOpenAI-Client");

    public GenericOpenAIClient(String apiKey, String modelName, String baseUrl) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        // Ensure baseUrl ends with "/" but doesn't have double slashes
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Base URL cannot be null or empty");
        }
        this.baseUrl = normalizeApiBaseUrl(baseUrl);
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    private static String normalizeApiBaseUrl(String rawBaseUrl) {
        String normalized = rawBaseUrl.trim().replaceAll("/+$", "");
        if (normalized.endsWith("/chat/completions")) {
            normalized = normalized.substring(0, normalized.length() - "/chat/completions".length());
        } else if (normalized.endsWith("/completions")) {
            normalized = normalized.substring(0, normalized.length() - "/completions".length());
        } else if (normalized.endsWith("/chat")) {
            normalized = normalized.substring(0, normalized.length() - "/chat".length());
        }

        if (normalized.equals("https://openrouter.ai")) {
            normalized = "https://openrouter.ai/api/v1";
        }

        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    @Override
    public String sendPrompt(String systemPrompt, String userPrompt) {
        try {
            // Construct the request body for chat completions
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("model", this.modelName);

            JsonArray messages = new JsonArray();

            // 1. Create the system message object and add it to the array
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", "system");
            systemMessage.addProperty("content", systemPrompt);
            messages.add(systemMessage);

            // 2. Create the user message object and add it to the array
            JsonObject userMessage = new JsonObject();
            userMessage.addProperty("role", "user");
            userMessage.addProperty("content", userPrompt);
            messages.add(userMessage);

            requestBody.add("messages", messages);
            requestBody.addProperty("max_tokens", MAX_OUTPUT_TOKENS);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(REQUEST_TIMEOUT)
                    .POST(BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            LOGGER.info("Custom chat completion response: HTTP {} from {}", response.statusCode(), request.uri());

            // Handle HTTP error codes
            if (response.statusCode() != 200) {
                return "Error: " + response.statusCode() + " - " + response.body();
            }

            // Parse the JSON response
            JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();

            // Extract the content from the chat message
            return jsonResponse.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();

        } catch (Exception e) {
            LOGGER.error("Error occurred while sending prompt", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Checks if the API is reachable and the key is valid by making a
     * quick, lightweight request to the models endpoint.
     *
     * @return true if the API returns a 200 status code, false otherwise.
     */
    @Override
    public boolean isReachable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "models"))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            LOGGER.info("Custom models response: HTTP {} from {}", response.statusCode(), request.uri());
            return response.statusCode() == 200;
        } catch (Exception e) {
            LOGGER.error("Custom API reachability check failed: {}", e.getMessage(), e);
            return false;
        }
    }

    @Override
    public String getProvider() {
        return "Generic OpenAI Compatible";
    }
}
