package com.inappify.sdk.internal

import java.math.BigInteger

/** API-21-compatible equivalent of `BigInteger.longValueExact()`. */
internal fun BigInteger.toLongExact(): Long {
    if (bitLength() > 63) {
        throw ArithmeticException("Value does not fit in a signed 64-bit integer.")
    }
    return toLong()
}
