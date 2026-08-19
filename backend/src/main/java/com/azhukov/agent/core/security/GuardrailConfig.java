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
    private int warnAfterIdempotentNoProgress = 3;
    private int hardStopAfterExactFailure = 6;
    private int hardStopAfterSameToolFailure = 5;
    private int hardStopAfterIdempotentNoProgress = 5;
}
