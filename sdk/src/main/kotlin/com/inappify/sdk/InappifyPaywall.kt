package com.inappify.sdk

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.inappify.sdk.internal.v2.allowedUrl
import java.util.Locale

/** One app-bundled font family. Remote font names and URLs are never used. */
public class InappifyPaywallTypography @JvmOverloads public constructor(
    public val regular: Int? = null,
    public val medium: Int? = null,
    public val bold: Int? = null,
) {
    public fun resourceForWeight(weight: Int): Int? {
        val candidates = listOfNotNull(regular?.let { 400 to it }, medium?.let { 500 to it }, bold?.let { 700 to it })
        return candidates.minByOrNull { kotlin.math.abs(it.first - weight) }?.second
    }
    internal fun validate(context: Context) {
        regular?.let { requireNotNull(ResourcesCompat.getFont(context, it)) { "Unable to load the regular paywall font." } }
    }
    internal fun typeface(context: Context, weight: Int): Typeface =
        resourceForWeight(weight)?.let { runCatching { ResourcesCompat.getFont(context, it) }.getOrNull() }
            ?: if (weight >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
}

/** Safe semantic IDs, including normalization of the documented legacy editor spelling. */
public object InappifyPaywallIcons {
    private val allowed = setOf("close", "chevron_left", "chevron_right", "check", "check_circle",
        "fiber_manual_record", "none", "arrow", "chevron", "circle_arrow", "circle_arrow_filled",
        "listcheck", "title", "image", "list", "card", "product", "purchase", "links", "footer", "group", "control")
    @JvmStatic
    public fun normalize(value: String?): String? {
        if (value == null || !value.matches(Regex("[A-Za-z_]+(?:\\.svg)?", RegexOption.IGNORE_CASE))) return null
        return value.lowercase(Locale.ROOT).removeSuffix(".svg").takeIf { it in allowed }
    }
}

/** Envelope validation independent of UI. Unsupported element grammars require package fallback. */
public object InappifyPaywallPolicy {
    @JvmStatic
    public fun isCompatible(document: Map<String, Any?>?, schemaVersion: Int, rendererVersion: Int): Boolean {
        if (document == null) return false
        fun positive(name: String): Long? = (document[name] as? Number)?.toString()?.toBigDecimalOrNull()
            ?.let { runCatching { it.longValueExact() }.getOrNull() }?.takeIf { it > 0 }
        if (positive("id") == null || positive("revision") == null ||
            (positive("schema_version") ?: return false) > schemaVersion ||
            (positive("minimum_renderer_version") ?: return false) > rendererVersion) return false
        var nodes = 0
        var bytes = 0L
        fun bounded(value: Any?, depth: Int): Boolean {
            if (depth > 24 || ++nodes > 10000) return false
            return when (value) {
                is String -> { bytes += value.toByteArray().size; value.length <= 10000 && bytes <= 1024 * 1024 }
                is Map<*, *> -> value.size <= 1000 && value.all { bounded(it.key, depth + 1) && bounded(it.value, depth + 1) }
                is List<*> -> value.size <= 1000 && value.all { bounded(it, depth + 1) }
                else -> true
            }
        }
        return bounded(document, 0)
    }

    /** Retains only rendering properties; rejects remote font material and unsafe asset URLs. */
    @JvmStatic
    public fun sanitizeAsset(asset: Map<String, Any?>, assetHosts: Set<String>): Map<String, Any?>? {
        val type = asset["type"] as? String ?: return null
        val fields = when (type) {
            "font" -> setOf("id", "type", "size", "align", "weight", "style")
            "color" -> setOf("id", "type", "value", "opacity", "darkValue", "darkOpacity")
            "assets" -> {
                if (!allowedUrl(asset["url"] as? String ?: return null, assetHosts)) return null
                setOf("id", "type", "url", "name", "opacity")
            }
            else -> return null
        }
        return java.util.Collections.unmodifiableMap(asset.filterKeys { it in fields })
    }

    /** Actions can reference only a package belonging to the supplied offering. */
    @JvmStatic
    public fun resolvePackage(offering: InappifyOffering, packageId: String): InappifyPackage? =
        offering.packages?.firstOrNull { it.identifier == packageId && !it.product?.identifier.isNullOrBlank() }
}

/**
 * Native, accessible package fallback for missing or unsupported Paywalls. Call on the main thread.
 * The callback receives a package from this exact offering; the host starts the existing purchase API.
 */
public object InappifyPaywall {
    @JvmStatic
    @JvmOverloads
    public fun createPackageView(
        context: Context,
        offering: InappifyOffering,
        typography: InappifyPaywallTypography = InappifyPaywallTypography(),
        locale: Locale = Locale.getDefault(),
        darkMode: Boolean = false,
        onPurchase: (InappifyPackage) -> Unit,
    ): View {
        typography.validate(context)
        val rtl = locale.language in setOf("fa", "ar", "he", "ur")
        val textColor = if (darkMode) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setPadding(24, 24, 24, 24)
            setBackgroundColor(if (darkMode) 0xff121212.toInt() else android.graphics.Color.WHITE)
        }
        offering.packages.orEmpty().filter { !it.product?.identifier.isNullOrBlank() && !it.identifier.isNullOrBlank() }
            .forEach { item ->
                content.addView(TextView(context).apply {
                    text = item.product?.name ?: item.name ?: item.identifier
                    textSize = 20f; typeface = typography.typeface(context, 700); setTextColor(textColor)
                    setPadding(0, 16, 0, 8)
                })
                content.addView(TextView(context).apply {
                    val price = item.product?.prices?.firstOrNull()
                    text = listOfNotNull(price?.let { "${it.price} ${it.currency.orEmpty()}" },
                        item.product?.trialDays?.takeIf { it > 0 }?.let {
                            if (rtl) "$it روز آزمایشی" else "$it trial days"
                        }).joinToString(" · ")
                    textSize = 16f; typeface = typography.typeface(context, 400); setTextColor(textColor)
                })
                content.addView(Button(context).apply {
                    text = if (locale.language == "fa") "خرید" else "Purchase"
                    contentDescription = "$text ${item.product?.name ?: item.name.orEmpty()}"
                    typeface = typography.typeface(context, 500)
                    setOnClickListener { onPurchase(item) }
                })
            }
        return ScrollView(context).apply { isFillViewport = true; addView(content) }
    }
}
