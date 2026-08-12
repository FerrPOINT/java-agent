package com.azhukov.agent.core.budget;

import com.azhukov.agent.config.AgentProperties;

public class AgentPropertiesStub extends AgentProperties {

    private final BudgetProperties budget;

    public AgentPropertiesStub() {
        this.budget = new BudgetProperties();
        this.budget.setEnabled(true);
        this.budget.setMaxModelCallsPerTurn(5);
        this.budget.setMaxToolExecutionsPerTurn(200);
        this.budget.setMaxTokensPerTurn(200000);
        this.budget.setMaxToolDurationMsPerTurn(600000);
    }

    public void setBudgetEnabled(boolean enabled) {
        this.budget.setEnabled(enabled);
    }

    @Override
    public BudgetProperties getBudget() {
        return budget;
    }
}
