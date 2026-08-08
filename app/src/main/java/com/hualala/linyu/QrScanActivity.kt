package com.hualala.linyu

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * 扫码绑定热水器。
 *
 * 趣智校园热水器上的二维码形如：KLCXKJ-Water,M,C47F0EDA85D8
 * 解析最后一个字段（冒号去掉后为 12 位十六进制）作为设备 snCode。
 *
 * 返回：RESULT_OK + extra "sn_code"
 */
class QrScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SN_CODE = "sn_code"
        private const val DEVICE_PREFIX = "KLCXKJ-Water"

        fun parseSnCode(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val last = raw.split(",").lastOrNull()?.trim() ?: return null
            val normalized = last.replace(":", "").uppercase()
            // 12 位十六进制
            if (normalized.length == 12 && normalized.matches(Regex("[0-9A-F]{12}"))) {
                return normalized
            }
            return null
        }
    }

    private lateinit var previewView: PreviewView
    private var scanning = false
    private var camera: androidx.camera.core.Camera? = null
    private var torchOn = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showError("需要相机权限才能扫码")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK

        // 简单布局：标题 + PreviewView + 取消按钮 + 手电筒
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val title = TextView(this).apply {
            text = "扫描设备二维码"
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        val cancel = TextView(this).apply {
            text = "取消"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 16, 0, 16)
            gravity = android.view.Gravity.CENTER
            setOnClickListener { finish() }
        }
        // 手电筒按钮（右上角）
        val torchBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_flashlight)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(0, 0, 0, 0)
            setOnClickListener { toggleTorch() }
        }

        root.addView(previewView)
        root.addView(title, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            topMargin = 80
        })
        root.addView(cancel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.BOTTOM
            bottomMargin = 60
        })
        val btnSize = (48 * resources.displayMetrics.density).toInt()
        root.addView(torchBtn, FrameLayout.LayoutParams(btnSize, btnSize).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            topMargin = 24
            rightMargin = 16
        })
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) {
            showError("设备不支持闪光灯")
            return
        }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                if (scanning) { imageProxy.close(); return@setAnalyzer }
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    val scanner = BarcodeScanning.getClient()
                    scanner.process(inputImage)
                        .addOnSuccessListener { barcodes ->
                            for (barcode in barcodes) {
                                val raw = barcode.rawValue
                                if (raw?.contains(DEVICE_PREFIX, ignoreCase = true) == true) {
                                    val sn = parseSnCode(raw)
                                    if (sn != null && !scanning) {
                                        scanning = true
                                        scanner.close()
                                        setResult(RESULT_OK, Intent().putExtra(EXTRA_SN_CODE, sn))
                                        finish()
                                        return@addOnSuccessListener
                                    }
                                }
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                } else {
                    imageProxy.close()
                }
            }
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        // 退出时关灯
        try { camera?.cameraControl?.enableTorch(false) } catch (_: Exception) {}
    }

    private fun showError(msg: String) {
        runOnUiThread {
            setResult(RESULT_CANCELED, Intent().putExtra("error", msg))
            finish()
        }
    }
}
