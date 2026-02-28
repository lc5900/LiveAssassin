package com.lc5900.liveassassin

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.common.util.concurrent.ListenableFuture
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.serenegiant.usb.Size
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera

class MainActivity : AppCompatActivity() {
    private val cameraPermissionRequest = 2000
    private val audioPermissionRequest = 2001

    private lateinit var cameraView: AspectRatioTextureView
    private lateinit var previewContainer: FrameLayout
    private lateinit var pipCameraView: PreviewView
    private lateinit var pipSwitch: SwitchCompat
    private lateinit var pipControlsPanel: View
    private lateinit var pipControlsToggleButton: Button
    private lateinit var resolutionSpinner: Spinner
    private lateinit var controlPanel: View
    private lateinit var statusView: TextView
    private lateinit var fullscreenButton: Button

    private var usbMonitor: USBMonitor? = null
    private var uvcCamera: UVCCamera? = null
    private lateinit var audioLoopback: UsbAudioLoopback

    private var isFullscreenMode = false
    private var chromeHidden = false
    private var currentDevice: UsbDevice? = null
    private var currentPreviewWidth = 0
    private var currentPreviewHeight = 0
    private var pipSizePercent = 28
    private var pipXPercent = 0
    private var pipYPercent = 100
    private var pipCameraProvider: ProcessCameraProvider? = null
    private var isPipEnabled = false
    private var suppressResolutionCallback = false
    private var selectedResolutionKey: String? = null
    private val resolutionOptions = mutableListOf<PreviewOption>()
    private var pendingCameraPermissionForUsbOpen = false
    private var pendingCameraPermissionForPip = false

    private val usbListener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice) {
            Log.i(TAG, "onAttach: $device")
            currentDevice = device
            runOnUiThread { statusView.setText(R.string.status_device_attached) }
        }

        override fun onDetach(device: UsbDevice) {
            Log.i(TAG, "onDetach: $device")
            if (currentDevice == device) {
                currentDevice = null
            }
            runOnUiThread {
                stopPreviewAndAudio()
                statusView.setText(R.string.status_waiting_device)
            }
        }

        override fun onConnect(
            device: UsbDevice,
            ctrlBlock: USBMonitor.UsbControlBlock,
            createNew: Boolean
        ) {
            Log.i(TAG, "onConnect: $device, createNew=$createNew")
            currentDevice = device
            openCameraDirect(ctrlBlock)
        }

        override fun onDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
            Log.i(TAG, "onDisconnect: $device")
            runOnUiThread {
                stopPreviewAndAudio()
                statusView.setText(R.string.status_waiting_device)
            }
        }

        override fun onCancel(device: UsbDevice) {
            Log.w(TAG, "onCancel permission: $device")
            runOnUiThread {
                Toast.makeText(this@MainActivity, "USB 权限被拒绝", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraView = findViewById(R.id.camera_view)
        previewContainer = findViewById(R.id.preview_container)
        pipCameraView = findViewById(R.id.pip_camera_view)
        controlPanel = findViewById(R.id.control_panel)
        statusView = findViewById(R.id.tv_status)
        val startButton: Button = findViewById(R.id.btn_start)
        val stopButton: Button = findViewById(R.id.btn_stop)
        fullscreenButton = findViewById(R.id.btn_fullscreen)
        resolutionSpinner = findViewById(R.id.spinner_resolution)
        pipSwitch = findViewById(R.id.switch_pip_front_camera)
        val pipSizeSeek: SeekBar = findViewById(R.id.seek_pip_size)
        val pipXSeek: SeekBar = findViewById(R.id.seek_pip_x)
        val pipYSeek: SeekBar = findViewById(R.id.seek_pip_y)
        pipControlsPanel = findViewById(R.id.pip_controls_panel)
        pipControlsToggleButton = findViewById(R.id.btn_toggle_pip_controls)

        cameraView.setAspectRatio(16, 9)
        pipCameraView.scaleX = -1f

        cameraView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                if (uvcCamera != null && currentPreviewWidth > 0 && currentPreviewHeight > 0) {
                    try {
                        surface.setDefaultBufferSize(currentPreviewWidth, currentPreviewHeight)
                        uvcCamera?.setPreviewTexture(surface)
                        uvcCamera?.startPreview()
                        resetPreviewTransform()
                    } catch (e: Exception) {
                        Log.w(TAG, "rebind preview texture failed", e)
                    }
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                resetPreviewTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        audioLoopback = UsbAudioLoopback(this)
        usbMonitor = USBMonitor(this, usbListener)

        startButton.setOnClickListener { requestOpenDevice() }
        stopButton.setOnClickListener {
            stopPreviewAndAudio()
            statusView.setText(R.string.status_preview_stopped)
        }
        fullscreenButton.setOnClickListener { toggleFullscreenRotate() }
        resolutionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressResolutionCallback || position !in resolutionOptions.indices) {
                    return
                }
                val option = resolutionOptions[position]
                selectedResolutionKey = option.key
                if (uvcCamera != null) {
                    applyResolutionOption(option)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        pipSwitch.setOnCheckedChangeListener { _, isChecked -> setPipEnabled(isChecked) }
        pipControlsToggleButton.setOnClickListener { togglePipControlsPanel() }

        pipSizeSeek.setOnSeekBarChangeListener(object : SimpleProgressListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                pipSizePercent = clamp(progress, 15, 60)
                updatePipLayout()
            }
        })
        pipXSeek.setOnSeekBarChangeListener(object : SimpleProgressListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                pipXPercent = clamp(progress, 0, 100)
                updatePipLayout()
            }
        })
        pipYSeek.setOnSeekBarChangeListener(object : SimpleProgressListener() {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                pipYPercent = clamp(progress, 0, 100)
                updatePipLayout()
            }
        })

        val previewTapListener = View.OnClickListener {
            if (isFullscreenMode) {
                setChromeHidden(!chromeHidden)
            }
        }
        previewContainer.setOnClickListener(previewTapListener)
        cameraView.setOnClickListener(previewTapListener)
        pipCameraView.setOnClickListener(previewTapListener)
        previewContainer.post { updatePipLayout() }
    }

    override fun onStart() {
        super.onStart()
        usbMonitor?.let {
            if (!it.isRegistered) {
                it.register()
            }
        }
        if (isPipEnabled) {
            startPipCamera()
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            stopPreviewAndAudio()
            stopPipCamera()
            usbMonitor?.unregister()
        }
        super.onStop()
    }

    override fun onDestroy() {
        stopPipCamera()
        usbMonitor?.destroy()
        usbMonitor = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(@NonNull newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyAspectForCurrentOrientation()
        resetPreviewTransform()
        updatePipLayout()
        if (isFullscreenMode) {
            enterImmersiveMode()
        }
    }

    private fun toggleFullscreenRotate() {
        isFullscreenMode = !isFullscreenMode
        if (isFullscreenMode) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            enterImmersiveMode()
            setChromeHidden(true)
            fullscreenButton.setText(R.string.exit_fullscreen)
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            exitImmersiveMode()
            setChromeHidden(false)
            fullscreenButton.setText(R.string.enter_fullscreen)
        }
    }

    private fun setChromeHidden(hidden: Boolean) {
        chromeHidden = hidden
        controlPanel.alpha = if (hidden) 0f else 1f
        controlPanel.visibility = if (hidden) View.GONE else View.VISIBLE
        statusView.visibility = if (hidden) View.GONE else View.VISIBLE
        if (hidden) {
            enterImmersiveMode()
        } else if (isFullscreenMode) {
            enterImmersiveMode()
        } else {
            exitImmersiveMode()
        }
        resetPreviewTransform()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (controller != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun exitImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (controller != null) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun requestOpenDevice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingCameraPermissionForUsbOpen = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                cameraPermissionRequest
            )
            return
        }
        val monitor = usbMonitor ?: return
        val devices = monitor.deviceList
        if (devices.isNullOrEmpty()) {
            Toast.makeText(this, "未检测到 USB 采集卡", Toast.LENGTH_SHORT).show()
            statusView.setText(R.string.status_waiting_device)
            return
        }
        val target = currentDevice ?: devices[0]
        currentDevice = target
        monitor.requestPermission(target)
    }

    private fun openCameraDirect(ctrlBlock: USBMonitor.UsbControlBlock) {
        stopPreviewAndAudio()
        try {
            uvcCamera = UVCCamera().apply { open(ctrlBlock) }
            Log.i(TAG, "supportedSizeRaw=${uvcCamera?.supportedSize}")
            val mjpeg = uvcCamera?.getSupportedSizeList(UVCCamera.FRAME_FORMAT_MJPEG)
            val yuyv = uvcCamera?.getSupportedSizeList(UVCCamera.FRAME_FORMAT_YUYV)
            Log.i(TAG, "mjpegSizes=${toSizeText(mjpeg)}")
            Log.i(TAG, "yuyvSizes=${toSizeText(yuyv)}")

            val options = buildResolutionOptions(mjpeg, yuyv)
            if (options.isEmpty()) {
                throw IllegalStateException("no supported preview size")
            }
            val picked = chooseResolutionOption(options)
            val width = picked.width
            val height = picked.height
            val format = picked.format
            currentPreviewWidth = width
            currentPreviewHeight = height

            applyAspectForCurrentOrientation()
            trySetPreviewSize(width, height, format)

            val texture = cameraView.surfaceTexture ?: throw IllegalStateException("camera view surface is null")
            texture.setDefaultBufferSize(width, height)
            uvcCamera?.setPreviewTexture(texture)
            uvcCamera?.startPreview()
            runOnUiThread { resetPreviewTransform() }

            runOnUiThread {
                updateResolutionSpinner(options, picked)
                statusView.setText(R.string.status_preview_running)
                startAudioIfPermitted()
                updatePipLayout()
            }
            Log.i(TAG, "preview opened size=${width}x$height, format=$format")
        } catch (e: Exception) {
            Log.e(TAG, "openCameraDirect failed", e)
            runOnUiThread { statusView.setText(R.string.status_camera_open_failed) }
            stopPreviewAndAudio()
        }
    }

    private fun trySetPreviewSize(width: Int, height: Int, format: Int) {
        try {
            uvcCamera?.setPreviewSize(width, height, format)
            return
        } catch (_: Exception) {
        }
        try {
            uvcCamera?.setPreviewSize(width, height, format, 0.5f)
            return
        } catch (_: Exception) {
        }
        uvcCamera?.setPreviewSize(width, height, format, 1, 30, 0.5f)
    }

    private fun buildResolutionOptions(mjpeg: List<Size>?, yuyv: List<Size>?): List<PreviewOption> {
        val map = linkedMapOf<String, PreviewOption>()
        (mjpeg ?: emptyList()).forEach { s ->
            val option = PreviewOption(s.width, s.height, UVCCamera.FRAME_FORMAT_MJPEG)
            map[option.key] = option
        }
        (yuyv ?: emptyList()).forEach { s ->
            val option = PreviewOption(s.width, s.height, UVCCamera.FRAME_FORMAT_YUYV)
            if (!map.containsKey(option.key)) {
                map[option.key] = option
            }
        }
        return map.values.sortedWith(
            compareByDescending<PreviewOption> { it.width * it.height }
                .thenByDescending { it.width }
        )
    }

    private fun chooseResolutionOption(options: List<PreviewOption>): PreviewOption {
        val selected = selectedResolutionKey
        if (selected != null) {
            options.firstOrNull { it.key == selected }?.let { return it }
        }
        return options.first()
    }

    private fun updateResolutionSpinner(options: List<PreviewOption>, selected: PreviewOption) {
        resolutionOptions.clear()
        resolutionOptions.addAll(options)
        val labels = options.map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        suppressResolutionCallback = true
        resolutionSpinner.adapter = adapter
        val selectedIndex = options.indexOfFirst { it.key == selected.key }.coerceAtLeast(0)
        resolutionSpinner.setSelection(selectedIndex, false)
        suppressResolutionCallback = false
        selectedResolutionKey = selected.key
    }

    private fun applyResolutionOption(option: PreviewOption) {
        val camera = uvcCamera ?: return
        try {
            camera.stopPreview()
        } catch (_: Exception) {
        }
        try {
            currentPreviewWidth = option.width
            currentPreviewHeight = option.height
            applyAspectForCurrentOrientation()
            trySetPreviewSize(option.width, option.height, option.format)
            val texture = cameraView.surfaceTexture ?: throw IllegalStateException("camera view surface is null")
            texture.setDefaultBufferSize(option.width, option.height)
            camera.setPreviewTexture(texture)
            camera.startPreview()
            resetPreviewTransform()
            statusView.text = getString(R.string.status_preview_running) + " (${option.label})"
        } catch (e: Exception) {
            Log.e(TAG, "applyResolutionOption failed", e)
            statusView.setText(R.string.status_camera_open_failed)
        }
    }

    private fun toSizeText(sizes: List<Size>?): String {
        if (sizes == null) return "null"
        return buildString {
            append("[")
            sizes.forEachIndexed { index, size ->
                append(size.width).append("x").append(size.height)
                if (index < sizes.size - 1) append(", ")
            }
            append("]")
        }
    }

    private fun resetPreviewTransform() {
        if (currentPreviewWidth <= 0 || currentPreviewHeight <= 0) return
        applyAspectForCurrentOrientation()
        val viewW = cameraView.width
        val viewH = cameraView.height
        if (viewW <= 0 || viewH <= 0) return

        val sourceRatio = currentPreviewWidth.toFloat() / currentPreviewHeight.toFloat()
        val viewRatio = viewW.toFloat() / viewH.toFloat()
        var scaleX = 1f
        var scaleY = 1f
        if (viewRatio > sourceRatio) {
            scaleX = sourceRatio / viewRatio
        } else {
            scaleY = viewRatio / sourceRatio
        }

        val matrix = Matrix()
        matrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f)
        cameraView.setTransform(matrix)
        cameraView.translationX = 0f
        cameraView.translationY = 0f
    }

    private fun applyAspectForCurrentOrientation() {
        if (currentPreviewWidth <= 0 || currentPreviewHeight <= 0) return
        cameraView.setAspectRatio(currentPreviewWidth, currentPreviewHeight)
    }

    private fun setPipEnabled(enabled: Boolean) {
        isPipEnabled = enabled
        if (!enabled) {
            pipControlsPanel.visibility = View.GONE
            pipControlsToggleButton.setText(R.string.pip_show_controls)
            stopPipCamera()
            pipCameraView.visibility = View.GONE
            return
        }
        startPipCamera()
    }

    private fun togglePipControlsPanel() {
        if (!isPipEnabled) {
            Toast.makeText(this, "请先打开前置摄像头画中画", Toast.LENGTH_SHORT).show()
            return
        }
        val willShow = pipControlsPanel.visibility != View.VISIBLE
        pipControlsPanel.visibility = if (willShow) View.VISIBLE else View.GONE
        pipControlsToggleButton.setText(
            if (willShow) R.string.pip_hide_controls else R.string.pip_show_controls
        )
    }

    private fun startPipCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingCameraPermissionForPip = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                cameraPermissionRequest
            )
            return
        }
        pipCameraView.visibility = View.VISIBLE
        updatePipLayout()
        val future: ListenableFuture<ProcessCameraProvider> = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                pipCameraProvider = future.get()
                pipCameraProvider?.unbindAll()
                val preview = Preview.Builder().build()
                val selector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()
                preview.setSurfaceProvider(pipCameraView.surfaceProvider)
                pipCameraProvider?.bindToLifecycle(this, selector, preview)
            } catch (e: Exception) {
                Log.e(TAG, "startPipCamera failed", e)
                runOnUiThread {
                    pipCameraView.visibility = View.GONE
                    pipSwitch.isChecked = false
                    Toast.makeText(this, "前置摄像头启动失败", Toast.LENGTH_SHORT).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopPipCamera() {
        try {
            pipCameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
    }

    private fun updatePipLayout() {
        val containerW = previewContainer.width
        val containerH = previewContainer.height
        if (containerW <= 0 || containerH <= 0) {
            previewContainer.post { updatePipLayout() }
            return
        }
        var pipW = maxOf(120, containerW * pipSizePercent / 100)
        var pipH = maxOf(68, pipW * 9 / 16)
        if (pipH > containerH) {
            pipH = containerH
            pipW = maxOf(120, pipH * 16 / 9)
        }
        val left = (containerW - pipW) * pipXPercent / 100
        val top = (containerH - pipH) * pipYPercent / 100
        val lp = pipCameraView.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.TOP or Gravity.START
        lp.width = pipW
        lp.height = pipH
        lp.leftMargin = left
        lp.topMargin = top
        lp.marginStart = left
        pipCameraView.layoutParams = lp
        pipCameraView.bringToFront()
    }

    private fun clamp(value: Int, min: Int, max: Int): Int = maxOf(min, minOf(max, value))

    private fun startAudioIfPermitted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                audioPermissionRequest
            )
            return
        }
        if (!audioLoopback.start()) {
            Toast.makeText(this, "音频输出启动失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPreviewAndAudio() {
        audioLoopback.stop()
        try {
            uvcCamera?.stopPreview()
        } catch (_: Exception) {
        }
        try {
            uvcCamera?.destroy()
        } catch (_: Exception) {
        }
        uvcCamera = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            if (requestCode == audioPermissionRequest) {
                statusView.setText(R.string.status_audio_permission_denied)
            } else {
                if (pendingCameraPermissionForPip) {
                    pipSwitch.isChecked = false
                }
                pendingCameraPermissionForUsbOpen = false
                pendingCameraPermissionForPip = false
                statusView.setText(R.string.status_camera_open_failed)
            }
            return
        }
        if (requestCode == cameraPermissionRequest) {
            val shouldOpenUsb = pendingCameraPermissionForUsbOpen
            val shouldStartPip = pendingCameraPermissionForPip || isPipEnabled
            pendingCameraPermissionForUsbOpen = false
            pendingCameraPermissionForPip = false
            if (shouldOpenUsb) {
                requestOpenDevice()
            }
            if (shouldStartPip) {
                startPipCamera()
            }
        } else if (requestCode == audioPermissionRequest) {
            startAudioIfPermitted()
        }
    }

    private abstract class SimpleProgressListener : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

        override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
    }

    companion object {
        private const val TAG = "LiveAssassin"
    }

    private data class PreviewOption(val width: Int, val height: Int, val format: Int) {
        val key: String = "${width}x$height"
        val label: String = "${width}x$height"
    }
}
