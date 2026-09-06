package com.ratelimitly.internal;

import com.ratelimitly.AuthMethod;
import com.ratelimitly.ApiKeyInfo;
import com.ratelimitly.LatencyGuard;
import com.ratelimitly.LatencyReport;
import com.ratelimitly.RateLimitRequest;
import com.ratelimitly.RateLimitlyClientConfig;
import com.ratelimitly.RateLimitlyException;
import com.ratelimitly.ServiceLatencyReport;
import com.ratelimitly.ApiKeyQuotas;
import java.util.Optional;

public final class ProtocolValidation {
    private static final int MAX_PACKET_SIZE = 1200;
    private static final int API_KEY_TLV_SIZE = 40;
    private static final int AUTH_NONE_SIZE = 4;
    private static final int AUTH_COOKIE_SIZE = 36;
    private static final int AUTH_AES_SIZE = 32;
    private static final int PDU_HEADER_SIZE = 8;
    private static final int RATE_REQUEST_COUNTS_SIZE = 4;
    private static final int LATENCY_REPORT_COUNT_BLOCK_SIZE = 4;
    private static final int GUARD_BLOCK_SIZE = 36;
    private static final int RESOURCE_BLOCK_SIZE = 28;
    private static final int LATENCY_REPORT_BLOCK_SIZE = 32;

    private ProtocolValidation() {
    }

    public static void validateConfig(
        RateLimitlyClientConfig config,
        ApiKeyInfo credential
    ) throws RateLimitlyException {
        if (credential.authMethod() == AuthMethod.SECRET || credential.quotas().isEmpty()) {
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.CONFIGURATION,
                "A management key cannot authenticate client requests"
            );
        }
        Optional<ApiKeyQuotas> quotas = credential.quotas();
        if (quotas.isPresent()) {
            try {
                config.requestPolicy().horizonMs(quotas.get().dedupTtlMsMax());
            } catch (IllegalArgumentException error) {
                throw new RateLimitlyException(
                    RateLimitlyException.ErrorKind.CONFIGURATION,
                    error.getMessage(),
                    error
                );
            }
        }
    }

    public static void validateRateLimitRequest(
        RateLimitlyClientConfig config,
        ApiKeyInfo credential,
        RateLimitRequest request
    ) throws RateLimitlyException {
        enforceRateWindowQuota(credential.quotas(), request);

        long size = API_KEY_TLV_SIZE
            + authHeaderSize(credential.authMethod())
            + PDU_HEADER_SIZE
            + RATE_REQUEST_COUNTS_SIZE
            + ((long) request.guards().size() * GUARD_BLOCK_SIZE)
            + ((long) request.resources().size() * RESOURCE_BLOCK_SIZE)
            + metricsLabelSize(request.metricsLabel());

        if (size > MAX_PACKET_SIZE) {
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.REQUEST_TOO_LARGE,
                "Rate request would exceed the target packet size of " + MAX_PACKET_SIZE
                    + " bytes (estimated " + size + " bytes)"
            );
        }

    }

    public static void validateLatencyReport(
        RateLimitlyClientConfig config,
        ApiKeyInfo credential,
        LatencyReport report
    ) throws RateLimitlyException {
        long size = API_KEY_TLV_SIZE
            + authHeaderSize(credential.authMethod())
            + PDU_HEADER_SIZE
            + LATENCY_REPORT_COUNT_BLOCK_SIZE
            + ((long) report.reports().size() * LATENCY_REPORT_BLOCK_SIZE);

        if (size > MAX_PACKET_SIZE) {
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.REQUEST_TOO_LARGE,
                "Latency report would exceed the target packet size of " + MAX_PACKET_SIZE
                    + " bytes (estimated " + size + " bytes)"
            );
        }

    }

    private static int metricsLabelSize(String label) throws RateLimitlyException {
        if (label == null) {
            return 0;
        }
        return MetricsLabelTlv.encode(label).length;
    }

    private static int authHeaderSize(AuthMethod authMethod) {
        return switch (authMethod) {
            case NONE -> AUTH_NONE_SIZE;
            case COOKIE -> AUTH_COOKIE_SIZE;
            case AES -> AUTH_AES_SIZE;
            case SECRET -> AUTH_AES_SIZE;
        };
    }

    private static void enforceRateWindowQuota(Optional<ApiKeyQuotas> quotas, RateLimitRequest request)
        throws RateLimitlyException {
        if (quotas.isEmpty()) {
            return;
        }
        long limit = quotas.get().rateWindowSizeMsMax();
        for (var resource : request.resources()) {
            if (resource.windowSizeMs() > limit) {
                throw new RateLimitlyException(
                    RateLimitlyException.ErrorKind.CONFIGURATION,
                    "window_size_ms " + resource.windowSizeMs()
                        + " exceeds API-key quota rate_window_size_ms_max " + limit
                );
            }
        }
    }
}
