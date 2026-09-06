package com.ratelimitly;

/**
 * Result returned for one resource consumption in a resource request.
 *
 * @param bucketName application-defined bucket name
 * @param tokensDeficit number of additional tokens that would have been needed, or zero
 * @param actualRate current rate observed by the selected server
 */
public record ResourceDecision(
    String bucketName,
    int tokensDeficit,
    long actualRate
) {
}
