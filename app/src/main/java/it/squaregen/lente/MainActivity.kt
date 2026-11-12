package it.squaregen.lente

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), FrameAnalyzer.Listener, SmartFocus.Callbacks, SensorEventListener {

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var frozenView: ZoomImageView
    private lateinit var bigLabel: TextView
    private lateinit var btnZoomIn: View
    private lateinit var btnZoomOut: View
    private lateinit var btnMenu: View
    private lateinit var menuPanel: View
    private lateinit var menuContent: LinearLayout
    private lateinit var maskTop: View
    private lateinit var maskBottom: View
    private lateinit var guard: ScreenGuard

    private val main = Handler(Looper.getMainLooper())
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private lateinit var analyzer: FrameAnalyzer
    private val smartFocus = SmartFocus(this)

    private var minZoom = 1f
    private var maxZoom = 1f        // camera limit
    private var totalMaxZoom = 1f   // camera limit plus extra software zoom
    private var cameraZoom = 1f
    private var zoom = 2f
    private var hasFlash = false

    private var frozen = false
    private var frozenFile: File? = null
    private var freezeToken = 0
    private var readWhenFrozenReady = false
    private var busyCapturing = false

    // Inactivity / screen saver
    private var lastActivity = SystemClock.elapsedRealtime()
    private var lastMotion = 0L
    private var dimmed = false
    private var keepOn = true
    private var lumaRef = -1f
    private var lumaRefTime = 0L
    private var lastAccel: FloatArray? = null
    private var sensorManager: SensorManager? = null

    private var lockStartDone = false

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showPermissionPanel()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(Ui.standardScale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = findViewById(R.id.root)
        previewView = findViewById(R.id.preview)
        frozenView = findViewById(R.id.frozen)
        bigLabel = findViewById(R.id.bigLabel)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        btnMenu = findViewById(R.id.btnMenu)
        menuPanel = findViewById(R.id.menuPanel)
        menuContent = findViewById(R.id.menuContent)
        maskTop = findViewById(R.id.maskTop)
        maskBottom = findViewById(R.id.maskBottom)

        // TextureView-based preview so the colour filters can be applied to it.
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        cameraExecutor = Executors.newSingleThreadExecutor()
        analyzer = FrameAnalyzer(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager

        // The corner buttons never disappear (she knows where they are): after a few seconds, or
        // as soon as the picture is touched, they fade to faint but stay tappable. They also drift
        // by a few pixels now and then, so they cannot burn into the screen.
        val corner = listOf(btnZoomIn, btnZoomOut, btnMenu)
        guard = ScreenGuard(corner, corner, resources.displayMetrics.density)
        guard.shownAlpha = 0.75f
        guard.hiddenAlpha = 0.2f

        zoom = prefs.zoom
        setupControls()
        setupGestures()
        setupBack()
        // Turning the phone over (landscape to reverse landscape) does not recreate the screen:
        // keep photos and text recognition upright.
        (getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager).registerDisplayListener(displayListener, main)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutMaskAndExclusions() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
        cleanOldPictures()
    }

    override fun onResume() {
        super.onResume()
        applySettings()
        hideSystemBars()
        undim()
        lastActivity = SystemClock.elapsedRealtime()
        guard.start()
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, 5000)
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (prefs.lockOnStart && !lockStartDone) {
            lockStartDone = true
            main.postDelayed({ setPinned(true) }, 600)
        }
        if (permissionGranted() && cameraProvider != null && camera == null) bindCamera()
    }

    override fun onPause() {
        super.onPause()
        guard.stop()
        main.removeCallbacks(idleCheck)
        sensorManager?.unregisterListener(this)
        prefs.zoom = zoom
    }

    override fun onDestroy() {
        super.onDestroy()
        (getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager).unregisterDisplayListener(displayListener)
        cameraExecutor.shutdown()
        analyzer.close()
    }

    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            val r = previewView.display?.rotation ?: return
            imageCapture?.targetRotation = r
            imageAnalysis?.targetRotation = r
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation: rebind so the preview, the crop and the photos follow the new orientation.
        if (camera != null) previewView.post { bindCamera() }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        markActive()
        guard.poke()
    }

    // ---------------------------------------------------------------- settings

    /** Re-reads settings (they may have changed in the settings screen). */
    private fun applySettings() {
        if (requestedOrientation != prefs.orientationFlag) requestedOrientation = prefs.orientationFlag
        guard.autoHideMs = prefs.autoHideSec * 1000L
        updateZoomLimits()
        smartFocus.enabled = prefs.smartFocus
        analyzer.ocrEnabled = prefs.smartFocus
        applyColors()
        applyBrightness()
        applyMask()
    }

    private fun applyBrightness() {
        val lp = window.attributes
        lp.screenBrightness = when {
            dimmed -> 0.02f
            prefs.maxBrightness -> 1f
            else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = lp
    }

    private fun applyColors() {
        val mode = ColorModes.byId(prefs.colorMode)
        val filter = ColorModes.filter(mode, prefs.contrast, prefs.brightness, threshold)
        appliedThreshold = threshold
        if (Build.VERSION.SDK_INT >= 31) {
            previewView.setRenderEffect(filter?.let { RenderEffect.createColorFilterEffect(it) })
        } else {
            if (filter == null) previewView.setLayerType(View.LAYER_TYPE_NONE, null)
            else previewView.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = filter })
        }
        frozenView.colorFilter = filter
    }

    // Auto contrast for the two-colour modes: split ink and paper halfway between the dark and
    // light parts of the picture, smoothed so it does not flicker.
    private var threshold = 128f
    private var appliedThreshold = 128f

    private fun updateThreshold(dark: Int, light: Int) {
        if (frozen) return
        val target = if (light - dark >= 40) ((dark + light) / 2f).coerceIn(50f, 210f) else 128f
        threshold += (target - threshold) * 0.08f
        if (ColorModes.byId(prefs.colorMode).kind == ColorModes.Kind.TWO_COLOR && abs(threshold - appliedThreshold) > 3f) applyColors()
    }

    private fun applyMask() {
        val on = prefs.readingMask
        maskTop.visibility = if (on) View.VISIBLE else View.GONE
        maskBottom.visibility = if (on) View.VISIBLE else View.GONE
        layoutMaskAndExclusions()
    }

    private fun layoutMaskAndExclusions() {
        val h = root.height
        val w = root.width
        if (h == 0) return
        val band = (h * 0.26f).toInt()
        val each = (h - band) / 2
        if (maskTop.layoutParams.height != each) {
            maskTop.layoutParams = maskTop.layoutParams.apply { height = each }
            maskBottom.layoutParams = maskBottom.layoutParams.apply { height = each }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            // Keep the edges near the lower half for our own touches, so a hand gripping the
            // phone does not trigger the system "back" gesture.
            val edge = (32 * resources.displayMetrics.density).toInt()
            val tall = (200 * resources.displayMetrics.density).toInt()
            val rects = listOf(Rect(0, h - tall, edge, h), Rect(w - edge, h - tall, w, h))
            if (root.systemGestureExclusionRects != rects) root.systemGestureExclusionRects = rects
        }
    }

    // ---------------------------------------------------------------- camera

    private fun permissionGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showPermissionPanel() {
        val panel = findViewById<View>(R.id.permissionPanel)
        panel.visibility = View.VISIBLE
        findViewById<View>(R.id.btnPermission).setOnClickListener {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                requestCamera.launch(Manifest.permission.CAMERA)
            } else {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }
    }

    private fun startCamera() {
        findViewById<View>(R.id.permissionPanel).visibility = View.GONE
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun chooseCamera(provider: ProcessCameraProvider): CameraSelector = when {
        provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
        else -> CameraSelector.DEFAULT_FRONT_CAMERA
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        if (!permissionGranted()) return
        if (previewView.width == 0) {
            previewView.post { bindCamera() }
            return
        }
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val selector = try { chooseCamera(provider) } catch (_: Exception) { return }

        // Try the richest configuration first, then fall back on phones that cannot stream it.
        val attempts = listOf(true to true, false to true, false to false)
        for ((stabilize, withAnalysis) in attempts) {
            try {
                val previewBuilder = Preview.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy(android.util.Size(1920, 1080),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                            .build()
                    )
                    .setTargetRotation(rotation)
                Camera2Interop.Extender(previewBuilder)
                    .setSessionCaptureCallback(captureCallback)
                if (stabilize) {
                    val caps = Preview.getPreviewCapabilities(provider.getCameraInfo(selector))
                    if (caps.isStabilizationSupported) previewBuilder.setPreviewStabilizationEnabled(true)
                }
                val preview = previewBuilder.build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(android.util.Size(4000, 3000),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                            .build()
                    )
                    .setTargetRotation(rotation)
                    .build()

                // No viewport: photos and text analysis use the whole sensor (more of the page),
                // even if the screen shows a narrower slice of it.
                val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture)
                var analysis: ImageAnalysis? = null
                if (withAnalysis) {
                    analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                .setResolutionStrategy(ResolutionStrategy(android.util.Size(1600, 1200),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                                .build()
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetRotation(rotation)
                        .build()
                    analysis.setAnalyzer(cameraExecutor, analyzer)
                    group.addUseCase(analysis)
                }

                provider.unbindAll()
                val cam = provider.bindToLifecycle(this, selector, group.build())
                camera = cam
                imageCapture = capture
                imageAnalysis = analysis
                onCameraReady(cam, analysis)
                return
            } catch (e: Exception) {
                android.util.Log.w("Lente", "Camera config failed (stabilize=$stabilize analysis=$withAnalysis)", e)
            }
        }
        showLabel("Fotocamera non disponibile", 4000)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun onCameraReady(cam: Camera, analysis: ImageAnalysis?) {
        val zs = cam.cameraInfo.zoomState.value
        minZoom = maxOf(1f, zs?.minZoomRatio ?: 1f)
        maxZoom = maxOf(minZoom, zs?.maxZoomRatio ?: 1f)
        // Beyond the camera limit keep enlarging the picture itself, so there is always
        // at least 12x (some phones and emulators have little or no camera zoom).
        updateZoomLimits()
        setZoom(zoom, announce = false)

        smartFocus.camera = cam
        smartFocus.bufferPointFactory = analysis?.let { SurfaceOrientedMeteringPointFactory(1f, 1f, it) }
        smartFocus.minFocusDiopters = try {
            Camera2CameraInfo.from(cam.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        } catch (_: Exception) { 0f }

        hasFlash = cam.cameraInfo.hasFlashUnit()
        if (hasFlash && prefs.torch) cam.cameraControl.enableTorch(true)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            smartFocus.onCaptureResult(result.get(CaptureResult.CONTROL_AF_STATE), result.get(CaptureResult.LENS_FOCUS_DISTANCE))
        }
    }

    // ---------------------------------------------------------------- zoom

    private fun setZoom(value: Float, announce: Boolean = true) {
        zoom = value.coerceIn(minZoom, maxOf(minZoom, totalMaxZoom))
        cameraZoom = minOf(zoom, maxZoom)
        camera?.cameraControl?.setZoomRatio(cameraZoom)
        val extra = zoom / cameraZoom
        previewView.scaleX = extra
        previewView.scaleY = extra
        if (announce) previewView.contentDescription = "Zoom ${String.format(Locale.ITALIAN, "%.1f", zoom)}"
    }

    /** Camera zoom first, then the picture itself is enlarged up to the limit chosen in settings. */
    private fun updateZoomLimits() {
        totalMaxZoom = maxOf(maxZoom, prefs.maxZoom.toFloat())
        frozenView.maxRelativeZoom = prefs.maxZoom.toFloat()
        if (camera != null) setZoom(zoom, announce = false)
    }

    private fun zoomStep(factor: Float) {
        if (frozen) frozenView.zoomBy(factor) else setZoom(zoom * factor)
    }

    // ---------------------------------------------------------------- controls

    private fun setupControls() {
        btnZoomIn.setOnClickListener { tap(it); guard.show(); zoomStep(1.25f) }
        btnZoomOut.setOnClickListener { tap(it); guard.show(); zoomStep(0.8f) }
        btnMenu.setOnClickListener { tap(it); if (menuPanel.visibility == View.VISIBLE) closeMenu() else openMenu() }
        // Frozen picture: a tap only closes the menu / fades the buttons, a double tap reads the text.
        frozenView.onSingleTap = { touchedPicture() }
        frozenView.onDoubleTapAction = { readText() }
    }

    /** Any touch on the picture: close the menu and fade the corner buttons. */
    private fun touchedPicture() {
        if (menuPanel.visibility == View.VISIBLE) closeMenu()
        guard.hide()
    }

    private fun tap(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                setZoom(zoom * d.scaleFactor, announce = false)
                return true
            }
        })
        // Tap: focus there. Double tap: read the text.
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                smartFocus.focusAtPoint(previewView.meteringPointFactory.createPoint(e.x, e.y))
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                readText()
                return true
            }
        })
        val edge = 28 * resources.displayMetrics.density
        var ignore = false
        previewView.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                // A finger landing on the very edge is usually the hand gripping the phone.
                ignore = e.rawX < edge || e.rawX > root.width - edge
                if (!ignore) {
                    // Touching the picture closes the menu (and does nothing else this time).
                    if (menuPanel.visibility == View.VISIBLE) { closeMenu(); ignore = true }
                    guard.hide()
                }
            }
            if (ignore) return@setOnTouchListener true
            scale.onTouchEvent(e)
            if (e.pointerCount == 1 && !scale.isInProgress) taps.onTouchEvent(e)
            true
        }
    }

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Never leave on a stray back gesture: it only closes the menu.
                // Exiting is from the menu (Esci dall'app).
                if (menuPanel.visibility == View.VISIBLE) closeMenu()
            }
        })
    }

    private fun torchOn() = camera?.cameraInfo?.torchState?.value == androidx.camera.core.TorchState.ON

    private fun setTorch(on: Boolean) {
        camera?.cameraControl?.enableTorch(on)
        prefs.torch = on
    }

    // ---------------------------------------------------------------- freeze

    private fun freeze() {
        val bmp = previewView.bitmap ?: return
        frozen = true
        freezeToken++
        frozenFile = null
        frozenView.setPicture(bmp)
        // The captured frame does not include the extra software zoom: re-apply it.
        val extra = zoom / cameraZoom
        if (extra > 1.01f) frozenView.post { frozenView.zoomBy(extra) }
        frozenView.visibility = View.VISIBLE
        analyzer.ocrEnabled = false
        // Take a full-resolution photo of the same view and swap it in, so zooming stays sharp.
        val token = freezeToken
        takePicture({ bmp }) { file ->
            if (file == null || !frozen || token != freezeToken) return@takePicture
            frozenFile = file
            cameraExecutor.execute {
                val hi = decodeUpright(file, 4096)
                runOnUiThread {
                    if (hi != null && frozen && token == freezeToken) frozenView.replaceKeepingView(hi)
                    if (readWhenFrozenReady) { readWhenFrozenReady = false; openReader(file) }
                }
            }
        }
    }

    private fun unfreeze() {
        frozen = false
        freezeToken++
        readWhenFrozenReady = false
        frozenView.visibility = View.GONE
        frozenView.clear()
        analyzer.ocrEnabled = prefs.smartFocus
    }

    // ---------------------------------------------------------------- reading

    private fun readText() {
        if (menuPanel.visibility == View.VISIBLE) closeMenu()
        if (frozen) {
            val f = frozenFile
            buzz(120)
            if (f != null) openReader(f) else readWhenFrozenReady = true
            return
        }
        if (busyCapturing) return
        buzz(120)
        smartFocus.focusThen {
            takePicture({ previewView.bitmap }) { file ->
                if (file != null) openReader(file) else showLabel("Foto non riuscita, riprova", 2500)
            }
        }
    }

    /** Vibration that works even when the system touch feedback is off. */
    private fun buzz(ms: Long) {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) (getSystemService(VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator)
            v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    private fun openReader(file: File) {
        bigLabel.visibility = View.GONE
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_IMAGE, file.absolutePath))
    }

    /**
     * Takes a full-resolution photo. If the camera does not deliver it within 4 seconds (it happens
     * on some phones), [fallback] provides the picture on screen instead, so reading still works.
     */
    private fun takePicture(fallback: () -> Bitmap?, done: (File?) -> Unit) {
        val stamp = System.currentTimeMillis()
        val file = File(cacheDir, "pic_$stamp.jpg")
        val screenFile = File(cacheDir, "pic_${stamp}_screen.jpg")
        var finished = false
        fun finish(result: File?) {
            if (finished) return
            finished = true
            busyCapturing = false
            done(result)
        }
        val useFallback = Runnable {
            val bmp = fallback()
            if (bmp == null) { finish(null); return@Runnable }
            cameraExecutor.execute {
                val ok = try {
                    screenFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                } catch (_: Exception) { false }
                runOnUiThread { finish(if (ok) screenFile else null) }
            }
        }
        val capture = imageCapture ?: run { useFallback.run(); return }
        busyCapturing = true
        main.postDelayed(useFallback, 4000)
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                main.removeCallbacks(useFallback)
                finish(file)
            }
            override fun onError(exception: ImageCaptureException) {
                android.util.Log.w("Lente", "Capture failed", exception)
                main.removeCallbacks(useFallback)
                useFallback.run()
            }
        })
    }

    private fun decodeUpright(file: File, maxSide: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        val deg = when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        if (bmp == null || deg == 0) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
    } catch (e: Throwable) {
        null
    }

    private fun cleanOldPictures() {
        cameraExecutor.execute {
            val cutoff = System.currentTimeMillis() - 60 * 60 * 1000
            cacheDir.listFiles { f -> f.name.startsWith("pic_") && f.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    // ---------------------------------------------------------------- analyzer callbacks

    override fun onFrameStats(sharpness: Float, meanLuma: Float, dark: Int, light: Int) {
        main.post {
            updateThreshold(dark, light)
            smartFocus.onSharpness(sharpness)
            // Scene changes (a page moved under the camera) count as "in use" for the screen saver.
            val now = SystemClock.elapsedRealtime()
            if (lumaRef < 0 || now - lumaRefTime > 1000) {
                if (lumaRef >= 0 && abs(meanLuma - lumaRef) > 6f) markActive()
                lumaRef = meanLuma
                lumaRefTime = now
            }
        }
    }

    override fun onTextScan(found: Boolean, bufferX: Float, bufferY: Float) {
        // Text position is only used to aim the focus; nothing is shown on screen.
        if (!frozen) smartFocus.onText(found, bufferX, bufferY)
    }

    // ---------------------------------------------------------------- smart focus callbacks

    override fun isDeviceMoving(): Boolean = SystemClock.elapsedRealtime() - lastMotion < 400

    override fun onTooClose() {
        // Clean screen: no message over the picture.
    }

    // ---------------------------------------------------------------- motion / idle

    override fun onSensorChanged(event: SensorEvent) {
        val prev = lastAccel
        val cur = event.values.copyOf()
        lastAccel = cur
        if (prev == null) return
        val dx = cur[0] - prev[0]; val dy = cur[1] - prev[1]; val dz = cur[2] - prev[2]
        val delta = sqrt(dx * dx + dy * dy + dz * dz)
        if (delta > 0.35f) {
            lastMotion = SystemClock.elapsedRealtime()
            if (delta > 0.8f) markActive()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun markActive() {
        lastActivity = SystemClock.elapsedRealtime()
        if (dimmed) undim()
        if (!keepOn) {
            keepOn = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * When the phone is left alone (no touch, no movement, nothing changing in front of the
     * camera) the screen dims after 2 minutes, to protect it, and after the chosen time is
     * allowed to switch off. Any touch or movement brings everything back.
     */
    private val idleCheck = object : Runnable {
        override fun run() {
            val idle = SystemClock.elapsedRealtime() - lastActivity
            if (!dimmed && idle > 2 * 60_000) dim()
            val offMin = prefs.screenOffMin
            if (keepOn && offMin > 0 && idle > offMin * 60_000L) {
                keepOn = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            main.postDelayed(this, 5000)
        }
    }

    private fun dim() {
        dimmed = true
        guard.hide()
        applyBrightness()
    }

    private fun undim() {
        if (!dimmed) return
        dimmed = false
        applyBrightness()
    }

    // ---------------------------------------------------------------- big label

    private val hideLabel = Runnable {
        bigLabel.animate().alpha(0f).setDuration(300).withEndAction { bigLabel.visibility = View.GONE }.start()
    }

    private fun showLabel(text: String, ms: Long) {
        bigLabel.animate().cancel()
        bigLabel.text = text
        bigLabel.alpha = 1f
        bigLabel.visibility = View.VISIBLE
        main.removeCallbacks(hideLabel)
        main.postDelayed(hideLabel, ms)
    }

    // ---------------------------------------------------------------- system bars / pinning

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun isPinned(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    private fun setPinned(pin: Boolean) {
        try {
            if (pin && !isPinned()) startLockTask()
            if (!pin && isPinned()) stopLockTask()
        } catch (e: Exception) {
            showLabel("Blocco non disponibile su questo telefono", 3000)
        }
    }

    // ---------------------------------------------------------------- "Altro" menu

    private fun openMenu() {
        buildMenu()
        // Leave part of the picture uncovered: touching it closes the menu.
        val landscape = root.width > root.height
        menuPanel.layoutParams = menuPanel.layoutParams.apply {
            if (landscape) width = (root.width * 0.55f).toInt() else height = (root.height * 0.72f).toInt()
        }
        menuPanel.visibility = View.VISIBLE
        menuPanel.scrollTo(0, 0)
        guard.show()
        guard.hold()
    }

    private fun closeMenu() {
        menuPanel.visibility = View.GONE
        guard.poke()
    }

    private fun buildMenu() {
        val c = menuContent
        c.removeAllViews()
        c.addView(Ui.button(this, "Chiudi il menu", R.drawable.ic_close) { closeMenu() })

        c.addView(Ui.button(this, if (frozen) "Riprendi l'immagine dal vivo" else "Ferma l'immagine",
            if (frozen) R.drawable.ic_play else R.drawable.ic_pause) {
            if (frozen) unfreeze() else freeze()
            closeMenu()
        })
        c.addView(Ui.button(this, "Leggi il testo (doppio tocco)", R.drawable.ic_text) { readText() })
        if (hasFlash) c.addView(Ui.toggle(this, "Luce", { prefs.torch }) { setTorch(it) })

        c.addView(Ui.header(this, "Colori"))
        c.addView(Ui.button(this, "Colori: ${ColorModes.byId(prefs.colorMode).label}", R.drawable.ic_contrast) { v ->
            // The modes chosen in the settings (plus the current one).
            val all = ColorModes.ALL.filter { prefs.enabledModes.contains(it.id) || it.id == prefs.colorMode }
            val i = all.indexOfFirst { it.id == prefs.colorMode }
            val next = all[(i + 1) % all.size]
            prefs.colorMode = next.id
            applyColors()
            v.text = "Colori: ${next.label}"
            v.announceForAccessibility(next.label)
        })
        c.addView(Ui.stepper(this, { "Contrasto: ${prefs.contrast}" },
            { prefs.contrast = prefs.contrast - 1; applyColors() },
            { prefs.contrast = prefs.contrast + 1; applyColors() }))
        c.addView(Ui.stepper(this, { "Luminosità: ${if (prefs.brightness > 0) "+" else ""}${prefs.brightness}" },
            { prefs.brightness = prefs.brightness - 1; applyColors() },
            { prefs.brightness = prefs.brightness + 1; applyColors() }))
        c.addView(Ui.button(this, "Ripristina contrasto e luminosità") {
            prefs.contrast = 5; prefs.brightness = 0; applyColors(); buildMenu()
        })

        c.addView(Ui.header(this, "Lettura e messa a fuoco"))
        c.addView(Ui.toggle(this, "Guida di lettura (banda)", { prefs.readingMask }) { prefs.readingMask = it; applyMask() })
        c.addView(Ui.toggle(this, "Blocca la messa a fuoco", { smartFocus.locked }) { smartFocus.setLocked(it) })

        c.addView(Ui.header(this, "Altro"))
        c.addView(Ui.toggle(this, "Blocca dentro l'app", { isPinned() }) { want ->
            setPinned(want)
            if (want) closeMenu()
        })
        c.addView(Ui.button(this, "Impostazioni", R.drawable.ic_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        c.addView(Ui.button(this, "Esci dall'app", R.drawable.ic_close) {
            setPinned(false)
            prefs.zoom = zoom
            finishAndRemoveTask()
        })
    }

    // ---------------------------------------------------------------- hardware keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeZoom && menuPanel.visibility != View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { markActive(); zoomStep(1.15f); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { markActive(); zoomStep(1 / 1.15f); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeZoom && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }
}
