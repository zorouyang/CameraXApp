/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zorouyang.cameraxapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.Camera
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.*
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.zxing.Result
import com.zorouyang.cameraxapp.util.*
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

/** Milliseconds used for UI animations */
const val ANIMATION_FAST_MILLIS = 50L
const val ANIMATION_SLOW_MILLIS = 100L

/** Helper type alias used for analysis use case callbacks */
typealias LumaListener = (luma: Double) -> Unit

/**
 * Main fragment for this app. Implements all camera operations including:
 * - Viewfinder
 * - Photo taking
 * - Image analysis
 */
class CameraFragment : Fragment(), Decoder.OnResultListener {

    private lateinit var container: ConstraintLayout
    private lateinit var viewFinder: TextureView

    private var displayId = -1
    private var lensFacing = CameraX.LensFacing.BACK
    private lateinit var preview: Preview
    private lateinit var imageCapture: ImageCapture
    private lateinit var imageAnalyzer: ImageAnalysis

    /** Internal reference of the [DisplayManager] */
    //private lateinit var displayManager: DisplayManager

    /**
     * We need a display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    /*private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                preview?.setTargetRotation(view.display.rotation)
//                imageCapture?.setTargetRotation(view.display.rotation)
                imageAnalyzer?.setTargetRotation(view.display.rotation)
            }
        } ?: Unit
    }*/

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mark this as a retain fragment, so the lifecycle does not get restarted on config change
        retainInstance = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        CameraX.unbindAll()
        //displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {

        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_camera, container, false)
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        container = view as ConstraintLayout
        viewFinder = container.findViewById(R.id.view_finder)

        // Every time the orientation of device changes, recompute layout
        /*displayManager = viewFinder.context
                .getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)*/

        if (ContextCompat.checkSelfPermission(context!!, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            return

        // Wait for the views to be properly laid out
        viewFinder.post {
            // Keep track of the display in which this view is attached
            displayId = viewFinder.display.displayId

            // Build UI controls and bind all camera use cases
            updateCameraUi()
            bindCameraUseCases()
        }
    }

    /** Declare and bind preview, capture and analysis use cases */
    private fun bindCameraUseCases() {

        /*var point = DisplayUtils.getDeviceRealDResolution(context)
        Log.d(TAG, "getDeviceRealDResolution: ${point.x} x ${point.y}")

        point = DisplayUtils.getScreenResolution(context)
        Log.d(TAG, "getScreenResolution: ${point.x} x ${point.y}")*/

        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
        val screenAspectRatio = Rational(metrics.widthPixels, metrics.heightPixels)
        Log.d(TAG, "Screen metrics: ${metrics.widthPixels} x ${metrics.heightPixels}")

        // Set up the view finder use case to display camera preview
        val viewFinderConfig = PreviewConfig.Builder().apply {
            setLensFacing(lensFacing)
            // We request aspect ratio but no resolution to let CameraX optimize our use cases
            setTargetAspectRatio(screenAspectRatio)
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            setTargetRotation(viewFinder.display.rotation)
        }.build()

        // Use the auto-fit preview builder to automatically handle size and orientation changes
        preview = AutoFitPreviewBuilder.build(viewFinderConfig, viewFinder)

        // Set up the capture use case to allow users to take photos
        val imageCaptureConfig = ImageCaptureConfig.Builder().apply {
            setLensFacing(lensFacing)
            setCaptureMode(ImageCapture.CaptureMode.MIN_LATENCY)
            setFlashMode(FlashMode.AUTO)
            // We request aspect ratio but no resolution to match preview config but letting
            // CameraX optimize for whatever specific resolution best fits requested capture mode
            setTargetAspectRatio(screenAspectRatio)
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            setTargetRotation(viewFinder.display.rotation)
        }.build()

        imageCapture = ImageCapture(imageCaptureConfig)

        // Setup image analysis pipeline that computes average pixel luminance in real time
        val analyzerConfig = ImageAnalysisConfig.Builder().apply {
            setLensFacing(lensFacing)
            // Use a worker thread for image analysis to prevent preview glitches
            val analyzerThread = HandlerThread("LuminosityAnalysis").apply { start() }
            setCallbackHandler(Handler(analyzerThread.looper))
            // In our analysis, we care more about the latest image than analyzing *every* image
            setImageReaderMode(ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE)
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            setTargetRotation(viewFinder.display.rotation)
//            setTargetResolution(Size(1920, 1080))
            setTargetResolution(Size(4256, 2832))
        }.build()

        /*imageAnalyzer = ImageAnalysis(analyzerConfig).apply {
            analyzer = LuminosityAnalyzer { luma ->
                // Values returned from our analyzer are passed to the attached listener
                // We log image analysis results here -- you should do something useful instead!
                val fps = (analyzer as LuminosityAnalyzer).framesPerSecond
                Log.d(TAG, "Average luminosity: $luma. " +
                        "Frames per second: ${"%.01f".format(fps)}")
            }
        }*/
        imageAnalyzer = ImageAnalysis(analyzerConfig)
        setAnalyzer()

        // Apply declared configs to CameraX using the same lifecycle owner
        CameraX.bindToLifecycle(
                viewLifecycleOwner, preview, imageCapture, imageAnalyzer)


        val finder = container.findViewById<ViewFinderView>(R.id.view_finder_rect)
        finder.framingRect
        decoder = Decoder(Size(metrics.widthPixels, metrics.heightPixels), this)

        updateViewFinder()
    }

    private lateinit var decoder: Decoder
    private var dialog: Dialog? = null

    private fun setAnalyzer() {
        imageAnalyzer.setAnalyzer { image: ImageProxy, rotationDegrees: Int ->
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.capacity()).also { buffer.get(it) }

            decoder.decode(bytes, image.width, image.height)
        }
    }

    private fun updateViewFinder() {
        /*val viewFinder = container.findViewById<FrameLayout>(R.id.view_finder_rect)
        val layoutParams: ConstraintLayout.LayoutParams =
            viewFinder.layoutParams as ConstraintLayout.LayoutParams
        layoutParams.height = decoder.framingRect.height()
        layoutParams.width = decoder.framingRect.width()
//        layoutParams.topMargin = decoder.framingRect.top
//        layoutParams.marginStart = decoder.framingRect.left
        viewFinder.layoutParams = layoutParams

        val animation = TranslateAnimation(
            Animation.RELATIVE_TO_PARENT, 0.0f,
            Animation.RELATIVE_TO_PARENT, 0.0f,
            Animation.RELATIVE_TO_PARENT, 0.0f,
            Animation.RELATIVE_TO_PARENT, 1.0f
        )
        animation.duration = 2000
        animation.repeatCount = -1
        animation.repeatMode = Animation.RESTART

        val mLine = container.findViewById<View>(R.id.scan_line)
        mLine.startAnimation(animation)*/

        //Update photos button location
        val viewGallery = container.findViewById<View>(R.id.photo_view_button)
        val layoutParams = viewGallery.layoutParams as ConstraintLayout.LayoutParams
        layoutParams.bottomMargin = decoder.framingRect.top - viewGallery.height - DisplayUtils.dpToPx(30f)
        viewGallery.layoutParams = layoutParams

        val viewDescribeText = container.findViewById<View>(R.id.describe_view_text)
        val txtParams = viewDescribeText.layoutParams as ConstraintLayout.LayoutParams
        txtParams.topMargin = decoder.framingRect.top / 2 + viewDescribeText.height
        viewDescribeText.layoutParams = txtParams
    }

    /** Method used to re-draw the camera UI controls, called every time configuration changes */
    private fun updateCameraUi() {

        container.findViewById<View>(R.id.photo_view_button).setOnClickListener {
            if (ContextCompat.checkSelfPermission(context!!, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                goPicture()
            } else {
                ActivityCompat.requestPermissions(activity!!, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), _accessExternalStorage)
            }
        }

        container.findViewById<View>(R.id.camera_capture_button).setOnClickListener {
            // Get a stable reference of the modifiable image capture use case
            imageCapture?.let { imageCapture ->

                // Create output file to hold the image
                val photoFile = createFile(getOutputDirectory(context!!), FILENAME, PHOTO_EXTENSION)

                // Setup image capture metadata
                val metadata = ImageCapture.Metadata().apply {
                    // Mirror image when using the front camera
                    isReversedHorizontal = lensFacing == CameraX.LensFacing.FRONT
                }

                // Setup image capture listener which is triggered after photo has been taken
                imageCapture.takePicture(photoFile, imageSavedListener, metadata)

                // We can only change the foreground Drawable using API level 23+ API
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                    // Display flash animation to indicate that photo was captured
                    container.postDelayed({
                        container.foreground = ColorDrawable(Color.WHITE)
                        container.postDelayed(
                            { container.foreground = null }, ANIMATION_FAST_MILLIS)
                    }, ANIMATION_SLOW_MILLIS)
                }
            }
        }
    }

    override fun onResult(result: Result?) {
        activity?.runOnUiThread {
            // Stop analysis while showing dialog
            imageAnalyzer.removeAnalyzer()

            // Avoid showing more than one dialog
            if (dialog?.isShowing == true) {
                return@runOnUiThread
            }

            dialog = AlertDialog.Builder(context!!)
                .setTitle(result?.barcodeFormat?.name)
                .setMessage(result.toString())
                //.setCancelable(false)
                .setPositiveButton(android.R.string.ok) { _, _ -> setAnalyzer() }
                .setOnCancelListener { setAnalyzer() }
                .show()
        }
    }

    private val _accessExternalStorage = 100
    private val _accessGalleryCode = 101

    private fun goPicture() {
        val intent = Intent(Intent.ACTION_GET_CONTENT);
        intent.type = "image/*";
        startActivityForResult(intent, _accessGalleryCode);
    }

    private fun decodeImage(contentUri: Uri) {
        try {
            val result = decoder.decode(GalleryPhotoUtil.getPath(context, contentUri))
            Log.i(TAG, "result: $result")

            if (result == null) {
                Toast.makeText(context, "No QRCode was identified", Toast.LENGTH_LONG).show()
                return
            } else {
                // has handler with onResultListener
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception: ${e.message}", e)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == _accessGalleryCode && resultCode == Activity.RESULT_OK) {
            val contentUri = data?.data ?: return

            decodeImage(contentUri)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == _accessExternalStorage && grantResults.isNotEmpty()) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                goPicture()
            }
        }
    }

    /** Define callback that will be triggered after a photo has been taken and saved to disk */
    private val imageSavedListener = object : ImageCapture.OnImageSavedListener {
        override fun onError(
            error: ImageCapture.UseCaseError, message: String, exc: Throwable?) {
            Log.e(TAG, "Photo capture failed: $message")
            exc?.printStackTrace()
        }

        override fun onImageSaved(photoFile: File) {
            Log.d(TAG, "Photo capture succeeded: ${photoFile.absolutePath}")

            // Implicit broadcasts will be ignored for devices running API
            // level >= 24, so if you only target 24+ you can remove this statement
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                requireActivity().sendBroadcast(
                    Intent(Camera.ACTION_NEW_PICTURE, Uri.fromFile(photoFile)))
            }

            // If the folder selected is an external media directory, this is unnecessary
            // but otherwise other apps will not be able to access our images unless we
            // scan them using [MediaScannerConnection]
            val mimeType = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(photoFile.extension)
            MediaScannerConnection.scanFile(
                context, arrayOf(photoFile.absolutePath), arrayOf(mimeType), null)
        }
    }

    /**
     * Our custom image analysis class.
     *
     * <p>All we need to do is override the function `analyze` with our desired operations. Here,
     * we compute the average luminosity of the image by looking at the Y plane of the YUV frame.
     */
    private class LuminosityAnalyzer(listener: LumaListener? = null) : ImageAnalysis.Analyzer {
        private val frameRateWindow = 8
        private val frameTimestamps = ArrayDeque<Long>(5)
        private val listeners = ArrayList<LumaListener>().apply { listener?.let { add(it) } }
        private var lastAnalyzedTimestamp = 0L
        var framesPerSecond: Double = -1.0
            private set

        /**
         * Used to add listeners that will be called with each luma computed
         */
        fun onFrameAnalyzed(listener: LumaListener) = listeners.add(listener)

        /**
         * Helper extension function used to extract a byte array from an image plane buffer
         */
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        /**
         * Analyzes an image to produce a result.
         *
         * <p>The caller is responsible for ensuring this analysis method can be executed quickly
         * enough to prevent stalls in the image acquisition pipeline. Otherwise, newly available
         * images will not be acquired and analyzed.
         *
         * <p>The image passed to this method becomes invalid after this method returns. The caller
         * should not store external references to this image, as these references will become
         * invalid.
         *
         * @param image image being analyzed VERY IMPORTANT: do not close the image, it will be
         * automatically closed after this method returns
         * @return the image analysis result
         */
        override fun analyze(image: ImageProxy, rotationDegrees: Int) {
            Log.d(TAG, "analyze: ${image.width}x${image.height}, degrees: $rotationDegrees" )
            // If there are no listeners attached, we don't need to perform analysis
            if (listeners.isEmpty()) return

            // Keep track of frames analyzed
            frameTimestamps.push(System.currentTimeMillis())

            // Compute the FPS using a moving average
            while (frameTimestamps.size >= frameRateWindow) frameTimestamps.removeLast()
            framesPerSecond = 1.0 / ((frameTimestamps.peekFirst() -
                    frameTimestamps.peekLast())  / frameTimestamps.size.toDouble()) * 1000.0

            // Calculate the average luma no more often than every second
            if (frameTimestamps.first - lastAnalyzedTimestamp >= TimeUnit.SECONDS.toMillis(1)) {
                // Since format in ImageAnalysis is YUV, image.planes[0] contains the Y
                // (luminance) plane
                val buffer = image.planes[0].buffer

                // Extract image data from callback object
                val data = buffer.toByteArray()

                // Convert the data into an array of pixel values
                val pixels = data.map { it.toInt() and 0xFF }

                // Compute average luminance for the image
                val luma = pixels.average()

                // Call all listeners with new value
                listeners.forEach { it(luma) }

                lastAnalyzedTimestamp = frameTimestamps.first
            }
        }
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpg"

        /** Use external media if it is available, our app's file directory otherwise */
        private fun getOutputDirectory(context: Context): File {
            val appContext = context.applicationContext
            val mediaDir = context.externalMediaDirs.firstOrNull()?.let {
                File(it, appContext.resources.getString(R.string.app_name)).apply { mkdirs() } }
            return if (mediaDir != null && mediaDir.exists())
                mediaDir else appContext.filesDir
        }

        private fun createFile(baseFolder: File, format: String, extension: String) =
            File(baseFolder, SimpleDateFormat(format, Locale.US)
                .format(System.currentTimeMillis()) + extension)
    }
}
