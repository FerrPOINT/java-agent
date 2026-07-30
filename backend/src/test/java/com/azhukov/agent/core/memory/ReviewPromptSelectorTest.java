package com.azhukov.agent.core.memory;

import com.azhukov.agent.core.model.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewPromptSelectorTest {

    @Test
    void selectReviewType_memorySignals_returnsMemoryOnly() {
        List<Message> messages = List.of(
            Message.user("I like Java and I prefer dark themes"),
            Message.assistant("Great!", 0)
        );
        assertThat(ReviewPromptSelector.selectReviewType(messages))
            .isEqualTo(ReviewPromptSelector.ReviewType.MEMORY_ONLY);
    }

    @Test
    void selectPrompt_memorySignals_returnsMemoryPrompt() {
        List<Message> messages = List.of(
            Message.user("I prefer concise answers"),
            Message.assistant("Understood.", 0)
        );
        String prompt = ReviewPromptSelector.selectPrompt(messages);
        assertThat(prompt).isEqualTo(ReviewPrompts.MEMORY_REVIEW_PROMPT);
    }

    @Test
    void selectReviewType_skillSignals_returnsSkillOnly() {
        List<Message> messages = List.of(
            Message.user("Can you debug this?"),
            Message.assistant("Let me try a workaround technique.", 0)
        );
        assertThat(ReviewPromptSelector.selectReviewType(messages))
            .isEqualTo(ReviewPromptSelector.ReviewType.SKILL_ONLY);
    }

    @Test
    void selectPrompt_skillSignals_returnsSkillPrompt() {
        List<Message> messages = List.of(
            Message.user("stop doing that, this is too verbose"),
            Message.assistant("Sorry about that.", 0)
        );
        String prompt = ReviewPromptSelector.selectPrompt(messages);
        assertThat(prompt).isEqualTo(ReviewPrompts.SKILL_REVIEW_PROMPT);
    }

    @Test
    void selectReviewType_bothSignals_returnsCombined() {
        List<Message> messages = List.of(
            Message.user("I like Java and I prefer concise answers"),
            Message.assistant("Let me try a workaround for this debugging approach.", 0)
        );
        assertThat(ReviewPromptSelector.selectReviewType(messages))
            .isEqualTo(ReviewPromptSelector.ReviewType.COMBINED);
    }

    @Test
    void selectPrompt_bothSignals_returnsCombinedPrompt() {
        List<Message> messages = List.of(
            Message.user("I prefer dark themes, remember that"),
            Message.assistant("This debugging workflow approach should work.", 0)
        );
        String prompt = ReviewPromptSelector.selectPrompt(messages);
        assertThat(prompt).isEqualTo(ReviewPrompts.COMBINED_REVIEW_PROMPT);
    }

    @Test
    void selectPrompt_noSignals_defaultsToCombined() {
        List<Message> messages = List.of(
            Message.user("hello"),
            Message.assistant("hi there", 0)
        );
        String prompt = ReviewPromptSelector.selectPrompt(messages);
        assertThat(prompt).isEqualTo(ReviewPrompts.COMBINED_REVIEW_PROMPT);
    }

    @Test
    void selectPrompt_nullMessages_defaultsToCombined() {
        String prompt = ReviewPromptSelector.selectPrompt(null);
        assertThat(prompt).isEqualTo(ReviewPrompts.COMBINED_REVIEW_PROMPT);
    }

    @Test
    void selectPrompt_emptyMessages_defaultsToCombined() {
        String prompt = ReviewPromptSelector.selectPrompt(List.of());
        assertThat(prompt).isEqualTo(ReviewPrompts.COMBINED_REVIEW_PROMPT);
    }

    @Test
    void selectPrompt_memorySignalsOnlyInAssistant_doesNotTriggerMemory() {
        // Memory signals should only be checked in user messages
        List<Message> messages = List.of(
            Message.assistant("I prefer dark themes and I like Java", 0)
        );
        // No user message with memory signals, but assistant has them — should not trigger memory
        // It might trigger skill if the assistant mentions a skill signal, but "prefer" alone
        // is a memory signal, not a skill signal
        // Actually "I prefer" is a memory signal, but it's in an assistant message, not user
        ReviewPromptSelector.ReviewType type = ReviewPromptSelector.selectReviewType(messages);
        // Should not be MEMORY_ONLY since memory signals are only checked in user messages
        assertThat(type).isNotEqualTo(ReviewPromptSelector.ReviewType.MEMORY_ONLY);
    }

    @Test
    void selectPrompt_skillViewMention_triggersSkill() {
        List<Message> messages = List.of(
            Message.user("Can you use skill_view to check the documentation?"),
            Message.assistant("Sure.", 0)
        );
        assertThat(ReviewPromptSelector.selectReviewType(messages))
            .isEqualTo(ReviewPromptSelector.ReviewType.SKILL_ONLY);
    }
}