package com.ratelimitly;

import org.junit.jupiter.api.Test;

final class NetworkPolicyTest {
    @Test
    void exchangesRequestWithInjectedUdpServer() throws Exception {
        ClientTestScenarios.testMockUdpExchangeWithInjectedResolver();
    }

    @Test
    void promptOldestServerResponseWins() throws Exception {
        ClientTestScenarios.testHaSelectsPromptOldestResponse();
    }

    @Test
    void unreachableAddressDoesNotHideReachableReplica() throws Exception {
        ClientTestScenarios.testUnreachableEndpointDoesNotAbortRequest();
    }

    @Test
    void steeringCursorAdvancesAndWrapsMonotonically() {
        ClientTestScenarios.testSteeringCursorIncrementsAndWraps();
    }

    @Test
    void steeringSkipsOccupiedPort() throws Exception {
        ClientTestScenarios.testSteeringSkipsOccupiedCandidate();
    }

    @Test
    void steeringWaitsForConcurrentRequestsToDrain() throws Exception {
        ClientTestScenarios.testSteeringDrainsConcurrentRequests();
    }
}
