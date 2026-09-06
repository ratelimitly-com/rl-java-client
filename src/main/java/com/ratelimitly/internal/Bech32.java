package com.ratelimitly.internal;

import com.ratelimitly.RateLimitlyException;
import java.util.ArrayList;
import java.util.List;

public final class Bech32 {
    private static final int[] GEN = {
        0x3B6A57B2,
        0x26508E6D,
        0x1EA119FA,
        0x3D4233DD,
        0x2A1462B3
    };

    private Bech32() {
    }

    public static Decoded decode(String value) throws RateLimitlyException {
        if (value == null || value.isEmpty()) {
            throw config("Credential is empty");
        }

        boolean hasLower = false;
        boolean hasUpper = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 33 || c > 126) {
                throw config("Credential contains non-printable characters");
            }
            hasLower |= Character.isLowerCase(c);
            hasUpper |= Character.isUpperCase(c);
        }
        if (hasLower && hasUpper) {
            throw config("Credential must not use mixed case");
        }

        String normalized = value.toLowerCase();
        int separator = normalized.lastIndexOf('1');
        if (separator < 1) {
            throw config("Credential is missing the Bech32 separator");
        }
        if (normalized.length() - separator - 1 < 6) {
            throw config("Credential checksum is too short");
        }

        String hrp = normalized.substring(0, separator);
        String dataPart = normalized.substring(separator + 1);
        byte[] data = new byte[dataPart.length()];
        for (int i = 0; i < dataPart.length(); i++) {
            data[i] = charToValue(dataPart.charAt(i));
        }

        if (!verifyChecksum(hrp, data)) {
            throw config("Credential checksum verification failed");
        }

        byte[] payload5 = new byte[data.length - 6];
        System.arraycopy(data, 0, payload5, 0, payload5.length);
        byte[] payload = convertBits(payload5, 5, 8, false);
        return new Decoded(hrp, payload);
    }

    private static byte charToValue(char c) throws RateLimitlyException {
        return switch (c) {
            case 'q' -> 0;
            case 'p' -> 1;
            case 'z' -> 2;
            case 'r' -> 3;
            case 'y' -> 4;
            case '9' -> 5;
            case 'x' -> 6;
            case '8' -> 7;
            case 'g' -> 8;
            case 'f' -> 9;
            case '2' -> 10;
            case 't' -> 11;
            case 'v' -> 12;
            case 'd' -> 13;
            case 'w' -> 14;
            case '0' -> 15;
            case 's' -> 16;
            case '3' -> 17;
            case 'j' -> 18;
            case 'n' -> 19;
            case '5' -> 20;
            case '4' -> 21;
            case 'k' -> 22;
            case 'h' -> 23;
            case 'c' -> 24;
            case 'e' -> 25;
            case '6' -> 26;
            case 'm' -> 27;
            case 'u' -> 28;
            case 'a' -> 29;
            case '7' -> 30;
            case 'l' -> 31;
            default -> throw config("Credential contains invalid Bech32 character: " + c);
        };
    }

    private static boolean verifyChecksum(String hrp, byte[] data) {
        List<Byte> values = hrpExpand(hrp);
        for (byte datum : data) {
            values.add(datum);
        }
        return polymod(values) == 1;
    }

    private static List<Byte> hrpExpand(String hrp) {
        List<Byte> out = new ArrayList<>(hrp.length() * 2 + 1);
        for (int i = 0; i < hrp.length(); i++) {
            out.add((byte) (hrp.charAt(i) >> 5));
        }
        out.add((byte) 0);
        for (int i = 0; i < hrp.length(); i++) {
            out.add((byte) (hrp.charAt(i) & 31));
        }
        return out;
    }

    private static int polymod(List<Byte> values) {
        int chk = 1;
        for (byte value : values) {
            int top = chk >>> 25;
            chk = ((chk & 0x1FF_FFFF) << 5) ^ (value & 0xFF);
            for (int i = 0; i < GEN.length; i++) {
                if (((top >>> i) & 1) != 0) {
                    chk ^= GEN[i];
                }
            }
        }
        return chk;
    }

    private static byte[] convertBits(byte[] data, int fromBits, int toBits, boolean pad)
        throws RateLimitlyException {
        int acc = 0;
        int bits = 0;
        int maxValue = (1 << toBits) - 1;
        int maxAcc = (1 << (fromBits + toBits - 1)) - 1;
        List<Byte> out = new ArrayList<>();

        for (byte value : data) {
            int unsigned = value & 0xFF;
            if ((unsigned >>> fromBits) != 0) {
                throw config("Credential contains invalid Bech32 padding");
            }
            acc = ((acc << fromBits) | unsigned) & maxAcc;
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                out.add((byte) ((acc >>> bits) & maxValue));
            }
        }

        if (pad) {
            if (bits > 0) {
                out.add((byte) ((acc << (toBits - bits)) & maxValue));
            }
        } else {
            if (bits >= fromBits || ((acc << (toBits - bits)) & maxValue) != 0) {
                throw config("Credential contains invalid Bech32 padding");
            }
        }

        byte[] decoded = new byte[out.size()];
        for (int i = 0; i < out.size(); i++) {
            decoded[i] = out.get(i);
        }
        return decoded;
    }

    private static RateLimitlyException config(String message) {
        return new RateLimitlyException(RateLimitlyException.ErrorKind.CONFIGURATION, message);
    }

    public record Decoded(String hrp, byte[] payload) {
        public Decoded {
            payload = payload == null ? new byte[0] : payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
