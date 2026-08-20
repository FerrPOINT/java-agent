package com.azhukov.agent.client.langchain4j;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hermes parity tests: ErrorClassifier CONTEXT_OVERFLOW patterns
 * (error_classifier.py _CONTEXT_OVERFLOW_PATTERNS additions — GLM 1210,
 * Chinese provider messages, Ollama slot context, Together/Fireworks wording).
 */
class ErrorClassifierGlmPatternsTest {

    private final ErrorClassifier classifier = new ErrorClassifier();

    private ErrorClassifier.ErrorType classify(String message) {
        return classifier.classifyWithHints(new RuntimeException(message)).type();
    }

    @Test
    void zaiGlm1210EnglishForm() {
        assertEquals(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW,
            classify("Error code: 1210 - tokens in request more than max tokens allowed"));
    }

    @Test
    void chineseProviderMessages() {
        assertEquals(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW, classify("请求失败: 超过最大长度"));
        assertEquals(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW, classify("输入 上下文长度 超限"));
    }

    @Test
    void ollamaSlotContext() {
        assertEquals(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW,
            classify("slot context: 8192 tokens, prompt 9000 tokens"));
        assertEquals(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW, classify("n_ctx_slot exceeded"));
    }

    @Test
    void togetherFireworksWording() {
        assertEquals(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW,
            classify("Input length 131393 exceeds the maximum allowed input length of 131040 tokens."));
        assertEquals(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW,
            classify("max input token count exceeded"));
    }

    @Test
    void unrelatedErrorsNotContextOverflow() {
        assertNotEquals(ErrorClassifier.ErrorType.CONTEXT_OVERFLOW, classify("connection refused to upstream"));
    }
}
