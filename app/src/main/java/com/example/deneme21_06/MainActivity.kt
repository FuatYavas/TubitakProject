package com.example.deneme21_06

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.deneme21_06.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.nio.FloatBuffer
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private lateinit var uploadImageLauncher: ActivityResultLauncher<Intent>

    private lateinit var ortEnvironment: OrtEnvironment
    private lateinit var session: OrtSession
    private lateinit var faceDetector: FaceDetector

    private var lastAnalyzedTimestamp = 0L

    private val inputImageWidth = 224
    private val inputImageHeight = 224
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ağır başlatma işlemleri bitene kadar UI etkileşimini sınırla
        binding.switchCameraButton.isEnabled = false
        binding.uploadImageButton.isEnabled = false

        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {
            try {
                // ONNX Runtime ve Face Detector'ı arka planda başlat
                ortEnvironment = OrtEnvironment.getEnvironment()
                session = createOrtSession()

                val faceDetectorOptions = FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                    .build()
                faceDetector = FaceDetection.getClient(faceDetectorOptions)

                // Başlatma tamamlandığında UI'ı ana iş parçacığında güncelle
                runOnUiThread {
                    if (allPermissionsGranted()) {
                        startCamera()
                    } else {
                        ActivityCompat.requestPermissions(
                            this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
                        )
                    }
                    binding.switchCameraButton.isEnabled = true
                    binding.uploadImageButton.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Başlatma sırasında hata", e)
                runOnUiThread {
                    Toast.makeText(this, "Başlatma hatası: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.switchCameraButton.setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            startCamera()
        }

        binding.uploadImageButton.setOnClickListener {
            openGallery()
        }

        uploadImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                data?.data?.let { uri ->
                    try {
                        val bitmap = if (Build.VERSION.SDK_INT < 28) {
                            MediaStore.Images.Media.getBitmap(this.contentResolver, uri)
                        } else {
                            val source = ImageDecoder.createSource(this.contentResolver, uri)
                            ImageDecoder.decodeBitmap(source)
                        }.copy(Bitmap.Config.ARGB_8888, true)

                        binding.cameraPreview.visibility = View.GONE
                        binding.imageView.visibility = View.VISIBLE
                        binding.imageView.setImageBitmap(bitmap)
                        analyzeBitmap(bitmap)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading image from gallery", e)
                        Toast.makeText(this, "Resim yüklenirken hata oluştu.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        uploadImageLauncher.launch(intent)
    }

    private fun analyzeBitmap(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0] // İlk tespit edilen yüze odaklan
                    val faceBitmap = cropFace(bitmap, face)
                    val scaledBitmap =
                        Bitmap.createScaledBitmap(faceBitmap, inputImageWidth, inputImageHeight, true)

                    val finalFloatBuffer = bitmapToFloatBuffer(scaledBitmap)
                    val sentiment = runSentimentAnalysis(finalFloatBuffer)

                    runOnUiThread {
                        binding.sentimentResultText.text = "Duygu: $sentiment"
                    }
                } else {
                    runOnUiThread {
                        binding.sentimentResultText.text = "Yüz bulunamadı"
                        Toast.makeText(this, "Seçilen resimde yüz bulunamadı.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Bitmap ile yüz tanıma başarısız oldu", e)
                runOnUiThread {
                    binding.sentimentResultText.text = "Analiz hatası"
                }
            }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyzeImage(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnalyzedTimestamp >= 250) { // Hızı artırmak için aralığı düşür (saniyede 4 kare)
            val image = imageProxy.image
            if (image != null) {
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val inputImage = InputImage.fromMediaImage(image, rotationDegrees)

                faceDetector.process(inputImage)
                    .addOnSuccessListener { faces ->
                        if (faces.isNotEmpty()) {
                            val face = faces[0] // İlk tespit edilen yüze odaklan
                            val bitmap = imageProxy.toBitmap()
                            val rotatedBitmap = rotateBitmap(bitmap, rotationDegrees)

                            // Yüzün etrafındaki kırpılmış görüntüyü al
                            val faceBitmap = cropFace(rotatedBitmap, face)
                            val scaledBitmap =
                                Bitmap.createScaledBitmap(faceBitmap, inputImageWidth, inputImageHeight, true)

                            val finalFloatBuffer = bitmapToFloatBuffer(scaledBitmap)
                            val sentiment = runSentimentAnalysis(finalFloatBuffer)

                            runOnUiThread {
                                binding.sentimentResultText.text = "Duygu: $sentiment"
                            }
                        }
                        imageProxy.close()
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Yüz tanıma başarısız oldu", e)
                        imageProxy.close()
                    }
                lastAnalyzedTimestamp = currentTime
            } else {
                imageProxy.close()
            }
        } else {
            imageProxy.close()
        }
    }

    private fun cropFace(bitmap: Bitmap, face: Face): Bitmap {
        val boundingBox = face.boundingBox
        val left = Math.max(0, boundingBox.left)
        val top = Math.max(0, boundingBox.top)
        val width = boundingBox.width()
        val height = boundingBox.height()

        // Bitmap sınırlarını aşmadığından emin ol
        val croppedWidth = if (left + width > bitmap.width) bitmap.width - left else width
        val croppedHeight = if (top + height > bitmap.height) bitmap.height - top else height

        return Bitmap.createBitmap(bitmap, left, top, croppedWidth, croppedHeight)
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun startCamera() {
        binding.imageView.visibility = View.GONE
        binding.cameraPreview.visibility = View.VISIBLE

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }
            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(1280, 960)) // 4:3 oranına daha yakın
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, this::analyzeImage)
                }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun createOrtSession(): OrtSession {
        val modelBytes = assets.open("sentiment_analysis.onnx").readBytes()
        return ortEnvironment.createSession(modelBytes)
    }

    private fun runSentimentAnalysis(floatBuffer: FloatBuffer): String {
        val inputName = session.inputNames.iterator().next()
        val shape = longArrayOf(1, 3, inputImageHeight.toLong(), inputImageWidth.toLong())
        val tensor = OnnxTensor.createTensor(ortEnvironment, floatBuffer, shape)
        val scores = tensor.use {
            session.run(Collections.singletonMap(inputName, it)).use { result ->
                (result[0] as OnnxTensor).floatBuffer.array().copyOf()
            }
        }
        val maxScoreIndex = scores.indices.maxByOrNull { scores[it] } ?: -1
        Log.d(TAG, "Model Scores: ${scores.joinToString()}")
        return when (maxScoreIndex) {
            0 -> "Dil Çıkartma"; 1 -> "Kızgın"; 2 -> "Mutlu"; 3 -> "Nötr"; 4 -> "Üzgün"; 5 -> "Şaşkın"; else -> "Bilinmiyor"
        }
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val imageSize = inputImageWidth * inputImageHeight
        val floatBuffer = FloatBuffer.allocate(imageSize * 3)
        val pixels = IntArray(imageSize)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in 0 until imageSize) {
            val pixel = pixels[i]
            val r = (((pixel shr 16) and 0xFF) / 255.0f - mean[0]) / std[0]
            val g = (((pixel shr 8) and 0xFF) / 255.0f - mean[1]) / std[1]
            val b = (((pixel and 0xFF) / 255.0f) - mean[2]) / std[2]
            floatBuffer.put(i, r)
            floatBuffer.put(i + imageSize, g)
            floatBuffer.put(i + imageSize * 2, b)
        }
        return floatBuffer
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        // Ortam ve oturumu serbest bırak
        try {
            session.close()
            ortEnvironment.close()
        } catch (e: Exception) {
            Log.e(TAG, "ONNX Runtime kaynakları serbest bırakılırken hata oluştu", e)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "İzinler kullanıcı tarafından verilmedi.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
