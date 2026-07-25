package com.azhukov.agent.security;

public class GuardrailConfig {
    private boolean warningsEnabled = true;
    private boolean hardStopEnabled = true;
    private int warnAfterExactFailure = 3;
    private int warnAfterSameToolFailure = 3;
    private int warnAfterIdempotentNoProgress = 3;
    private int hardStopAfterExactFailure = 6;
    private int hardStopAfterSameToolFailure = 5;
    private int hardStopAfterIdempotentNoProgress = 5;

    public boolean isWarningsEnabled() { return warningsEnabled; }
    public void setWarningsEnabled(boolean warningsEnabled) { this.warningsEnabled = warningsEnabled; }
    public boolean isHardStopEnabled() { return hardStopEnabled; }
    public void setHardStopEnabled(boolean hardStopEnabled) { this.hardStopEnabled = hardStopEnabled; }
    public int getWarnAfterExactFailure() { return warnAfterExactFailure; }
    public void setWarnAfterExactFailure(int warnAfterExactFailure) { this.warnAfterExactFailure = warnAfterExactFailure; }
    public int getWarnAfterSameToolFailure() { return warnAfterSameToolFailure; }
    public void setWarnAfterSameToolFailure(int warnAfterSameToolFailure) { this.warnAfterSameToolFailure = warnAfterSameToolFailure; }
    public int getWarnAfterIdempotentNoProgress() { return warnAfterIdempotentNoProgress; }
    public void setWarnAfterIdempotentNoProgress(int warnAfterIdempotentNoProgress) { this.warnAfterIdempotentNoProgress = warnAfterIdempotentNoProgress; }
    public int getHardStopAfterExactFailure() { return hardStopAfterExactFailure; }
    public void setHardStopAfterExactFailure(int hardStopAfterExactFailure) { this.hardStopAfterExactFailure = hardStopAfterExactFailure; }
    public int getHardStopAfterSameToolFailure() { return hardStopAfterSameToolFailure; }
    public void setHardStopAfterSameToolFailure(int hardStopAfterSameToolFailure) { this.hardStopAfterSameToolFailure = hardStopAfterSameToolFailure; }
    public int getHardStopAfterIdempotentNoProgress() { return hardStopAfterIdempotentNoProgress; }
    public void setHardStopAfterIdempotentNoProgress(int hardStopAfterIdempotentNoProgress) { this.hardStopAfterIdempotentNoProgress = hardStopAfterIdempotentNoProgress; }
}
