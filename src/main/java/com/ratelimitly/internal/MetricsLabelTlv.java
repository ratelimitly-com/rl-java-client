package com.ratelimitly.internal;

import com.ratelimitly.RateLimitlyException;
import java.nio.charset.StandardCharsets;

public final class MetricsLabelTlv {
    public static final int TLV_TYPE = 0x4C4D;

    private MetricsLabelTlv() {
    }

    public static byte[] encode(String label) throws RateLimitlyException {
        byte[] labelBytes = label == null ? new byte[0] : label.getBytes(StandardCharsets.UTF_8);
        if (labelBytes.length > 0xFFFF) {
            throw new RateLimitlyException(
                RateLimitlyException.ErrorKind.REQUEST_TOO_LARGE,
                "Metrics label exceeds the 16-bit protocol length field"
            );
        }

        int bodyLength = 2 + labelBytes.length;
        int padding = (4 - (bodyLength % 4)) % 4;
        int tlvSize = 4 + bodyLength + padding;
        byte[] out = new byte[tlvSize];

        writeLe16(out, 0, TLV_TYPE);
        writeLe16(out, 2, tlvSize);
        writeLe16(out, 4, labelBytes.length);
        System.arraycopy(labelBytes, 0, out, 6, labelBytes.length);
        return out;
    }

    private static void writeLe16(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) (value & 0xFF);
        buffer[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }
}
