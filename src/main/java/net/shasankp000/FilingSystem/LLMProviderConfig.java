package net.shasankp000.FilingSystem;

import org.slf4j.Logger;

import java.util.Locale;

public final class LLMProviderConfig {
    public static final String DEFAULT_PROVIDER = "custom";

    private LLMProviderConfig() {
    }

    public static String getConfiguredProvider() {
        return normalizeProvider(System.getProperty("aiplayer.llmMode", DEFAULT_PROVIDER));
    }

    public static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return DEFAULT_PROVIDER;
        }

        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "openai-compatible", "openai_compatible", "openai-compatible-endpoint", "compatible" -> "custom";
            case "google" -> "gemini";
            case "anthropic" -> "claude";
            case "xai" -> "grok";
            default -> normalized;
        };
    }

    public static boolean isServiceProvider(String provider) {
        return switch (normalizeProvider(provider)) {
            case "openai", "gpt", "gemini", "claude", "grok", "custom" -> true;
            default -> false;
        };
    }

    public static boolean isOllama(String provider) {
        return "ollama".equals(normalizeProvider(provider));
    }

    public static String warnAndDefault(Logger logger, String provider) {
        String normalized = normalizeProvider(provider);
        if (normalized.equals(provider)) {
            return normalized;
        }
        logger.warn("Unsupported or empty aiplayer.llmMode '{}'. Defaulting to OpenAI-compatible custom provider.", provider);
        return normalized;
    }
}
