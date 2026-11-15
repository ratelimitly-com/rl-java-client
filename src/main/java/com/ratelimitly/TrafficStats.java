package com.ratelimitly;

import java.math.BigInteger;

/**
 * Immutable lifetime physical UDP snapshot, retained after close and socket rotations.
 * @param sent successfully submitted UDP traffic, including retries, replicas and reports
 * @param received all received UDP traffic, including malformed or unmatched datagrams
 * @param sendFailures failed UDP send attempts, not included in sent bytes
 * @param incomplete saturation or unavailable IP-family information prevents reconciliation
 */
public record TrafficStats(TrafficDirection sent, TrafficDirection received,
                          BigInteger sendFailures, boolean incomplete) { }
