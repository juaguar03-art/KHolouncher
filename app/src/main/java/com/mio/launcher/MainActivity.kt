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
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.*
import android.widget.*
import java.io.File

val HOLO_BLUE = 0xFF33B5E5.toInt()
const val REQ_ICON = 42
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class App(
    val key: String, val pkg: String, val cls: String,
    val originalLabel: String, val originalIcon: Drawable
) {
    var label = originalLabel
    var icon: Drawable = originalIcon
}

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private val apps = mutableListOf<App>()
    private val byKey = HashMap<String, App>()
    private val home = mutableListOf<String>()
    private val homeApps = mutableListOf<App>()
    private val dock = arrayOfNulls<String>(4)
    private val dockViews = arrayOfNulls<ImageView>(4)
    private lateinit var drawer: LinearLayout
    private lateinit var drawerAdapter: AppAdapter
    private lateinit var homeAdapter: AppAdapter
    private var pendingIconKey: String? = null

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = loadApps()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        pendingIconKey = b?.getString("pending")

        drawerAdapter = AppAdapter { apps }
        homeAdapter = AppAdapter { homeApps }

        val root = FrameLayout(this)

        // ---------- Escritorio ----------
        val homeLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        homeLayout.addView(makeGrid(homeAdapter), LinearLayout.LayoutParams(MATCH, 0, 1f))
        homeLayout.addView(View(this).apply { setBackgroundColor(Color.argb(60, 255, 255, 255)) },
            LinearLayout.LayoutParams(MATCH, 1).apply { setMargins(dp(16), 0, dp(16), 0) })

        // ---------- Dock ----------
        val dockBar = LinearLayout(this).apply {
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
                    setOnLongClickListener {
                        dock[slot]?.let { byKey[it] }?.let { appMenu(it, slot) }; true
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
        drawer.addView(makeGrid(drawerAdapter).apply {
            isFastScrollEnabled = true
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(drawer, FrameLayout.LayoutParams(MATCH, MATCH))

        setContentView(root)

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, RECEIVER_EXPORTED)
        else registerReceiver(pkgReceiver, f)

        loadApps()
    }

    override fun onDestroy() {
        unregisterReceiver(pkgReceiver)
        super.onDestroy()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("pending", pendingIconKey)
    }

    // ---------- Datos ----------
    private fun iconFile(key: String) = File(filesDir, "icons/" + key.replace('/', '_') + ".png")

    private fun loadApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps.clear(); byKey.clear()
        for (ri in packageManager.queryIntentActivities(intent, 0)) {
            val ai = ri.activityInfo
            if (ai.packageName == packageName) continue
            val key = "${ai.packageName}/${ai.name}"
            val app = App(key, ai.packageName, ai.name,
                ri.loadLabel(packageManager).toString(), ri.loadIcon(packageManager))
            prefs.getString("name_$key", null)?.let { app.label = it }
            val file = iconFile(key)
            if (file.exists()) BitmapFactory.decodeFile(file.path)?.let {
                app.icon = BitmapDrawable(resources, it)
            }
            apps.add(app); byKey[key] = app
        }
        apps.sortBy { it.label.lowercase() }
        home.clear()
        prefs.getString("home", "")!!.split(";").filter { it.isNotEmpty() }.forEach { home.add(it) }
        for (i in 0..3) dock[i] = prefs.getString("dock_$i", null)
        refresh()
    }

    private fun refresh() {
        homeApps.clear()
        home.mapNotNullTo(homeApps) { byKey[it] }
        homeAdapter.notifyDataSetChanged()
        drawerAdapter.notifyDataSetChanged()
        for (i in 0..3) dockViews[i]?.setImageDrawable(dock[i]?.let { byKey[it] }?.icon)
    }

    private fun save() {
        val e = prefs.edit().putString("home", home.joinToString(";"))
        for (i in 0..3) e.putString("dock_$i", dock[i])
        e.apply()
    }

    // ---------- Interfaz ----------
    inner class AppAdapter(private val source: () -> List<App>) : BaseAdapter() {
        override fun getCount() = source().size
        override fun getItem(p: Int): App = source()[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val cell = (convert as? LinearLayout) ?: makeCell()
            val app = getItem(p)
            (cell.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            (cell.getChildAt(1) as TextView).text = app.label
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
        setOnItemClickListener { _, _, p, _ -> launch(adapter.getItem(p)) }
        setOnItemLongClickListener { _, _, p, _ -> appMenu(adapter.getItem(p), null); true }
    }

    private fun openDrawer() {
        drawer.alpha = 0f; drawer.scaleX = 0.9f; drawer.scaleY = 0.9f
        drawer.visibility = View.VISIBLE
        drawer.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
    }

    private fun closeDrawer() {
        drawer.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(150)
            .withEndAction { drawer.visibility = View.GONE }.start()
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
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(app.pkg, app.cls)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            drawer.visibility = View.GONE
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Menú de edición ----------
    private fun appMenu(app: App, dockSlot: Int?) {
        val titles = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun add(t: String, f: () -> Unit) { titles.add(t); actions.add(f) }

        add("Renombrar") { rename(app) }
        add("Cambiar icono") { pickIcon(app) }
        if (prefs.contains("name_${app.key}") || iconFile(app.key).exists())
            add("Restaurar original") { restore(app) }
        if (app.key in home) add("Quitar del escritorio") { home.remove(app.key); save(); refresh() }
        else add("Añadir al escritorio") { home.add(app.key); save(); refresh() }
        if (dockSlot != null) add("Quitar del dock") { dock[dockSlot] = null; save(); refresh() }
        else add("Poner en el dock") { chooseDockSlot(app) }
        add("Info de la app") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
        }
        add("Desinstalar") {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
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
