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
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.util.Size
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val TAG = "MainActivity"
        private const val ACTION_USB_PERMISSION = "com.example.externalcameraapp.USB_PERMISSION"
    }

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private lateinit var usbManager: UsbManager
    private lateinit var viewFinder: TextureView
    private var captureSession: CameraCaptureSession? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var previewSize: Size = Size(640, 480) // Default size
    
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Starting activity")
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        
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
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        Log.d(TAG, "onCreate: Checking camera permissions")
        checkCameraPermission()

        viewFinder = findViewById(R.id.viewFinder)
        viewFinder.surfaceTextureListener = surfaceTextureListener
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

    private fun checkCameraPermission() {
        Log.d(TAG, "checkCameraPermission: Checking if camera permission is granted")
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "checkCameraPermission: Permission not granted, requesting permission")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        } else {
            Log.i(TAG, "checkCameraPermission: Permission already granted")
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
                val cameraLocation = characteristics.get(CameraCharacteristics.LENS_FACING)
                Log.d(TAG, "initializeCamera: Checking camera $cameraId, facing: $cameraLocation")
                externalCameraId = cameraId
                break
//                if (cameraLocation == CameraCharacteristics.LENS_FACING_EXTERNAL) {
//                    Log.i(TAG, "initializeCamera: Found external camera with id: $cameraId")
//                    externalCameraId = cameraId
//                    break
//                }
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
                previewSize = map.getOutputSizes(SurfaceTexture::class.java)[0]
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
            camera.close()
            cameraDevice = null
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
            val texture = viewFinder.surfaceTexture
            texture?.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface = Surface(texture)

            val captureRequestBuilder = cameraDevice?.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            )?.apply {
                addTarget(previewSurface)
            }

            cameraDevice?.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return

                        captureSession = session
                        try {
                            captureRequestBuilder?.let { builder ->
                                builder.set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                )
                                val captureRequest = builder.build()
                                session.setRepeatingRequest(
                                    captureRequest,
                                    null,
                                    cameraExecutor.run { null }
                                )
                                Log.d(TAG, "Camera preview started")
                            }
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "Failed to start camera preview: ${e.message}")
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
                null
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create preview session: ${e.message}")
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Cleaning up camera resources")
        unregisterReceiver(usbReceiver)
        cameraExecutor.shutdown()
        captureSession?.close()
        cameraDevice?.close()
        super.onDestroy()
    }
}