package com.ratelimitly;

import org.junit.jupiter.api.Test;

final class RequestPolicyTest {
    @Test
    void enforcesDeduplicationTtlQuota() throws Exception {
        ClientTestScenarios.testDedupQuotaEnforcement();
    }

    @Test
    void enforcesRateWindowQuota() throws Exception {
        ClientTestScenarios.testRateWindowQuotaEnforcement();
    }

    @Test
    void computesUnifiedPolicySchedules() {
        ClientTestScenarios.testUnifiedPolicySchedules();
    }
}
