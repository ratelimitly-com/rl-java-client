package com.ratelimitly;

import org.junit.jupiter.api.Test;

final class ProtocolCodecTest {
    @Test
    void padsMetricsLabelTlv() throws Exception {
        ClientTestScenarios.testMetricsLabelTlvPadding();
    }

    @Test
    void matchesBlake2sKnownAnswer() {
        ClientTestScenarios.testBlake2sKnownVector();
    }

    @Test
    void matchesCanonicalStateIdKnownAnswers() {
        ClientTestScenarios.testCanonicalStateIdVectors();
    }

    @Test
    void encodesCanonicalIdsInRateRequest() throws Exception {
        ClientTestScenarios.testCanonicalStateIdsInRateRequest();
    }

    @Test
    void encodesThirtyTwoByteLatencyReportBlocks() throws Exception {
        ClientTestScenarios.testLatencyReportUses32ByteBlocks();
    }

    @Test
    void roundTripsNoneAuthenticatedResponse() throws Exception {
        ClientTestScenarios.testRateResponseRoundTripNoneAuth();
    }

    @Test
    void roundTripsGuardOnlyRequest() throws Exception {
        ClientTestScenarios.testGuardOnlyRequestRoundTrip();
    }

    @Test
    void roundTripsAesAuthenticatedResponse() throws Exception {
        ClientTestScenarios.testRateResponseRoundTripAesAuth();
    }
}
