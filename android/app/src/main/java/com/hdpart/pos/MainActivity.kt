package com.hdpart.pos

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Base64
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var backPressedOnce = false

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (filePathCallback == null) return@registerForActivityResult
        val results: Array<Uri>? = if (result.resultCode == RESULT_OK) {
            val dataString = result.data?.dataString
            val clipData = result.data?.clipData
            when {
                clipData != null -> Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                dataString != null -> arrayOf(Uri.parse(dataString))
                else -> null
            }
        } else {
            null
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swipeRefresh = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)

        swipeRefresh.isEnabled = false
        swipeRefresh.setOnRefreshListener {
            webView.reload()
        }

        configureWebView()
        setupBackNavigation()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("file:///android_asset/index.html")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (isExternalAppUrl(url)) {
                    openExternalIntent(url)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                injectNativePrintOverride()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(this@MainActivity, "Gagal membuka pemilih file", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        webView.setDownloadListener(DownloadListener { url, _, _, _, _ ->
            if (url.startsWith("data:image/")) {
                saveBase64Image(url, shareAfterSave = false)
            } else if (url.startsWith("http")) {
                openExternalIntent(url)
            }
        })
    }

    private fun isExternalAppUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("tel:") ||
            lower.startsWith("mailto:") ||
            lower.startsWith("whatsapp:") ||
            lower.startsWith("intent:") ||
            lower.startsWith("https://wa.me/") ||
            lower.startsWith("http://wa.me/") ||
            lower.startsWith("https://api.whatsapp.com/")
    }

    private fun openExternalIntent(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Aplikasi tujuan tidak terpasang di perangkat", Toast.LENGTH_SHORT).show()
        }
    }

    private fun injectNativePrintOverride() {
        val js = """
            (function() {
                if (window.__androidPrintPatched) return;
                window.__androidPrintPatched = true;
                window.print = function() {
                    if (window.AndroidBridge && window.AndroidBridge.printPage) {
                        window.AndroidBridge.printPage();
                    }
                };
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun saveBase64Image(dataUrl: String, shareAfterSave: Boolean) {
        try {
            val base64Part = dataUrl.substringAfter("base64,", "")
            if (base64Part.isEmpty()) return
            val bytes = Base64.decode(base64Part, Base64.DEFAULT)
            val fileName = "SPB_HDPART_${System.currentTimeMillis()}.png"

            if (shareAfterSave) {
                val cacheDir = File(cacheDir, "shared_receipts").apply { mkdirs() }
                val outFile = File(cacheDir, fileName)
                FileOutputStream(outFile).use { it.write(bytes) }
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", outFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Bagikan Struk SPB"))
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/HDPART_POS")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { out -> out.write(bytes) }
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "HDPART_POS")
                if (!dir.exists()) dir.mkdirs()
                FileOutputStream(File(dir, fileName)).use { it.write(bytes) }
            }
            Toast.makeText(this, "Struk disimpan ke Galeri (Pictures/HDPART_POS)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal menyimpan gambar: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    inner class AndroidBridge(private val context: Context) {
        @JavascriptInterface
        fun printPage() {
            runOnUiThread {
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val adapter = webView.createPrintDocumentAdapter("HDPART POS_SPB")
                printManager.print("HDPART POS Document", adapter, PrintAttributes.Builder().build())
            }
        }

        @JavascriptInterface
        fun shareBase64Image(dataUrl: String) {
            runOnUiThread {
                saveBase64Image(dataUrl, shareAfterSave = true)
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else if (backPressedOnce) {
                    finish()
                } else {
                    backPressedOnce = true
                    Toast.makeText(this@MainActivity, "Tekan kembali sekali lagi untuk keluar", Toast.LENGTH_SHORT).show()
                    webView.postDelayed({ backPressedOnce = false }, 2000)
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }
}
