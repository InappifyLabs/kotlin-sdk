package com.inappify.sdk

/** Result of one semantic SDK operation. */
public sealed interface InappifyResult<out T> {

    /** Authoritative state returned by the operation, when available. */
    public val snapshot: InappifySnapshot?

    /** Successful operation with its complete authoritative [snapshot]. */
    public class Success<out T> public constructor(
        public val data: T,
        public override val snapshot: InappifySnapshot,
    ) : InappifyResult<T> {

        public override fun toString(): String =
            "InappifyResult.Success(snapshot=$snapshot)"
    }

    /** Semantic failure and the latest authoritative state, when available. */
    public class Failure public constructor(
        public val error: InappifyError,
        public override val snapshot: InappifySnapshot? = null,
    ) : InappifyResult<Nothing> {

        public override fun toString(): String =
            "InappifyResult.Failure(error=$error, snapshot=$snapshot)"
    }
}
