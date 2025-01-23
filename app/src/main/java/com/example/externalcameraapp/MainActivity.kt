package com.example.externalcameraapp

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.util.Size
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.media.ImageReader
import kotlin.math.abs
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val TAG = "MainActivity"
        private const val ACTION_USB_PERMISSION = "com.example.externalcameraapp.USB_PERMISSION"
        private const val CAPTURE_INTERVAL = 10000L // 10 seconds in milliseconds
        private const val CAMERA_STABILIZATION_DELAY = 5000L // 5 seconds delay
    }

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private lateinit var usbManager: UsbManager
    private lateinit var viewFinder: TextureView
    private var captureSession: CameraCaptureSession? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var previewSize: Size = Size(1920, 1080) // Default size
    
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.apply {
                                Log.i(TAG, "USB Permission granted for device: ${device.deviceName}")
                                initializeCamera()
                            }
                        } else {
                            Log.w(TAG, "USB Permission denied for device: ${device?.deviceName}")
                            Toast.makeText(context, "USB permission denied", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB Device attached")
                    checkUsbDevice(intent.getParcelableExtra(UsbManager.EXTRA_DEVICE))
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB Device detached")
                    cameraDevice?.close()
                    cameraDevice = null
                }
            }
        }
    }

    private var isCapturing = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var imageReader: ImageReader? = null
    private var isCameraStabilized = false
    private var previewImageReader: ImageReader? = null

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (isCapturing) {
                captureImage()
                mainHandler.postDelayed(this, CAPTURE_INTERVAL)
            }
        }
    }

    private fun captureImage() {
        if (!isCameraStabilized) {
            Log.d(TAG, "Skipping capture - camera not stabilized")
            return
        }
        
        try {
            val reader = imageReader
            if (reader == null) {
                Log.e(TAG, "Cannot capture image - ImageReader is null")
                return
            }

            val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            if (captureBuilder == null) {
                Log.e(TAG, "Cannot capture image - CaptureRequest.Builder is null")
                return
            }

            captureBuilder.apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.JPEG_ORIENTATION, 0)
            }

            captureSession?.capture(
                captureBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        super.onCaptureCompleted(session, request, result)
                        Log.d(TAG, "Image capture completed at: ${System.currentTimeMillis()}")
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        super.onCaptureFailed(session, request, failure)
                        Log.e(TAG, "Image capture failed: ${failure.reason}")
                    }
                },
                mainHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing image", e)
        }
    }

    private fun startPeriodicCapture() {
        if (!isCameraStabilized) {
            Log.d(TAG, "Waiting for camera to stabilize")
            return
        }
        
        if (cameraDevice == null || imageReader?.surface == null || captureSession == null) {
            Log.e(TAG, "Cannot start periodic capture - camera not ready")
            return
        }
        
        isCapturing = true
        mainHandler.post(captureRunnable)
        Log.d(TAG, "Started periodic capture")
    }

    private fun stopPeriodicCapture() {
        isCapturing = false
        mainHandler.removeCallbacks(captureRunnable)
        Log.d(TAG, "Stopped periodic capture")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Initialize views first
        viewFinder = findViewById(R.id.viewFinder)
        viewFinder.surfaceTextureListener = surfaceTextureListener
        
        // Initialize USB manager
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        
        // Register USB receiver
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, filter)

        // Check for already connected devices
        checkForExistingUsbDevices()
        
        Log.d(TAG, "onCreate: Checking camera permissions")
        checkPermissions()
    }

    private fun checkForExistingUsbDevices() {
        Log.d(TAG, "Checking for existing USB devices")
        usbManager.deviceList.values.forEach { device ->
            checkUsbDevice(device)
        }
    }

    private fun checkUsbDevice(device: UsbDevice?) {
        device?.let {
            Log.d(TAG, "Found USB device: ${it.deviceName}, Product ID: ${it.productId}")
            if (!usbManager.hasPermission(it)) {
                val permissionIntent = PendingIntent.getBroadcast(
                    this,
                    0,
                    Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE
                )
                usbManager.requestPermission(it, permissionIntent)
            } else {
                initializeCamera()
            }
        }
    }

    private fun checkPermissions() {
        // Check camera permission
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ),
                CAMERA_PERMISSION_CODE
            )
        } else {
            initializeCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d(TAG, "onRequestPermissionsResult: Processing permission result")
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "onRequestPermissionsResult: Camera permission granted")
                initializeCamera()
            } else {
                Log.w(TAG, "onRequestPermissionsResult: Camera permission denied")
                Toast.makeText(
                    this,
                    "Camera permission is required for this app",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun initializeCamera() {
        Log.d(TAG, "initializeCamera: Initializing camera")
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        
        try {
            val cameraList = cameraManager.cameraIdList
            Log.d(TAG, "initializeCamera: Found ${cameraList.size} cameras")
            
            var externalCameraId: String? = null
            for (cameraId in cameraList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val streamConfigurationMap = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                )
                
                // Initialize ImageReader with supported size
                streamConfigurationMap?.let { map ->
                    val outputSizes = map.getOutputSizes(ImageFormat.JPEG)
                    if (outputSizes.isNotEmpty()) {
                        // Find highest resolution available
                        val maxSize = outputSizes.maxByOrNull { it.width * it.height } ?: outputSizes[0]
                        
                        // Close any existing ImageReader
                        imageReader?.close()
                        // Create new ImageReader with maximum size
                        imageReader = ImageReader.newInstance(
                            maxSize.width,
                            maxSize.height,
                            ImageFormat.JPEG,
                            2
                        ).apply {
                            setOnImageAvailableListener({ reader ->
                                val image = reader.acquireLatestImage()
                                try {
                                    if (image != null) {
                                        // Save the image
                                        val buffer = image.planes[0].buffer
                                        val bytes = ByteArray(buffer.remaining())
                                        buffer.get(bytes)
                                        
                                        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                        val fileName = "IMG_$timeStamp.jpg"
                                        
                                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                        val imageFile = File(downloadsDir, fileName)
                                        
                                        try {
                                            FileOutputStream(imageFile).use { output ->
                                                output.write(bytes)
                                            }
                                            runOnUiThread {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Image saved: $fileName",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                            Log.d(TAG, "Image saved successfully: ${imageFile.absolutePath}")
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error saving image", e)
                                            runOnUiThread {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Failed to save image",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                } finally {
                                    image?.close()
                                }
                            }, mainHandler)
                        }
                        Log.d(TAG, "ImageReader initialized with size: ${maxSize.width}x${maxSize.height}")
                    }
                }
                
                externalCameraId = cameraId
                break
            }
            Log.d(TAG, "initializeCamera: externalCameraId $externalCameraId")
            if (externalCameraId != null) {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    Log.i(TAG, "initializeCamera: Opening external camera")
                    cameraManager.openCamera(externalCameraId, cameraStateCallback, null)
                }
            } else {
                Log.w(TAG, "initializeCamera: No external camera found")
                Toast.makeText(
                    this,
                    "No external camera found",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "initializeCamera: Failed to access camera", e)
            Toast.makeText(
                this,
                "Failed to access camera: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.i(TAG, "CameraDevice.onOpened: Camera opened successfully")
            cameraDevice = camera
            
            // Get optimal preview size
            val characteristics = cameraManager.getCameraCharacteristics(camera.id)
            val streamConfigurationMap = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
            streamConfigurationMap?.let { map ->
                val outputSizes = map.getOutputSizes(SurfaceTexture::class.java)
                // Try to find a compatible size
                previewSize = outputSizes.firstOrNull { 
                    it.width <= 1920 && it.height <= 1080 && it.width % 2 == 0 && it.height % 2 == 0
                } ?: outputSizes[0]
                Log.d(TAG, "Selected preview size: ${previewSize.width}x${previewSize.height}")
            }
            
            // Create preview session if surface is available
            if (viewFinder.isAvailable) {
                createCameraPreviewSession()
            }
            
            Toast.makeText(
                this@MainActivity,
                "External camera connected successfully",
                Toast.LENGTH_SHORT
            ).show()
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "CameraDevice.onDisconnected: Camera disconnected")
            isCameraStabilized = false
            camera.close()
            cameraDevice = null
            stopPeriodicCapture()
            Toast.makeText(
                this@MainActivity,
                "Camera disconnected",
                Toast.LENGTH_SHORT
            ).show()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            val errorMsg = when (error) {
                ERROR_CAMERA_DEVICE -> "Camera device error"
                ERROR_CAMERA_DISABLED -> "Camera disabled"
                ERROR_CAMERA_IN_USE -> "Camera in use"
                ERROR_CAMERA_SERVICE -> "Camera service error"
                ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                else -> "Unknown camera error"
            }
            Log.e(TAG, "CameraDevice.onError: Camera error: $errorMsg (code: $error)")
            camera.close()
            cameraDevice = null
            Toast.makeText(
                this@MainActivity,
                "Camera error: $errorMsg",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            Log.d(TAG, "Surface texture available, width: $width, height: $height")
            // If camera is already opened, start preview
            if (cameraDevice != null) {
                createCameraPreviewSession()
            }
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            Log.d(TAG, "Surface texture size changed")
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            Log.d(TAG, "Surface texture destroyed")
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
            // This is called every time the preview frame is updated
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val texture = viewFinder.surfaceTexture ?: return
            
            // Set the default buffer size only once
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            
            val previewSurface = Surface(texture)
            val surfaces = mutableListOf(previewSurface)
            
            // Close any existing preview ImageReader
            previewImageReader?.close()
            
            // Create new ImageReader for preview
            previewImageReader = ImageReader.newInstance(
                previewSize.width,
                previewSize.height,
                ImageFormat.YUV_420_888,  // Use YUV format for preview
                2
            )
            
            previewImageReader?.surface?.let { surfaces.add(it) }
            // Add still capture ImageReader surface
            imageReader?.surface?.let { surfaces.add(it) }

            cameraDevice?.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            captureSession = session
                            
                            // Create preview request
                            val previewRequestBuilder = cameraDevice?.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW
                            )?.apply {
                                addTarget(previewSurface)
                                
                                // Basic preview settings
                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            }

                            previewRequestBuilder?.build()?.let { previewRequest ->
                                session.setRepeatingRequest(
                                    previewRequest,
                                    object : CameraCaptureSession.CaptureCallback() {
                                        override fun onCaptureCompleted(
                                            session: CameraCaptureSession,
                                            request: CaptureRequest,
                                            result: TotalCaptureResult
                                        ) {
                                            // Preview frame completed
                                        }
                                    },
                                    mainHandler
                                )
                                Log.d(TAG, "Preview started successfully")
                                
                                // Add delay before starting capture
                                mainHandler.postDelayed({
                                    isCameraStabilized = true
                                    startPeriodicCapture()
                                    Log.d(TAG, "Camera stabilized, starting capture")
                                }, CAMERA_STABILIZATION_DELAY)
                            }
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Failed to start preview", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Failed to configure camera session")
                        Toast.makeText(
                            this@MainActivity,
                            "Failed to configure camera",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                mainHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create preview session: ${e.message}")
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Cleaning up camera resources")
        isCameraStabilized = false
        stopPeriodicCapture()
        mainHandler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver might not be registered
        }
        cameraExecutor.shutdown()
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        previewImageReader?.close()
        super.onDestroy()
    }
}