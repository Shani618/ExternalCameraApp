package com.example.externalcameraapp

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.os.IBinder
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.graphics.ImageFormat
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat

class CameraService : Service() {
    companion object {
        private const val TAG = "CameraService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "CameraServiceChannel"
        private const val CAPTURE_INTERVAL = 10000L // 10 seconds
        private const val CAMERA_STABILIZATION_DELAY = 5000L // 5 seconds
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

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        initializeCamera()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Camera Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Camera capture service"
            enableLights(true)
            lightColor = Color.BLUE
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Camera Service")
            .setContentText("Capturing images...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun initializeCamera() {
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        
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

            externalCameraId?.let {
                cameraManager.openCamera(it, cameraStateCallback, mainHandler)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to initialize camera", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "No camera permission", e)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopPeriodicCapture()
        mainHandler.removeCallbacksAndMessages(null)
        cameraDevice?.close()
        imageReader?.close()
        super.onDestroy()
    }
} 