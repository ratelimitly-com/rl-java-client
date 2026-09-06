package com.ratelimitly;

import org.junit.jupiter.api.Test;

final class ApiKeyTest {
    @Test
    void decodesNoneApiKey() throws Exception {
        ClientTestScenarios.testDecodeNoneCredential();
    }

    @Test
    void decodesCookieAndAesApiKeys() throws Exception {
        ClientTestScenarios.testDecodeCookieAndAesCredentials();
    }

    @Test
    void protectsEncodedAndDecodedCredentialMaterial() throws Exception {
        ClientTestScenarios.testCredentialSafety();
    }

    @Test
    void rejectsManagementKeyForClientRequests() throws Exception {
        ClientTestScenarios.testRejectManagementKey();
    }

    @Test
    void rejectsLegacyApiKeyEncoding() throws Exception {
        ClientTestScenarios.testRejectLegacyCredential();
    }

    @Test
    void rejectsInvalidVersionAndQuotaBoundaries() throws Exception {
        ClientTestScenarios.testRejectInvalidApiKeyV1Boundaries();
    }
}
