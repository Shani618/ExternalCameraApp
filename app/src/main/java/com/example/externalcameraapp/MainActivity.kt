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
    private var captureSession: CameraCaptureSession? = null
    private var isCapturing = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var imageReader: ImageReader? = null
    private var isCameraStabilized = false

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (isCapturing) {
                captureImage()
                mainHandler.postDelayed(this, CAPTURE_INTERVAL)
            }
        }
    }

    private fun createCaptureSession() {
        try {
            val surfaces = mutableListOf<Surface>()
            imageReader?.surface?.let { surfaces.add(it) }

            cameraDevice?.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        mainHandler.postDelayed({
                            isCameraStabilized = true
                            startPeriodicCapture()
                            Log.d(TAG, "Camera stabilized, starting capture")
                        }, CAMERA_STABILIZATION_DELAY)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Failed to configure camera session")
                    }
                },
                mainHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create capture session: ${e.message}")
        }
    }

    private fun initializeCamera() {
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val cameraList = cameraManager.cameraIdList
            var externalCameraId: String? = null
            
            for (cameraId in cameraList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val streamConfigurationMap = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                )
                
                streamConfigurationMap?.let { map ->
                    val outputSizes = map.getOutputSizes(ImageFormat.JPEG)
                    if (outputSizes.isNotEmpty()) {
                        val maxSize = outputSizes.maxByOrNull { it.width * it.height } ?: outputSizes[0]
                        
                        imageReader?.close()
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
                                        val buffer = image.planes[0].buffer
                                        val bytes = ByteArray(buffer.remaining())
                                        buffer.get(bytes)
                                        
                                        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                                        val fileName = "IMG_$timeStamp.jpg"
                                        
                                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                        val imageFile = File(downloadsDir, fileName)
                                        
                                        FileOutputStream(imageFile).use { output ->
                                            output.write(bytes)
                                        }
                                        Log.d(TAG, "Image saved: ${imageFile.absolutePath}")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error saving image", e)
                                } finally {
                                    image?.close()
                                }
                            }, mainHandler)
                        }
                    }
                }
                externalCameraId = cameraId
                break
            }

            if (externalCameraId != null && ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                cameraManager.openCamera(externalCameraId, cameraStateCallback, mainHandler)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to initialize camera", e)
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCaptureSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
            stopPeriodicCapture()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
        }
    }

    private lateinit var viewFinder: TextureView

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

    private fun checkPermissions(): Boolean {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.POST_NOTIFICATIONS
        )

        val notGrantedPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGrantedPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                notGrantedPermissions.toTypedArray(),
                CAMERA_PERMISSION_CODE
            )
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startCameraService()
            } else {
                Toast.makeText(this, "Permissions required for camera operation", Toast.LENGTH_LONG).show()
            }
        }
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
        if (checkPermissions()) {
            startCameraService()
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            Log.d(TAG, "Surface texture available, width: $width, height: $height")
            // If camera is already opened, start preview
            if (cameraDevice != null) {
                createCaptureSession()
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

    private fun startCameraService() {
        Intent(this, CameraService::class.java).also { intent ->
            startForegroundService(intent)
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
        cameraDevice?.close()
        imageReader?.close()
        super.onDestroy()
    }
}