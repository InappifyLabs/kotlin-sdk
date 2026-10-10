package com.inappify.sdk

/**
 * Immutable asynchronous state change emitted by an [InappifyClient].
 *
 * [snapshot] is the complete authoritative, token-free state after the
 * change. [requestId] is present only when the SDK owns a non-sensitive
 * correlation identifier for the operation that caused the event. Backend
 * messages, API keys, access tokens, identity tickets, purchase tokens, and
 * customer identifiers must never be used as a request identifier.
 */
public class InappifyEvent private constructor(
    public val type: InappifyEventType,
    public val snapshot: InappifySnapshot,
    public val requestId: String?,
) {

    /** Returns diagnostics without customer state or correlation data. */
    public override fun toString(): String =
        "InappifyEvent(type=$type, snapshot=<redacted>)"

    internal companion object {
        private const val MAX_REQUEST_ID_LENGTH = 128

        /** Creates an event while rejecting unsafe correlation identifiers. */
        internal fun create(
            type: InappifyEventType,
            snapshot: InappifySnapshot,
            requestId: String? = null,
        ): InappifyEvent = InappifyEvent(
            type = type,
            snapshot = snapshot,
            requestId = requestId.toSafeRequestId(),
        )

        private fun String?.toSafeRequestId(): String? =
            this
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.takeIf { it.length <= MAX_REQUEST_ID_LENGTH }
                ?.takeIf { value ->
                    value.all { character ->
                        character.isLetterOrDigit() ||
                            character == '-' ||
                            character == '_' ||
                            character == '.' ||
                            character == ':'
                    }
                }
    }
}
