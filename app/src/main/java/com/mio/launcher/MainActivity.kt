package com.mio.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.io.File
import java.text.Normalizer
import kotlin.math.abs

val HOLO_BLUE = 0xFF33B5E5.toInt()
const val REQ_ICON = 42
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class App(
    val key: String, val pkg: String, val cls: String,
    val originalLabel: String, val originalIcon: Drawable,
    val self: Boolean = false   // la entrada del propio launcher en el cajón
) {
    var label = originalLabel
    var icon: Drawable = originalIcon
}

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private val apps = mutableListOf<App>()          // todas las apps
    private val drawerApps = mutableListOf<App>()    // visibles en el cajón (sin ocultas, filtradas)
    private val byKey = HashMap<String, App>()
    private val home = mutableListOf<String>()
    private val homeApps = mutableListOf<App>()
    private val hidden = HashSet<String>()
    private val dock = arrayOfNulls<String>(4)
    private val dockViews = arrayOfNulls<ImageView>(4)
    private lateinit var drawer: LinearLayout
    private lateinit var drawerGrid: GridView
    private lateinit var homeGrid: GridView
    private lateinit var dockBar: LinearLayout
    private lateinit var homeLayout: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val reloadTask = Runnable { loadApps() }
    private var loadGen = 0
    private lateinit var clockBox: LinearLayout
    private lateinit var search: EditText
    private lateinit var drawerAdapter: AppAdapter
    private lateinit var homeAdapter: AppAdapter
    private lateinit var gestures: GestureDetector
    private var pendingIconKey: String? = null

    // ---------- Ajustes ----------
    private val columns get() = prefs.getInt("cols", 4)
    private val showClock get() = prefs.getBoolean("clock", false)
    private val homeLabels get() = prefs.getBoolean("home_labels", true)
    private val autoKeyboard get() = prefs.getBoolean("auto_kb", false)
    private val haptics get() = prefs.getBoolean("haptics", true)

    private val pkgReceiver = object : BroadcastReceiver() {
        // Algunos teléfonos mandan muchos avisos seguidos: se agrupan en una sola recarga
        override fun onReceive(c: Context, i: Intent) {
            handler.removeCallbacks(reloadTask)
            handler.postDelayed(reloadTask, 400)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val imm get() = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        pendingIconKey = b?.getString("pending")

        drawerAdapter = AppAdapter(false) { drawerApps }
        homeAdapter = AppAdapter(true) { homeApps }

        val root = FrameLayout(this).apply { isFocusableInTouchMode = true }

        // ---------- Escritorio ----------
        homeLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        clockBox = makeClock()
        homeLayout.addView(clockBox, LinearLayout.LayoutParams(MATCH, WRAP))

        homeGrid = makeGrid(homeAdapter)
        homeLayout.addView(homeGrid, LinearLayout.LayoutParams(MATCH, 0, 1f))
        homeLayout.addView(View(this).apply { setBackgroundColor(Color.argb(60, 255, 255, 255)) },
            LinearLayout.LayoutParams(MATCH, 1).apply { setMargins(dp(16), 0, dp(16), 0) })

        // ---------- Dock ----------
        dockBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(12))
        }
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
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
                    setBackgroundResource(tv.resourceId)
                    setOnClickListener { dock[slot]?.let { byKey[it] }?.let { launch(it) } }
                    setOnLongClickListener { v ->
                        val app = dock[slot]?.let { byKey[it] }
                        if (app != null) { buzz(v); appMenu(app, slot) }
                        app != null
                    }
                }
                dockViews[slot] = iv
                dockBar.addView(iv, lp)
            }
        }
        homeLayout.addView(dockBar, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(homeLayout, FrameLayout.LayoutParams(MATCH, MATCH))

        // ---------- Cajón de apps ----------
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 0, 0, 0))
            visibility = View.GONE
            isClickable = true
        }
        val tab = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tab.addView(TextView(this).apply {
            text = "APLICACIONES"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(14), dp(16), dp(10))
        })
        tab.addView(View(this).apply { setBackgroundColor(HOLO_BLUE) },
            LinearLayout.LayoutParams(MATCH, dp(4)))
        drawer.addView(tab, LinearLayout.LayoutParams(WRAP, WRAP))
        drawer.addView(View(this).apply { setBackgroundColor(HOLO_BLUE); alpha = 0.5f },
            LinearLayout.LayoutParams(MATCH, dp(1)))

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
        drawer.addView(search, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(dp(12), dp(8), dp(12), 0)
        })

        drawerGrid = makeGrid(drawerAdapter).apply {
            isFastScrollEnabled = true
            setPadding(dp(4), dp(8), dp(4), dp(8))
            clipToPadding = false
        }
        drawer.addView(drawerGrid, LinearLayout.LayoutParams(MATCH, 0, 1f))
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
                    if (dy > 0 && !drawerGrid.canScrollVertically(-1)) { closeDrawer(); return true }
                    return false
                }
                if (dy < 0) openDrawer() else expandNotifications()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (drawer.visibility == View.VISIBLE) return
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
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::gestures.isInitialized) gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        handler.removeCallbacks(reloadTask)
        unregisterReceiver(pkgReceiver)
        super.onDestroy()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("pending", pendingIconKey)
    }

    // ---------- Datos ----------
    private fun iconFile(key: String) = File(filesDir, "icons/" + key.replace('/', '_') + ".png")

    // Dibuja el icono una sola vez en un bitmap del tamaño final: hace el scroll mucho más fluido
    private fun raster(d: Drawable): Drawable {
        val s = dp(52)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, s, s)
        d.draw(Canvas(bmp))
        return BitmapDrawable(resources, bmp)
    }

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
                loadState()
            }
        }.start()
    }

    private fun loadState() {
        home.clear()
        prefs.getString("home", "")!!.split(";").filter { it.isNotEmpty() }.forEach { home.add(it) }
        hidden.clear()
        hidden.addAll(prefs.getStringSet("hidden", emptySet())!!)
        for (i in 0..3) dock[i] = prefs.getString("dock_$i", null)
        refresh()
    }

    private fun refresh() {
        homeApps.clear()
        home.mapNotNullTo(homeApps) { byKey[it] }
        homeAdapter.notifyDataSetChanged()
        filterDrawer()
        for (i in 0..3) dockViews[i]?.setImageDrawable(dock[i]?.let { byKey[it] }?.icon)
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
        val e = prefs.edit().putString("home", home.joinToString(";"))
        for (i in 0..3) e.putString("dock_$i", dock[i])
        e.putStringSet("hidden", HashSet(hidden))
        e.apply()
    }

    private fun applySettings() {
        homeGrid.numColumns = columns
        drawerGrid.numColumns = columns
        clockBox.visibility = if (showClock) View.VISIBLE else View.GONE
        homeGrid.isHapticFeedbackEnabled = haptics
        drawerGrid.isHapticFeedbackEnabled = haptics
        homeAdapter.notifyDataSetChanged()
        drawerAdapter.notifyDataSetChanged()
    }

    // ---------- Interfaz ----------
    inner class AppAdapter(private val isHome: Boolean, private val source: () -> List<App>) : BaseAdapter() {
        override fun getCount() = source().size
        override fun getItem(p: Int): App = source()[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val cell = (convert as? LinearLayout) ?: makeCell()
            val app = getItem(p)
            (cell.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            (cell.getChildAt(1) as TextView).apply {
                text = app.label
                visibility = if (isHome && !homeLabels) View.GONE else View.VISIBLE
            }
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

    private fun makeGrid(adapter: AppAdapter) = GridView(this).apply {
        numColumns = 4
        this.adapter = adapter
        overScrollMode = View.OVER_SCROLL_NEVER   // sin el efecto "estirar" de Android 12+
        isVerticalFadingEdgeEnabled = false
        setOnItemClickListener { _, _, p, _ -> launch(adapter.getItem(p)) }
        setOnItemLongClickListener { _, _, p, _ -> appMenu(adapter.getItem(p), null); true }
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
        try { startActivity(i) } catch (e: Exception) {
            Toast.makeText(this, "No disponible en este teléfono", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isOnEmptyHome(x: Float, y: Float): Boolean {
        val loc = IntArray(2)
        dockBar.getLocationOnScreen(loc)
        if (y >= loc[1]) return false
        homeGrid.getLocationOnScreen(loc)
        return homeGrid.pointToPosition((x - loc[0]).toInt(), (y - loc[1]).toInt()) ==
            AdapterView.INVALID_POSITION
    }

    private fun expandNotifications() {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager")
                .getMethod("expandNotificationsPanel").invoke(sb)
        } catch (_: Exception) { }
    }

    private fun openDrawer() {
        drawer.alpha = 0f; drawer.scaleX = 0.9f; drawer.scaleY = 0.9f
        drawer.visibility = View.VISIBLE
        drawer.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
            .withEndAction { homeLayout.visibility = View.INVISIBLE }.start()  // no dibujar el escritorio detrás
        if (autoKeyboard) {
            search.requestFocus()
            search.postDelayed({ imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT) }, 150)
        }
    }

    private fun resetDrawer() {
        imm.hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
        if (search.text.isNotEmpty()) search.setText("")
        drawerGrid.setSelection(0)
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
        if (drawer.visibility == View.VISIBLE) closeDrawer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (drawer.visibility == View.VISIBLE) closeDrawer()
    }

    private fun launch(app: App) {
        if (app.self) { launcherMenu(); return }
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(app.pkg, app.cls)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            if (drawer.visibility == View.VISIBLE) hideDrawerNow()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Menú del escritorio (mantener presionado en vacío) ----------
    private fun launcherMenu() {
        val items = arrayOf("Fondo de pantalla", "Ajustes del launcher", "Ocultar apps",
            "Launcher predeterminado", "Ajustes del sistema")
        AlertDialog.Builder(this).setItems(items) { _, w ->
            when (w) {
                0 -> safeStart(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Fondo de pantalla"))
                1 -> settingsDialog()
                2 -> hiddenDialog()
                3 -> safeStart(Intent(Settings.ACTION_HOME_SETTINGS))
                4 -> safeStart(Intent(Settings.ACTION_SETTINGS))
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
                prefs.edit().putBoolean(keys[i], v).apply(); applySettings()
            }
            .setNeutralButton("Columnas: $columns") { _, _ -> columnsDialog() }
            .setPositiveButton("Listo", null)
            .show()
    }

    private fun columnsDialog() {
        val opts = arrayOf("4 columnas", "5 columnas")
        AlertDialog.Builder(this).setTitle("Columnas")
            .setSingleChoiceItems(opts, columns - 4) { d, i ->
                prefs.edit().putInt("cols", i + 4).apply(); applySettings(); d.dismiss()
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

    // ---------- Menú de una app ----------
    private fun appMenu(app: App, dockSlot: Int?) {
        val titles = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun add(t: String, f: () -> Unit) { titles.add(t); actions.add(f) }

        add("Renombrar") { rename(app) }
        add("Cambiar icono") { pickIcon(app) }
        if (prefs.contains("name_${app.key}") || iconFile(app.key).exists())
            add("Restaurar original") { restore(app) }

        val idx = home.indexOf(app.key)
        if (idx >= 0) {
            if (idx > 0) add("Mover a la izquierda") { home.add(idx - 1, home.removeAt(idx)); save(); refresh() }
            if (idx < home.size - 1) add("Mover a la derecha") { home.add(idx + 1, home.removeAt(idx)); save(); refresh() }
            add("Quitar del escritorio") { home.remove(app.key); save(); refresh() }
        } else add("Añadir al escritorio") { home.add(app.key); save(); refresh() }

        if (dockSlot != null) add("Quitar del dock") { dock[dockSlot] = null; save(); refresh() }
        else add("Poner en el dock") { chooseDockSlot(app) }

        if (app.key in hidden) add("Mostrar en el cajón") { hidden.remove(app.key); save(); refresh() }
        else add("Ocultar del cajón") { hidden.add(app.key); save(); refresh() }

        add("Info de la app") {
            safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
        }
        add("Desinstalar") {
            safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
        }

        AlertDialog.Builder(this).setTitle(app.label)
            .setItems(titles.toTypedArray()) { _, w -> actions[w]() }
            .show()
    }

    private fun chooseDockSlot(app: App) {
        val names = Array(4) { i ->
            val side = if (i < 2) "izquierda" else "derecha"
            val cur = dock[i]?.let { byKey[it]?.label } ?: "vacía"
            "Posición ${i + 1} ($side) — $cur"
        }
        AlertDialog.Builder(this).setTitle("Poner en el dock")
            .setItems(names) { _, i -> dock[i] = app.key; save(); refresh() }
            .show()
    }

    private fun rename(app: App) {
        val input = EditText(this).apply {
            setText(app.label); setSingleLine(); setSelection(text.length)
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this).setTitle("Renombrar").setView(box)
            .setPositiveButton("Aceptar") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) {
                    app.label = n
                    prefs.edit().putString("name_${app.key}", n).apply()
                    apps.sortBy { it.label.lowercase() }
                    refresh()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_ICON)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val key = pendingIconKey ?: return
        pendingIconKey = null
        val uri = data?.data
        if (req != REQ_ICON || res != RESULT_OK || uri == null) return
        val bmp = loadScaled(uri, dp(96))
        if (bmp == null) {
            Toast.makeText(this, "No se pudo leer la imagen", Toast.LENGTH_SHORT).show(); return
        }
        val file = iconFile(key)
        file.parentFile?.mkdirs()
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        byKey[key]?.icon = BitmapDrawable(resources, bmp)
        refresh()
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

// Botón circular de "todas las apps", estilo ICS
class AllAppsButton(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) * 0.36f
        p.style = Paint.Style.FILL; p.color = Color.argb(200, 40, 40, 40)
        cv.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.08f
        p.color = if (isPressed) HOLO_BLUE else Color.argb(220, 255, 255, 255)
        cv.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.FILL; p.color = Color.WHITE
        val gap = r * 0.36f; val dot = r * 0.09f
        for (i in -1..1) for (j in -1..1) cv.drawCircle(cx + i * gap, cy + j * gap, dot, p)
    }
}
