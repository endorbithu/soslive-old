package info.soslive.stream.stream

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView

/**
 * Camera preview + RTMP publishing (RootEncoder). Hardware H.264/AAC via MediaCodec, Camera2 API.
 *
 * Lives as long as its [view] (the UI owns it); reports connection changes to [listener] on the main thread.
 */
class StreamController(
    private val context: Context,
    private val listener: Listener,
) : ConnectChecker, SurfaceHolder.Callback {

    interface Listener {
        fun onStreamConnected()
        fun onStreamFailed(reason: String)
        fun onStreamDisconnected()

        /** The preview surface went away while streaming (app backgrounded) - the stream was stopped. */
        fun onStreamInterrupted()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    val view: OpenGlView = OpenGlView(context).also { it.holder.addCallback(this) }
    private val camera = RtmpCamera2(view, this)

    val isStreaming: Boolean get() = camera.isStreaming

    /** @return false when the encoders could not be prepared on this device. */
    fun startStream(publishUrl: String): Boolean {
        // A reconnect after a dropped connection: restart the encoders cleanly.
        if (camera.isStreaming) camera.stopStream()
        val rotation = CameraHelper.getCameraOrientation(context)
        val prepared = camera.prepareAudio(AUDIO_BITRATE, AUDIO_SAMPLE_RATE, true, true, true) &&
            camera.prepareVideo(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS, VIDEO_BITRATE, rotation)
        if (!prepared) return false
        camera.startStream(publishUrl)
        return true
    }

    fun stopStream() {
        if (camera.isStreaming) camera.stopStream()
    }

    fun switchCamera() {
        runCatching { camera.switchCamera() }.onFailure { Log.w(TAG, "switchCamera failed", it) }
    }

    /** @return the new lantern state, or null if the current camera has no flash. */
    fun toggleFlash(): Boolean? = try {
        if (camera.isLanternEnabled) {
            camera.disableLantern()
            false
        } else {
            camera.enableLantern()
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "Flash not available", e)
        null
    }

    fun release() {
        stopStream()
        if (camera.isOnPreview) camera.stopPreview()
        view.holder.removeCallback(this)
    }

    // --- SurfaceHolder.Callback ---

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!camera.isOnPreview) {
            runCatching { camera.startPreview() }.onFailure { Log.e(TAG, "Cannot start preview", it) }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (camera.isStreaming) {
            camera.stopStream()
            listener.onStreamInterrupted()
        }
        if (camera.isOnPreview) camera.stopPreview()
    }

    // --- ConnectChecker (called on a RootEncoder thread) ---

    override fun onConnectionStarted(url: String) = Unit

    override fun onConnectionSuccess() {
        mainHandler.post { listener.onStreamConnected() }
    }

    override fun onConnectionFailed(reason: String) {
        mainHandler.post {
            if (camera.isStreaming) camera.stopStream()
            listener.onStreamFailed(reason)
        }
    }

    override fun onNewBitrate(bitrate: Long) = Unit

    override fun onDisconnect() {
        mainHandler.post { listener.onStreamDisconnected() }
    }

    override fun onAuthError() {
        mainHandler.post {
            if (camera.isStreaming) camera.stopStream()
            listener.onStreamFailed("RTMP authentication failed")
        }
    }

    override fun onAuthSuccess() = Unit

    private companion object {
        const val TAG = "StreamController"
        const val VIDEO_WIDTH = 1280
        const val VIDEO_HEIGHT = 720
        const val VIDEO_FPS = 30
        const val VIDEO_BITRATE = 2_500 * 1024
        const val AUDIO_BITRATE = 128 * 1024
        const val AUDIO_SAMPLE_RATE = 44_100
    }
}
