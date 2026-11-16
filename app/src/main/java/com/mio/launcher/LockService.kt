package com.mio.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent

// Servicio de accesibilidad mínimo: solo sirve para bloquear la pantalla con doble toque
class LockService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        var instance: LockService? = null

        fun lock(): Boolean =
            Build.VERSION.SDK_INT >= 28 &&
                (instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false)
    }
}
