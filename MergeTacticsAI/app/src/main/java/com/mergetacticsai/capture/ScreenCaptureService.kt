package com.mergetacticsai.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        @Volatile var running = false; private set
        @Volatile private var instance: ScreenCaptureService? = null

        /** Последний кадр (уменьшенный вдвое) или null, если захват не запущен. */
        fun captureFrame(): Bitmap? = instance?.grab()
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("capture", "Захват экрана", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "capture").setContentTitle("Merge Tactics AI")
            .setContentText("Анализ экрана включён").setSmallIcon(android.R.drawable.ic_menu_view).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))
        projection = mp

        val dm = resources.displayMetrics
        val w = dm.widthPixels / 2; val h = dm.heightPixels / 2
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        display = mp.createVirtualDisplay("mt-ai", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
        instance = this; running = true
        return START_NOT_STICKY
    }

    private fun grab(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return null
        img.use {
            val p = it.planes[0]
            val rowPad = p.rowStride - p.pixelStride * it.width
            val bmp = Bitmap.createBitmap(it.width + rowPad / p.pixelStride, it.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(p.buffer)
            return Bitmap.createBitmap(bmp, 0, 0, it.width, it.height)
        }
    }

    override fun onDestroy() {
        running = false; instance = null
        display?.release(); reader?.close(); projection?.stop()
        super.onDestroy()
    }
}
