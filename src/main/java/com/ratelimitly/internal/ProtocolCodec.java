package com.ratelimitly.internal;

import com.ratelimitly.AuthMethod;
import com.ratelimitly.CanonicalIds;
import com.ratelimitly.ApiKeyInfo;
import com.ratelimitly.GuardDecision;
import com.ratelimitly.LatencyGuard;
import com.ratelimitly.LatencyReport;
import com.ratelimitly.RateLimitDecision;
import com.ratelimitly.RateLimitRequest;
import com.ratelimitly.RateLimitlyClientConfig;
import com.ratelimitly.RateLimitlyException;
import com.ratelimitly.ResourceDecision;
import com.ratelimitly.ResourceRequest;
import com.ratelimitly.ServiceLatencyReport;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class ProtocolCodec {
    private static final int TLV_API_KEY = 0x4C52;
    private static final int TLV_AUTH_NONE = 0x414E;
    private static final int TLV_AUTH_COOKIE = 0x4143;
    private static final int TLV_AUTH_AES = 0x4541;
    private static final int PDU_RATE_REQUEST = 0x5452;
    private static final int PDU_RATE_RESPONSE = 0x5252;
    private static final int PDU_LATENCY_REPORT = 0x524C;
    private static final int API_KEY_TLV_SIZE = 40;
    private static final int PDU_HEADER_SIZE = 8;
    private static final int GUARD_BLOCK_SIZE = 36;
    private static final int RESOURCE_BLOCK_SIZE = 28;
    private static final int LATENCY_REPORT_BLOCK_SIZE = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ThreadLocal<Cipher> AES_GCM_ENCRYPT_CIPHER =
        ThreadLocal.withInitial(() -> initCipher("AES/GCM/NoPadding"));
    private static final ThreadLocal<Cipher> AES_GCM_DECRYPT_CIPHER =
        ThreadLocal.withInitial(() -> initCipher("AES/GCM/NoPadding"));

    private ProtocolCodec() {
    }

    public static EncodedRequest encodeRateLimitRequest(
        RateLimitlyClientConfig config,
        ApiKeyInfo credential,
        RateLimitRequest request,
        UUID requestId
    ) throws RateLimitlyException {
        byte[] body = buildRateRequestBody(request);
        long dedupLimit = credential.quotas()
            .map(quotas -> quotas.dedupTtlMsMax())
            .orElse(0xFFFF_FFFFL);
        final long horizon;
        try {
            horizon = config.requestPolicy().horizonMs(dedupLimit);
        } catch (IllegalArgumentException error) {
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.CONFIGURATION,
                error.getMessage(),
                error
            );
        }
        byte[] pdu = buildRateRequestPdu((int) horizon, body);
        return encodePacket(config, credential, requestId, pdu);
    }

    public static EncodedRequest encodeLatencyReport(
        RateLimitlyClientConfig config,
        ApiKeyInfo credential,
        LatencyReport report,
        UUID requestId
    ) throws RateLimitlyException {
        byte[] body = buildLatencyReportBody(report);
        byte[] pdu = buildSimplePdu(PDU_LATENCY_REPORT, body);
        return encodePacket(config, credential, requestId, pdu);
    }

    public static RateLimitDecision parseRateResponse(
        byte[] packet,
        ApiKeyInfo credential,
        RateLimitRequest request,
        byte[] expectedRequestId
    ) throws RateLimitlyException {
        if (packet.length < API_KEY_TLV_SIZE + 4) {
            throw protocol("Response is too small");
        }
        ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        int apiKeyType = Short.toUnsignedInt(buffer.getShort());
        int apiKeySize = Short.toUnsignedInt(buffer.getShort());
        if (apiKeyType != TLV_API_KEY || apiKeySize < API_KEY_TLV_SIZE || packet.length < apiKeySize) {
            throw protocol("Invalid API-key header");
        }
        long serverId = buffer.getLong();
        byte[] requestId = new byte[16];
        buffer.get(requestId);
        if (!Arrays.equals(expectedRequestId, requestId)) {
            throw protocol("Response request_id does not match the inflight request");
        }
        buffer.getLong(); // timestamp
        boolean steeringFeedback = buffer.get() != 0;
        buffer.get(); // management_flag
        buffer.getShort(); // padding

        int authType = Short.toUnsignedInt(buffer.getShort());
        int authSize = Short.toUnsignedInt(buffer.getShort());
        byte[] pdu = switch (authType) {
            case TLV_AUTH_NONE -> {
                if (credential.authMethod() != AuthMethod.NONE || authSize != 4) {
                    throw protocol("Invalid AUTH_NONE response");
                }
                yield Arrays.copyOfRange(packet, apiKeySize + authSize, packet.length);
            }
            case TLV_AUTH_COOKIE -> {
                if (credential.authMethod() != AuthMethod.COOKIE || authSize != 36) {
                    throw protocol("Invalid AUTH_COOKIE response");
                }
                byte[] cookie = Arrays.copyOfRange(packet, apiKeySize + 4, apiKeySize + authSize);
                try {
                    if (!credential.secretEquals(cookie)) {
                        throw new RateLimitlyException(
                            RateLimitlyException.ErrorKind.AUTHENTICATION,
                            "Cookie auth mismatch in response"
                        );
                    }
                } finally {
                    Arrays.fill(cookie, (byte) 0);
                }
                yield Arrays.copyOfRange(packet, apiKeySize + authSize, packet.length);
            }
            case TLV_AUTH_AES -> {
                if (credential.authMethod() != AuthMethod.AES || authSize != 32) {
                    throw protocol("Invalid AUTH_AES response");
                }
                int authPos = apiKeySize + 4;
                byte[] nonce = Arrays.copyOfRange(packet, authPos, authPos + 12);
                byte[] authTag = Arrays.copyOfRange(packet, authPos + 12, authPos + 28);
                byte[] ciphertext = Arrays.copyOfRange(packet, apiKeySize + authSize, packet.length);
                byte[] aad = Arrays.copyOfRange(packet, 0, authPos + 12);
                byte[] secret = credential.authSecret();
                try {
                    yield decrypt(ciphertext, secret, nonce, authTag, aad);
                } finally {
                    Arrays.fill(secret, (byte) 0);
                }
            }
            default -> throw protocol("Unknown auth TLV type: 0x" + Integer.toHexString(authType));
        };

        return parseRateResponsePdu(pdu, request, serverId, steeringFeedback);
    }

    public static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }

    private static byte[] buildRateRequestBody(RateLimitRequest request) throws RateLimitlyException {
        int tlvSize = request.metricsLabel() == null ? 0 : MetricsLabelTlv.encode(request.metricsLabel()).length;
        ByteBuffer body = ByteBuffer.allocate(
            4 + (request.guards().size() * GUARD_BLOCK_SIZE) + (request.resources().size() * RESOURCE_BLOCK_SIZE) + tlvSize
        ).order(ByteOrder.LITTLE_ENDIAN);

        body.putShort((short) request.guards().size());
        body.putShort((short) request.resources().size());

        for (LatencyGuard guard : request.guards()) {
            body.put(CanonicalIds.latencyTrackerId(
                guard.latencyTrackerName(),
                guard.ttlMs(),
                guard.maxSamples(),
                guard.minSampleThreshold()
            ));
            body.putInt((int) guard.ttlMs());
            body.putInt((int) guard.maxSamples());
            body.putInt((int) guard.minSampleThreshold());
            body.putInt((int) guard.thresholdMs());
            body.putInt(0);
        }

        for (ResourceRequest resource : request.resources()) {
            body.put(CanonicalIds.bucketId(
                resource.bucketName(),
                resource.windowSizeMs(),
                resource.rateLimit()
            ));
            body.putInt((int) resource.windowSizeMs());
            body.putInt((int) resource.rateLimit());
            body.putShort((short) resource.tokensRequested());
            body.putShort((short) 0);
        }

        if (request.metricsLabel() != null) {
            body.put(MetricsLabelTlv.encode(request.metricsLabel()));
        }

        return body.array();
    }

    private static byte[] buildLatencyReportBody(LatencyReport report) {
        ByteBuffer body = ByteBuffer.allocate(4 + (report.reports().size() * LATENCY_REPORT_BLOCK_SIZE))
            .order(ByteOrder.LITTLE_ENDIAN);
        body.putShort((short) report.reports().size());
        body.putShort((short) 0);
        for (ServiceLatencyReport latencyReport : report.reports()) {
            body.put(CanonicalIds.latencyTrackerId(
                latencyReport.latencyTrackerName(),
                latencyReport.ttlMs(),
                latencyReport.maxSamples(),
                latencyReport.minSampleThreshold()
            ));
            body.putInt((int) latencyReport.ttlMs());
            body.putInt((int) latencyReport.maxSamples());
            body.putInt((int) latencyReport.minSampleThreshold());
            body.putInt((int) latencyReport.observedLatencyMs());
        }
        return body.array();
    }

    private static byte[] buildRateRequestPdu(int dedupTtlMs, byte[] body) {
        ByteBuffer pdu = ByteBuffer.allocate(PDU_HEADER_SIZE + body.length).order(ByteOrder.LITTLE_ENDIAN);
        pdu.putShort((short) PDU_RATE_REQUEST);
        pdu.putShort((short) (PDU_HEADER_SIZE + body.length));
        pdu.putInt(dedupTtlMs);
        pdu.put(body);
        return pdu.array();
    }

    private static byte[] buildSimplePdu(int pduType, byte[] body) {
        ByteBuffer pdu = ByteBuffer.allocate(PDU_HEADER_SIZE + body.length).order(ByteOrder.LITTLE_ENDIAN);
        pdu.putShort((short) pduType);
        pdu.putShort((short) (PDU_HEADER_SIZE + body.length));
        pdu.putInt(0);
        pdu.put(body);
        return pdu.array();
    }

    private static EncodedRequest encodePacket(
        RateLimitlyClientConfig config,
        ApiKeyInfo credential,
        UUID requestId,
        byte[] pdu
    ) throws RateLimitlyException {
        byte[] requestIdBytes = uuidToBytes(requestId);
        byte[] apiKeyTlv = buildApiKeyTlv(credential.keyId(), requestIdBytes, config.steeringFeedback());

        byte[] authAndPdu = switch (credential.authMethod()) {
            case NONE -> {
                ByteBuffer buffer = ByteBuffer.allocate(4 + pdu.length).order(ByteOrder.LITTLE_ENDIAN);
                buffer.putShort((short) TLV_AUTH_NONE);
                buffer.putShort((short) 4);
                buffer.put(pdu);
                yield buffer.array();
            }
            case COOKIE -> {
                ByteBuffer buffer = ByteBuffer.allocate(36 + pdu.length).order(ByteOrder.LITTLE_ENDIAN);
                buffer.putShort((short) TLV_AUTH_COOKIE);
                buffer.putShort((short) 36);
                byte[] secret = credential.authSecret();
                try {
                    buffer.put(secret);
                    buffer.put(pdu);
                    yield buffer.array();
                } finally {
                    Arrays.fill(secret, (byte) 0);
                }
            }
            case AES -> {
                byte[] nonce = new byte[12];
                SECURE_RANDOM.nextBytes(nonce);
                byte[] authHeader = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort((short) TLV_AUTH_AES)
                    .putShort((short) 32)
                    .array();
                byte[] aad = concat(apiKeyTlv, authHeader, nonce);
                byte[] secret = credential.authSecret();
                try {
                    AesPayload encrypted = encrypt(pdu, secret, nonce, aad);
                    ByteBuffer buffer = ByteBuffer.allocate(32 + encrypted.ciphertext.length).order(ByteOrder.LITTLE_ENDIAN);
                    buffer.put(authHeader);
                    buffer.put(nonce);
                    buffer.put(encrypted.authTag);
                    buffer.put(encrypted.ciphertext);
                    yield buffer.array();
                } finally {
                    Arrays.fill(secret, (byte) 0);
                }
            }
            case SECRET -> throw protocol("rl-secret is not a client API key");
        };

        ByteBuffer packet = ByteBuffer.allocate(apiKeyTlv.length + authAndPdu.length).order(ByteOrder.LITTLE_ENDIAN);
        packet.put(apiKeyTlv);
        packet.put(authAndPdu);
        return new EncodedRequest(packet.array(), requestIdBytes);
    }

    private static byte[] buildApiKeyTlv(long keyId, byte[] requestId, boolean steeringFeedback) {
        ByteBuffer apiKeyHeader = ByteBuffer.allocate(API_KEY_TLV_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        apiKeyHeader.putShort((short) TLV_API_KEY);
        apiKeyHeader.putShort((short) API_KEY_TLV_SIZE);
        apiKeyHeader.putLong(keyId);
        apiKeyHeader.put(requestId);
        apiKeyHeader.putLong(System.currentTimeMillis());
        apiKeyHeader.put((byte) (steeringFeedback ? 1 : 0));
        apiKeyHeader.put((byte) 0);
        apiKeyHeader.putShort((short) 0);
        return apiKeyHeader.array();
    }

    private static RateLimitDecision parseRateResponsePdu(
        byte[] pdu,
        RateLimitRequest request,
        long serverId,
        boolean steeringFeedback
    ) throws RateLimitlyException {
        ByteBuffer buffer = ByteBuffer.wrap(pdu).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.remaining() < PDU_HEADER_SIZE) {
            throw protocol("PDU is too small");
        }
        int pduType = Short.toUnsignedInt(buffer.getShort());
        int pduSize = Short.toUnsignedInt(buffer.getShort());
        if (pduType != PDU_RATE_RESPONSE || pduSize < PDU_HEADER_SIZE || pdu.length < pduSize) {
            throw protocol("Invalid rate response PDU");
        }
        buffer.getInt(); // reserved
        int guardCount = Short.toUnsignedInt(buffer.getShort());
        int resourceCount = Short.toUnsignedInt(buffer.getShort());
        if (guardCount != request.guards().size() || resourceCount != request.resources().size()) {
            throw protocol("Response counts do not match request counts");
        }

        List<GuardDecision> guards = new ArrayList<>(guardCount);
        for (int i = 0; i < guardCount; i++) {
            buffer.position(buffer.position() + 16);
            buffer.getInt();
            buffer.getInt();
            buffer.getInt();
            long threshold = Integer.toUnsignedLong(buffer.getInt());
            long currentLatency = Integer.toUnsignedLong(buffer.getInt());
            String latencyTrackerName = request.guards().get(i).latencyTrackerName();
            guards.add(new GuardDecision(latencyTrackerName, threshold, currentLatency, currentLatency < threshold));
        }

        List<ResourceDecision> resources = new ArrayList<>(resourceCount);
        for (int i = 0; i < resourceCount; i++) {
            buffer.position(buffer.position() + 16);
            buffer.getInt();
            long actualRate = Integer.toUnsignedLong(buffer.getInt());
            int deficit = Short.toUnsignedInt(buffer.getShort());
            buffer.getShort();
            String bucketName = request.resources().get(i).bucketName();
            resources.add(new ResourceDecision(bucketName, deficit, actualRate));
        }

        boolean success = guards.stream().allMatch(GuardDecision::passed)
            && resources.stream().allMatch(r -> r.tokensDeficit() == 0);
        return new RateLimitDecision(success, guards, resources, serverId, steeringFeedback);
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] part : parts) {
            len += part.length;
        }
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, pos, part.length);
            pos += part.length;
        }
        return out;
    }

    private static AesPayload encrypt(byte[] pdu, byte[] keyBytes, byte[] nonce, byte[] aad) throws RateLimitlyException {
        try {
            Cipher cipher = AES_GCM_ENCRYPT_CIPHER.get();
            SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
            GCMParameterSpec spec = new GCMParameterSpec(128, nonce);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);
            cipher.updateAAD(aad);
            byte[] ciphertextWithTag = cipher.doFinal(pdu);
            byte[] ciphertext = Arrays.copyOf(ciphertextWithTag, ciphertextWithTag.length - 16);
            byte[] tag = Arrays.copyOfRange(ciphertextWithTag, ciphertextWithTag.length - 16, ciphertextWithTag.length);
            return new AesPayload(ciphertext, tag);
        } catch (Exception e) {
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.PROTOCOL,
                "AES encryption failed",
                e
            );
        }
    }

    private static byte[] decrypt(byte[] ciphertext, byte[] keyBytes, byte[] nonce, byte[] authTag, byte[] aad)
        throws RateLimitlyException {
        try {
            Cipher cipher = AES_GCM_DECRYPT_CIPHER.get();
            SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
            GCMParameterSpec spec = new GCMParameterSpec(128, nonce);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            cipher.updateAAD(aad);
            byte[] ciphertextWithTag = new byte[ciphertext.length + authTag.length];
            System.arraycopy(ciphertext, 0, ciphertextWithTag, 0, ciphertext.length);
            System.arraycopy(authTag, 0, ciphertextWithTag, ciphertext.length, authTag.length);
            return cipher.doFinal(ciphertextWithTag);
        } catch (Exception e) {
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.PROTOCOL,
                "AES decryption failed",
                e
            );
        }
    }

    private static RateLimitlyException protocol(String message) {
        return new RateLimitlyException(RateLimitlyException.ErrorKind.PROTOCOL, message);
    }

    private static Cipher initCipher(String transformation) {
        try {
            return Cipher.getInstance(transformation);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public record EncodedRequest(byte[] packet, byte[] requestId) {
        public EncodedRequest {
            packet = packet.clone();
            requestId = requestId.clone();
        }

        @Override
        public byte[] packet() {
            return packet;
        }

        @Override
        public byte[] requestId() {
            return requestId;
        }
    }

    private record AesPayload(byte[] ciphertext, byte[] authTag) {
    }
}
