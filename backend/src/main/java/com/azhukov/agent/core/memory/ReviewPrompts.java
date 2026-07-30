package com.azhukov.agent.core.memory;

/**
 * Review prompt templates for self-improvement background review.
 * Ported from Hermes' background_review.py with Java-specific adaptations.
 */
public final class ReviewPrompts {

    private ReviewPrompts() {}

    public static final String MEMORY_REVIEW_PROMPT = """
        Review the conversation above and consider saving to memory if appropriate.

        Focus on:
        1. Has the user revealed things about themselves — their persona, desires,
           preferences, or personal details worth remembering?
        2. Has the user expressed expectations about how you should behave, their work
           style, or ways they want you to operate?

        If something stands out, save it using the memory tool.
        If nothing is worth saving, just say 'Nothing to save.' and stop.

        Do NOT capture (these become persistent self-imposed constraints):
        - Environment-dependent failures: missing binaries, fresh-install errors,
          post-migration path mismatches, 'command not found', unconfigured credentials.
          The user can fix these — they are not durable rules.
        - Negative claims about tools or features ('X tool is broken', 'cannot use Y').
          These harden into refusals the agent cites against itself long after the
          actual problem was fixed.
        - Session-specific transient errors that resolved before the conversation ended.
          If retrying worked, the lesson is the retry pattern, not the original failure.
        - One-off task narratives. A user asking 'summarize today's market' or 'analyze
          this PR' is not a class of work that warrants a memory entry.
        """;

    public static final String SKILL_REVIEW_PROMPT = """
        Review the conversation above and update the skill library. Be
        ACTIVE — most sessions produce at least one skill update, even if
        small. A pass that does nothing is a missed learning opportunity,
        not a neutral outcome.

        Target shape of the library: CLASS-LEVEL skills, each with a rich
        SKILL.md and a references/ directory for session-specific detail.
        Not a long flat list of narrow one-session-one-skill entries. This
        shapes HOW you update, not WHETHER you update.

        Signals to look for (any one of these warrants action):
          - User corrected your style, tone, format, legibility, or verbosity.
            Frustration signals like 'stop doing X', 'this is too verbose',
            'don't format like this', 'just give me the answer' are FIRST-CLASS
            skill signals, not just memory signals. Update the relevant skill(s)
            to embed the preference so the next session starts already knowing.
          - User corrected your workflow, approach, or sequence of steps.
            Encode the correction as a pitfall or explicit step in the skill
            that governs that class of task.
          - Non-trivial technique, fix, workaround, debugging path, or tool-usage
            pattern emerged that a future session would benefit from. Capture it.
          - A skill that got loaded or consulted this session turned out to be
            wrong, missing a step, or outdated. Patch it NOW.

        Preference order — prefer the earliest action that fits, but do pick one:
          1. UPDATE A CURRENTLY-LOADED SKILL. Look back through the conversation
             for skills the user loaded or you read via skill_view. If any of them
             covers the territory of the new learning, PATCH that one first.
          2. UPDATE AN EXISTING UMBRELLA (via skills_list + skill_view). If no loaded
             skill fits but an existing class-level skill does, patch it. Add a
             subsection, a pitfall, or broaden a trigger.
          3. ADD A SUPPORT FILE under an existing umbrella. Skills can be packaged
             with support files — use the right directory per kind:
             - references/<topic>.md — session-specific detail and condensed knowledge
             - templates/<name>.<ext> — starter files meant to be copied and modified
             - scripts/<name>.<ext> — statically re-runnable actions
             Add support files via skill_manage action=write_file.
          4. CREATE A NEW CLASS-LEVEL UMBRELLA SKILL when no existing skill covers
             the class. The name MUST be at the class level. The name MUST NOT be a
             specific PR number, error string, feature codename, library-alone name,
             or 'fix-X / debug-Y' session artifact.

        Protected skills (DO NOT edit these):
          - Bundled/built-in skills (shipped with the agent).
          - Hub-installed skills (installed via skills hub).
        If the only skills that need updating are protected, say
        'Nothing to save.' and stop.

        Do NOT capture as skills (these become persistent self-imposed constraints):
          - Environment-dependent failures: missing binaries, fresh-install errors,
            'command not found', unconfigured credentials, uninstalled packages.
          - Negative claims about tools or features ('browser tools do not work',
            'X tool is broken', 'cannot use Y from execute_code').
          - Session-specific transient errors that resolved before the conversation ended.
          - One-off task narratives.

        If a tool failed because of setup state, capture the FIX (install command,
        config step, env var to set) under an existing setup or troubleshooting skill —
        never 'this tool does not work' as a standalone constraint.

        'Nothing to save.' is a real option but should NOT be the default. If the
        session ran smoothly with no corrections and produced no new technique, just
        say 'Nothing to save.' and stop. Otherwise, act.

        You can only call memory and skill management tools. Other tools will be
        denied at runtime — do not attempt them.
        """;

    public static final String COMBINED_REVIEW_PROMPT = """
        Review the conversation above and update two things:

        **Memory**: who the user is. Did the user reveal persona, desires, preferences,
        personal details, or expectations about how you should behave? Save facts about
        the user and durable preferences with the memory tool.

        **Skills**: how to do this class of task. Be ACTIVE — most sessions produce at
        least one skill update. A pass that does nothing is a missed learning opportunity,
        not a neutral outcome.

        Target shape of the skill library: CLASS-LEVEL skills with a rich SKILL.md and
        a references/ directory for session-specific detail. Not a long flat list of
        narrow one-session-one-skill entries.

        Signals that warrant a skill update (any one is enough):
          - User corrected your style, tone, format, legibility, verbosity, or approach.
            Frustration is a FIRST-CLASS skill signal, not just a memory signal. 'stop
            doing X', 'don't format like this', 'I hate when you Y' — embed the lesson
            in the skill that governs that task so the next session starts fixed.
          - Non-trivial technique, fix, workaround, or debugging path emerged.
          - A skill that was loaded or consulted turned out wrong, missing, or outdated
            — patch it now.

        Preference order for skills — pick the earliest that fits:
          1. UPDATE A CURRENTLY-LOADED SKILL. Check what skills were loaded or read via
             skill_view in the conversation. If one covers the learning, PATCH it first.
          2. UPDATE AN EXISTING UMBRELLA (skills_list + skill_view to find the right one).
          3. ADD A SUPPORT FILE under an existing umbrella via skill_manage action=write_file.
             Three kinds: references/<topic>.md for session-specific detail OR condensed
             knowledge banks; templates/<name>.<ext> for starter files; scripts/<name>.<ext>
             for statically re-runnable actions. Add a one-line pointer in SKILL.md.
          4. CREATE A NEW CLASS-LEVEL UMBRELLA when nothing exists. Name at the class level
             — NOT a PR number, error string, codename, library-alone name, or 'fix-X'
             session artifact. If the name only fits today's task, fall back to (1), (2), (3).

        User-preference embedding: when the user complains about how you handled a task,
        update the skill that governs that task — memory alone isn't enough. Memory says
        'who the user is'; skills say 'how to do this class of task for this user'.

        If you notice overlapping existing skills, mention it — the background curator
        handles consolidation.

        Protected skills (DO NOT edit these):
          - Bundled/built-in skills (shipped with the agent).
          - Hub-installed skills (installed via skills hub).
        If the only skills that need updating are protected, say
        'Nothing to save.' and stop.

        Do NOT capture as skills (these become persistent self-imposed constraints):
          - Environment-dependent failures: missing binaries, fresh-install errors,
            'command not found', unconfigured credentials, uninstalled packages.
          - Negative claims about tools or features ('browser tools do not work',
            'X tool is broken', 'cannot use Y from execute_code').
          - Session-specific transient errors that resolved before the conversation ended.
          - One-off task narratives.

        If a tool failed because of setup state, capture the FIX (install command, config
        step, env var to set) under an existing setup or troubleshooting skill — never
        'this tool does not work' as a standalone constraint.

        Act on whichever of the two dimensions has real signal. If genuinely nothing stands
        out on either, say 'Nothing to save.' and stop — but don't reach for that conclusion
        as a default.

        You can only call memory and skill management tools. Other tools will be denied at
        runtime — do not attempt them.
        """;

    /**
     * System prompt for the review agent — explains the whitelist and constraints.
     */
    public static final String REVIEW_SYSTEM_PROMPT = """
        You are a background self-improvement review agent. Your job is to analyze
        the conversation and decide what should be saved to memory or skills.

        RULES:
        - You can ONLY use these tools: memory, skill_manage, skills_list, skill_view
        - Other tools are denied — do not attempt them
        - Be active: most conversations produce at least one update
        - 'Nothing to save.' is valid but should not be your default
        - Do not save environment-dependent failures or transient errors
        - Do not modify protected/built-in skills
        """;
}