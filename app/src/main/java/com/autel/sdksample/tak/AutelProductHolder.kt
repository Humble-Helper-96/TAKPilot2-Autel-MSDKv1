package com.autel.sdksample.tak

import com.taklite.util.AppLog
import com.autel.sdk.Autel
import com.autel.sdk.ProductConnectListener
import com.autel.sdk.product.BaseProduct
import com.autel.sdk.product.Evo2Aircraft
import com.autel.sdk.video.AutelCodec

/**
 * Process-wide holder for the connected Autel aircraft (the Autel-side replacement for
 * DJI's KeyManager singleton pattern used by TAKPilot2).
 *
 * Install once (from [TakPilotHomeActivity] / app start); every TAK component reads the
 * product from here. On connect the telemetry listeners in [AutelTakBridge] are (re)armed.
 */
object AutelProductHolder {
    private const val TAG = "AutelProductHolder"

    @Volatile var product: BaseProduct? = null
        private set

    /** The EVO II V3 view of the product, or null if not connected / different airframe. */
    val evo2: Evo2Aircraft? get() = product as? Evo2Aircraft

    val codec: AutelCodec? get() = product?.codec

    val isConnected: Boolean get() = product != null

    private val listeners = ArrayList<(Boolean) -> Unit>()
    // Single listener instance — re-registering it is harmless, and re-registration is
    // REQUIRED: Autel.setProductConnectListener is a global single slot, and the stock
    // sample's ProductActivity (reachable via "SDK Test Tools") overwrites it. Home and
    // Flight re-install on every onResume to reclaim the slot.
    private val connectListener = object : ProductConnectListener {
            override fun productConnected(baseProduct: BaseProduct?) {
                AppLog.i(TAG, "productConnected: ${baseProduct?.type}")
                product = baseProduct
                // Re-arm telemetry subscriptions on every (re)connect — Autel listener
                // registrations don't survive a product cycle.
                TakBridgeHolder.onProductConnected()
                notifyAll(true)
            }

            override fun productDisconnected() {
                AppLog.i(TAG, "productDisconnected")
                product = null
                TakBridgeHolder.onProductDisconnected()
                notifyAll(false)
            }
        }

    /** Wires (or re-wires) [Autel.setProductConnectListener]. Call from onResume. */
    @Synchronized
    fun install() {
        Autel.setProductConnectListener(connectListener)
    }

    fun addListener(l: (Boolean) -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: (Boolean) -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    private fun notifyAll(connected: Boolean) {
        val copy = synchronized(listeners) { listeners.toList() }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            copy.forEach { runCatching { it(connected) } }
        }
    }
}
