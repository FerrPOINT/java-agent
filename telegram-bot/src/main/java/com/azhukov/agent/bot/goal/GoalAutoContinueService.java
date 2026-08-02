package com.azhukov.agent.bot.goal;

import com.azhukov.agent.bot.commands.impl.GoalCommand;
import com.azhukov.agent.bot.config.BotProperties;
import com.azhukov.agent.bot.core.AgentBackendClient;
import com.azhukov.agent.bot.session.BotSessionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * P0: Auto-continuation for standing goals in the Telegram bot.
 *
 * After the agent replies to a user message, if a standing goal is active and not paused,
 * this service asks a lightweight judge model (via the backend) whether the goal is done.
 * If not, it runs additional agent turns until the goal appears complete or the max
 * number of continuation turns is reached.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GoalAutoContinueService {

    private static final String JUDGE_PROMPT_TEMPLATE = """
        [System note: Evaluate goal completion. Answer exactly YES or NO.]
        Standing goal: %s
        Subgoals:
        %s
        Assistant's last response:
        %s
        Question: Have the assistant's actions fully satisfied the standing goal and all subgoals?
        Answer only YES or NO.""";

    private static final String CONTINUE_PROMPT_TEMPLATE = """
        [System note: Continue autonomously toward the standing goal. Do not ask the user questions.]
        Standing goal: %s
        Subgoals:
        %s
        Your previous response:
        %s
        Continue taking the next concrete action toward the goal. Use tools when needed.""";

    private final AgentBackendClient backendClient;
    private final BotProperties properties;

    /**
     * Run auto-continuation for an active goal.
     *
     * @param session      bot session containing the standing goal
     * @param lastResponse the agent's response to the last user message
     * @return list of additional response texts produced by continuation turns
     */
    public List<String> runAutoContinue(BotSessionEntity session, String lastResponse) {
        List<String> continuationMessages = new ArrayList<>();
        String goal = GoalCommand.getActiveGoal(session);
        if (goal == null || goal.isBlank()) {
            return continuationMessages;
        }

        int maxContinuations = properties.getGoalAutoContinue().getMaxTurns();
        if (maxContinuations <= 0) {
            return continuationMessages;
        }

        String sessionId = session.getId() != null ? session.getId().toString() : null;
        String currentResponse = lastResponse != null ? lastResponse : "";

        for (int i = 0; i < maxContinuations; i++) {
            if (isGoalComplete(session, currentResponse)) {
                log.debug("Goal auto-continue: goal marked complete after {} turns", i);
                break;
            }

            String continuePrompt = buildContinuePrompt(session, currentResponse);
            AgentBackendClient.ChatResult result = backendClient.chat(continuePrompt, sessionId, session);
            if (result == null || result.content() == null || result.content().isBlank()) {
                log.warn("Goal auto-continue: empty result on turn {}", i + 1);
                break;
            }

            currentResponse = result.content();
            continuationMessages.add(currentResponse);

            if (looksLikeCompletion(currentResponse)) {
                log.debug("Goal auto-continue: completion signal detected after {} turns", i + 1);
                break;
            }
        }

        return continuationMessages;
    }

    private boolean isGoalComplete(BotSessionEntity session, String lastResponse) {
        String goal = GoalCommand.getActiveGoal(session);
        if (goal == null) return true;

        String judgePrompt = buildJudgePrompt(session, lastResponse);
        String sessionId = session.getId() != null ? session.getId().toString() : null;
        AgentBackendClient.ChatResult result = backendClient.chat(judgePrompt, sessionId, session);
        if (result == null || result.content() == null) {
            return false;
        }
        String answer = result.content().trim().toUpperCase();
        return answer.startsWith("YES");
    }

    private String buildJudgePrompt(BotSessionEntity session, String lastResponse) {
        String goal = GoalCommand.getActiveGoal(session);
        List<String> subgoals = GoalCommand.getSubgoals(session);
        String subgoalBlock = subgoals.isEmpty()
            ? "  (none)"
            : subgoals.stream().map(s -> "  - " + s).reduce((a, b) -> a + "\n" + b).orElse("  (none)");
        return String.format(JUDGE_PROMPT_TEMPLATE,
            goal,
            subgoalBlock,
            lastResponse != null ? lastResponse : "(no response yet)");
    }

    private String buildContinuePrompt(BotSessionEntity session, String lastResponse) {
        String goal = GoalCommand.getActiveGoal(session);
        List<String> subgoals = GoalCommand.getSubgoals(session);
        String subgoalBlock = subgoals.isEmpty()
            ? "  (none)"
            : subgoals.stream().map(s -> "  - " + s).reduce((a, b) -> a + "\n" + b).orElse("  (none)");
        return String.format(CONTINUE_PROMPT_TEMPLATE,
            goal,
            subgoalBlock,
            lastResponse != null ? lastResponse : "(no response yet)");
    }

    private boolean looksLikeCompletion(String text) {
        String lower = text.toLowerCase();
        return lower.contains("done") || lower.contains("completed") || lower.contains("finished");
    }
}
