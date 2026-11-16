package com.mio.launcher

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import kotlin.math.abs

// Lee un paquete de íconos estándar (formato ADW / Nova): appfilter.xml + drawables
class IconPack(ctx: Context, private val pkg: String) {
    private val res: Resources = ctx.packageManager.getResourcesForApplication(pkg)
    private val byComponent = HashMap<String, String>()   // "paquete/clase" -> drawable
    private val byPackage = HashMap<String, String>()     // respaldo si cambió la actividad
    private val backs = mutableListOf<String>()
    private var upon: String? = null
    private var mask: String? = null
    private var scale = 1f

    init { parse() }

    private fun parser(): XmlPullParser? {
        val id = res.getIdentifier("appfilter", "xml", pkg)
        if (id != 0) return res.getXml(id)
        return try {
            XmlPullParserFactory.newInstance().newPullParser().apply {
                setInput(res.assets.open("appfilter.xml"), "UTF-8")
            }
        } catch (_: Exception) { null }
    }

    private fun parse() {
        val p = parser() ?: return
        try {
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) when (p.name) {
                    "item" -> {
                        val comp = p.getAttributeValue(null, "component")
                        val dr = p.getAttributeValue(null, "drawable")
                        if (comp != null && dr != null) component(comp)?.let { (pk, cl) ->
                            byComponent.putIfAbsent("$pk/$cl", dr)
                            byPackage.putIfAbsent(pk, dr)
                        }
                    }
                    "iconback" -> for (i in 0 until p.attributeCount) p.getAttributeValue(i)?.let { backs.add(it) }
                    "iconupon" -> upon = if (p.attributeCount > 0) p.getAttributeValue(0) else null
                    "iconmask" -> mask = if (p.attributeCount > 0) p.getAttributeValue(0) else null
                    "scale" -> scale = p.getAttributeValue(null, "factor")?.toFloatOrNull() ?: 1f
                }
                ev = p.next()
            }
        } catch (_: Exception) { }
    }

    // "ComponentInfo{com.app/com.app.Main}" -> (com.app, com.app.Main)
    private fun component(s: String): Pair<String, String>? {
        val inner = s.substringAfter("{", "").substringBefore("}")
        val pk = inner.substringBefore("/", "")
        var cl = inner.substringAfter("/", "")
        if (pk.isEmpty() || cl.isEmpty()) return null
        if (cl.startsWith(".")) cl = pk + cl
        return pk to cl
    }

    fun drawable(name: String): Drawable? {
        var id = res.getIdentifier(name, "drawable", pkg)
        if (id == 0) id = res.getIdentifier(name, "mipmap", pkg)
        if (id == 0) return null
        return try { res.getDrawable(id, null) } catch (_: Exception) { null }
    }

    private fun bitmapOf(d: Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(b))
        return b
    }

    // Icono del paquete para esa app; si no lo tiene, el original sobre el fondo del paquete
    fun iconFor(appPkg: String, cls: String, original: Drawable, size: Int): Drawable? {
        (byComponent["$appPkg/$cls"] ?: byPackage[appPkg])?.let { name -> drawable(name)?.let { return it } }
        if (backs.isEmpty()) return null
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        drawable(backs[abs(appPkg.hashCode()) % backs.size])?.let { it.setBounds(0, 0, size, size); it.draw(c) }
        val s = (size * scale).toInt().coerceIn(1, size)
        val o = (size - s) / 2
        val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        original.setBounds(o, o, o + s, o + s)
        original.draw(lc)
        mask?.let { drawable(it) }?.let { m ->
            lc.drawBitmap(bitmapOf(m, size), 0f, 0f, Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            })
        }
        c.drawBitmap(layer, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        upon?.let { drawable(it) }?.let { it.setBounds(0, 0, size, size); it.draw(c) }
        return android.graphics.drawable.BitmapDrawable(res, out)
    }

    // Todos los íconos del paquete (drawable.xml si existe; si no, los de appfilter)
    fun allIcons(): List<String> {
        val names = LinkedHashSet<String>()
        try {
            val id = res.getIdentifier("drawable", "xml", pkg)
            val p: XmlPullParser? = if (id != 0) res.getXml(id) else try {
                XmlPullParserFactory.newInstance().newPullParser().apply {
                    setInput(res.assets.open("drawable.xml"), "UTF-8")
                }
            } catch (_: Exception) { null }
            if (p != null) {
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && p.name == "item")
                        p.getAttributeValue(null, "drawable")?.let { names.add(it) }
                    ev = p.next()
                }
            }
        } catch (_: Exception) { }
        if (names.isEmpty()) names.addAll(byComponent.values.sorted())
        return names.toList()
    }

    // El ícono que el paquete asigna a esa app (para ponerlo primero en la lista)
    fun suggested(appPkg: String, cls: String): String? = byComponent["$appPkg/$cls"] ?: byPackage[appPkg]

    companion object {
        val ACTIONS = listOf(
            "org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.teslacoilsw.launcher.THEME",
            "com.gau.go.launcherex.theme", "com.anddoes.launcher.THEME", "com.fede.launcher.THEME_ICONPACK"
        )

        // Paquetes de íconos instalados: (paquete, nombre)
        fun installed(ctx: Context): List<Pair<String, String>> {
            val pm = ctx.packageManager
            val found = LinkedHashMap<String, String>()
            for (a in ACTIONS) for (ri in pm.queryIntentActivities(Intent(a), 0))
                found[ri.activityInfo.packageName] = ri.loadLabel(pm).toString()
            return found.map { it.key to it.value }.sortedBy { it.second.lowercase() }
        }
    }
}
