package com.assessment.orchestrator.core.policy;

import com.assessment.orchestrator.core.model.StageId;

public class PolicyViolation {

    private final String policyName;
    private final StageId stage;
    private final String detail;

    public PolicyViolation(String policyName, StageId stage, String detail) {
        this.policyName = policyName;
        this.stage = stage;
        this.detail = detail;
    }

    public String getPolicyName() { return policyName; }
    public StageId getStage() { return stage; }
    public String getDetail() { return detail; }

    @Override
    public String toString() {
        return policyName + " @ " + stage + ": " + detail;
    }
}
