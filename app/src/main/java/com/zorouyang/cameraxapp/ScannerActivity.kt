package com.zorouyang.cameraxapp

// different implementations so we list them here to disambiguate
import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.zorouyang.cameraxapp.util.AutoFitPreviewBuilder
import com.zorouyang.cameraxapp.util.Decoder
import com.zorouyang.cameraxapp.util.DisplayUtils
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit


// This is an arbitrary number we are using to keep tab of the permission
// request. Where an app has multiple context for requesting permission,
// this can help differentiate the different contexts
private const val REQUEST_CODE_PERMISSIONS = 10

// This is an array of all the permission specified in the manifest
private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

class ScannerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scanner);

        DisplayUtils.setStableFullscreen(this)

        setSupportActionBar(findViewById(R.id.toolbar))

        // Add this at the end of onCreate function

        viewFinder = findViewById(R.id.view_finder)

        // Request camera permissions
        if (allPermissionsGranted()) {
            viewFinder.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Every time the provided texture view changes, recompute layout
        viewFinder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTransform()
        }
    }

    // Add this after onCreate

    private lateinit var viewFinder: TextureView
    private lateinit var preview: Preview
    private var lensFacing = CameraX.LensFacing.BACK

    private fun startCamera() {

        val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
        //val screenSize = Size(metrics.widthPixels, metrics.heightPixels)
        val screenAspectRatio = Rational(metrics.widthPixels, metrics.heightPixels)


        // Create configuration object for the viewfinder use case
        val previewConfig = PreviewConfig.Builder().apply {
//            setTargetAspectRatio(Rational(1, 1))
//            setTargetResolution(Size(1280, 1280))
            setLensFacing(lensFacing)
//            setTargetResolution(screenSize)
            setTargetAspectRatio(screenAspectRatio)
//            setTargetRotation(windowManager.defaultDisplay.rotation)
            setTargetRotation(viewFinder.display.rotation)
        }.build()

        /*// Build the viewfinder use case
        val preview = Preview(previewConfig)

        // Every time the viewfinder is updated, recompute layout
        preview.setOnPreviewOutputUpdateListener {

            // To update the SurfaceTexture, we have to remove it and re-add it
            val parent = viewFinder.parent as ViewGroup
            parent.removeView(viewFinder)
            parent.addView(viewFinder, 0)

            viewFinder.surfaceTexture = it.surfaceTexture
            updateTransform()
        }*/

        preview = AutoFitPreviewBuilder.build(previewConfig, viewFinder)

        /*// Create configuration object for the image capture use case
        val imageCaptureConfig = ImageCaptureConfig.Builder()
            .apply {
                //setTargetAspectRatio(Rational(1, 1))

                setLensFacing(lensFacing)
                setTargetAspectRatio(screenAspectRatio)
                setTargetRotation(viewFinder.display.rotation)
                // We don't set a resolution for image capture; instead, we
                // select a capture mode which will infer the appropriate
                // resolution based on aspect ration and requested mode
                setCaptureMode(ImageCapture.CaptureMode.MIN_LATENCY)
            }.build()

        // Build the image capture use case and attach button click listener
        val imageCapture = ImageCapture(imageCaptureConfig)
        findViewById<View>(R.id.view_finder).setOnClickListener {
            val file = File(externalMediaDirs.first(), "${System.currentTimeMillis()}.jpg")
            imageCapture.takePicture(file,
                object : ImageCapture.OnImageSavedListener {
                    override fun onError(error: ImageCapture.UseCaseError,
                                         message: String, exc: Throwable?) {
                        val msg = "Photo capture failed: $message"
                        Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                        Log.e("CameraXApp", msg)
                        exc?.printStackTrace()
                    }

                        override fun onImageSaved(file: File) {
                        val msg = "Photo capture succeeded: ${file.absolutePath}"
                        Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                        Log.d("CameraXApp", msg)
                    }
                })
        }*/


        // Setup image analysis pipeline that computes average pixel luminance
        val analyzerConfig = ImageAnalysisConfig.Builder().apply {
            // Use a worker thread for image analysis to prevent glitches
            val analyzerThread = HandlerThread(
                "LuminosityAnalysis").apply { start() }
            setCallbackHandler(Handler(analyzerThread.looper))
            // In our analysis, we care more about the latest image than
            // analyzing *every* image
            setImageReaderMode(
                ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE)
        }.build()

        // Build the image analysis use case and instantiate our analyzer
        imageAnalysis = ImageAnalysis(analyzerConfig)/*.apply {
            analyzer = LuminosityAnalyzer()
        }*/
        setAnalyzer()

        // Bind use cases to lifecycle
        // If Android Studio complains about "this" being not a LifecycleOwner
        // try rebuilding the project or updating the appcompat dependency to
        // version 1.1.0 or higher.
        CameraX.bindToLifecycle(this, preview, /*imageCapture,*/ imageAnalysis)

        decoder = Decoder(Size(metrics.widthPixels, metrics.heightPixels), Decoder.OnResultListener { result ->
            runOnUiThread {
                // Stop analysis while showing dialog
                imageAnalysis.removeAnalyzer()

                // Avoid showing more than one dialog
                if (dialog?.isShowing == true) {
                    return@runOnUiThread
                }

                dialog = AlertDialog.Builder(this)
                    .setTitle(result?.barcodeFormat?.name)
                    .setMessage(result.toString())
                    .setPositiveButton(android.R.string.ok) { _, _ -> setAnalyzer() }
                    .show()
            }
        })
    }

    private lateinit var imageAnalysis: ImageAnalysis
    private lateinit var decoder: Decoder
    private var dialog: Dialog? = null

    private fun setAnalyzer() {
        imageAnalysis.setAnalyzer { image: ImageProxy, _: Int ->
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.capacity()).also { buffer.get(it) }
            decoder.decode(bytes, image.width, image.height)
        }
    }

    private fun updateViewFinder() {
        val viewFinder = findViewById<FrameLayout>(R.id.view_finder)
        val layoutParams: FrameLayout.LayoutParams =
            viewFinder.layoutParams as FrameLayout.LayoutParams
        layoutParams.height = decoder.framingRect.height()
        layoutParams.width = decoder.framingRect.width()
        layoutParams.topMargin = decoder.framingRect.top
        layoutParams.marginStart = decoder.framingRect.left
        viewFinder.layoutParams = layoutParams
    }

    private class LuminosityAnalyzer : ImageAnalysis.Analyzer {
        private var lastAnalyzedTimestamp = 0L

        /**
         * Helper extension function used to extract a byte array from an
         * image plane buffer
         */
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        override fun analyze(image: ImageProxy, rotationDegrees: Int) {
            val currentTimestamp = System.currentTimeMillis()
            // Calculate the average luma no more often than every second
            if (currentTimestamp - lastAnalyzedTimestamp >=
                TimeUnit.SECONDS.toMillis(1)) {
                // Since format in ImageAnalysis is YUV, image.planes[0]
                // contains the Y (luminance) plane
                val buffer = image.planes[0].buffer
                // Extract image data from callback object
                val data = buffer.toByteArray()
                // Convert the data into an array of pixel values
                val pixels = data.map { it.toInt() and 0xFF }
                // Compute average luminance for the image
                val luma = pixels.average()
                // Log the new luma value
                Log.d("CameraXApp", "Average luminosity: $luma")
                // Update timestamp of last analyzed frame
                lastAnalyzedTimestamp = currentTimestamp
            }
        }
    }

    private fun updateTransform() {
        val matrix = Matrix()

        // Compute the center of the view finder
        val centerX = viewFinder.width / 2f
        val centerY = viewFinder.height / 2f

        // Correct preview output to account for display rotation
        val rotationDegrees = when(viewFinder.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }
        matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)

        // Finally, apply transformations to our TextureView
        viewFinder.setTransform(matrix)
    }

    /**
     * Process result from permission request dialog box, has the request
     * been granted? If yes, start Camera. Otherwise display a toast
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                viewFinder.post { startCamera() }
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /**
     * Check if all permission specified in the manifest have been granted
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
}
