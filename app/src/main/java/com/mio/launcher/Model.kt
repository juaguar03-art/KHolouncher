package com.mio.launcher

import org.json.JSONArray
import org.json.JSONObject

// Un elemento del escritorio: app, carpeta o widget, ubicado en una pantalla y celda
class Item(
    var id: Long,
    var type: Int,
    var page: Int,
    var x: Int,
    var y: Int,
    var w: Int = 1,
    var h: Int = 1,
    var key: String? = null,
    var title: String = "Carpeta",
    val apps: MutableList<String> = mutableListOf(),
    var widgetId: Int = -1
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("type", type); put("page", page)
        put("x", x); put("y", y); put("w", w); put("h", h)
        key?.let { put("key", it) }
        put("title", title)
        put("apps", JSONArray(apps))
        put("wid", widgetId)
    }

    companion object {
        const val APP = 0
        const val FOLDER = 1
        const val WIDGET = 2

        fun fromJson(o: JSONObject): Item {
            val a = o.optJSONArray("apps")
            val list = mutableListOf<String>()
            if (a != null) for (i in 0 until a.length()) list.add(a.getString(i))
            return Item(
                o.getLong("id"), o.getInt("type"), o.getInt("page"),
                o.getInt("x"), o.getInt("y"), o.optInt("w", 1), o.optInt("h", 1),
                if (o.has("key")) o.getString("key") else null,
                o.optString("title", "Carpeta"), list, o.optInt("wid", -1)
            )
        }
    }
}
