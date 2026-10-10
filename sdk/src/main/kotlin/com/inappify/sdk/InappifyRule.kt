package com.inappify.sdk

/** Immutable server targeting rule used to resolve an offering. */
public class InappifyRule public constructor(
    public val defaultOffering: String? = null,
    public val sort: Long? = null,
    conditions: List<InappifyCondition>? = null,
    public val placements: InappifyPlacement? = null,
) {

    /** Defensive, unmodifiable copy of all conditions in this rule. */
    public val conditions: List<InappifyCondition>? = immutableList(conditions)

    /** Redacts condition values that may originate from customer attributes. */
    public override fun toString(): String =
        "InappifyRule(" +
            "defaultOffering=$defaultOffering, " +
            "sort=$sort, " +
            "conditions=${conditions.redactedCollection()}, " +
            "placements=$placements" +
            ")"

    public override fun equals(other: Any?): Boolean =
        other is InappifyRule &&
            defaultOffering == other.defaultOffering &&
            sort == other.sort &&
            conditions == other.conditions &&
            placements == other.placements

    public override fun hashCode(): Int {
        var result = defaultOffering?.hashCode() ?: 0
        result = 31 * result + (sort?.hashCode() ?: 0)
        result = 31 * result + (conditions?.hashCode() ?: 0)
        result = 31 * result + (placements?.hashCode() ?: 0)
        return result
    }
}

/** Immutable comparison condition contained in an offering rule. */
public class InappifyCondition public constructor(
    public val id: Long? = null,
    public val targetId: Long? = null,
    public val context: String? = null,
    public val field: String? = null,
    public val operator: String? = null,
    public val value: String? = null,
) {

    /** Redacts context and value because custom-attribute rules can contain PII. */
    public override fun toString(): String =
        "InappifyCondition(" +
            "id=$id, " +
            "targetId=$targetId, " +
            "context=${context.redactedValue()}, " +
            "field=$field, " +
            "operator=$operator, " +
            "value=${value.redactedValue()}" +
            ")"

    public override fun equals(other: Any?): Boolean =
        other is InappifyCondition &&
            id == other.id &&
            targetId == other.targetId &&
            context == other.context &&
            field == other.field &&
            operator == other.operator &&
            value == other.value

    public override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + (targetId?.hashCode() ?: 0)
        result = 31 * result + (context?.hashCode() ?: 0)
        result = 31 * result + (field?.hashCode() ?: 0)
        result = 31 * result + (operator?.hashCode() ?: 0)
        result = 31 * result + (value?.hashCode() ?: 0)
        return result
    }
}

/** Immutable placement mappings attached to a targeting rule. */
public class InappifyPlacement public constructor(
    public val fallbackOfferingId: String? = null,
    placementOfferings: List<InappifyPlacementOffering>? = null,
) {

    /** Defensive, unmodifiable copy of placement-to-offering mappings. */
    public val placementOfferings: List<InappifyPlacementOffering>? =
        immutableList(placementOfferings)

    public override fun toString(): String =
        "InappifyPlacement(" +
            "fallbackOfferingId=$fallbackOfferingId, " +
            "placementOfferings=${placementOfferings.redactedCollection()}" +
            ")"

    public override fun equals(other: Any?): Boolean =
        other is InappifyPlacement &&
            fallbackOfferingId == other.fallbackOfferingId &&
            placementOfferings == other.placementOfferings

    public override fun hashCode(): Int =
        31 * (fallbackOfferingId?.hashCode() ?: 0) + (placementOfferings?.hashCode() ?: 0)
}

/** Immutable mapping from a placement identifier to an offering identifier. */
public class InappifyPlacementOffering public constructor(
    public val placementIdentifier: String? = null,
    public val offeringIdentifier: String? = null,
) {

    public override fun toString(): String =
        "InappifyPlacementOffering(" +
            "placementIdentifier=$placementIdentifier, " +
            "offeringIdentifier=$offeringIdentifier" +
            ")"

    public override fun equals(other: Any?): Boolean =
        other is InappifyPlacementOffering &&
            placementIdentifier == other.placementIdentifier &&
            offeringIdentifier == other.offeringIdentifier

    public override fun hashCode(): Int =
        31 * (placementIdentifier?.hashCode() ?: 0) +
            (offeringIdentifier?.hashCode() ?: 0)
}
