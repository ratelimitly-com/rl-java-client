package com.ratelimitly;

import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/** Mutable unsigned bit patterns. BigInteger conversion only at snapshot; no I/O under this lock. */
final class TrafficCounters {
    private final long[] sent = new long[3];
    private final long[] received = new long[3];
    private long failures;
    private boolean incomplete;

    private long add(long before, long amount) {
        long sum = before + amount;
        if (Long.compareUnsigned(sum, before) < 0) {
            incomplete = true;
            return -1L;
        }
        return sum;
    }

    synchronized void record(boolean sending, long payload, InetAddress address) {
        long[] values = sending ? sent : received;
        values[0] = add(values[0], 1);
        values[1] = add(values[1], payload);
        int overhead = 0;
        if (address instanceof Inet4Address) overhead = 28;
        else if (address instanceof Inet6Address) {
            byte[] b = address.getAddress();
            boolean mapped = b[10] == -1 && b[11] == -1;
            for (int i = 0; i < 10; i++) mapped &= b[i] == 0;
            overhead = mapped ? 28 : 48;
        }
        if (overhead == 0) incomplete = true;
        else {
            values[2] = add(values[2], payload);
            values[2] = add(values[2], overhead);
        }
    }

    synchronized void failedSend() { failures = add(failures, 1); }

    private static BigInteger unsigned(long value) {
        BigInteger result = BigInteger.valueOf(value & Long.MAX_VALUE);
        return value < 0 ? result.setBit(63) : result;
    }

    private static TrafficDirection direction(long[] v) {
        return new TrafficDirection(unsigned(v[0]), unsigned(v[1]), unsigned(v[2]));
    }

    synchronized TrafficStats snapshot() {
        return new TrafficStats(direction(sent), direction(received), unsigned(failures), incomplete);
    }
}
