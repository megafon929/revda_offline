package ru.m929.offline_revda

import android.content.Intent
import android.content.res.Configuration
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

        // Восстановление состояния после пересоздания Activity
        savedInstanceState?.let { state ->
            currentFile = state.getString(KEY_CURRENT)
            state.getStringArrayList(KEY_HISTORY)?.let { saved ->
                // сохранили от вершины стека к дну, поэтому кладём в обратном порядке
                for (i in saved.indices.reversed()) historyStack.push(saved[i])
            }
        }

        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            window.statusBarColor = if (isNight) 0xFF121212.toInt() else Color.WHITE
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isNight
        }

        webView = findViewById(R.id.webView)
        // Фон до загрузки страницы, чтобы не было белой вспышки в тёмной теме
        webView.setBackgroundColor(if (isNight) 0xFF121212.toInt() else 0xFFFAFAFA.toInt())
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isPageLoaded = true
                val file = currentFile
                if (file != null) renderMarkdownFile(file) else openFirstArticle()
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = handleUrl(url)

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) =
                handleUrl(request?.url?.toString())
        }

        setupBackNavigation()
        webView.loadUrl("file:///android_asset/template.html")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CURRENT, currentFile)
        // ArrayDeque итерируется от вершины стека (последний push) к дну
        outState.putStringArrayList(KEY_HISTORY, ArrayList(historyStack))
    }

    override fun onDestroy() {
        webView.stopLoading()
        super.onDestroy()
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
                    val previous = historyStack.pop()
                    currentFile = previous
                    renderMarkdownFile(previous)
                } else {
                    // Не отключаем callback, иначе после сворачивания история перестанет работать
                    finish()
                }
            }
        })
    }

    private fun handleUrl(url: String?): Boolean {
        if (url == null) return false
        return when {
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
            else -> {
                // Ссылки на .md (в том числе с #якорем или ?параметрами)
                val name = Uri.parse(url).lastPathSegment
                if (name != null && name.endsWith(".md")) {
                    openArticle(name)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun openArticle(filename: String) {
        val current = currentFile
        if (current != null && current != filename) historyStack.push(current)
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

    private companion object {
        const val KEY_CURRENT = "current_file"
        const val KEY_HISTORY = "history_stack"
    }
}