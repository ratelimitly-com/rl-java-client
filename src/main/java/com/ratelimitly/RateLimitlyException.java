package com.ratelimitly;

import java.io.Serial;

/** Checked failure raised when the client cannot produce the requested successful operation. */
public class RateLimitlyException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    /** Stable category suitable for application failure-policy selection. */
    public enum ErrorKind {
        /** Server membership could not be discovered. */
        DNS_DISCOVERY,
        /** No usable response arrived within the request-policy horizon. */
        TIMEOUT,
        /** A packet or protocol value was invalid. */
        PROTOCOL,
        /** Local authentication or cryptographic processing failed. */
        AUTHENTICATION,
        /** API-key, policy, executor, or client configuration was invalid. */
        CONFIGURATION,
        /** Socket creation, send, receive, or interruption failed. */
        TRANSPORT_IO,
        /** Responses arrived but none could be accepted as a decision. */
        NO_VALID_RESPONSE,
        /** The encoded operation would exceed the packet-size limit. */
        REQUEST_TOO_LARGE,
        /** The requested behavior is not implemented by this client version. */
        UNSUPPORTED
    }

    /** Stable category associated with this failure. */
    private final ErrorKind kind;

    /**
     * Creates a categorized failure.
     *
     * @param kind stable error category
     * @param message human-readable diagnostic without credential material
     */
    public RateLimitlyException(ErrorKind kind, String message) {
        super(message);
        this.kind = kind;
    }

    /**
     * Creates a categorized failure with its underlying cause.
     *
     * @param kind stable error category
     * @param message human-readable diagnostic without credential material
     * @param cause underlying failure
     */
    public RateLimitlyException(ErrorKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /**
     * Returns the category applications should use for failure-policy selection.
     *
     * @return stable error category
     */
    public ErrorKind kind() {
        return kind;
    }
}
