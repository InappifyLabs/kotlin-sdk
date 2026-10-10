package com.inappify.sdk

/**
 * Aggregate result of independently restoring owned store purchases.
 *
 * A successful SDK result may still have a non-zero [failedCount], because one
 * invalid purchase must not prevent other subscriptions or non-consumables
 * from being restored.
 */
public class InappifyRestoreResult public constructor(
    public val restoredCount: Int,
    public val alreadyProcessedCount: Int,
    public val failedCount: Int,
) {
    init {
        require(restoredCount >= 0) { "restoredCount must not be negative." }
        require(alreadyProcessedCount >= 0) {
            "alreadyProcessedCount must not be negative."
        }
        require(failedCount >= 0) { "failedCount must not be negative." }
    }

    /** Total number of independently evaluated store purchases. */
    public val totalCount: Int
        get() = restoredCount + alreadyProcessedCount + failedCount

    public override fun toString(): String =
        "InappifyRestoreResult(" +
            "restoredCount=$restoredCount, " +
            "alreadyProcessedCount=$alreadyProcessedCount, " +
            "failedCount=$failedCount" +
            ")"

    public override fun equals(other: Any?): Boolean =
        other is InappifyRestoreResult &&
            restoredCount == other.restoredCount &&
            alreadyProcessedCount == other.alreadyProcessedCount &&
            failedCount == other.failedCount

    public override fun hashCode(): Int {
        var result = restoredCount
        result = 31 * result + alreadyProcessedCount
        result = 31 * result + failedCount
        return result
    }
}
