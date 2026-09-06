package com.ratelimitly;

/** Authentication method encoded by a RateLimitly credential prefix. */
public enum AuthMethod {
    /** Development credential without authentication. */
    NONE("none"),
    /** Reusable-cookie authentication for trusted private networks. */
    COOKIE("cookie"),
    /** AES-256-GCM authenticated encryption. */
    AES("aes"),
    /** Management-secret credential, which cannot authorize resource requests. */
    SECRET("secret");

    private final String wireName;

    AuthMethod(String wireName) {
        this.wireName = wireName;
    }

    /**
     * Returns the stable lower-case name used in the API-key prefix.
     *
     * @return the encoded authentication-method name
     */
    public String wireName() {
        return wireName;
    }

    /**
     * Finds the authentication method represented by an encoded name.
     *
     * @param wireName lower-case authentication-method name
     * @return the corresponding authentication method
     * @throws RateLimitlyException if the name is not supported
     */
    public static AuthMethod fromWireName(String wireName) throws RateLimitlyException {
        for (AuthMethod method : values()) {
            if (method.wireName.equals(wireName)) {
                return method;
            }
        }
        throw new RateLimitlyException(
            RateLimitlyException.ErrorKind.CONFIGURATION,
            "Unsupported auth method in API key: " + wireName
        );
    }
}
