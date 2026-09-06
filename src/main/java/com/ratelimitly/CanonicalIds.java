package com.ratelimitly;

import com.ratelimitly.internal.Blake2s;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Derives the canonical 16-byte identifiers used for server-side state.
 */
public final class CanonicalIds {
    private static final byte[] RESOURCE_DOMAIN =
        "ratelimitly.resource.v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LATENCY_TRACKER_DOMAIN =
        "ratelimitly.latency-tracker.v2\0".getBytes(StandardCharsets.US_ASCII);

    private CanonicalIds() {
    }

    /**
     * Derives a bucket ID from a UTF-8 bucket name and its complete definition.
     *
     * @param bucketName application-defined bucket name
     * @param windowSizeMs window duration in milliseconds
     * @param rateLimit token capacity for the window
     * @return a new 16-byte canonical identifier
     * @throws NullPointerException if {@code bucketName} is null
     * @throws IllegalArgumentException if a numeric field does not fit an unsigned 32-bit integer
     */
    public static byte[] bucketId(
        String bucketName,
        long windowSizeMs,
        long rateLimit
    ) {
        Objects.requireNonNull(bucketName, "bucketName");
        return bucketId(bucketName.getBytes(StandardCharsets.UTF_8), windowSizeMs, rateLimit);
    }

    /**
     * Derives a bucket ID from raw name bytes and its complete definition.
     *
     * @param bucketName application-defined bucket name bytes
     * @param windowSizeMs window duration in milliseconds
     * @param rateLimit token capacity for the window
     * @return a new 16-byte canonical identifier
     * @throws NullPointerException if {@code bucketName} is null
     * @throws IllegalArgumentException if an input is too large or a numeric field does not fit
     *     an unsigned 32-bit integer
     */
    public static byte[] bucketId(
        byte[] bucketName,
        long windowSizeMs,
        long rateLimit
    ) {
        return derive(RESOURCE_DOMAIN, bucketName, windowSizeMs, rateLimit);
    }

    /**
     * Derives a latency-tracker ID from a UTF-8 name and its complete definition.
     *
     * @param latencyTrackerName application-defined tracker name
     * @param ttlMs lifetime of one latency sample in milliseconds
     * @param maxSamples maximum samples considered by the tracker
     * @param minSampleThreshold warm-up sample threshold before tracked latency controls admission
     * @return a new 16-byte canonical identifier
     * @throws NullPointerException if {@code latencyTrackerName} is null
     * @throws IllegalArgumentException if a numeric field does not fit an unsigned 32-bit integer
     */
    public static byte[] latencyTrackerId(
        String latencyTrackerName,
        long ttlMs,
        long maxSamples,
        long minSampleThreshold
    ) {
        Objects.requireNonNull(latencyTrackerName, "latencyTrackerName");
        return latencyTrackerId(
            latencyTrackerName.getBytes(StandardCharsets.UTF_8),
            ttlMs,
            maxSamples,
            minSampleThreshold
        );
    }

    /**
     * Derives a latency-tracker ID from raw name bytes and its complete definition.
     *
     * @param latencyTrackerName application-defined tracker name bytes
     * @param ttlMs lifetime of one latency sample in milliseconds
     * @param maxSamples maximum samples considered by the tracker
     * @param minSampleThreshold warm-up sample threshold before tracked latency controls admission
     * @return a new 16-byte canonical identifier
     * @throws NullPointerException if {@code latencyTrackerName} is null
     * @throws IllegalArgumentException if an input is too large or a numeric field does not fit
     *     an unsigned 32-bit integer
     */
    public static byte[] latencyTrackerId(
        byte[] latencyTrackerName,
        long ttlMs,
        long maxSamples,
        long minSampleThreshold
    ) {
        return derive(
            LATENCY_TRACKER_DOMAIN,
            latencyTrackerName,
            ttlMs,
            maxSamples,
            minSampleThreshold
        );
    }

    private static byte[] derive(byte[] domain, byte[] name, long... fields) {
        Objects.requireNonNull(name, "name");
        int payloadLength;
        try {
            payloadLength = Math.addExact(domain.length, 4);
            payloadLength = Math.addExact(payloadLength, name.length);
            payloadLength = Math.addExact(payloadLength, Math.multiplyExact(fields.length, 4));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("canonical ID input is too large", exception);
        }

        ByteBuffer input = ByteBuffer.allocate(payloadLength).order(ByteOrder.LITTLE_ENDIAN);
        input.put(domain);
        input.putInt(name.length);
        input.put(name);
        for (long field : fields) {
            if (field < 0 || field > 0xFFFF_FFFFL) {
                throw new IllegalArgumentException("canonical ID fields must fit uint32");
            }
            input.putInt((int) field);
        }
        return Arrays.copyOf(Blake2s.digest(input.array()), 16);
    }
}
