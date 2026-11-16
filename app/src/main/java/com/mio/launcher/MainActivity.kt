package com.mio.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.*
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Base64
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

val HOLO_BLUE = 0xFF33B5E5.toInt()
val HOLO_RED = 0xFFFF4444.toInt()
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
const val REQ_ICON = 42
const val REQ_BIND = 43
const val REQ_CONFIG = 44
const val REQ_WALL = 45
const val REQ_EXPORT = 46
const val REQ_IMPORT = 47
const val WIDGET_HOST_ID = 2048

class App(
    val key: String, val pkg: String, val cls: String,
    val originalLabel: String, val originalIcon: Drawable,
    val self: Boolean = false   // la entrada del propio launcher en el cajón
) {
    var label = originalLabel
    var icon: Drawable = originalIcon
}

// Qué se está arrastrando y de dónde viene
class DragSource(
    val item: Item? = null,          // elemento del escritorio
    val appKey: String? = null,      // app del cajón, del dock o de una carpeta
    val dockSlot: Int = -1,
    val folder: Item? = null,        // carpeta de origen
    val fromDrawer: Boolean = false
) {
    val key: String? get() = appKey ?: item?.key
    val isApp: Boolean get() = appKey != null || item?.type == Item.APP
}

class WidgetEntry(val info: AppWidgetProviderInfo, val label: String, val preview: Drawable?, val w: Int, val h: Int)

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private val apps = mutableListOf<App>()          // todas las apps
    private val drawerApps = mutableListOf<App>()    // visibles en el cajón (sin ocultas, filtradas)
    private val byKey = HashMap<String, App>()
    private val hidden = HashSet<String>()
    private val items = mutableListOf<Item>()        // escritorio
    private var nextId = 1L
    private val dock = arrayOfNulls<String>(4)
    private val dockViews = arrayOfNulls<ImageView>(4)
    private val removedPkgs = HashSet<String>()
    private var rows = 5

    private lateinit var root: FrameLayout
    private lateinit var homeLayout: LinearLayout
    private lateinit var clockBox: LinearLayout
    private lateinit var pager: PagedView
    private lateinit var indicator: PageIndicator
    private lateinit var dockBar: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var appsPanel: LinearLayout
    private lateinit var drawerGrid: GridView
    private lateinit var widgetsGrid: GridView
    private lateinit var search: EditText
    private lateinit var tabApps: Tab
    private lateinit var tabWidgets: Tab
    private lateinit var dropBar: LinearLayout
    private lateinit var dropRemove: TextView
    private lateinit var dropInfo: TextView
    private lateinit var drawerAdapter: AppAdapter
    private val widgetList = mutableListOf<WidgetEntry>()
    private lateinit var widgetAdapter: WidgetAdapter
    private lateinit var gestures: GestureDetector
    private var selectableBg = 0

    private lateinit var widgetHost: LauncherWidgetHost
    private val widgetManager by lazy { AppWidgetManager.getInstance(this) }
    private val widgetViews = HashMap<Int, AppWidgetHostView>()
    private val widgetSizes = HashMap<Int, Pair<Int, Int>>()
    private var pendingWidgetId = -1
    private var pendingIconKey: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val reloadTask = Runnable { loadApps() }
    private var loadGen = 0

    // Arrastrar y soltar
    private var drag: DragSource? = null
    private var dragStarted = false
    private var dragBmp: Bitmap? = null
    private var dragView: ImageView? = null
    private var draggingItem: Item? = null
    private var dragOffX = 0f
    private var dragOffY = 0f
    private var startRawX = 0f
    private var startRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var edgeDir = 0
    private val edgeTask = Runnable {
        if (drag != null && edgeDir != 0) {
            pager.snapTo(pager.current + edgeDir)
            edgeDir = 0
            handler.postDelayed({ updateDropHint() }, 340)
        }
    }

    // Carpeta abierta
    private var folderView: View? = null
    private var folderItem: Item? = null
    private var folderTitle: EditText? = null

    // ---------- Ajustes ----------
    private val columns get() = prefs.getInt("cols", 4)
    private val showClock get() = prefs.getBoolean("clock", false)
    private val homeLabels get() = prefs.getBoolean("home_labels", true)
    private val autoKeyboard get() = prefs.getBoolean("auto_kb", false)
    private val haptics get() = prefs.getBoolean("haptics", true)

    private val pkgReceiver = object : BroadcastReceiver() {
        // Algunos teléfonos mandan muchos avisos seguidos: se agrupan en una sola recarga
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_PACKAGE_REMOVED && !i.getBooleanExtra(Intent.EXTRA_REPLACING, false))
                i.data?.schemeSpecificPart?.let { removedPkgs.add(it) }
            handler.removeCallbacks(reloadTask)
            handler.postDelayed(reloadTask, 400)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val imm get() = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        pendingIconKey = b?.getString("pending")
        pendingWidgetId = b?.getInt("pw", -1) ?: -1
        widgetHost = LauncherWidgetHost(applicationContext, WIDGET_HOST_ID)

        val dm = resources.displayMetrics
        rows = if (max(dm.heightPixels, dm.widthPixels) / dm.density > 720) 6 else 5
        loadItems()

        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        selectableBg = tv.resourceId

        drawerAdapter = AppAdapter()
        widgetAdapter = WidgetAdapter()
        root = FrameLayout(this).apply { isFocusableInTouchMode = true }

        // ---------- Escritorio ----------
        homeLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clockBox = makeClock()
        homeLayout.addView(clockBox, LinearLayout.LayoutParams(MATCH, WRAP))

        indicator = PageIndicator(this)
        pager = PagedView(this).apply { onScroll = { indicator.pos = it } }
        pager.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateWidgetSizes() }
        homeLayout.addView(pager, LinearLayout.LayoutParams(MATCH, 0, 1f))
        homeLayout.addView(indicator, LinearLayout.LayoutParams(MATCH, dp(14)))
        homeLayout.addView(View(this).apply { setBackgroundColor(Color.argb(60, 255, 255, 255)) },
            LinearLayout.LayoutParams(MATCH, 1).apply { setMargins(dp(16), 0, dp(16), 0) })

        // ---------- Dock ----------
        dockBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(12))
        }
        for (i in 0..4) {
            val lp = LinearLayout.LayoutParams(0, dp(64), 1f)
            if (i == 2) {
                dockBar.addView(AllAppsButton(this).apply {
                    contentDescription = "Aplicaciones"
                    setOnClickListener { openDrawer() }
                }, lp)
            } else {
                val slot = if (i < 2) i else i - 1
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setBackgroundResource(selectableBg)
                    setOnClickListener { dock[slot]?.let { byKey[it] }?.let { launch(it) } }
                    setOnLongClickListener { v ->
                        val key = dock[slot]
                        if (key != null && byKey[key] != null) beginDrag(DragSource(appKey = key, dockSlot = slot), v)
                        key != null
                    }
                }
                dockViews[slot] = iv
                dockBar.addView(iv, lp)
            }
        }
        homeLayout.addView(dockBar, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(homeLayout, FrameLayout.LayoutParams(MATCH, MATCH))

        // ---------- Barra de soltar (arriba, al arrastrar) ----------
        dropBar = makeDropBar()
        root.addView(dropBar, FrameLayout.LayoutParams(MATCH, dp(56), Gravity.TOP))

        // ---------- Cajón ----------
        drawer = makeDrawer()
        root.addView(drawer, FrameLayout.LayoutParams(MATCH, MATCH))

        setContentView(root)
        root.requestFocus()

        // ---------- Gestos ----------
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (abs(dy) < dp(80) || abs(dy) < abs(dx) * 1.5f || abs(vy) < 800) return false
                if (drawer.visibility == View.VISIBLE) {
                    if (dy > 0 && drawerContentAtTop()) { closeDrawer(); return true }
                    return false
                }
                if (dy < 0) openDrawer() else expandNotifications()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (drawer.visibility == View.VISIBLE || drag != null) return
                if (!isOnEmptyHome(e.rawX, e.rawY)) return
                buzz(window.decorView)
                launcherMenu()
            }
        })

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, RECEIVER_EXPORTED)
        else registerReceiver(pkgReceiver, f)

        applySettings()
        loadApps()
        if (b == null && fromOtherLauncher(intent)) handler.post { launcherMenu() }
    }

    // Abierto desde el icono en otro launcher (activity-alias .LauncherEntry)
    private fun fromOtherLauncher(i: Intent?) =
        i?.component?.className?.endsWith(".LauncherEntry") == true

    override fun onStart() {
        super.onStart()
        try { widgetHost.startListening() } catch (_: Exception) { }
    }

    override fun onStop() {
        super.onStop()
        try { widgetHost.stopListening() } catch (_: Exception) { }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(pkgReceiver)
        super.onDestroy()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("pending", pendingIconKey)
        out.putInt("pw", pendingWidgetId)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastRawX = ev.rawX
        lastRawY = ev.rawY
        if (drag != null) { handleDrag(ev); return true }
        if (::gestures.isInitialized && folderView == null) gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ================= Datos =================
    private fun iconFile(key: String) = File(filesDir, "icons/" + key.replace('/', '_') + ".png")

    private fun loadItems() {
        items.clear()
        val s = prefs.getString("items", null)
        if (s != null) {
            try {
                val a = JSONArray(s)
                for (i in 0 until a.length()) items.add(Item.fromJson(a.getJSONObject(i)))
            } catch (_: Exception) { }
        } else {
            // Migración desde la versión anterior (lista simple del escritorio)
            val per = columns * rows
            prefs.getString("home", "")!!.split(";").filter { it.isNotEmpty() }.forEachIndexed { i, k ->
                items.add(Item(i + 1L, Item.APP, i / per, (i % per) % columns, (i % per) / columns, key = k))
            }
        }
        nextId = (items.maxOfOrNull { it.id } ?: 0L) + 1
    }

    // Dibuja el icono una sola vez en un bitmap del tamaño final: hace el scroll mucho más fluido
    private fun raster(d: Drawable): Drawable {
        val s = dp(52)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, s, s)
        d.draw(Canvas(bmp))
        return BitmapDrawable(resources, bmp)
    }

    // Copia para que cada vista tenga su propio drawable
    private fun Drawable.copy(): Drawable =
        (this as? BitmapDrawable)?.bitmap?.let { BitmapDrawable(resources, it) } ?: this

    private fun buildApp(key: String, pkg: String, cls: String, label: String, icon: Drawable, self: Boolean = false): App {
        val app = App(key, pkg, cls, label, raster(icon), self)
        prefs.getString("name_$key", null)?.let { app.label = it }
        val file = iconFile(key)
        if (file.exists()) BitmapFactory.decodeFile(file.path)?.let {
            app.icon = BitmapDrawable(resources, it)
        }
        return app
    }

    // Carga en segundo plano para no trabar la interfaz
    private fun loadApps() {
        val gen = ++loadGen
        Thread {
            val list = mutableListOf<App>()
            try {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                for (ri in packageManager.queryIntentActivities(intent, 0)) {
                    val ai = ri.activityInfo
                    if (ai.packageName == packageName) continue
                    list.add(buildApp("${ai.packageName}/${ai.name}", ai.packageName, ai.name,
                        ri.loadLabel(packageManager).toString(), ri.loadIcon(packageManager)))
                }
                // El propio launcher, para poder abrir sus ajustes o desinstalarlo desde el cajón
                list.add(buildApp("$packageName/self", packageName, "",
                    applicationInfo.loadLabel(packageManager).toString(),
                    packageManager.getApplicationIcon(packageName), self = true))
            } catch (_: Exception) { }
            list.sortBy { it.label.lowercase() }
            runOnUiThread {
                if (gen != loadGen || isDestroyed) return@runOnUiThread
                apps.clear(); byKey.clear()
                apps.addAll(list)
                list.forEach { byKey[it.key] = it }
                widgetList.clear()
                loadState()
            }
        }.start()
    }

    private fun loadState() {
        hidden.clear()
        hidden.addAll(prefs.getStringSet("hidden", emptySet())!!)
        for (i in 0..3) dock[i] = prefs.getString("dock_$i", null)
        // Quitar del escritorio las apps desinstaladas (no las que solo se actualizan)
        if (removedPkgs.isNotEmpty()) {
            fun gone(k: String?) = k != null && k.substringBefore('/') in removedPkgs
            items.removeAll { it.type == Item.APP && gone(it.key) }
            items.filter { it.type == Item.FOLDER }.forEach { f -> f.apps.removeAll { gone(it) } }
            for (i in 0..3) if (gone(dock[i])) dock[i] = null
            removedPkgs.clear()
            fixFolders()
            save()
        }
        refresh()
    }

    private fun refresh() {
        if (drag == null) rebuildWorkspace()
        filterDrawer()
        refreshDock()
    }

    private fun refreshDock() {
        for (i in 0..3) dockViews[i]?.setImageDrawable(dock[i]?.let { byKey[it] }?.icon?.copy())
    }

    private fun norm(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    private fun filterDrawer() {
        val q = if (::search.isInitialized) norm(search.text.toString().trim()) else ""
        drawerApps.clear()
        apps.filterTo(drawerApps) { it.key !in hidden && (q.isEmpty() || norm(it.label).contains(q)) }
        drawerAdapter.notifyDataSetChanged()
    }

    private fun save() {
        val e = prefs.edit().putString("items", JSONArray(items.map { it.toJson() }).toString())
        for (i in 0..3) e.putString("dock_$i", dock[i])
        e.putStringSet("hidden", HashSet(hidden))
        e.apply()
    }

    private fun commit() {
        fixFolders()
        normalizePages()
        save()
        rebuildWorkspace()
        refreshDock()
    }

    private fun applySettings() {
        clockBox.visibility = if (showClock) View.VISIBLE else View.GONE
        drawerGrid.numColumns = columns
        drawerGrid.isHapticFeedbackEnabled = haptics
        fitItems()
        rebuildWorkspace()
        drawerAdapter.notifyDataSetChanged()
    }

    // ================= Cuadrícula del escritorio =================
    private fun fits(page: Int, x: Int, y: Int, w: Int, h: Int, among: List<Item> = items, exclude: Item? = null): Boolean {
        if (x < 0 || y < 0 || x + w > columns || y + h > rows) return false
        return among.none { o ->
            o !== exclude && o.page == page &&
                x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
        }
    }

    private fun findSpot(startPage: Int, w: Int, h: Int, among: List<Item> = items, exclude: Item? = null): Triple<Int, Int, Int> {
        var p = max(0, startPage)
        while (p < 100) {
            for (y in 0..(rows - h)) for (x in 0..(columns - w))
                if (fits(p, x, y, w, h, among, exclude)) return Triple(p, x, y)
            p++
        }
        return Triple(p, 0, 0)
    }

    private fun itemAt(page: Int, cx: Int, cy: Int, exclude: Item?): Item? =
        items.firstOrNull { o ->
            o !== exclude && o.page == page && cx >= o.x && cx < o.x + o.w && cy >= o.y && cy < o.y + o.h
        }

    // Acomoda lo que no cabe (por ejemplo al cambiar de 5 a 4 columnas)
    private fun fitItems() {
        val placed = mutableListOf<Item>()
        for (it in items.sortedWith(compareBy({ it.page }, { it.y }, { it.x }))) {
            it.w = it.w.coerceIn(1, columns)
            it.h = it.h.coerceIn(1, rows)
            if (!fits(it.page, it.x, it.y, it.w, it.h, placed)) {
                val s = findSpot(it.page, it.w, it.h, placed)
                it.page = s.first; it.x = s.second; it.y = s.third
            }
            placed.add(it)
        }
    }

    // Quita pantallas vacías
    private fun normalizePages() {
        val used = items.map { it.page }.distinct().sorted()
        val map = HashMap<Int, Int>()
        used.forEachIndexed { i, p -> map[p] = i }
        items.forEach { it.page = map[it.page] ?: 0 }
    }

    private fun fixFolders() {
        for (f in items.filter { it.type == Item.FOLDER }) {
            when (f.apps.size) {
                0 -> items.remove(f)
                1 -> {
                    items.remove(f)
                    items.add(Item(nextId++, Item.APP, f.page, f.x, f.y, key = f.apps[0]))
                }
            }
        }
    }

    private fun placeApp(key: String) {
        val s = findSpot(pager.current, 1, 1)
        items.add(Item(nextId++, Item.APP, s.first, s.second, s.third, key = key))
    }

    private fun removeItem(it: Item) {
        if (it.type == Item.WIDGET) {
            try { widgetHost.deleteAppWidgetId(it.widgetId) } catch (_: Exception) { }
            widgetViews.remove(it.widgetId)
            widgetSizes.remove(it.widgetId)
        }
        items.remove(it)
    }

    private fun rebuildWorkspace(extraPage: Boolean = false) {
        if (!::pager.isInitialized) return
        val keep = pager.current
        for (i in 0 until pager.pageCount) pager.page(i).removeAllViews()
        pager.removeAllViews()
        val n = (items.maxOfOrNull { it.page } ?: 0) + 1 + (if (extraPage) 1 else 0)
        for (p in 0 until n) pager.addView(CellLayout(this, columns, rows).apply {
            setPadding(dp(4), dp(8), dp(4), 0)
        }, ViewGroup.LayoutParams(MATCH, MATCH))
        for (it in items) {
            if (it === draggingItem) continue
            val v = makeItemView(it) ?: continue
            pager.page(it.page).addView(v, CellLayout.LP(it.x, it.y, it.w, it.h))
        }
        pager.snapTo(keep, false)
        indicator.count = n
        indicator.pos = pager.current.toFloat()
    }

    private fun bindCell(cell: LinearLayout, icon: Drawable, label: String, showLabel: Boolean) {
        (cell.getChildAt(0) as ImageView).setImageDrawable(icon)
        (cell.getChildAt(1) as TextView).apply {
            text = label
            visibility = if (showLabel) View.VISIBLE else View.GONE
        }
        cell.isHapticFeedbackEnabled = haptics
    }

    private fun makeItemView(item: Item): View? = when (item.type) {
        Item.APP -> {
            val app = byKey[item.key]
            if (app == null) null else makeCell().apply {
                gravity = Gravity.CENTER
                setBackgroundResource(selectableBg)
                bindCell(this, app.icon.copy(), app.label, homeLabels)
                setOnClickListener { launch(app) }
                setOnLongClickListener { v -> beginDrag(DragSource(item = item), v); true }
            }
        }
        Item.FOLDER -> makeCell().apply {
            gravity = Gravity.CENTER
            setBackgroundResource(selectableBg)
            bindCell(this, folderIcon(item), item.title, homeLabels)
            setOnClickListener { openFolder(item) }
            setOnLongClickListener { v -> beginDrag(DragSource(item = item), v); true }
        }
        Item.WIDGET -> widgetView(item)
        else -> null
    }

    // Icono de carpeta estilo ICS: círculo oscuro con hasta 3 iconos apilados
    private fun folderIcon(f: Item): Drawable {
        val s = dp(52)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val d = resources.displayMetrics.density
        val r = s / 2f - 2 * d
        p.color = Color.argb(160, 20, 20, 20)
        c.drawCircle(s / 2f, s / 2f, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * d
        p.color = Color.argb(200, 255, 255, 255)
        c.drawCircle(s / 2f, s / 2f, r, p)
        p.style = Paint.Style.FILL
        val icons = f.apps.mapNotNull { (byKey[it]?.icon as? BitmapDrawable)?.bitmap }.take(3)
        for (i in icons.indices.reversed()) {
            val sz = s * (0.56f - i * 0.07f)
            val cx = s / 2f
            val cy = s / 2f + s * 0.07f - i * s * 0.1f
            p.alpha = if (i == 0) 255 else 210
            c.drawBitmap(icons[i], null, RectF(cx - sz / 2, cy - sz / 2, cx + sz / 2, cy + sz / 2), p)
        }
        return BitmapDrawable(resources, bmp)
    }

    // ================= Interfaz =================
    inner class AppAdapter : BaseAdapter() {
        override fun getCount() = drawerApps.size
        override fun getItem(p: Int): App = drawerApps[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val cell = (convert as? LinearLayout) ?: makeCell()
            val app = getItem(p)
            (cell.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            (cell.getChildAt(1) as TextView).text = app.label
            return cell
        }
    }

    inner class WidgetAdapter : BaseAdapter() {
        override fun getCount() = widgetList.size
        override fun getItem(p: Int): WidgetEntry = widgetList[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val cell = (convert as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(12), dp(6), dp(12))
                addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
                    LinearLayout.LayoutParams(MATCH, dp(100)))
                addView(TextView(context).apply {
                    setTextColor(Color.WHITE); textSize = 13f; maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, 0)
                }, LinearLayout.LayoutParams(MATCH, WRAP))
                addView(TextView(context).apply {
                    setTextColor(Color.argb(150, 255, 255, 255)); textSize = 11f; gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            val e = getItem(p)
            (cell.getChildAt(0) as ImageView).setImageDrawable(e.preview)
            (cell.getChildAt(1) as TextView).text = e.label
            (cell.getChildAt(2) as TextView).text = "${e.w} × ${e.h}"
            return cell
        }
    }

    private fun makeCell() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(10), 0, dp(10))
        addView(ImageView(context), LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
            setPadding(dp(2), dp(4), dp(2), 0)
        }, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private inner class Tab(label: String, onClick: () -> Unit) {
        val tv = TextView(this@MainActivity).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        val line = View(this@MainActivity)
        val box = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv)
            addView(line, LinearLayout.LayoutParams(MATCH, dp(4)))
            setOnClickListener { onClick() }
        }

        fun select(on: Boolean) {
            tv.alpha = if (on) 1f else 0.55f
            line.setBackgroundColor(if (on) HOLO_BLUE else Color.TRANSPARENT)
        }
    }

    private fun makeDrawer(): LinearLayout {
        val d = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 0, 0, 0))
            visibility = View.GONE
            isClickable = true
        }
        tabApps = Tab("APLICACIONES") { selectTab(false) }
        tabWidgets = Tab("WIDGETS") { selectTab(true) }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(tabApps.box, LinearLayout.LayoutParams(WRAP, WRAP))
        tabs.addView(tabWidgets.box, LinearLayout.LayoutParams(WRAP, WRAP))
        d.addView(tabs, LinearLayout.LayoutParams(MATCH, WRAP))
        d.addView(View(this).apply { setBackgroundColor(HOLO_BLUE); alpha = 0.5f },
            LinearLayout.LayoutParams(MATCH, dp(1)))

        appsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        search = EditText(this).apply {
            hint = "Buscar aplicaciones"
            setSingleLine()
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(110, 255, 255, 255))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO
            setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0)
            compoundDrawablePadding = dp(6)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = filterDrawer()
            })
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) {
                    drawerApps.firstOrNull()?.let { launch(it) }; true
                } else false
            }
        }
        appsPanel.addView(search, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(dp(12), dp(8), dp(12), 0)
        })
        drawerGrid = GridView(this).apply {
            numColumns = 4
            adapter = drawerAdapter
            overScrollMode = View.OVER_SCROLL_NEVER   // sin el efecto "estirar" de Android 12+
            isFastScrollEnabled = true
            setPadding(dp(4), dp(8), dp(4), dp(8))
            clipToPadding = false
            setOnItemClickListener { _, _, p, _ -> launch(drawerApps[p]) }
            setOnItemLongClickListener { _, v, p, _ ->
                beginDrag(DragSource(appKey = drawerApps[p].key, fromDrawer = true), v); true
            }
        }
        appsPanel.addView(drawerGrid, LinearLayout.LayoutParams(MATCH, 0, 1f))
        d.addView(appsPanel, LinearLayout.LayoutParams(MATCH, 0, 1f))

        widgetsGrid = GridView(this).apply {
            numColumns = 2
            adapter = widgetAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(4), dp(8), dp(4), dp(8))
            clipToPadding = false
            visibility = View.GONE
            setOnItemClickListener { _, _, p, _ ->
                val e = widgetList[p]
                hideDrawerNow()
                addWidget(e.info)
            }
        }
        d.addView(widgetsGrid, LinearLayout.LayoutParams(MATCH, 0, 1f))
        tabApps.select(true)
        tabWidgets.select(false)
        return d
    }

    private fun selectTab(widgets: Boolean) {
        tabApps.select(!widgets)
        tabWidgets.select(widgets)
        appsPanel.visibility = if (widgets) View.GONE else View.VISIBLE
        widgetsGrid.visibility = if (widgets) View.VISIBLE else View.GONE
        if (widgets) {
            imm.hideSoftInputFromWindow(search.windowToken, 0)
            search.clearFocus()
            if (widgetList.isEmpty()) loadWidgets()
        }
    }

    private fun drawerContentAtTop() =
        if (widgetsGrid.visibility == View.VISIBLE) !widgetsGrid.canScrollVertically(-1)
        else !drawerGrid.canScrollVertically(-1)

    private fun makeDropBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(210, 0, 0, 0))
            visibility = View.GONE
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun label(t: String) = TextView(this).apply {
            text = t
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }
        dropRemove = label("✕  QUITAR")
        dropInfo = label("ⓘ  INFO")
        row.addView(dropRemove, LinearLayout.LayoutParams(0, MATCH, 1f))
        row.addView(dropInfo, LinearLayout.LayoutParams(0, MATCH, 1f))
        bar.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))
        bar.addView(View(this).apply { setBackgroundColor(HOLO_BLUE); alpha = 0.6f },
            LinearLayout.LayoutParams(MATCH, dp(2)))
        return bar
    }

    // Reloj estilo Holo (fuente fina), se activa en Ajustes del launcher
    private fun makeClock() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(28), 0, dp(12))
        setOnClickListener { safeStart(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
        addView(TextClock(context).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            textSize = 72f
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 2f, Color.argb(120, 0, 0, 0))
        })
        addView(TextClock(context).apply {
            format12Hour = "EEEE, d 'de' MMMM"
            format24Hour = "EEEE, d 'de' MMMM"
            textSize = 13f
            isAllCaps = true
            letterSpacing = 0.12f
            setTextColor(Color.argb(230, 255, 255, 255))
            setShadowLayer(4f, 0f, 1f, Color.argb(120, 0, 0, 0))
        })
    }

    private fun buzz(v: View) {
        if (haptics) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun safeStart(i: Intent) {
        try { startActivity(i) } catch (e: Exception) { toast("No disponible en este teléfono") }
    }

    private fun hit(v: View, x: Float, y: Float): Boolean {
        if (!v.isShown) return false
        val l = IntArray(2)
        v.getLocationOnScreen(l)
        return x >= l[0] && x < l[0] + v.width && y >= l[1] && y < l[1] + v.height
    }

    private fun isOnEmptyHome(x: Float, y: Float): Boolean {
        if (folderView != null || pager.pageCount == 0) return false
        if (hit(dockBar, x, y) || !hit(pager, x, y)) return false
        val cl = pager.page(pager.current)
        val l = IntArray(2)
        cl.getLocationOnScreen(l)
        return cl.childAt(x - l[0], y - l[1]) == null
    }

    private fun expandNotifications() {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel").invoke(sb)
        } catch (_: Exception) { }
    }

    // ================= Cajón =================
    private fun openDrawer() {
        closeFolder()
        drawer.alpha = 0f; drawer.scaleX = 0.9f; drawer.scaleY = 0.9f
        drawer.visibility = View.VISIBLE
        drawer.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
            .withEndAction { homeLayout.visibility = View.INVISIBLE }.start()  // no dibujar el escritorio detrás
        if (autoKeyboard && appsPanel.visibility == View.VISIBLE) {
            search.requestFocus()
            search.postDelayed({ imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT) }, 150)
        }
    }

    private fun resetDrawer() {
        imm.hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
        if (search.text.isNotEmpty()) search.setText("")
        drawerGrid.setSelection(0)
        selectTab(false)
    }

    private fun hideDrawerNow() {
        drawer.animate().cancel()
        drawer.visibility = View.GONE
        homeLayout.visibility = View.VISIBLE
        resetDrawer()
    }

    private fun closeDrawer() {
        homeLayout.visibility = View.VISIBLE
        imm.hideSoftInputFromWindow(search.windowToken, 0)
        drawer.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(150)
            .withEndAction { drawer.visibility = View.GONE; resetDrawer() }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            folderView != null -> { closeFolder(); commit() }
            drawer.visibility == View.VISIBLE -> closeDrawer()
        }
    }

    override fun onNewIntent(intent: Intent) {
        val alreadyHome = hasWindowFocus() &&
            (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0
        super.onNewIntent(intent)
        when {
            folderView != null -> { closeFolder(); commit() }
            drawer.visibility == View.VISIBLE -> closeDrawer()
            alreadyHome -> pager.snapTo(0)   // botón de inicio estando en el escritorio: pantalla principal
        }
        if (fromOtherLauncher(intent)) handler.post { launcherMenu() }
    }

    private fun launch(app: App) {
        if (app.self) { launcherMenu(); return }
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(app.pkg, app.cls)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            if (drawer.visibility == View.VISIBLE) hideDrawerNow()
        } catch (e: Exception) {
            toast("No se pudo abrir")
        }
    }

    // ================= Carpetas =================
    private fun openFolder(f: Item) {
        closeFolder()
        folderItem = f
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            isClickable = true
            setOnClickListener { closeFolder(); commit() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(240, 20, 20, 20))
                setStroke(dp(1), Color.argb(120, 51, 181, 229))
                cornerRadius = dp(2).toFloat()
            }
            setPadding(dp(8), dp(12), dp(8), dp(8))
            isClickable = true
        }
        val grid = GridLayout(this).apply { columnCount = min(4, max(2, f.apps.size)) }
        for (k in f.apps.toList()) {
            val app = byKey[k] ?: continue
            val cell = makeCell().apply {
                setBackgroundResource(selectableBg)
                bindCell(this, app.icon.copy(), app.label, true)
                setOnClickListener { closeFolder(); commit(); launch(app) }
                setOnLongClickListener { v -> beginDrag(DragSource(appKey = k, folder = f), v); true }
            }
            grid.addView(cell, ViewGroup.LayoutParams(dp(80), WRAP))
        }
        panel.addView(grid, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val title = EditText(this).apply {
            setText(f.title)
            setSingleLine()
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { v, a, _ ->
                if (a == EditorInfo.IME_ACTION_DONE) {
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                    v.clearFocus()
                    text.toString().trim().takeIf { it.isNotEmpty() }?.let { f.title = it; save() }
                    true
                } else false
            }
        }
        folderTitle = title
        panel.addView(title, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        scrim.addView(panel, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        folderView = scrim
        panel.alpha = 0f; panel.scaleX = 0.85f; panel.scaleY = 0.85f
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    private fun closeFolder() {
        val v = folderView ?: return
        val f = folderItem
        folderTitle?.let { t ->
            imm.hideSoftInputFromWindow(t.windowToken, 0)
            val s = t.text.toString().trim()
            if (f != null && s.isNotEmpty()) f.title = s
        }
        root.removeView(v)
        folderView = null
        folderItem = null
        folderTitle = null
    }

    // ================= Arrastrar y soltar =================
    private sealed class Target {
        object None : Target()
        object Remove : Target()
        object Info : Target()
        class Dock(val slot: Int) : Target()
        class Cell(val page: Int, val x: Int, val y: Int, val over: Item?) : Target()
    }

    private fun beginDrag(src: DragSource, v: View, vibrate: Boolean = false) {
        if (drag != null) return
        drag = src
        dragStarted = false
        startRawX = lastRawX
        startRawY = lastRawY
        if (vibrate) buzz(v)
        v.isPressed = false
        val bmp = Bitmap.createBitmap(max(1, v.width), max(1, v.height), Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        dragBmp = bmp
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        dragOffX = startRawX - loc[0]
        dragOffY = startRawY - loc[1]
        // Cancela el toque en las vistas: desde aquí lo maneja el arrastre
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun handleDrag(ev: MotionEvent) {
        val src = drag ?: return
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragStarted) {
                    if (hypot(ev.rawX - startRawX, ev.rawY - startRawY) < dp(12)) return
                    startDragVisual()
                }
                moveDragView()
                updateDropHint()
                checkEdge()
            }
            MotionEvent.ACTION_UP -> {
                if (!dragStarted) { resetDrag(); showMenuFor(src) }
                else { drop(src); endDrag() }
            }
            MotionEvent.ACTION_CANCEL -> if (dragStarted) endDrag() else resetDrag()
        }
    }

    private fun startDragVisual() {
        val src = drag ?: return
        dragStarted = true
        if (drawer.visibility == View.VISIBLE) hideDrawerNow()
        if (folderView != null) closeFolder()
        draggingItem = src.item
        if (src.dockSlot >= 0) dockViews[src.dockSlot]?.alpha = 0.25f
        rebuildWorkspace(extraPage = true)
        val bmp = dragBmp ?: return
        val dv = ImageView(this).apply {
            setImageBitmap(bmp)
            alpha = 0.85f
            scaleX = 1.08f
            scaleY = 1.08f
        }
        dragView = dv
        root.addView(dv, FrameLayout.LayoutParams(bmp.width, bmp.height))
        dropRemove.text = if (src.fromDrawer) "✕  DESINSTALAR" else "✕  QUITAR"
        dropInfo.visibility = if (src.isApp) View.VISIBLE else View.GONE
        dropBar.visibility = View.VISIBLE
        dropBar.bringToFront()
        dragView?.bringToFront()
        moveDragView()
    }

    private fun moveDragView() {
        val v = dragView ?: return
        val rl = IntArray(2)
        root.getLocationOnScreen(rl)
        v.x = lastRawX - dragOffX - rl[0]
        v.y = lastRawY - dragOffY - rl[1]
    }

    private fun checkEdge() {
        val w = resources.displayMetrics.widthPixels
        val dir = when {
            hit(dropBar, lastRawX, lastRawY) || hit(dockBar, lastRawX, lastRawY) -> 0
            lastRawX < w * 0.06f -> -1
            lastRawX > w * 0.94f -> 1
            else -> 0
        }
        if (dir != edgeDir) {
            handler.removeCallbacks(edgeTask)
            edgeDir = dir
            if (dir != 0) handler.postDelayed(edgeTask, 550)
        }
    }

    private fun dropTarget(): Target {
        val src = drag ?: return Target.None
        val x = lastRawX
        val y = lastRawY
        if (hit(dropRemove, x, y)) return Target.Remove
        if (hit(dropInfo, x, y)) return Target.Info
        if (hit(dockBar, x, y)) {
            for (i in 0..3) dockViews[i]?.let { if (hit(it, x, y)) return Target.Dock(i) }
            return Target.None
        }
        if (pager.pageCount == 0 || !hit(pager, x, y)) return Target.None
        val cl = pager.page(pager.current)
        val l = IntArray(2)
        cl.getLocationOnScreen(l)
        val w = src.item?.w ?: 1
        val h = src.item?.h ?: 1
        val dvw = dragView?.width ?: 0
        val dvh = dragView?.height ?: 0
        val cx = cl.cellX(x - dragOffX + dvw / 2f - l[0])
        val cy = cl.cellY(y - dragOffY + dvh / 2f - l[1])
        val over = itemAt(pager.current, cx, cy, src.item)
        val tx = (cx - (w - 1) / 2).coerceIn(0, max(0, columns - w))
        val ty = (cy - (h - 1) / 2).coerceIn(0, max(0, rows - h))
        return Target.Cell(pager.current, tx, ty, over)
    }

    private fun canFolder(src: DragSource, over: Item?) =
        src.isApp && over != null && (over.type == Item.APP || over.type == Item.FOLDER) && over !== src.folder

    private fun updateDropHint() {
        val src = drag ?: return
        val t = dropTarget()
        for (i in 0 until pager.pageCount) pager.page(i).hint = null
        dropRemove.setTextColor(if (t is Target.Remove) HOLO_RED else Color.WHITE)
        dropInfo.setTextColor(if (t is Target.Info) HOLO_BLUE else Color.WHITE)
        for (i in 0..3) dockViews[i]?.setBackgroundColor(
            if (t is Target.Dock && t.slot == i && src.isApp) Color.argb(70, 51, 181, 229) else Color.TRANSPARENT)
        if (t is Target.Cell && t.page < pager.pageCount) {
            val cl = pager.page(t.page)
            val over = t.over
            if (canFolder(src, over) && over != null) {
                cl.hintOk = true
                cl.hint = Rect(over.x, over.y, over.x + over.w, over.y + over.h)
            } else {
                val w = src.item?.w ?: 1
                val h = src.item?.h ?: 1
                cl.hintOk = fits(t.page, t.x, t.y, w, h, items, src.item)
                cl.hint = Rect(t.x, t.y, t.x + w, t.y + h)
            }
        }
    }

    private fun removeFromSource(src: DragSource, key: String) {
        when {
            src.item != null -> items.remove(src.item)
            src.dockSlot >= 0 -> dock[src.dockSlot] = null
            src.folder != null -> src.folder.apps.remove(key)
        }
    }

    private fun drop(src: DragSource) {
        val t = dropTarget()
        val key = src.key
        when (t) {
            is Target.Remove -> when {
                src.fromDrawer -> key?.let { byKey[it] }?.let { uninstall(it) }
                src.item != null -> removeItem(src.item)
                src.dockSlot >= 0 -> dock[src.dockSlot] = null
                src.folder != null && key != null -> src.folder.apps.remove(key)
            }
            is Target.Info -> key?.let { byKey[it] }?.let { appInfo(it) }
            is Target.Dock -> if (src.isApp && key != null) {
                val prev = dock[t.slot]
                when {
                    src.dockSlot >= 0 -> { dock[src.dockSlot] = prev; dock[t.slot] = key }
                    src.item != null -> {
                        val old = src.item
                        dock[t.slot] = key
                        items.remove(old)
                        // la app que estaba en el dock toma el lugar de la que subió
                        if (prev != null) items.add(Item(nextId++, Item.APP, old.page, old.x, old.y, key = prev))
                    }
                    src.folder != null -> { dock[t.slot] = key; src.folder.apps.remove(key) }
                    else -> dock[t.slot] = key
                }
            }
            is Target.Cell -> dropOnCell(src, t, key)
            is Target.None -> {}
        }
    }

    private fun dropOnCell(src: DragSource, t: Target.Cell, key: String?) {
        val over = t.over
        if (key != null && canFolder(src, over) && over != null) {
            if (over.type == Item.APP) {
                if (over.key == key) return
                val f = Item(nextId++, Item.FOLDER, over.page, over.x, over.y)
                over.key?.let { f.apps.add(it) }
                f.apps.add(key)
                items.remove(over)
                items.add(f)
            } else if (key !in over.apps) over.apps.add(key)
            removeFromSource(src, key)
            return
        }
        val w = src.item?.w ?: 1
        val h = src.item?.h ?: 1
        if (!fits(t.page, t.x, t.y, w, h, items, src.item)) return
        val it0 = src.item
        if (it0 != null) {
            it0.page = t.page; it0.x = t.x; it0.y = t.y
        } else if (key != null) {
            items.add(Item(nextId++, Item.APP, t.page, t.x, t.y, key = key))
            removeFromSource(src, key)
        }
    }

    private fun resetDrag() {
        drag = null
        dragStarted = false
        dragBmp = null
    }

    private fun endDrag() {
        handler.removeCallbacks(edgeTask)
        edgeDir = 0
        dragView?.let { root.removeView(it) }
        dragView = null
        dropBar.visibility = View.GONE
        for (i in 0..3) dockViews[i]?.apply { alpha = 1f; setBackgroundResource(selectableBg) }
        draggingItem = null
        resetDrag()
        commit()
    }

    private fun showMenuFor(src: DragSource) {
        val item = src.item
        when {
            item != null && item.type == Item.WIDGET -> widgetMenu(item)
            item != null && item.type == Item.FOLDER -> folderMenu(item)
            else -> src.key?.let { byKey[it] }?.let { appMenu(it, src) }
        }
    }

    // ================= Widgets =================
    private fun cellSize(): Pair<Int, Int> {
        if (pager.pageCount > 0) {
            val cl = pager.page(0)
            if (cl.cw > 1 && cl.ch > 1) return cl.cw to cl.ch
        }
        return resources.displayMetrics.widthPixels / columns to dp(110)
    }

    private fun spansFor(info: AppWidgetProviderInfo, cw: Int, ch: Int): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0 && info.targetCellHeight > 0)
            return info.targetCellWidth.coerceIn(1, columns) to info.targetCellHeight.coerceIn(1, rows)
        val w = ceil(info.minWidth.toFloat() / cw).toInt().coerceIn(1, columns)
        val h = ceil(info.minHeight.toFloat() / ch).toInt().coerceIn(1, rows)
        return w to h
    }

    private fun loadWidgets() {
        val (cw, ch) = cellSize()
        Thread {
            val list = try {
                widgetManager.installedProviders.map { info ->
                    val (w, h) = spansFor(info, cw, ch)
                    val preview = try {
                        info.loadPreviewImage(this, 0) ?: info.loadIcon(this, 0)
                    } catch (_: Exception) { null }
                    WidgetEntry(info, info.loadLabel(packageManager) ?: "", preview, w, h)
                }.sortedBy { it.label.lowercase() }
            } catch (_: Exception) { emptyList() }
            runOnUiThread {
                widgetList.clear()
                widgetList.addAll(list)
                widgetAdapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun addWidget(info: AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        val ok = try {
            widgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)
        } catch (_: Exception) { false }
        if (ok) { configureOrPlace(id); return }
        try {
            startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile), REQ_BIND)
        } catch (_: Exception) {
            cancelPendingWidget()
            toast("No se pudo agregar el widget")
        }
    }

    private fun configureOrPlace(id: Int) {
        val info = widgetManager.getAppWidgetInfo(id)
        if (info == null) { cancelPendingWidget(); return }
        if (info.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG, null)
                return
            } catch (_: Exception) { }
        }
        placeWidget(id)
    }

    private fun placeWidget(id: Int) {
        val info = widgetManager.getAppWidgetInfo(id)
        if (info == null) { cancelPendingWidget(); return }
        val (cw, ch) = cellSize()
        val (w, h) = spansFor(info, cw, ch)
        val s = findSpot(pager.current, w, h)
        items.add(Item(nextId++, Item.WIDGET, s.first, s.second, s.third, w, h, widgetId = id))
        pendingWidgetId = -1
        commit()
        pager.snapTo(s.first)
    }

    private fun cancelPendingWidget() {
        if (pendingWidgetId != -1) {
            try { widgetHost.deleteAppWidgetId(pendingWidgetId) } catch (_: Exception) { }
        }
        pendingWidgetId = -1
    }

    private fun widgetView(item: Item): View? {
        val id = item.widgetId
        val v = widgetViews[id] ?: run {
            val info = widgetManager.getAppWidgetInfo(id) ?: return null
            widgetHost.createView(this, id, info).also { widgetViews[id] = it }
        }
        (v.parent as? ViewGroup)?.removeView(v)
        (v as? LauncherWidgetView)?.onLongPress = { beginDrag(DragSource(item = item), it, vibrate = true) }
        return v
    }

    // Avisa a cada widget de su tamaño real para que se dibuje bien
    private fun updateWidgetSizes() {
        val (cw, ch) = cellSize()
        if (pager.pageCount == 0 || pager.page(0).cw <= 1) return
        val d = resources.displayMetrics.density
        for (it in items) {
            if (it.type != Item.WIDGET) continue
            val v = widgetViews[it.widgetId] ?: continue
            val w = (it.w * cw / d).toInt()
            val h = (it.h * ch / d).toInt()
            if (widgetSizes[it.widgetId] == (w to h)) continue
            widgetSizes[it.widgetId] = w to h
            @Suppress("DEPRECATION")
            v.updateAppWidgetSize(Bundle(), w, h, w, h)
        }
    }

    private fun widgetMenu(item: Item) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Cambiar tamaño", "Quitar widget")) { _, w ->
                when (w) {
                    0 -> resizeDialog(item)
                    1 -> { removeItem(item); commit() }
                }
            }.show()
    }

    private fun resizeDialog(item: Item) {
        val wp = NumberPicker(this).apply { minValue = 1; maxValue = columns; value = item.w; wrapSelectorWheel = false }
        val hp = NumberPicker(this).apply { minValue = 1; maxValue = rows; value = item.h; wrapSelectorWheel = false }
        fun column(label: String, picker: NumberPicker) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(context).apply { text = label; gravity = Gravity.CENTER })
            addView(picker)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), 0)
            addView(column("Ancho", wp))
            addView(TextView(context).apply { text = "×"; textSize = 20f; setPadding(dp(20), 0, dp(20), 0) })
            addView(column("Alto", hp))
        }
        AlertDialog.Builder(this).setTitle("Tamaño del widget").setView(box)
            .setPositiveButton("Aplicar") { _, _ ->
                val nw = wp.value
                val nh = hp.value
                val nx = min(item.x, columns - nw)
                val ny = min(item.y, rows - nh)
                if (fits(item.page, nx, ny, nw, nh, items, item)) {
                    item.x = nx; item.y = ny; item.w = nw; item.h = nh
                    commit()
                } else toast("No hay espacio para ese tamaño en esta pantalla")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ================= Menús =================
    private fun launcherMenu() {
        val opts = arrayOf("Fondo de pantalla", "Widgets", "Ajustes del launcher", "Ocultar apps",
            "Copia de seguridad", "Launcher predeterminado", "Ajustes del sistema")
        AlertDialog.Builder(this).setItems(opts) { _, w ->
            when (w) {
                0 -> wallpaperMenu()
                1 -> { openDrawer(); selectTab(true) }
                2 -> settingsDialog()
                3 -> hiddenDialog()
                4 -> backupMenu()
                5 -> safeStart(Intent(Settings.ACTION_HOME_SETTINGS))
                6 -> safeStart(Intent(Settings.ACTION_SETTINGS))
            }
        }.show()
    }

    private fun settingsDialog() {
        val keys = arrayOf("clock", "home_labels", "auto_kb", "haptics")
        val labels = arrayOf("Mostrar reloj", "Nombres en el escritorio",
            "Abrir teclado en el cajón", "Vibrar al mantener presionado")
        val checked = booleanArrayOf(showClock, homeLabels, autoKeyboard, haptics)
        AlertDialog.Builder(this).setTitle("Ajustes del launcher")
            .setMultiChoiceItems(labels, checked) { _, i, v ->
                prefs.edit().putBoolean(keys[i], v).apply()
                applySettings()
            }
            .setNeutralButton("Columnas: $columns") { _, _ -> columnsDialog() }
            .setPositiveButton("Listo", null)
            .show()
    }

    private fun columnsDialog() {
        val opts = arrayOf("4 columnas", "5 columnas")
        AlertDialog.Builder(this).setTitle("Columnas")
            .setSingleChoiceItems(opts, columns - 4) { d, i ->
                prefs.edit().putInt("cols", i + 4).apply()
                applySettings()
                save()
                d.dismiss()
            }
            .show()
    }

    private fun hiddenDialog() {
        val list = apps.toList()
        val names = list.map { it.label }.toTypedArray()
        val checked = BooleanArray(list.size) { list[it].key in hidden }
        AlertDialog.Builder(this).setTitle("Ocultar apps del cajón")
            .setMultiChoiceItems(names, checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("Guardar") { _, _ ->
                list.forEachIndexed { i, a -> if (checked[i]) hidden.add(a.key) else hidden.remove(a.key) }
                save(); refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun folderMenu(f: Item) {
        AlertDialog.Builder(this).setTitle(f.title)
            .setItems(arrayOf("Renombrar carpeta", "Quitar carpeta")) { _, w ->
                when (w) {
                    0 -> textDialog("Renombrar carpeta", f.title) { f.title = it; commit() }
                    1 -> { items.remove(f); commit() }
                }
            }.show()
    }

    private fun appMenu(app: App, src: DragSource) {
        val titles = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun add(t: String, f: () -> Unit) { titles.add(t); actions.add(f) }

        add("Renombrar") { rename(app) }
        add("Cambiar icono") { pickIcon(app) }
        if (prefs.contains("name_${app.key}") || iconFile(app.key).exists())
            add("Restaurar original") { restore(app) }

        val item = src.item
        val folder = src.folder
        when {
            item != null -> add("Quitar del escritorio") { items.remove(item); commit() }
            src.dockSlot >= 0 -> add("Quitar del dock") { dock[src.dockSlot] = null; commit() }
            folder != null -> add("Sacar de la carpeta") { folder.apps.remove(app.key); placeApp(app.key); commit() }
            else -> add("Añadir al escritorio") { placeApp(app.key); commit() }
        }
        if (src.dockSlot < 0) add("Poner en el dock") { chooseDockSlot(app, src) }

        if (app.key in hidden) add("Mostrar en el cajón") { hidden.remove(app.key); save(); refresh() }
        else add("Ocultar del cajón") { hidden.add(app.key); save(); refresh() }

        add("Info de la app") { appInfo(app) }
        add("Desinstalar") { uninstall(app) }

        AlertDialog.Builder(this).setTitle(app.label)
            .setItems(titles.toTypedArray()) { _, w -> actions[w]() }
            .show()
    }

    private fun appInfo(app: App) =
        safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))

    private fun uninstall(app: App) =
        safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))

    private fun chooseDockSlot(app: App, src: DragSource) {
        val names = Array(4) { i ->
            val side = if (i < 2) "izquierda" else "derecha"
            val cur = dock[i]?.let { byKey[it]?.label } ?: "vacía"
            "Posición ${i + 1} ($side) — $cur"
        }
        AlertDialog.Builder(this).setTitle("Poner en el dock")
            .setItems(names) { _, i ->
                dock[i] = app.key
                src.item?.let { items.remove(it) }
                src.folder?.apps?.remove(app.key)
                commit()
            }
            .show()
    }

    private fun textDialog(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial); setSingleLine(); setSelection(text.length)
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("Aceptar") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) onOk(n)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun rename(app: App) = textDialog("Renombrar", app.label) { n ->
        app.label = n
        prefs.edit().putString("name_${app.key}", n).apply()
        apps.sortBy { it.label.lowercase() }
        refresh()
    }

    private fun restore(app: App) {
        prefs.edit().remove("name_${app.key}").apply()
        iconFile(app.key).delete()
        app.label = app.originalLabel
        app.icon = app.originalIcon
        apps.sortBy { it.label.lowercase() }
        refresh()
    }

    private fun pickIcon(app: App) {
        pendingIconKey = app.key
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_ICON)
        } catch (_: Exception) { toast("No hay galería disponible") }
    }

    // ================= Fondo de pantalla =================
    private fun wallpaperMenu() {
        AlertDialog.Builder(this).setTitle("Fondo de pantalla")
            .setItems(arrayOf("Elegir de la galería", "Fondos del sistema")) { _, w ->
                when (w) {
                    0 -> try {
                        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_WALL)
                    } catch (_: Exception) { toast("No hay galería disponible") }
                    1 -> safeStart(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Fondo de pantalla"))
                }
            }.show()
    }

    private fun askWallpaperTarget(uri: Uri) {
        val opts = arrayOf("Escritorio y bloqueo", "Solo escritorio", "Solo pantalla de bloqueo")
        val flags = intArrayOf(
            WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
            WallpaperManager.FLAG_SYSTEM,
            WallpaperManager.FLAG_LOCK
        )
        AlertDialog.Builder(this).setTitle("Aplicar en")
            .setItems(opts) { _, w -> applyWallpaper(uri, flags[w]) }
            .show()
    }

    private fun screenSize(): Pair<Int, Int> {
        val (a, b) = if (Build.VERSION.SDK_INT >= 30) {
            val r = windowManager.maximumWindowMetrics.bounds
            r.width() to r.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
        return min(a, b) to max(a, b)
    }

    // Recorta la foto al tamaño exacto de la pantalla, centrada: sin zoom ni proporciones raras
    private fun applyWallpaper(uri: Uri, flags: Int) {
        toast("Aplicando fondo…")
        val (sw, sh) = screenSize()
        Thread {
            val ok = try {
                val src: Bitmap? = if (Build.VERSION.SDK_INT >= 28) {
                    android.graphics.ImageDecoder.decodeBitmap(
                        android.graphics.ImageDecoder.createSource(contentResolver, uri)
                    ) { dec, info, _ ->
                        dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        val s = min(info.size.width / sw, info.size.height / sh)
                        if (s > 1) dec.setTargetSampleSize(s)
                    }
                } else {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                    val s = max(1, min(o.outWidth / sw, o.outHeight / sh))
                    contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
                    }
                }
                if (src == null) false else {
                    val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                    val scale = max(sw.toFloat() / src.width, sh.toFloat() / src.height)
                    val m = Matrix().apply {
                        postScale(scale, scale)
                        postTranslate((sw - src.width * scale) / 2f, (sh - src.height * scale) / 2f)
                    }
                    Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
                    val wm = WallpaperManager.getInstance(this)
                    try { wm.suggestDesiredDimensions(sw, sh) } catch (_: Exception) { }
                    wm.setBitmap(out, null, true, flags)
                    true
                }
            } catch (_: Exception) { false }
            runOnUiThread { toast(if (ok) "Fondo aplicado" else "No se pudo aplicar el fondo") }
        }.start()
    }

    // ================= Copia de seguridad =================
    private fun backupMenu() {
        AlertDialog.Builder(this).setTitle("Copia de seguridad")
            .setMessage("Guarda escritorio, carpetas, dock, nombres, iconos y ajustes. Los widgets no se incluyen.")
            .setPositiveButton("Exportar") { _, _ ->
                try {
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "milauncher-respaldo.json"), REQ_EXPORT)
                } catch (_: Exception) { toast("No disponible en este teléfono") }
            }
            .setNeutralButton("Importar") { _, _ ->
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*"), REQ_IMPORT)
                } catch (_: Exception) { toast("No disponible en este teléfono") }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun exportTo(uri: Uri) {
        try {
            save()
            val o = JSONObject()
            o.put("app", "MiLauncher")
            o.put("version", 1)
            val pj = JSONObject()
            for ((k, v) in prefs.all) {
                val e = JSONObject()
                when (v) {
                    is String -> { e.put("t", "s"); e.put("v", v) }
                    is Boolean -> { e.put("t", "b"); e.put("v", v) }
                    is Int -> { e.put("t", "i"); e.put("v", v) }
                    is Long -> { e.put("t", "l"); e.put("v", v) }
                    is Float -> { e.put("t", "f"); e.put("v", v.toDouble()) }
                    is Set<*> -> { e.put("t", "set"); e.put("v", JSONArray(v.map { it.toString() })) }
                    else -> continue
                }
                pj.put(k, e)
            }
            o.put("prefs", pj)
            val ic = JSONObject()
            File(filesDir, "icons").listFiles()?.forEach {
                ic.put(it.name, Base64.encodeToString(it.readBytes(), Base64.NO_WRAP))
            }
            o.put("icons", ic)
            contentResolver.openOutputStream(uri)?.use { it.write(o.toString().toByteArray()) }
            toast("Configuración exportada")
        } catch (_: Exception) {
            toast("No se pudo exportar")
        }
    }

    private fun importFrom(uri: Uri) {
        val o = try {
            val txt = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            JSONObject(txt).takeIf { it.optString("app") == "MiLauncher" }
        } catch (_: Exception) { null }
        if (o == null) { toast("Ese archivo no es un respaldo de Mi Launcher"); return }
        AlertDialog.Builder(this).setTitle("Importar configuración")
            .setMessage("Esto reemplaza tu configuración actual. ¿Seguir?")
            .setPositiveButton("Importar") { _, _ -> applyBackup(o) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun applyBackup(o: JSONObject) {
        try {
            items.filter { it.type == Item.WIDGET }.forEach {
                try { widgetHost.deleteAppWidgetId(it.widgetId) } catch (_: Exception) { }
            }
            widgetViews.clear()
            widgetSizes.clear()
            val e = prefs.edit().clear()
            val pj = o.getJSONObject("prefs")
            for (k in pj.keys()) {
                val x = pj.getJSONObject(k)
                when (x.getString("t")) {
                    "s" -> e.putString(k, x.getString("v"))
                    "b" -> e.putBoolean(k, x.getBoolean("v"))
                    "i" -> e.putInt(k, x.getInt("v"))
                    "l" -> e.putLong(k, x.getLong("v"))
                    "f" -> e.putFloat(k, x.getDouble("v").toFloat())
                    "set" -> {
                        val a = x.getJSONArray("v")
                        e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                    }
                }
            }
            e.commit()
            val dir = File(filesDir, "icons")
            dir.listFiles()?.forEach { it.delete() }
            dir.mkdirs()
            o.optJSONObject("icons")?.let { ic ->
                for (name in ic.keys()) File(dir, name).writeBytes(Base64.decode(ic.getString(name), Base64.NO_WRAP))
            }
            loadItems()
            items.removeAll { it.type == Item.WIDGET }   // los widgets no se pueden restaurar
            save()
            applySettings()
            loadApps()
            toast("Configuración importada")
        } catch (_: Exception) {
            toast("No se pudo importar")
        }
    }

    // ================= Resultados =================
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        when (req) {
            REQ_ICON -> {
                val key = pendingIconKey ?: return
                pendingIconKey = null
                val uri = data?.data
                if (res != RESULT_OK || uri == null) return
                val bmp = loadScaled(uri, dp(96))
                if (bmp == null) { toast("No se pudo leer la imagen"); return }
                val file = iconFile(key)
                file.parentFile?.mkdirs()
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                byKey[key]?.icon = BitmapDrawable(resources, bmp)
                refresh()
            }
            REQ_BIND -> if (res == RESULT_OK && pendingWidgetId != -1) configureOrPlace(pendingWidgetId) else cancelPendingWidget()
            REQ_CONFIG -> if (res == RESULT_OK && pendingWidgetId != -1) placeWidget(pendingWidgetId) else cancelPendingWidget()
            REQ_WALL -> if (res == RESULT_OK) data?.data?.let { askWallpaperTarget(it) }
            REQ_EXPORT -> if (res == RESULT_OK) data?.data?.let { exportTo(it) }
            REQ_IMPORT -> if (res == RESULT_OK) data?.data?.let { importFrom(it) }
        }
    }

    private fun loadScaled(uri: Uri, size: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (o.outWidth / (s * 2) >= size && o.outHeight / (s * 2) >= size) s *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = s }
        val b = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        b?.let { Bitmap.createScaledBitmap(it, size, (size.toLong() * it.height / it.width).toInt(), true) }
    } catch (e: Exception) { null }
}
