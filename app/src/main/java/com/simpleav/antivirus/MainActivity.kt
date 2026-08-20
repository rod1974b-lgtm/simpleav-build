package com.simpleav.antivirus

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.*
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.content.ContentValues
import android.provider.MediaStore

class MainActivity : AppCompatActivity() {
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var webView: WebView
    private var pendingDownloadName: String? = null

    // Safe folder name for Downloads — alphanumeric only, computed at build time
    private val APP_FOLDER = "simpleav"

    // Whether this app needs MANAGE_EXTERNAL_STORAGE (All Files Access)
    private val needsManageStorage: Boolean = true

    // All runtime permissions this app needs
    private val requiredPermissions: List<String> = listOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // Log results; WebView will re-prompt via onPermissionRequest if needed
        grants.forEach { (perm, granted) ->
            android.util.Log.d("KATZ", "$perm: ${if (granted) "GRANTED" else "DENIED"}")
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val results: Array<Uri>? = when {
                data?.clipData != null -> Array(data.clipData!!.itemCount) { i ->
                    data.clipData!!.getItemAt(i).uri
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
            fileUploadCallback?.onReceiveValue(results)
        } else {
            fileUploadCallback?.onReceiveValue(null)
        }
        fileUploadCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Fullscreen + hide notch/status bar/nav bar ──
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )

        webView = WebView(this)
        setContentView(webView)

        // Request all needed runtime permissions up front
        requestMissingPermissions()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            databaseEnabled = true
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Handle WebRTC / getUserMedia permission requests from the web page
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                callback.invoke(origin, true, false)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Cancel any pending callback first
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val acceptTypes = fileChooserParams?.acceptTypes
                    ?.filter { it.isNotEmpty() } ?: emptyList()
                val mimeType = if (acceptTypes.size == 1) acceptTypes[0] else "*/*"
                val allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                val isCapture = fileChooserParams?.isCaptureEnabled == true

                // Primary intent: ACTION_GET_CONTENT (works everywhere)
                val getContent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = mimeType
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                }
                // Secondary intent: ACTION_OPEN_DOCUMENT (better for persistent URIs on API 19+)
                val openDoc = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = mimeType
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                }

                // Build a combined chooser showing both options
                val intentList = mutableListOf<Intent>()
                // Add camera capture for image/* inputs
                if (isCapture || mimeType.startsWith("image/") || mimeType == "*/*") {
                    try {
                        val camIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                        if (camIntent.resolveActivity(packageManager) != null) {
                            intentList.add(camIntent)
                        }
                    } catch (_: Exception) {}
                }
                intentList.add(openDoc)

                val chooser = Intent.createChooser(getContent, "Choose File").apply {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, intentList.toTypedArray())
                }
                return try {
                    fileChooserLauncher.launch(chooser)
                    true
                } catch (e: Exception) {
                    // Last resort fallback: plain ACTION_GET_CONTENT
                    try {
                        fileChooserLauncher.launch(getContent)
                        true
                    } catch (e2: Exception) {
                        fileUploadCallback?.onReceiveValue(null)
                        fileUploadCallback = null
                        false
                    }
                }
            }
        }

        // ── APK / file download listener ──
        // Intercepts downloads triggered inside the WebView.
        // blob: and data: URLs are handled by injecting a fixed JS snippet
        // that calls back into the KatzSave JS interface with base64 bytes.
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                // Store pending filename so the JS interface can retrieve it
                pendingDownloadName = fileName
                // Inject a fixed JS snippet — no Kotlin string interpolation inside
                val js = "window.__katzHandleDownload()"
                webView.post { webView.evaluateJavascript(js, null) }
            } else {
                saveByteStream(url, fileName, APP_FOLDER)
            }
        }

        // ── Inject the blob-handler helper script into the page ──
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val helper = """
                    window.__katzLastBlobUrl = null;
                    var __origCreateObjectURL = URL.createObjectURL;
                    URL.createObjectURL = function(blob) {
                        var u = __origCreateObjectURL(blob);
                        window.__katzLastBlobUrl = u;
                        return u;
                    };
                    window.__katzHandleDownload = function() {
                        var u = window.__katzLastBlobUrl;
                        if (!u) return;
                        fetch(u).then(function(r){ return r.blob(); }).then(function(blob){
                            var fr = new FileReader();
                            fr.onloadend = function() {
                                var b64 = fr.result.split(',')[1];
                                KatzSave.saveBytesBase64(b64, KatzSave.getPendingName());
                            };
                            fr.readAsDataURL(blob);
                        });
                    };
                """.trimIndent()
                view.evaluateJavascript(helper, null)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
        }

        // ── JavaScript bridge so web pages can call window.KatzSave methods ──
        webView.addJavascriptInterface(KatzFileSaver(), "KatzSave")

        webView.loadUrl("file:///android_asset/www/index.html")
    }

    @android.annotation.SuppressLint("NewApi")
    private fun saveFileByUrl(url: String, fileName: String) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            pendingDownloadName = fileName
            webView.post { webView.evaluateJavascript("window.__katzHandleDownload()", null) }
            return
        }
        saveByteStream(url, fileName, APP_FOLDER)
    }
    @android.annotation.SuppressLint("NewApi")
    private fun saveBytesBase64ToFolder(base64Data: String, fileName: String) {
        Thread {
            try {
                val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                writeToDownloads(bytes.inputStream(), bytes.size.toLong(), fileName, APP_FOLDER)
            } catch (e: Exception) {
                runOnUiThread { showToast("Save error: ${e.message}") }
            }
        }.start()
    }

    @android.annotation.SuppressLint("NewApi")
    private fun saveByteStream(url: String, fileName: String, appFolder: String) {
        Thread {
            try {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 60000
                connection.connect()
                val size = connection.contentLength.toLong()
                connection.inputStream.use { stream ->
                    writeToDownloads(stream, size, fileName, appFolder)
                }
            } catch (e: Exception) {
                runOnUiThread { showToast("Download failed: ${e.message}") }
            }
        }.start()
    }

    @android.annotation.SuppressLint("NewApi")
    private fun writeToDownloads(stream: java.io.InputStream, size: Long, fileName: String, appFolder: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/" + appFolder)
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val collection = android.provider.MediaStore.Downloads.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, values) ?: run {
                    runOnUiThread { showToast("Could not create file in Downloads/${appFolder}") }
                    return
                }
                resolver.openOutputStream(itemUri)?.use { out -> stream.copyTo(out) }
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
                runOnUiThread { showToast("✓ Saved to Downloads/${appFolder}/${fileName}") }
            } else {
                val dir = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), appFolder)
                if (!dir.exists()) dir.mkdirs()
                val destFile = java.io.File(dir, fileName)
                java.io.FileOutputStream(destFile).use { out -> stream.copyTo(out) }
                sendBroadcast(android.content.Intent(
                    android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                    Uri.fromFile(destFile)))
                runOnUiThread { showToast("✓ Saved to Downloads/${appFolder}/${fileName}") }
            }
        } catch (e: Exception) {
            runOnUiThread { showToast("Write failed: ${e.message}") }
        }
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    // JavaScript interface callable as:
    //   window.KatzSave.saveFile(url, filename)    — http/https URLs
    //   window.KatzSave.saveApk(url, filename)     — saves as .apk
    //   window.KatzSave.saveBytesBase64(b64, name) — raw base64 bytes (for blob: URLs)
    inner class KatzFileSaver {
        @android.webkit.JavascriptInterface
        fun saveFile(url: String, fileName: String) {
            saveFileByUrl(url, fileName)
        }
        @android.webkit.JavascriptInterface
        fun saveApk(url: String, fileName: String) {
            val name = if (fileName.endsWith(".apk")) fileName else "${fileName}.apk"
            saveFileByUrl(url, name)
        }
        @android.webkit.JavascriptInterface
        fun saveBytesBase64(base64Data: String, fileName: String) {
            saveBytesBase64ToFolder(base64Data, fileName)
        }
        @android.webkit.JavascriptInterface
        fun getDownloadFolder(): String = "Downloads/${APP_FOLDER}"

        @android.webkit.JavascriptInterface
        fun getPendingName(): String = pendingDownloadName ?: "download.apk"
    }

    @android.annotation.SuppressLint("NewApi")
    private fun requestMissingPermissions() {
        // MANAGE_EXTERNAL_STORAGE (All Files Access) — Android 11+ only; cannot use requestPermissions()
        // Must direct the user to the Settings page for this app
        if (needsManageStorage && Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${packageName}")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback: open the general Manage All Files page if per-app URI fails
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (e2: Exception) {
                        android.util.Log.w("KATZ", "Could not open All Files Access settings: ${e2.message}")
                    }
                }
                return // Wait for user to return from Settings; app will re-check on resume
            }
        }

        if (requiredPermissions.isEmpty()) return
        val missing = requiredPermissions.filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }
        // API 33+: legacy READ/WRITE_EXTERNAL_STORAGE are silently denied — strip them
        // API <33: READ_MEDIA_* don't exist — strip them to avoid crashes
        val toRequest = if (Build.VERSION.SDK_INT >= 33) {
            missing.filter {
                it != android.Manifest.permission.READ_EXTERNAL_STORAGE &&
                it != android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
        } else {
            missing.filter { !it.startsWith("android.permission.READ_MEDIA_") }
        }
        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    @android.annotation.SuppressLint("NewApi")
    override fun onResume() {
        super.onResume()
        // Re-apply immersive fullscreen — Android can clear flags after dialogs/permission prompts
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
        // Re-check MANAGE_EXTERNAL_STORAGE when user returns from Settings
        if (needsManageStorage && Build.VERSION.SDK_INT >= 30) {
            val granted = Environment.isExternalStorageManager()
            android.util.Log.d("KATZ", "MANAGE_EXTERNAL_STORAGE on resume: ${if (granted) "GRANTED" else "NOT GRANTED"}")
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}