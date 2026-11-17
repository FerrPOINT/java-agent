package com.azhukov.agent.core.security;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GuardrailConfig {
    private boolean warningsEnabled = true;
    private boolean hardStopEnabled = true;
    private int warnAfterExactFailure = 3;
    private int warnAfterSameToolFailure = 3;
    private int warnAfterIdempotentNoProgress = 2;
    private int hardStopAfterExactFailure = 6;
    private int maxWebSearches = 50;
    private int maxSubagents = 50;
}
