package com.ratelimitly;

/**
 * Resource-request fan-out, replay, response-selection, and completion-delivery policy.
 *
 * @param unitMs base duration unit in milliseconds
 * @param replayCount replay rounds after the initial transmission
 * @param replayGap schedule defining each transmission round's duration in base units
 * @param finalReceiveUnits receive-only base units after the last transmission round
 * @param completionDelivery whether to resend to missing servers before returning a decision
 */
public record RequestPolicy(
    long unitMs,
    int replayCount,
    Schedule replayGap,
    int finalReceiveUnits,
    boolean completionDelivery
) {
    /** Largest replay count representable by the public and protocol contracts. */
    public static final int MAX_REPLAY_COUNT = 65_535;

    /** Validates the schedule and normalizes a null replay schedule to one fixed unit. */
    public RequestPolicy {
        if (unitMs <= 0) {
            throw new IllegalArgumentException("unitMs must be positive");
        }
        if (replayCount < 0 || replayCount > MAX_REPLAY_COUNT) {
            throw new IllegalArgumentException("replayCount must be in 0..65535");
        }
        replayGap = replayGap == null ? Schedule.fixed(1) : replayGap;
        if (finalReceiveUnits < 0) {
            throw new IllegalArgumentException("finalReceiveUnits must be non-negative");
        }
    }

    /**
     * Returns the standard three-unit policy: initial send, one replay, and one final wait.
     *
     * @return the immutable default policy
     */
    public static RequestPolicy defaultPolicy() {
        return new RequestPolicy(20, 1, Schedule.fixed(1), 1, true);
    }

    /**
     * Returns the complete policy horizon used as the request deduplication TTL.
     *
     * @param dedupTtlMsMax maximum horizon allowed by the API key, in milliseconds
     * @return complete policy horizon in milliseconds
     * @throws IllegalArgumentException if the limit is invalid, arithmetic overflows, or the
     *     policy exceeds the limit
     */
    public long horizonMs(long dedupTtlMsMax) {
        if (dedupTtlMsMax <= 0 || dedupTtlMsMax > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("dedupTtlMsMax must be in 1..2^32-1");
        }
        long totalUnits = finalReceiveUnits;
        for (int round = 0; round <= replayCount; round++) {
            try {
                totalUnits = Math.addExact(totalUnits, replayGap.units(round));
            } catch (ArithmeticException error) {
                throw new IllegalArgumentException("request policy horizon overflows", error);
            }
        }
        final long horizon;
        try {
            horizon = Math.multiplyExact(totalUnits, unitMs);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("request policy horizon overflows", error);
        }
        if (totalUnits == 0 || horizon > 0xFFFF_FFFFL || horizon > dedupTtlMsMax) {
            throw new IllegalArgumentException("request policy horizon exceeds dedup_ttl_ms_max");
        }
        return horizon;
    }

    /**
     * Duration schedule for successive transmission rounds.
     *
     * @param kind fixed, linear, or exponential growth
     * @param initialUnits duration of round zero in base units
     * @param maxUnits maximum duration of any round in base units
     * @param growth linear step or exponential factor; ignored for fixed schedules
     */
    public record Schedule(Kind kind, long initialUnits, long maxUnits, long growth) {
        /** Supported replay-gap growth models. */
        public enum Kind {
            /** Every transmission round has the same duration. */
            FIXED,
            /** Round duration grows by a constant step until capped. */
            LINEAR,
            /** Round duration grows by a constant factor until capped. */
            EXPONENTIAL
        }

        /** Validates and normalizes the schedule definition. */
        public Schedule {
            kind = kind == null ? Kind.FIXED : kind;
            if (initialUnits <= 0 || maxUnits < initialUnits) {
                throw new IllegalArgumentException("schedule must satisfy 1 <= initialUnits <= maxUnits");
            }
            if (kind == Kind.FIXED && maxUnits != initialUnits) {
                throw new IllegalArgumentException("fixed schedule requires maxUnits == initialUnits");
            }
            if (kind == Kind.LINEAR && growth <= 0) {
                throw new IllegalArgumentException("linear growth must be positive");
            }
            if (kind == Kind.EXPONENTIAL && growth < 2) {
                throw new IllegalArgumentException("exponential growth must be at least 2");
            }
        }

        /**
         * Creates a schedule whose rounds all have the same duration.
         *
         * @param units positive duration in base units
         * @return a fixed schedule
         */
        public static Schedule fixed(long units) {
            return new Schedule(Kind.FIXED, units, units, 0);
        }

        /**
         * Creates a linearly increasing schedule.
         *
         * @param initialUnits positive first-round duration in base units
         * @param stepUnits positive units added after each round
         * @param maxUnits inclusive duration cap in base units
         * @return a linear schedule
         */
        public static Schedule linear(long initialUnits, long stepUnits, long maxUnits) {
            return new Schedule(Kind.LINEAR, initialUnits, maxUnits, stepUnits);
        }

        /**
         * Creates an exponentially increasing schedule.
         *
         * @param initialUnits positive first-round duration in base units
         * @param factor multiplication factor of at least two
         * @param maxUnits inclusive duration cap in base units
         * @return an exponential schedule
         */
        public static Schedule exponential(long initialUnits, long factor, long maxUnits) {
            return new Schedule(Kind.EXPONENTIAL, initialUnits, maxUnits, factor);
        }

        /**
         * Returns the duration of a zero-based transmission round.
         *
         * @param round zero-based round number
         * @return round duration in base units, capped by {@link #maxUnits()}
         * @throws IllegalArgumentException if {@code round} is negative
         */
        public long units(int round) {
            if (round < 0) {
                throw new IllegalArgumentException("round must be non-negative");
            }
            return switch (kind) {
                case FIXED -> initialUnits;
                case LINEAR -> {
                    long room = maxUnits - initialUnits;
                    yield round > room / growth ? maxUnits : initialUnits + (round * growth);
                }
                case EXPONENTIAL -> {
                    long value = initialUnits;
                    for (int index = 0; index < round && value < maxUnits; index++) {
                        value = value > maxUnits / growth ? maxUnits : value * growth;
                    }
                    yield Math.min(value, maxUnits);
                }
            };
        }
    }
}
