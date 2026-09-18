package ru.m929.offline_revda

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import org.json.JSONArray
import java.util.ArrayDeque

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val historyStack = ArrayDeque<String>()
    private var currentFile: String? = null
    private var isPageLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.statusBarColor = Color.WHITE
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        }

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isPageLoaded = true
                currentFile?.let { renderMarkdownFile(it) } ?: openFirstArticle()
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = handleUrl(url)

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) =
                handleUrl(request?.url?.toString())
        }

        setupBackNavigation()
        webView.loadUrl("file:///android_asset/template.html")
    }

    private fun openFirstArticle() {
        try {
            val json = JSONArray(assets.open("articles/index.json").bufferedReader().use { it.readText() })
            if (json.length() > 0) openArticle(json.getJSONObject(0).getString("id"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (historyStack.isNotEmpty()) {
                    currentFile = historyStack.pop()
                    renderMarkdownFile(currentFile!!)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun handleUrl(url: String?): Boolean {
        if (url == null) return false
        return when {
            url.endsWith(".md") -> {
                openArticle(url.substringAfterLast("/"))
                true
            }
            url.startsWith("mailto:") -> {
                try {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SENDTO, Uri.parse(url)), "Отправить письмо..."))
                } catch (e: Exception) {
                    Toast.makeText(this, "Почтовый клиент не найден", Toast.LENGTH_SHORT).show()
                }
                true
            }
            url.startsWith("http://") || url.startsWith("https://") || url.startsWith("tel:") -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> false
        }
    }

    private fun openArticle(filename: String) {
        if (currentFile != null && currentFile != filename) historyStack.push(currentFile)
        currentFile = filename
        if (isPageLoaded) renderMarkdownFile(filename)
    }

    private fun renderMarkdownFile(filename: String) {
        try {
            val md = assets.open("articles/$filename").bufferedReader().use { it.readText() }
            val b64 = Base64.encodeToString(md.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            webView.post { webView.evaluateJavascript("renderMarkdownBase64('$b64');", null) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}