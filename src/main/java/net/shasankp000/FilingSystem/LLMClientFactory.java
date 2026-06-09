    package net.shasankp000.FilingSystem;

    import net.shasankp000.AIPlayer;
    import net.shasankp000.ServiceLLMClients.*;
    import org.slf4j.Logger;
    import org.slf4j.LoggerFactory;

    public class LLMClientFactory {

        private static final Logger LOGGER = LoggerFactory.getLogger("llm-client-factory");

        public static LLMClient createClient(String mode) {
            return switch (LLMProviderConfig.normalizeProvider(mode)) {
                case "openai", "gpt" -> {
                    if (AIPlayer.CONFIG.getOpenAIKey().isEmpty()) {
                        LOGGER.error("OpenAI API key not set in config!");
                        yield null;
                    }
                    yield new OpenAIClient(AIPlayer.CONFIG.getOpenAIKey(), AIPlayer.CONFIG.getSelectedLanguageModel());
                }
                case "claude" -> {
                    if (AIPlayer.CONFIG.getClaudeKey().isEmpty()) {
                        LOGGER.error("Claude API key not set in config!");
                        yield null;
                    }
                    yield new AnthropicClient(AIPlayer.CONFIG.getClaudeKey(), AIPlayer.CONFIG.getSelectedLanguageModel());
                }
                case "gemini" -> {
                    if (AIPlayer.CONFIG.getGeminiKey().isEmpty()) {
                        LOGGER.error("Gemini API key not set in config!");
                        yield null;
                    }
                    yield new GeminiClient(AIPlayer.CONFIG.getGeminiKey(), AIPlayer.CONFIG.getSelectedLanguageModel());
                }
                case "grok" -> {
                    if (AIPlayer.CONFIG.getGrokKey().isEmpty()) {
                        LOGGER.error("Grok API key not set in config!");
                        yield null;
                    }
                    yield new GrokClient(AIPlayer.CONFIG.getGrokKey(), AIPlayer.CONFIG.getSelectedLanguageModel());
                }
                case "custom" -> {
                    if (AIPlayer.CONFIG.getCustomApiUrl().isEmpty()) {
                        LOGGER.error("Custom API URL not set in config!");
                        yield null;
                    }
                    yield new GenericOpenAIClient(AIPlayer.CONFIG.getCustomApiKey(), AIPlayer.CONFIG.getSelectedLanguageModel(), AIPlayer.CONFIG.getCustomApiUrl());
                }
                default -> {
                    LOGGER.info("No service LLM client for mode {}; use ollama explicitly for the local Ollama client.", mode);
                    yield null;
                }
            };
        }

        public static LLMClient createConfiguredClient() {
            return createClient(LLMProviderConfig.getConfiguredProvider());
        }
    }
