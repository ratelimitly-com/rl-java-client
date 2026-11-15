package com.ratelimitly;

import java.math.BigInteger;

/**
 * Physical UDP totals in one direction, represented exactly as unsigned 64-bit values.
 * @param datagrams physical UDP datagrams
 * @param payloadBytes UDP payload bytes
 * @param ipBytes estimated payload plus UDP and minimal IP headers
 */
public record TrafficDirection(BigInteger datagrams, BigInteger payloadBytes, BigInteger ipBytes) { }
