package com.ratelimitly;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Validated information decoded from a RateLimitly API key.
 *
 * @param hrp the Bech32 human-readable prefix identifying the credential family
 * @param authMethod the authentication method selected by the prefix
 * @param formatVersion the encoded API-key format version
 * @param keyId the unsigned 64-bit API-key identifier stored in a Java {@code long}
 * @param authSecret the authentication secret, defensively copied on input and access
 * @param quotas client and service limits carried by request credentials, or empty when absent
 */
public record ApiKeyInfo(
    String hrp,
    AuthMethod authMethod,
    int formatVersion,
    long keyId,
    byte[] authSecret,
    Optional<ApiKeyQuotas> quotas
) {
    /** Validates the decoded fields and takes defensive copies of mutable data. */
    public ApiKeyInfo {
        if (hrp == null || hrp.isBlank()) {
            throw new IllegalArgumentException("hrp must not be blank");
        }
        authMethod = Objects.requireNonNull(authMethod, "authMethod");
        authSecret = authSecret == null ? new byte[0] : authSecret.clone();
        quotas = quotas == null ? Optional.empty() : quotas;
    }

    /**
     * Returns a defensive copy of the authentication secret.
     *
     * @return a new byte array containing the secret, possibly empty
     */
    @Override
    public byte[] authSecret() {
        return authSecret.clone();
    }

    /**
     * Returns the limits encoded in this request credential.
     *
     * @return the encoded limits, or an empty value for credential families without quotas
     */
    @Override
    public Optional<ApiKeyQuotas> quotas() {
        return quotas;
    }

    @Override
    public String toString() {
        return "ApiKeyInfo{"
            + "hrp='" + hrp + '\''
            + ", authMethod=" + authMethod
            + ", keyId=" + keyId
            + ", authSecretLength=" + authSecret.length
            + ", quotas=" + quotas
            + '}';
    }

    /**
     * Compares candidate secret bytes using {@link MessageDigest#isEqual(byte[], byte[])}.
     *
     * @param other candidate secret bytes
     * @return {@code true} when the candidate is non-null and equal to this secret
     */
    public boolean secretEquals(byte[] other) {
        return other != null && MessageDigest.isEqual(authSecret, other);
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof ApiKeyInfo other)) {
            return false;
        }
        return formatVersion == other.formatVersion
            && keyId == other.keyId
            && hrp.equals(other.hrp)
            && authMethod == other.authMethod
            && MessageDigest.isEqual(authSecret, other.authSecret)
            && quotas.equals(other.quotas);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hrp, authMethod, formatVersion, keyId, Arrays.hashCode(authSecret), quotas);
    }

    /**
     * Returns the production discovery domain derived from this API-key ID.
     *
     * @return the domain under which the client queries the RateLimitly SRV record
     */
    public String defaultDnsName() {
        return "c-" + Long.toUnsignedString(keyId) + ".p0.ratelimitly.com";
    }
}
