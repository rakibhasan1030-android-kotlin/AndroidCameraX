package rakib.hasan.androidcamerax

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import rakib.hasan.androidcamerax.databinding.ActivityMainBinding
import java.lang.Exception
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias LumaListener = (luma: Double) -> Unit

/**
 * {@author Rakib Hasan}
 *
 * [ NOTE: Combine VideoCapture with other use cases -
 * This step has device level requirements as specified in the Camera2 device documentation:
 * 1. Preview + VideoCapture + ImageCapture: LIMITED device and above.
 * 2. Preview + VideoCapture + ImageAnalysis: LEVEL_3 (the highest) device added to Android 7(N).
 * 3. Preview + VideoCapture + ImageAnalysis + ImageCapture: not supported.
 *
 * So, if we want to play in every possible device then we've to use ImageCapture+Preview or VideoCapture+Preview
 * */
class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null

    private var videoCapture: VideoCapture<Recorder>? = null

    private var recording: Recording? = null

    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Set up the listeners for take photo and video capture buttons
        viewBinding.imageCaptureButton.setOnClickListener { takePhoto() }
        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun takePhoto() {

        //Get a stable reference of the modifiable imageCapture use case
        // If the use case is null, exit out of the function.
        // This will be null If we tap the photo button before image capture is set up.
        // Without the return statement, the app would crash if it was null.
        val imageCapture = imageCapture ?: return

        // Create a MediaStore content value to hold the image.
        // Use a timestamp so the display name in MediaStore will be unique.
        val name: String = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P){
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        // Create an OutputFileOptions object.
        // This object is where we can specify things about how we want our output to be.
        // We want the output saved in the MediaStore so other apps could display it,
        // so add our MediaStore entry. This object which contains file + metadata
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        // Call takePicture() on the imageCapture object.
        // Pass in outputOptions, the executor, and a callback for when the image is saved.
        // You'll fill out the callback next.
        // Set up image capture listener, which is triggered after photo has been taken
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {

                //In the case that the image capture fails or saving the image capture fails,
                // add in an error case to log that it failed.
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                }
                //If the capture doesn't fail, the photo was taken successfully!
                // Save the photo to the file we created earlier,
                // present a toast to let the user know it was successful, and print a log statement.
            override fun onImageSaved(output: ImageCapture.OutputFileResults){
                val msg = "Photo capture succeeded: ${output.savedUri}"
                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                Log.d(TAG, msg)
            }
        })
    }

    private fun captureVideo() {

        //Check if the VideoCapture use case has been created: if not, do nothing.
        val videoCapture = this.videoCapture ?: return

        //Disable the UI until the request action is completed by CameraX.
        // it is re-enabled inside our registered VideoRecordListener in later steps.
        viewBinding.videoCaptureButton.isEnabled = false


        //If there is an active recording in progress, stop it and release the current recording.
        // We will be notified when the captured video file is ready to be used by our application.
        val curRecording = recording
        if (curRecording != null){
            //Stop the current recording session
            curRecording.stop()
            recording = null
            return
        }

        // To start recording, we create a new recording session.
        // First we create our intended MediaStore video content object,
        // with system timestamp as the display name(so we could capture multiple videos).
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraX-Video")
            }
        }

        //Create a MediaStoreOutputOptions.Builder with the external content option.
        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        //Configure the output option to the Recorder of VideoCapture<Recorder> and enable audio recording.
        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)

            //Enable Audio in this recording.
            .apply {
                if (PermissionChecker.checkSelfPermission(this@MainActivity,
                        Manifest.permission.RECORD_AUDIO) ==
                    PermissionChecker.PERMISSION_GRANTED)
                {
                    withAudioEnabled()
                }
            }

            //Start this new recording, and register a lambda VideoRecordEvent listener.
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when(recordEvent) {

                    //When the request recording is started by the camera device,
                    // toggle the "Start Capture" button text to say "Stop Capture".
                    is VideoRecordEvent.Start -> {
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.stop_capture)
                            isEnabled = true
                        }
                    }

                    //When the active recording is complete, notify the user with a toast,
                    // and toggle the "Stop Capture" button back to "Start Capture", and re-enable it.
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "Video capture succeeded: " +
                                    "${recordEvent.outputResults.outputUri}"
                            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                            Log.d(TAG, msg)
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video capture ends with error: " + "${recordEvent.error}")
                        }
                        viewBinding.videoCaptureButton.apply {
                            text = getString(R.string.start_capture)
                            isEnabled = true
                        }
                    }
                }
            }
    }

    private fun startCamera() {

        // Create an instance of the ProcessCameraProvider.
        // This is used to bind the lifecycle of cameras to the lifecycle owner.
        // This eliminates the task of opening and closing the camera since CameraX is lifecycle-aware.
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        // Add a listener to the cameraProviderFuture. Add a Runnable as one argument.
        // We will fill it in later. Add ContextCompat.getMainExecutor() as the second argument.
        // This returns an Executor that runs on the main thread.
        cameraProviderFuture.addListener({
            // In the Runnable, add a ProcessCameraProvider.
            // This is used to bind the lifecycle of our camera to the LifecycleOwner within the application's process.
            // Used to bind lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Initialize our Preview object, call build on it,
            // get a surface provider from viewfinder, and then set it on the preview.
            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider) }

            imageCapture = ImageCapture.Builder().build()
            val imageAnalyzer = ImageAnalysis.Builder()
                .build()
                .also { it.setAnalyzer(cameraExecutor, LuminosityAnalyzer{luma ->
                    Log.v(TAG, "Average luminosity: $luma")
                }) }


            //This will create the VideoCapture use case ---------------------------
            val recorder = Recorder.Builder()
                // QualitySelector HIGHEST, when only preview + video capture
                //.setQualitySelector(QualitySelector.from(Quality.HIGHEST))

                // QualitySelector HIGHEST, when only preview + video capture
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            //______________________________________________________________________



            // Create a CameraSelector object and select DEFAULT_BACK_CAMERA.
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            //Create a try block. Inside that block,
            // make sure nothing is bound to the cameraProvider,
            // and then bind our cameraSelector and preview object to the cameraProvider.
            try {
                //Unbind use cases before rebinding
                cameraProvider.unbindAll()

                //Bind use case to camera

                //for image capture
                //cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, imageAnalyzer)

                //for video capture
                //cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture)

                // Also in the startCamera(), bind the imageCapture use case with the existing preview
                // and videoCapture use cases (note: do not bind imageAnalyzer,
            // as a preview + imageCapture + videoCapture + imageAnalysis combination is not supported)
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture, videoCapture)


            }catch (e: Exception){
                // There are a few ways this code could fail,
                // like if the app is no longer in focus.
                // Wrap this code in a catch block to log if there's a failure.
                Log.v(TAG, "Use case binding failed", e)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        //Check if the request code is correct, ignore it otherwise.
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {

                //If the permissions are granted, call startCamera().
                startCamera()
            } else {
                //If permissions are not granted, present a toast to notify the user that the permissions were not granted.
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }


    private class LuminosityAnalyzer(private  val listener: LumaListener): ImageAnalysis.Analyzer{

        private fun ByteBuffer.toByteArray(): ByteArray{
            rewind() // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data) // Copy the buffer into a byte array
            return data // return the byte array
        }

        override fun analyze(image: ImageProxy) {

            val buffer = image.planes[0].buffer
            val data = buffer.toByteArray()
            val pixel = data.map { it.toInt() and 0xFF }
            val luma = pixel.average()
            listener(luma)
            image.close()
        }

    }

}