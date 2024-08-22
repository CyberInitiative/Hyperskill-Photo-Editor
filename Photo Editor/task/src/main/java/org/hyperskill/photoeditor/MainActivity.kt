package org.hyperskill.photoeditor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.pow

private const val PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE = 0

class MainActivity : AppCompatActivity() {

    private lateinit var currentImage: ImageView

    private lateinit var btnGallery: Button
    private lateinit var btnSave: Button

    private lateinit var slBrightness: Slider
    private lateinit var slContrast: Slider
    private lateinit var slSaturation: Slider
    private lateinit var slGamma: Slider

    private lateinit var defaultBitmap: Bitmap

    private var brightnessValue = 0
    private var contrastValue = 0
    private var saturationValue = 0
    private var gammaValue = 1.0

    private var avgBrightness: Int = 0

    private var lastJob: Job? = null

    private val activityResultLauncher =
        registerForActivityResult(StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val photoUri = result.data?.data ?: return@registerForActivityResult

                currentImage.setImageURI(photoUri)
                val currentImageBitmap = (currentImage.getDrawable() as BitmapDrawable).bitmap
                defaultBitmap = currentImageBitmap
                val mutableBitmap = currentImageBitmap.copy(currentImageBitmap.config, true)
                currentImage.setImageBitmap(mutableBitmap)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()

        val createdBitmap = createBitmap()
        currentImage.setImageBitmap(createdBitmap)
        defaultBitmap = createdBitmap.copy(createdBitmap.config, true)

        btnGallery.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            activityResultLauncher.launch(intent)
        }

        btnSave.setOnClickListener {
            permissionManagement()
        }

        slBrightness.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            brightnessValue = value.toInt()
            lastJob?.cancel()
            lastJob = applyFilters()
        })

        slContrast.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            contrastValue = value.toInt()
            lastJob?.cancel()
            lastJob = applyFilters()
        })

        slSaturation.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            saturationValue = value.toInt()
            lastJob?.cancel()
            lastJob = applyFilters()
        })

        slGamma.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            gammaValue = value.toDouble()
            lastJob?.cancel()
            lastJob = applyFilters()
        })
    }

    private fun copyBitmap(bitmap: Bitmap): Bitmap {
        return bitmap.copy(bitmap.config, true)
    }

    private fun applyFilters(): Job {
        return CoroutineScope(Dispatchers.Default).launch {

            val brightenCopyDeferred: Deferred<Bitmap> = this.async {
                setBitmapBrightness(brightnessValue)
            }
            val brightenCopy: Bitmap = brightenCopyDeferred.await()
            avgBrightness = calculateAverageBrightness(brightenCopy)

            val contrastedCopyDeferred: Deferred<Bitmap> = this.async {
                setBitmapContrast(contrastValue, brightenCopy)
            }
            val contrastedCopy: Bitmap = contrastedCopyDeferred.await()

            val saturatedCopyDeferred: Deferred<Bitmap> = this.async {
                setBitmapSaturation(saturationValue, contrastedCopy)
            }
            val saturatedCopy: Bitmap = saturatedCopyDeferred.await()

            val gammaCopyDeferred: Deferred<Bitmap> = this.async {
                setBitmapGamma(gammaValue, saturatedCopy)
            }
            val gammaCopy: Bitmap = gammaCopyDeferred.await()
            ensureActive()

            runOnUiThread {
                currentImage.setImageBitmap(gammaCopy)
            }
        }
    }

    private fun setColorBrightness(pixel: Int, value: Int): Int {
        val red = Color.red(pixel) + value
        val green = Color.green(pixel) + value
        val blue = Color.blue(pixel) + value

        val newRed = red.coerceIn(0, 255)
        val newGreen = green.coerceIn(0, 255)
        val newBlue = blue.coerceIn(0, 255)

        val newColor = Color.rgb(newRed, newGreen, newBlue)
        return newColor
    }

    private fun setBitmapBrightness(value: Int): Bitmap {
        val brightenedBitmap = copyBitmap(defaultBitmap)
        for (x in 0 until defaultBitmap.width) {
            for (y in 0 until defaultBitmap.height) {
                val pixel = defaultBitmap.getPixel(x, y)
                val newColor = setColorBrightness(pixel, value)
                brightenedBitmap.setPixel(x, y, newColor)
            }
        }
        return brightenedBitmap
    }

    private fun calculateAverageBrightness(bitmap: Bitmap): Int {
        val brightnessList = mutableListOf<Int>()
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                val pixel = bitmap.getPixel(x, y)

                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)

                val brightness = (red + green + blue) / 3
                brightnessList.add(brightness)
            }
        }

        return brightnessList.average().toInt()
    }

    private fun setColorContrast(pixel: Int, contrastValue: Int): Int {
        val alpha: Double = (255.0 + contrastValue) / (255.0 - contrastValue)

        val red = Color.red(pixel)
        val green = Color.green(pixel)
        val blue = Color.blue(pixel)

        val newRed = (alpha * (red - avgBrightness) + avgBrightness).toInt().coerceIn(0, 255)
        val newGreen = (alpha * (green - avgBrightness) + avgBrightness).toInt().coerceIn(0, 255)
        val newBlue = (alpha * (blue - avgBrightness) + avgBrightness).toInt().coerceIn(0, 255)

        return Color.rgb(newRed, newGreen, newBlue)
    }

    private fun setBitmapContrast(contrastValue: Int, copyBitmap: Bitmap): Bitmap {
        val contrastedBitmap = copyBitmap(copyBitmap)
        for (x in 0 until defaultBitmap.width) {
            for (y in 0 until defaultBitmap.height) {
                val pixel = copyBitmap.getPixel(x, y)
                val newColor = setColorContrast(pixel, contrastValue)
                contrastedBitmap.setPixel(x, y, newColor)
            }
        }
        return contrastedBitmap
    }

    private fun calculateAverageRGBValueForPixel(pixel: Int): Int {
        val red = Color.red(pixel)
        val green = Color.green(pixel)
        val blue = Color.blue(pixel)

        val rgbAvg = (red + green + blue) / 3

        return rgbAvg
    }

    private fun setColorSaturation(pixel: Int, saturationValue: Int): Int {
        val alpha: Double = (255.0 + saturationValue) / (255.0 - saturationValue)

        val red = Color.red(pixel)
        val green = Color.green(pixel)
        val blue = Color.blue(pixel)

        val avgRGB = calculateAverageRGBValueForPixel(pixel)

        val newRed = (alpha * (red - avgRGB) + avgRGB).toInt().coerceIn(0, 255)
        val newGreen = (alpha * (green - avgRGB) + avgRGB).toInt().coerceIn(0, 255)
        val newBlue = (alpha * (blue - avgRGB) + avgRGB).toInt().coerceIn(0, 255)

        return Color.rgb(newRed, newGreen, newBlue)
    }

    private fun setBitmapSaturation(saturationValue: Int, copyBitmap: Bitmap): Bitmap {
        val saturatedBitmap = copyBitmap(copyBitmap)
        for (x in 0 until defaultBitmap.width) {
            for (y in 0 until defaultBitmap.height) {
                val pixel = copyBitmap.getPixel(x, y)
                val newColor = setColorSaturation(pixel, saturationValue)
                saturatedBitmap.setPixel(x, y, newColor)
            }
        }
        return saturatedBitmap
    }

    private fun setColorGamma(pixel: Int, gammaValue: Double): Int {
        val red = Color.red(pixel) / 255.0
        val green = Color.green(pixel).toDouble() / 255.0
        val blue = Color.blue(pixel).toDouble() / 255.0

        fun calculateColorValue(color: Double): Double {
            val poweredValue = color.pow(gammaValue)

            return (255.0 * poweredValue)
        }

        val newRed = calculateColorValue(red)
        val newGreen = calculateColorValue(green)
        val newBlue = calculateColorValue(blue)

        return Color.rgb(
            newRed.toInt().coerceIn(0, 255),
            newGreen.toInt().coerceIn(0, 255),
            newBlue.toInt().coerceIn(0, 255)
        )
    }

    private fun setBitmapGamma(gammaValue: Double, copyBitmap: Bitmap): Bitmap {
        val gammaBitmap = copyBitmap(copyBitmap)
        for (x in 0 until defaultBitmap.width) {
            for (y in 0 until defaultBitmap.height) {
                val pixel = copyBitmap.getPixel(x, y)
                val newColor = setColorGamma(pixel, gammaValue)
                gammaBitmap.setPixel(x, y, newColor)
            }
        }
        return gammaBitmap
    }

    private fun permissionManagement() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED -> {
                saveImage()
            }

            ActivityCompat
                .shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) -> {
                AlertDialog.Builder(this)
                    .setTitle("Permission required")
                    .setMessage("This app needs permission to access this feature.")
                    .setPositiveButton("Grant") { _, _ ->
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                            PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE,
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            else -> {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE,
                )
            }

        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE -> {
                if ((grantResults.isNotEmpty() &&
                            grantResults[0] == PackageManager.PERMISSION_GRANTED)
                ) {
                    saveImage()
                } else {
                    // Explain to the user that the feature is unavailable because
                    // the feature requires a permission that the user has denied.
                    // At the same time, respect the user's decision. Don't link to
                    // system settings in an effort to convince the user to change
                    // their decision.
                }
                return
            }
        }
    }

    private fun saveImage() {
        val currentBitmap = (currentImage.getDrawable() as BitmapDrawable).bitmap

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.ImageColumns.WIDTH, currentBitmap.width)
            put(MediaStore.Images.ImageColumns.HEIGHT, currentBitmap.height)
        }

        val uri = this@MainActivity.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return

        contentResolver.openOutputStream(uri).use {
            currentBitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
        }
    }

    private fun bindViews() {
        currentImage = findViewById(R.id.ivPhoto)

        btnGallery = findViewById(R.id.btnGallery)
        btnSave = findViewById(R.id.btnSave)

        slBrightness = findViewById(R.id.slBrightness)
        slContrast = findViewById(R.id.slContrast)
        slSaturation = findViewById(R.id.slSaturation)
        slGamma = findViewById(R.id.slGamma)
    }

    // do not change this function
    fun createBitmap(): Bitmap {
        val width = 200
        val height = 100
        val pixels = IntArray(width * height)
        // get pixel array from source

        var R: Int
        var G: Int
        var B: Int
        var index: Int

        for (y in 0 until height) {
            for (x in 0 until width) {
                // get current index in 2D-matrix
                index = y * width + x
                // get color
                R = x % 100 + 40
                G = y % 100 + 80
                B = (x + y) % 100 + 120

                pixels[index] = Color.rgb(R, G, B)

            }
        }
        // output bitmap
        val bitmapOut = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        bitmapOut.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmapOut
    }
}