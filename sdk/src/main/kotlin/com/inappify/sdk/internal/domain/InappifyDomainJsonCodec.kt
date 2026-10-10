package com.inappify.sdk.internal.domain

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.inappify.sdk.InappifyAttribute
import com.inappify.sdk.InappifyCondition
import com.inappify.sdk.InappifyCustomerInfo
import com.inappify.sdk.InappifyDiscountCodeResult
import com.inappify.sdk.InappifyEntitlement
import com.inappify.sdk.InappifyOffering
import com.inappify.sdk.InappifyOfferings
import com.inappify.sdk.InappifyPackage
import com.inappify.sdk.InappifyPlacement
import com.inappify.sdk.InappifyPlacementOffering
import com.inappify.sdk.InappifyPrice
import com.inappify.sdk.InappifyPurchaseLink
import com.inappify.sdk.InappifyRule
import com.inappify.sdk.InappifyStoreProduct
import com.inappify.sdk.InappifyTransaction
import com.inappify.sdk.internal.toLongExact
import java.math.BigDecimal
import java.util.LinkedHashMap

/** Strict JSON boundary between service payloads and public domain models. */
internal object InappifyDomainJsonCodec {

    private val gson: Gson = GsonBuilder().serializeNulls().create()

    internal fun parseCustomerInfo(raw: String): InappifyCustomerInfo =
        parseDocument(raw, "customerInfo") { element ->
            parseCustomerInfoObject(element.requireObject("customerInfo"), "customerInfo")
        }

    internal fun parseOfferings(raw: String): InappifyOfferings =
        parseDocument(raw, "offerings") { element -> parseOfferingsElement(element, "offerings") }

    internal fun parseDiscountCodeResult(raw: String): InappifyDiscountCodeResult =
        parseDocument(raw, "discountCodeResult") { element ->
            parseDiscountCodeResultObject(
                element.requireObject("discountCodeResult"),
                "discountCodeResult",
            )
        }

    internal fun parseAttributes(raw: String): List<InappifyAttribute> =
        parseDocument(raw, "attributes") { element ->
            parseAttributes(element, "attributes") ?: emptyList()
        }

    internal fun encodeCustomerInfo(model: InappifyCustomerInfo): String =
        gson.toJson(encodeCustomerInfoObject(model))

    internal fun encodeOfferings(model: InappifyOfferings): String =
        gson.toJson(encodeOfferingsObject(model))

    internal fun encodeDiscountCodeResult(model: InappifyDiscountCodeResult): String =
        gson.toJson(encodeDiscountCodeResultObject(model))

    internal fun encodeAttributes(models: List<InappifyAttribute>): String =
        gson.toJson(models.toJsonArray(::encodeAttribute))

    private inline fun <T> parseDocument(
        raw: String,
        documentName: String,
        decode: (JsonElement) -> T,
    ): T {
        val root = try {
            JsonParser.parseString(raw)
        } catch (error: JsonParseException) {
            throw InappifyDomainJsonException("Invalid $documentName JSON.", error)
        } catch (error: RuntimeException) {
            throw InappifyDomainJsonException("Invalid $documentName JSON.", error)
        }
        return decode(root)
    }

    private fun parseCustomerInfoObject(source: JsonObject, path: String): InappifyCustomerInfo =
        InappifyCustomerInfo(
            originalAppUserId = source.string(path, "originalAppUserId", "original_app_user_id"),
            firstSeen = source.string(path, "firstSeen", "first_seen"),
            requestDate = source.string(path, "requestDate", "request_date"),
            latestExpirationDate = source.string(
                path,
                "latestExpirationDate",
                "latest_expiration_date",
            ),
            hasUsedTrial = source.boolean(path, "hasUsedTrial", "has_used_trial"),
            entitlements = parseEntitlements(source.field("entitlements"), "$path.entitlements"),
            transactions = parseObjectList(
                source.field("transactions"),
                "$path.transactions",
                ::parseTransaction,
            ),
            attributes = parseAttributes(source.field("attributes"), "$path.attributes"),
        )

    private fun parseEntitlements(element: JsonElement?, path: String): List<InappifyEntitlement>? =
        when {
            element == null || element.isJsonNull -> null
            element.isJsonArray -> element.asJsonArray.mapIndexed { index, item ->
                parseEntitlement(item.requireObject("$path[$index]"), "$path[$index]", null)
            }
            element.isJsonObject -> element.asJsonObject.entrySet().map { (identifier, item) ->
                parseEntitlement(
                    item.requireObject("$path.$identifier"),
                    "$path.$identifier",
                    identifier,
                )
            }
            else -> fail(path, "must be an array, object, or null")
        }

    private fun parseEntitlement(
        source: JsonObject,
        path: String,
        fallbackIdentifier: String?,
    ): InappifyEntitlement =
        InappifyEntitlement(
            identifier = if (source.has("identifier")) {
                source.string(path, "identifier")
            } else {
                fallbackIdentifier
            },
            isActive = source.boolean(path, "is_active", "isActive"),
            isSandbox = source.boolean(path, "is_sandbox", "isSandbox"),
            periodType = source.string(path, "period_type", "periodType"),
            purchaseDate = source.string(path, "purchase_date", "purchaseDate"),
            expirationDate = source.string(path, "expiration_date", "expirationDate"),
            ownershipType = source.string(path, "ownership_type", "ownershipType"),
            entitlementType = source.string(path, "entitlement_type", "entitlementType"),
            purchaseStoreRefHash = source.string(
                path,
                "purchase_store_ref_hash",
                "purchaseStoreRefHash",
            ),
            purchaseStoreTime = source.long(
                path,
                "purchase_store_time",
                "purchaseStoreTime",
            ),
        )

    private fun parseAttributes(element: JsonElement?, path: String): List<InappifyAttribute>? =
        when {
            element == null || element.isJsonNull -> null
            element.isJsonArray -> element.asJsonArray.mapIndexed { index, item ->
                val source = item.requireObject("$path[$index]")
                InappifyAttribute(
                    key = source.string("$path[$index]", "key"),
                    value = source.string("$path[$index]", "value"),
                )
            }
            element.isJsonObject -> element.asJsonObject.entrySet().map { (key, value) ->
                InappifyAttribute(key = key, value = value.attributeValue("$path.$key"))
            }
            else -> fail(path, "must be an array, object, or null")
        }

    private fun parseTransaction(source: JsonObject, path: String): InappifyTransaction =
        InappifyTransaction(
            number = source.string(path, "number"),
            status = source.long(path, "status"),
            amount = source.long(path, "amount"),
            transactionDate = source.string(path, "transactionDate", "transaction_date"),
            trackingNumber = source.string(path, "trackingNumber", "tracking_number"),
            isCryptoGate = source.boolean(path, "isCryptoGate", "is_crypto_gate"),
            packageName = source.string(path, "packageName", "package_name"),
            storePurchaseRefId = source.string(
                path,
                "storePurchaseRefId",
                "store_purchase_ref_id",
            ),
            isTrial = source.boolean(path, "isTrial", "is_trial"),
        )

    private fun parseDiscountCodeResultObject(
        source: JsonObject,
        path: String,
    ): InappifyDiscountCodeResult =
        InappifyDiscountCodeResult(
            isValid = source.boolean(path, "is_valid"),
            errorCode = source.long(path, "error_code"),
            code = source.string(path, "code"),
            discountId = source.long(path, "discount_id"),
            discountCodeId = source.long(path, "discount_code_id"),
            percent = source.long(path, "percent"),
            message = source.string(path, "message"),
            paymentLinks = parseObjectList(
                source.field("payment_links"),
                "$path.payment_links",
            ) { paymentLink, paymentLinkPath ->
                InappifyPurchaseLink(
                    offering = paymentLink.requiredString(paymentLinkPath, "offering"),
                    url = paymentLink.requiredString(paymentLinkPath, "url"),
                )
            },
            offering = source.field("offering")?.let { offering ->
                parseDiscountOffering(offering, "$path.offering")
            },
        )

    private fun parseOfferingsElement(element: JsonElement, path: String): InappifyOfferings {
        if (element.isJsonArray) {
            return InappifyOfferings(
                offerings = parseObjectList(element, path, ::parseOffering),
            )
        }

        val source = element.requireObject(path)
        val rawItems = source.coalescedField("offerings", "all", "items")
        val parsedItems = parseObjectList(rawItems, "$path.offerings", ::parseOffering)
        val items = parsedItems?.toMutableList()
        val currentElement = source.field("current")
        val current = when {
            currentElement == null || currentElement.isJsonNull -> null
            else -> parseOffering(currentElement.requireObject("$path.current"), "$path.current")
        }
        val mergedItems = mergeCurrentOffering(items, current)

        if (source.has("currentOffering") || source.has("placements")) {
            val placements = source.field("placements")?.takeUnless { it.isJsonNull }
                ?.requireObject("$path.placements")?.entrySet()?.associate { (key, value) ->
                    key to if (value.isJsonNull) null else value.requireString("$path.placements")
                }
            return InappifyOfferings.serverTargeted(mergedItems,
                source.long(path, "forceVersion", "force_version"), source.string(path, "fetchedAt", "fetched_at"),
                source.string(path, "currentOffering"), placements)
        }

        return InappifyOfferings(
            offerings = mergedItems,
            rules = parseObjectList(source.field("rules"), "$path.rules", ::parseRule),
            forceVersion = source.long(path, "forceVersion", "force_version"),
            fetchedAt = source.string(path, "fetchedAt", "fetched_at"),
        )
    }

    private fun mergeCurrentOffering(
        items: MutableList<InappifyOffering>?,
        current: InappifyOffering?,
    ): List<InappifyOffering>? {
        if (current == null) return items
        val result = items ?: mutableListOf()
        val index = result.indexOfFirst { it.identifier == current.identifier }
        if (index < 0) {
            result += current.withDefault(true)
        } else if (result.none { it.isDefault == true }) {
            result[index] = result[index].withDefault(true)
        }
        return result
    }

    private fun parseOffering(source: JsonObject, path: String): InappifyOffering = try {
        parseOfferingFields(source, path)
    } catch (_: InappifyDomainJsonException) {
        InappifyOffering()
    }

    private fun parseDiscountOffering(element: JsonElement, path: String): InappifyOffering =
        parseOffering(element.requireObject(path), path)

    private fun parseOfferingFields(
        source: JsonObject,
        path: String,
    ): InappifyOffering =
        InappifyOffering(
            identifier = source.string(path, "identifier"),
            isDefault = source.boolean(path, "isDefault", "is_default"),
            serverDescription = source.string(path, "serverDescription", "server_description"),
            trialDays = source.long(path, "trialDays", "trial_days"),
            metadata = source.field("metadata")?.toImmutableValue("$path.metadata"),
            packages = parseObjectList(
                source.field("packages"),
                "$path.packages",
            ) { packageSource, packagePath ->
                parsePackage(packageSource, packagePath)
            },
            paywall = source.field("paywall")?.toImmutableObjectMap("$path.paywall"),
        )

    private fun parsePackage(
        source: JsonObject,
        path: String,
    ): InappifyPackage =
        InappifyPackage(
            identifier = source.string(path, "identifier"),
            packageType = source.long(path, "packageType", "package_type"),
            description = source.string(path, "description"),
            entitlement = source.string(path, "entitlement"),
            name = source.string(path, "name"),
            discountPercent = source.long(path, "discountPercent", "discount_percent"),
            product = source.field("product")?.let { product ->
                if (product.isJsonNull) {
                    null
                } else {
                    parseProduct(
                        product.requireObject("$path.product"),
                        "$path.product",
                    )
                }
            },
        )

    private fun parseProduct(
        source: JsonObject,
        path: String,
    ): InappifyStoreProduct =
        InappifyStoreProduct(
            identifier = source.string(path, "identifier"),
            name = source.string(path, "name"),
            prices = parseObjectList(
                source.coalescedField("prices", "price"),
                "$path.prices",
            ) { priceSource, pricePath ->
                parsePrice(priceSource, pricePath)
            },
            dollarPrice = source.double(path, "dollarPrice", "dollar_price"),
            trialDays = source.long(path, "trialDays", "trial_days"),
        )

    private fun parsePrice(
        source: JsonObject,
        path: String,
    ): InappifyPrice {
        val amount = source.number(path, "amount")
        val originalAmount = source.coalescedNumber(
            path,
            "originalAmount",
            "originPrice",
            "originAmount",
        )
        return InappifyPrice(
            id = source.long(path, "id"),
            appProductId = source.long(path, "app_product_id", "appProductId"),
            currency = source.string(path, "currency"),
            amount = amount,
            originalAmount = originalAmount,
            discountAmount = source.number(path, "discountAmount", "discount_amount"),
            price = source.coalescedLong(path, "price", "amount"),
            createdAt = source.string(path, "created_at", "createdAt"),
            updatedAt = source.string(path, "updated_at", "updatedAt"),
            originPrice = source.coalescedLong(
                path,
                "originPrice",
                "originalAmount",
                "originAmount",
            ),
        )
    }

    private fun parseRule(source: JsonObject, path: String): InappifyRule =
        InappifyRule(
            defaultOffering = source.string(
                path,
                "default_offering",
                "defaultOfferingIdentifier",
            ),
            sort = source.long(path, "sort"),
            conditions = parseObjectList(
                source.field("conditions"),
                "$path.conditions",
                ::parseCondition,
            ),
            placements = source.field("placements")?.let { placements ->
                if (placements.isJsonNull) null else parsePlacement(
                    placements.requireObject("$path.placements"),
                    "$path.placements",
                )
            },
        )

    private fun parseCondition(source: JsonObject, path: String): InappifyCondition =
        InappifyCondition(
            id = source.long(path, "id"),
            targetId = source.long(path, "target_id", "targetId"),
            context = source.scalarString(path, "context"),
            field = source.string(path, "field"),
            operator = source.string(path, "operator"),
            value = source.conditionValue("value"),
        )

    private fun parsePlacement(source: JsonObject, path: String): InappifyPlacement =
        InappifyPlacement(
            fallbackOfferingId = source.string(
                path,
                "fallback_offering_id",
                "fallbackOfferingIdentifier",
            ),
            placementOfferings = parseObjectList(
                source.coalescedField("placement_offerings", "offerings"),
                "$path.placement_offerings",
                ::parsePlacementOffering,
            ),
        )

    private fun parsePlacementOffering(
        source: JsonObject,
        path: String,
    ): InappifyPlacementOffering =
        InappifyPlacementOffering(
            placementIdentifier = source.string(
                path,
                "placement_identifier",
                "placementIdentifier",
            ),
            offeringIdentifier = source.string(
                path,
                "offering_identifier",
                "offeringIdentifier",
            ),
        )

    private fun encodeCustomerInfoObject(model: InappifyCustomerInfo): JsonObject =
        JsonObject().apply {
            addString("originalAppUserId", model.originalAppUserId)
            addString("firstSeen", model.firstSeen)
            addString("requestDate", model.requestDate)
            addString("latestExpirationDate", model.latestExpirationDate)
            addBoolean("hasUsedTrial", model.hasUsedTrial)
            add("entitlements", model.entitlements.toJsonArray(::encodeEntitlement))
            add("transactions", model.transactions.toJsonArray(::encodeTransaction))
            add("attributes", model.attributes.toJsonArray(::encodeAttribute))
        }

    private fun encodeEntitlement(model: InappifyEntitlement): JsonObject =
        JsonObject().apply {
            addString("identifier", model.identifier)
            addBoolean("is_active", model.isActive)
            addBoolean("is_sandbox", model.isSandbox)
            addString("period_type", model.periodType)
            addString("purchase_date", model.purchaseDate)
            addString("expiration_date", model.expirationDate)
            addString("ownership_type", model.ownershipType)
            addString("entitlement_type", model.entitlementType)
            addString("purchase_store_ref_hash", model.purchaseStoreRefHash)
            addLong("purchase_store_time", model.purchaseStoreTime)
        }

    private fun encodeTransaction(model: InappifyTransaction): JsonObject =
        JsonObject().apply {
            addString("number", model.number)
            addLong("status", model.status)
            addLong("amount", model.amount)
            addString("transactionDate", model.transactionDate)
            addString("trackingNumber", model.trackingNumber)
            addBoolean("isCryptoGate", model.isCryptoGate)
            addString("packageName", model.packageName)
            addString("storePurchaseRefId", model.storePurchaseRefId)
            addBoolean("isTrial", model.isTrial)
        }

    private fun encodeAttribute(model: InappifyAttribute): JsonObject =
        JsonObject().apply {
            addString("key", model.key)
            addString("value", model.value)
        }

    private fun encodeDiscountCodeResultObject(
        model: InappifyDiscountCodeResult,
    ): JsonObject = JsonObject().apply {
        addBoolean("is_valid", model.isValid)
        addLong("error_code", model.errorCode)
        addString("code", model.code)
        addLong("discount_id", model.discountId)
        addLong("discount_code_id", model.discountCodeId)
        addLong("percent", model.percent)
        addString("message", model.message)
        add("payment_links", model.paymentLinks.toJsonArray(::encodePurchaseLink))
        add("offering", model.offering?.let(::encodeOffering) ?: JsonNull.INSTANCE)
    }

    private fun encodePurchaseLink(model: InappifyPurchaseLink): JsonObject =
        JsonObject().apply {
            addProperty("offering", model.offering)
            addProperty("url", model.url)
        }

    private fun encodeOfferingsObject(model: InappifyOfferings): JsonObject =
        JsonObject().apply {
            add("offerings", model.offerings.toJsonArray(::encodeOffering))
            add("rules", model.rules.toJsonArray(::encodeRule))
            addLong("forceVersion", model.forceVersion)
            addString("fetchedAt", model.fetchedAt)
            if (model.usesServerTargeting) {
                addString("currentOffering", model.currentOfferingIdentifier)
                add("placements", model.placements?.let { values ->
                    JsonObject().apply { values.forEach { (key, value) -> addString(key, value) } }
                } ?: JsonNull.INSTANCE)
            }
        }

    private fun encodeOffering(model: InappifyOffering): JsonObject =
        JsonObject().apply {
            addString("identifier", model.identifier)
            addBoolean("isDefault", model.isDefault)
            addString("serverDescription", model.serverDescription)
            addLong("trialDays", model.trialDays)
            add("metadata", model.metadata.toJsonElement())
            add("packages", model.packages.toJsonArray(::encodePackage))
            add("paywall", model.paywall.toJsonElement())
        }

    private fun encodePackage(model: InappifyPackage): JsonObject =
        JsonObject().apply {
            addString("identifier", model.identifier)
            addLong("packageType", model.packageType)
            addString("description", model.description)
            addString("entitlement", model.entitlement)
            addString("name", model.name)
            addLong("discountPercent", model.discountPercent)
            add("product", model.product?.let(::encodeProduct) ?: JsonNull.INSTANCE)
        }

    private fun encodeProduct(model: InappifyStoreProduct): JsonObject =
        JsonObject().apply {
            addString("identifier", model.identifier)
            addString("name", model.name)
            // The backend wire contract uses the singular key for this price list.
            add("price", model.prices.toJsonArray(::encodePrice))
            addDouble("dollarPrice", model.dollarPrice)
            addLong("trialDays", model.trialDays)
        }

    private fun encodePrice(model: InappifyPrice): JsonObject =
        JsonObject().apply {
            addLong("id", model.id)
            addLong("app_product_id", model.appProductId)
            addString("currency", model.currency)
            addNumber("amount", model.amount)
            addNumber("originalAmount", model.originalAmount)
            addNumber("discountAmount", model.discountAmount)
            addLong("price", model.price)
            addString("created_at", model.createdAt)
            addString("updated_at", model.updatedAt)
            addLong("originPrice", model.originPrice)
        }

    private fun encodeRule(model: InappifyRule): JsonObject =
        JsonObject().apply {
            addString("default_offering", model.defaultOffering)
            addLong("sort", model.sort)
            add("conditions", model.conditions.toJsonArray(::encodeCondition))
            add("placements", model.placements?.let(::encodePlacement) ?: JsonNull.INSTANCE)
        }

    private fun encodeCondition(model: InappifyCondition): JsonObject =
        JsonObject().apply {
            addLong("id", model.id)
            addLong("target_id", model.targetId)
            addString("context", model.context)
            addString("field", model.field)
            addString("operator", model.operator)
            addString("value", model.value)
        }

    private fun encodePlacement(model: InappifyPlacement): JsonObject =
        JsonObject().apply {
            addString("fallback_offering_id", model.fallbackOfferingId)
            add(
                "placement_offerings",
                model.placementOfferings.toJsonArray(::encodePlacementOffering),
            )
        }

    private fun encodePlacementOffering(model: InappifyPlacementOffering): JsonObject =
        JsonObject().apply {
            addString("placement_identifier", model.placementIdentifier)
            addString("offering_identifier", model.offeringIdentifier)
        }

    private fun InappifyOffering.withDefault(value: Boolean): InappifyOffering =
        InappifyOffering(
            identifier = identifier,
            isDefault = value,
            serverDescription = serverDescription,
            trialDays = trialDays,
            metadata = metadata,
            packages = packages,
            paywall = paywall,
        )

    private inline fun <T> parseObjectList(
        element: JsonElement?,
        path: String,
        parse: (JsonObject, String) -> T,
    ): List<T>? {
        if (element == null || element.isJsonNull) return null
        if (!element.isJsonArray) fail(path, "must be an array or null")
        return element.asJsonArray.mapIndexed { index, item ->
            parse(item.requireObject("$path[$index]"), "$path[$index]")
        }
    }

    private fun JsonObject.field(name: String, vararg aliases: String): JsonElement? {
        if (has(name)) return get(name).takeUnless(JsonElement::isJsonNull)
        aliases.forEach { alias ->
            if (has(alias)) return get(alias).takeUnless(JsonElement::isJsonNull)
        }
        return null
    }

    private fun JsonObject.coalescedField(vararg names: String): JsonElement? {
        names.forEach { name ->
            val value = get(name)
            if (value != null && !value.isJsonNull) return value
        }
        return null
    }

    private fun JsonObject.string(path: String, name: String, vararg aliases: String): String? {
        val value = field(name, *aliases) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            fail("$path.$name", "must be a string or null")
        }
        return value.asString
    }

    private fun JsonObject.requiredString(
        path: String,
        name: String,
        vararg aliases: String,
    ): String = string(path, name, *aliases) ?: fail("$path.$name", "must be a string")

    private fun JsonObject.scalarString(path: String, name: String): String? {
        val value = field(name) ?: return null
        if (!value.isJsonPrimitive) fail("$path.$name", "must be a scalar or null")
        return value.asJsonPrimitive.asString
    }

    private fun JsonObject.boolean(path: String, name: String, vararg aliases: String): Boolean? {
        val value = field(name, *aliases) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
            fail("$path.$name", "must be a boolean or null")
        }
        return value.asBoolean
    }

    /** Integer fields use a signed 64-bit boundary. */
    private fun JsonObject.long(path: String, name: String, vararg aliases: String): Long? =
        field(name, *aliases)?.truncatedLong("$path.$name")

    private fun JsonObject.coalescedLong(path: String, vararg names: String): Long? =
        coalescedField(*names)?.truncatedLong("$path.${names.first()}")

    private fun JsonObject.number(path: String, name: String, vararg aliases: String): Number? =
        field(name, *aliases)?.strictNumber("$path.$name")

    private fun JsonObject.coalescedNumber(path: String, vararg names: String): Number? =
        coalescedField(*names)?.strictNumber("$path.${names.first()}")

    private fun JsonObject.double(path: String, name: String, vararg aliases: String): Double? =
        field(name, *aliases)?.strictDouble("$path.$name")

    private fun JsonObject.conditionValue(name: String): String? {
        val value = field(name) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            value.asString
        } else {
            gson.toJson(value)
        }
    }

    private fun JsonElement.requireObject(path: String): JsonObject {
        if (!isJsonObject) fail(path, "must be an object")
        return asJsonObject
    }

    private fun JsonElement.requireString(path: String): String {
        if (!isJsonPrimitive || !asJsonPrimitive.isString) fail(path, "must be a string")
        return asString
    }

    /** Fractional values truncate toward zero and must remain Long-bounded. */
    private fun JsonElement.truncatedLong(path: String): Long {
        val decimal = strictDecimal(path)
        return try {
            decimal.toBigInteger().toLongExact()
        } catch (_: ArithmeticException) {
            fail(path, "must fit in a 64-bit integer after truncation")
        }
    }

    private fun JsonElement.strictNumber(path: String): Number {
        val decimal = strictDecimal(path)
        if (decimal.stripTrailingZeros().scale() <= 0) {
            return try {
                decimal.longValueExact()
            } catch (_: ArithmeticException) {
                fail(path, "integer is outside the 64-bit range")
            }
        }
        val value = decimal.toDouble()
        if (!value.isFinite()) fail(path, "must be a finite number")
        return value
    }

    private fun JsonElement.strictDouble(path: String): Double {
        val value = strictDecimal(path).toDouble()
        if (!value.isFinite()) fail(path, "must be a finite number")
        return value
    }

    private fun JsonElement.strictDecimal(path: String): BigDecimal {
        if (!isJsonPrimitive || !asJsonPrimitive.isNumber) fail(path, "must be a number")
        return try {
            asBigDecimal
        } catch (_: NumberFormatException) {
            fail(path, "must be a valid number")
        }
    }

    private fun JsonElement.attributeValue(path: String): String? {
        if (isJsonNull) return null
        if (!isJsonPrimitive) fail(path, "must be a scalar or null")
        return asJsonPrimitive.asString
    }

    private fun JsonElement.toImmutableObjectMap(path: String): Map<String, Any?>? {
        if (isJsonNull) return null
        return requireObject(path).entrySet().associateTo(LinkedHashMap()) { (key, value) ->
            key to value.toImmutableValue("$path.$key")
        }
    }

    private fun JsonElement.toImmutableValue(path: String): Any? =
        when {
            isJsonNull -> null
            isJsonObject -> asJsonObject.entrySet().associateTo(LinkedHashMap()) { (key, value) ->
                key to value.toImmutableValue("$path.$key")
            }
            isJsonArray -> asJsonArray.mapIndexed { index, item ->
                item.toImmutableValue("$path[$index]")
            }
            asJsonPrimitive.isString -> asString
            asJsonPrimitive.isBoolean -> asBoolean
            asJsonPrimitive.isNumber -> strictNumber(path)
            else -> fail(path, "contains an unsupported JSON value")
        }

    private fun Any?.toJsonElement(): JsonElement =
        when (this) {
            null -> JsonNull.INSTANCE
            is String -> JsonPrimitive(this)
            is Boolean -> JsonPrimitive(this)
            is Number -> {
                val value = toDouble()
                if (!value.isFinite()) fail("value", "must be a finite number")
                JsonPrimitive(this)
            }
            is Map<*, *> -> JsonObject().also { target ->
                forEach { (key, value) ->
                    if (key !is String) fail("value", "contains a non-string object key")
                    target.add(key, value.toJsonElement())
                }
            }
            is List<*> -> JsonArray().also { target -> forEach { target.add(it.toJsonElement()) } }
            else -> fail("value", "contains unsupported type ${this::class.java.name}")
        }

    private fun <T> List<T>?.toJsonArray(encode: (T) -> JsonElement): JsonElement {
        if (this == null) return JsonNull.INSTANCE
        return JsonArray().also { target -> forEach { target.add(encode(it)) } }
    }

    private fun JsonObject.addString(name: String, value: String?) {
        add(name, value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
    }

    private fun JsonObject.addBoolean(name: String, value: Boolean?) {
        add(name, value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
    }

    private fun JsonObject.addLong(name: String, value: Long?) {
        add(name, value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
    }

    private fun JsonObject.addDouble(name: String, value: Double?) {
        if (value != null && !value.isFinite()) fail(name, "must be a finite number")
        add(name, value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
    }

    private fun JsonObject.addNumber(name: String, value: Number?) {
        if (value != null && !value.toDouble().isFinite()) fail(name, "must be a finite number")
        add(name, value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
    }

    private fun fail(path: String, requirement: String): Nothing =
        throw InappifyDomainJsonException("$path $requirement.")
}

/** Controlled schema failure raised by [InappifyDomainJsonCodec]. */
internal class InappifyDomainJsonException internal constructor(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
