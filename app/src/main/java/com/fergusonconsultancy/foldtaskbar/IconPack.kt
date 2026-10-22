package com.fergusonconsultancy.foldtaskbar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import org.xmlpull.v1.XmlPullParser

/**
 * Reads an installed icon pack in the standard Android format (the one Smart Launcher,
 * Nova and others use), so the bar and the grid show exactly the icons on your home
 * screen. Icon Pack Studio publishes the pack you designed this way: an appfilter.xml
 * that maps each app to a drawable, plus a background, mask and overlay for apps the pack
 * does not cover, so even those match.
 */
class IconPack(private val context: Context, val pkg: String) {

    private val res: Resources = context.packageManager.getResourcesForApplication(pkg)
    private val map = HashMap<String, String>()
    private val backs = mutableListOf<Bitmap>()
    private var mask: Bitmap? = null
    private var upon: Bitmap? = null
    private var scale = 1f
    val size get() = map.size

    init { load() }

    private fun load() {
        val parser: XmlPullParser? = run {
            res.getIdentifier("appfilter", "raw", pkg).takeIf { it != 0 }?.let { id ->
                android.util.Xml.newPullParser().apply { setInput(res.openRawResource(id), null) }
            } ?: res.getIdentifier("appfilter", "xml", pkg).takeIf { it != 0 }?.let { res.getXml(it) }
                ?: runCatching {
                    android.util.Xml.newPullParser().apply {
                        setInput(context.createPackageContext(pkg, 0).assets.open("appfilter.xml"), null)
                    }
                }.getOrNull()
        }
        parser ?: return
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val comp = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (comp != null && drawable != null) key(comp)?.let { map[it] = drawable }
                    }
                    "iconback" -> for (i in 0 until parser.attributeCount) bitmap(parser.getAttributeValue(i))?.let { backs += it }
                    "iconmask" -> mask = parser.getAttributeValue(null, "img1")?.let { bitmap(it) }
                    "iconupon" -> upon = parser.getAttributeValue(null, "img1")?.let { bitmap(it) }
                    "scale" -> scale = parser.getAttributeValue(null, "factor")?.toFloatOrNull() ?: 1f
                }
            }
            event = parser.next()
        }
    }

    /** "ComponentInfo{pkg/cls}" to "pkg/cls", with short class names expanded. */
    private fun key(raw: String): String? {
        val inner = raw.substringAfter("{", "").substringBefore("}", "")
        if (!inner.contains('/')) return null
        val (p, c) = inner.split('/', limit = 2)
        return "$p/${if (c.startsWith(".")) p + c else c}"
    }

    private fun drawable(name: String): Drawable? {
        val id = res.getIdentifier(name, "drawable", pkg).takeIf { it != 0 }
            ?: res.getIdentifier(name, "mipmap", pkg).takeIf { it != 0 } ?: return null
        return runCatching { res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null) }.getOrNull()
            ?: runCatching { res.getDrawable(id, null) }.getOrNull()
    }

    private fun bitmap(name: String): Bitmap? = drawable(name)?.let { toBitmap(it, 192) }

    private fun toBitmap(d: Drawable, size: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return b
    }

    /**
     * The pack's icon for an app, or, for an app the pack does not cover, the app's own
     * icon set into the pack's background, mask and overlay, as launchers do.
     */
    fun iconFor(component: ComponentName, fallback: Drawable?): Drawable? {
        map["${component.packageName}/${component.className}"]?.let { name -> drawable(name)?.let { return it } }
        if (fallback == null || backs.isEmpty()) return fallback
        val size = 192
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val back = backs[(component.hashCode() and 0x7fffffff) % backs.size]
        c.drawBitmap(Bitmap.createScaledBitmap(back, size, size, true), 0f, 0f, null)
        val inner = (size * scale).toInt().coerceAtLeast(1)
        val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        val off = (size - inner) / 2
        fallback.setBounds(off, off, off + inner, off + inner)
        fallback.draw(lc)
        mask?.let { m ->
            lc.drawBitmap(Bitmap.createScaledBitmap(m, size, size, true), 0f, 0f,
                Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        }
        c.drawBitmap(layer, 0f, 0f, null)
        upon?.let { u -> c.drawBitmap(Bitmap.createScaledBitmap(u, size, size, true), 0f, 0f, null) }
        return BitmapDrawable(context.resources, out)
    }

    companion object {
        const val ICON_PACK_STUDIO = "ginlemon.iconpackstudio"

        /** Installed icon packs, Icon Pack Studio first, as package name to label. */
        fun installed(context: Context): List<Pair<String, String>> {
            val pm = context.packageManager
            val found = linkedMapOf<String, String>()
            runCatching {
                pm.getApplicationInfo(ICON_PACK_STUDIO, 0)
                found[ICON_PACK_STUDIO] = "Icon Pack Studio"
            }
            listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.gau.go.launcherex.theme",
                "com.anddoes.launcher.THEME", "com.teslacoilsw.launcher.THEME").forEach { action ->
                pm.queryIntentActivities(Intent(action), 0).forEach { r ->
                    val p = r.activityInfo.packageName
                    if (p !in found) found[p] = r.loadLabel(pm).toString()
                }
            }
            return found.map { it.key to it.value }
        }
    }
}
