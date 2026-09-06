package com.ratelimitly;

import java.time.Instant;

/**
 * Client-side observations for one discovered server.
 *
 * @param firstSeen time the client first observed this server
 * @param lastSeen time the client most recently observed this server
 * @param validResponses valid responses accepted from this server
 * @param timeouts logical requests for which this server did not answer in time
 * @param decryptFailures responses that could not be decrypted
 * @param authenticationFailures responses that failed authentication
 * @param idMismatchCount responses carrying an unexpected server identity
 * @param sendFailures datagrams that could not be sent to this server
 */
public record ServerStats(
    Instant firstSeen,
    Instant lastSeen,
    long validResponses,
    long timeouts,
    long decryptFailures,
    long authenticationFailures,
    long idMismatchCount,
    long sendFailures
) {
}
