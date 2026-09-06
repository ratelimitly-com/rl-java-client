package com.ratelimitly;

import com.ratelimitly.internal.Bech32;
import java.util.Optional;

/** Decodes and validates RateLimitly API keys without contacting a server. */
public final class ApiKeyDecoder {
    private static final int FORMAT_VERSION = 1;

    private ApiKeyDecoder() {
    }

    /**
     * Decodes an API key and returns its authentication and quota information.
     *
     * <p>The returned secret is protected by defensive copies. Rendering the result does not
     * expose the encoded key or secret bytes.</p>
     *
     * @param encoded the complete Bech32 API key
     * @return decoded API-key information
     * @throws RateLimitlyException if the key is malformed, has an unsupported family or version,
     *     or contains invalid quota data
     */
    public static ApiKeyInfo decode(String encoded) throws RateLimitlyException {
        try {
            Bech32.Decoded bech32 = Bech32.decode(encoded);
            String hrp = bech32.hrp();
            if (!hrp.startsWith("rl-")) {
                throw new RateLimitlyException(
                    RateLimitlyException.ErrorKind.CONFIGURATION,
                    "API-key HRP must start with rl-"
                );
            }

            String authMethodName = hrp.substring(3);
            AuthMethod authMethod = AuthMethod.fromWireName(authMethodName);
            byte[] payload = bech32.payload();

            return switch (authMethod) {
                case SECRET -> decodeSecret(hrp, authMethod, payload);
                case NONE -> decodeNone(hrp, authMethod, payload);
                case COOKIE, AES -> decodeApiKeyWithSecret(hrp, authMethod, payload);
            };
        } catch (RateLimitlyException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.CONFIGURATION,
                "Failed to decode Bech32 API key",
                e
            );
        }
    }

    private static ApiKeyInfo decodeSecret(
        String hrp,
        AuthMethod authMethod,
        byte[] payload
    ) throws RateLimitlyException {
        if (payload.length != 32) {
            throw invalidLength(authMethod, 32, payload.length);
        }
        return new ApiKeyInfo(hrp, authMethod, 0, 0, payload, Optional.empty());
    }

    private static ApiKeyInfo decodeNone(
        String hrp,
        AuthMethod authMethod,
        byte[] payload
    ) throws RateLimitlyException {
        if (payload.length != 13) {
            throw invalidLength(authMethod, 13, payload.length);
        }
        requireFormatVersion(payload);
        long keyId = readLe64(payload, 1);
        ApiKeyQuotas quotas = quotasFromPayload(payload, 9);
        return new ApiKeyInfo(hrp, authMethod, FORMAT_VERSION, keyId, new byte[0], Optional.of(quotas));
    }

    private static ApiKeyInfo decodeApiKeyWithSecret(
        String hrp,
        AuthMethod authMethod,
        byte[] payload
    ) throws RateLimitlyException {
        if (payload.length != 45) {
            throw invalidLength(authMethod, 45, payload.length);
        }
        requireFormatVersion(payload);
        long keyId = readLe64(payload, 1);
        byte[] secret = new byte[32];
        System.arraycopy(payload, 9, secret, 0, 32);
        ApiKeyQuotas quotas = quotasFromPayload(payload, 41);
        return new ApiKeyInfo(hrp, authMethod, FORMAT_VERSION, keyId, secret, Optional.of(quotas));
    }

    private static ApiKeyQuotas quotasFromPayload(byte[] payload, int offset) throws RateLimitlyException {
        long word = readLe32(payload, offset);
        int rateExp = (int) (word & 0x1F);
        int latencyExp = (int) ((word >>> 5) & 0x1F);
        int labelsExp = (int) ((word >>> 10) & 0x1F);
        int bufferExp = (int) ((word >>> 15) & 0x0F);
        int dedupUnits = (int) ((word >>> 19) & 0xFF);
        int windowExp = (int) ((word >>> 27) & 0x1F);
        if (rateExp > 24 || latencyExp > 24 || dedupUnits < 1 || dedupUnits > 200) {
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.CONFIGURATION,
                "Invalid packed API-key quota word"
            );
        }
        return new ApiKeyQuotas(
            1L << rateExp,
            1L << latencyExp,
            1L << labelsExp,
            1L << bufferExp,
            dedupUnits * 10L,
            windowExp == 31 ? 0xFFFF_FFFFL : 1L << windowExp
        );
    }

    private static void requireFormatVersion(byte[] payload) throws RateLimitlyException {
        int version = Byte.toUnsignedInt(payload[0]);
        if (version != FORMAT_VERSION) {
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.CONFIGURATION,
                "Unsupported API-key format version " + version
            );
        }
    }

    private static long readLe32(byte[] payload, int offset) {
        return ((long) payload[offset] & 0xFF)
            | (((long) payload[offset + 1] & 0xFF) << 8)
            | (((long) payload[offset + 2] & 0xFF) << 16)
            | (((long) payload[offset + 3] & 0xFF) << 24);
    }

    private static long readLe64(byte[] payload, int offset) {
        return ((long) payload[offset] & 0xFF)
            | (((long) payload[offset + 1] & 0xFF) << 8)
            | (((long) payload[offset + 2] & 0xFF) << 16)
            | (((long) payload[offset + 3] & 0xFF) << 24)
            | (((long) payload[offset + 4] & 0xFF) << 32)
            | (((long) payload[offset + 5] & 0xFF) << 40)
            | (((long) payload[offset + 6] & 0xFF) << 48)
            | (((long) payload[offset + 7] & 0xFF) << 56);
    }

    private static RateLimitlyException invalidLength(AuthMethod authMethod, int expected, int actual) {
        return new RateLimitlyException(
            RateLimitlyException.ErrorKind.CONFIGURATION,
            "Invalid payload length for " + authMethod.wireName() + " API key: expected "
                + expected + " bytes, got " + actual
        );
    }
}
