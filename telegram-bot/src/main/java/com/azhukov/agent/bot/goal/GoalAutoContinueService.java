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
import java.util.function.Supplier;

/**
 * P0: Auto-continuation for standing goals in the Telegram bot.
 *
 * <p>After the agent replies to a user message, if a standing goal is active and not paused,
 * this service asks a judge model (via the backend) whether the goal is done.
 * If not, it runs additional agent turns until the goal appears complete or the max
 * number of continuation turns is reached.
 *
 * <p>The judge call uses a null sessionId so it does not pollute the conversation history.
 * The continuation call uses the real sessionId so the model has full context.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GoalAutoContinueService {

    private static final int MAX_RESPONSE_TRUNCATION = 4000;

    private static final String JUDGE_PROMPT_TEMPLATE = """
        You are a goal completion evaluator. Determine whether the assistant's
        last response fully satisfies the standing goal and all subgoals.

        Standing goal: %s
        Subgoals:
        %s

        Assistant's last response:
        %s

        Evaluate whether the response demonstrates concrete completion of the goal and ALL subgoals.
        Answer with exactly one word: YES or NO.
        - YES: The goal and all subgoals are demonstrably complete.
        - NO: The goal is not yet complete, or some subgoals remain unaddressed.""";

    private static final String CONTINUE_PROMPT_TEMPLATE = """
        [System note: Continue autonomously toward the standing goal. Do not ask the user questions.
        Do not repeat your previous response. Take the next concrete action. Use tools when needed.]
        Standing goal: %s
        Subgoals:
        %s
        Your previous response:
        %s
        Continue taking the next concrete action toward the goal.""";

    private final AgentBackendClient backendClient;
    private final BotProperties properties;

    /**
     * Run auto-continuation for an active goal.
     *
     * @param session       bot session containing the standing goal
     * @param lastResponse  the agent's response to the last user message
     * @param interruptChecker  supplier that returns true if the user has interrupted the session
     * @return list of additional response texts produced by continuation turns
     */
    public List<String> runAutoContinue(BotSessionEntity session,
                                        String lastResponse,
                                        Supplier<Boolean> interruptChecker) {
        List<String> continuationMessages = new ArrayList<>();

        if (!properties.getGoalAutoContinue().isEnabled()) {
            return continuationMessages;
        }

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
        List<String> subgoals = GoalCommand.getSubgoals(session);

        for (int i = 0; i < maxContinuations; i++) {
            if (interruptChecker.get()) {
                log.debug("Goal auto-continue: interrupted by user after {} turns", i);
                break;
            }

            if (isGoalComplete(goal, subgoals, currentResponse)) {
                log.debug("Goal auto-continue: goal marked complete after {} turns", i);
                break;
            }

            String continuePrompt = buildContinuePrompt(goal, subgoals, currentResponse);
            AgentBackendClient.ChatResult result = backendClient.chat(continuePrompt, sessionId, session);
            if (isErrorResponse(result)) {
                log.warn("Goal auto-continue: backend error on turn {}, stopping", i + 1);
                break;
            }
            if (result == null || result.content() == null || result.content().isBlank()) {
                log.warn("Goal auto-continue: empty result on turn {}", i + 1);
                break;
            }

            currentResponse = result.content();
            continuationMessages.add(currentResponse);
            // Completion is evaluated by the judge at the top of the next iteration.
            // No substring heuristic — it produces false positives ("I'm not done yet").
        }

        return continuationMessages;
    }

    /** Convenience overload without interrupt checking. */
    public List<String> runAutoContinue(BotSessionEntity session, String lastResponse) {
        return runAutoContinue(session, lastResponse, () -> false);
    }

    private boolean isGoalComplete(String goal, List<String> subgoals, String lastResponse) {
        if (goal == null) return true;

        String judgePrompt = buildJudgePrompt(goal, subgoals, lastResponse);
        // Judge call uses null sessionId so it doesn't pollute conversation history
        AgentBackendClient.ChatResult result = backendClient.chat(judgePrompt, null, null);
        if (isErrorResponse(result)) {
            log.warn("Goal auto-continue: judge call failed, assuming not complete");
            return false;
        }
        if (result == null || result.content() == null) {
            return false;
        }
        String answer = result.content().trim().toUpperCase();
        return answer.startsWith("YES");
    }

    private boolean isErrorResponse(AgentBackendClient.ChatResult result) {
        return result != null
            && result.content() != null
            && result.content().startsWith("Error:");
    }

    private String buildJudgePrompt(String goal, List<String> subgoals, String lastResponse) {
        String subgoalBlock = formatSubgoals(subgoals);
        String truncatedResponse = truncate(lastResponse != null ? lastResponse : "(no response yet)");
        return String.format(JUDGE_PROMPT_TEMPLATE, goal, subgoalBlock, truncatedResponse);
    }

    private String buildContinuePrompt(String goal, List<String> subgoals, String lastResponse) {
        String subgoalBlock = formatSubgoals(subgoals);
        String truncatedResponse = truncate(lastResponse != null ? lastResponse : "(no response yet)");
        return String.format(CONTINUE_PROMPT_TEMPLATE, goal, subgoalBlock, truncatedResponse);
    }

    private String formatSubgoals(List<String> subgoals) {
        if (subgoals == null || subgoals.isEmpty()) {
            return "  (none)";
        }
        StringBuilder sb = new StringBuilder();
        for (String s : subgoals) {
            sb.append("  - ").append(s).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private String truncate(String text) {
        if (text == null) return "(no response yet)";
        return text.length() <= MAX_RESPONSE_TRUNCATION
            ? text
            : text.substring(0, MAX_RESPONSE_TRUNCATION) + "…[truncated]";
    }
}