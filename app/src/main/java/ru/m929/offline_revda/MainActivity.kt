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
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.drawerlayout.widget.DrawerLayout
import org.json.JSONArray
import java.util.ArrayDeque

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var listView: ListView

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
        drawerLayout = findViewById(R.id.drawerLayout)
        listView = findViewById(R.id.listView)

        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE

        setupNavigation()
        setupBackNavigation()
        webView.loadUrl("file:///android_asset/template.html")
    }

    private fun setupNavigation() {
        try {
            val jsonString = assets.open("articles/index.json").bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(jsonString)

            val titles = ArrayList<String>()
            val files = ArrayList<String>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                titles.add(obj.getString("title"))
                files.add(obj.getString("id"))
            }

            listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, titles)
            listView.setOnItemClickListener { _, _, position, _ ->
                openArticle(files[position])
                drawerLayout.closeDrawers()
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isPageLoaded = true
                    if (files.isNotEmpty() && currentFile == null) {
                        openArticle(files[0])
                    } else if (currentFile != null) {
                        renderMarkdownFile(currentFile!!)
                    }
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = handleUrl(url)

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                    handleUrl(request?.url?.toString())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    drawerLayout.isDrawerOpen(GravityCompat.START) -> drawerLayout.closeDrawer(GravityCompat.START)
                    historyStack.isNotEmpty() -> {
                        val previousFile = historyStack.pop()
                        currentFile = previousFile
                        renderMarkdownFile(previousFile)
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
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
                val mailIntent = Intent(Intent.ACTION_SENDTO, Uri.parse(url))
                try {
                    startActivity(Intent.createChooser(mailIntent, "Отправить письмо..."))
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
        if (currentFile != null && currentFile != filename) {
            historyStack.push(currentFile)
        }
        currentFile = filename
        if (isPageLoaded) renderMarkdownFile(filename)
    }

    private fun renderMarkdownFile(filename: String) {
        try {
            val mdContent = assets.open("articles/$filename").bufferedReader().use { it.readText() }
            val base64Md = Base64.encodeToString(mdContent.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            webView.post {
                webView.evaluateJavascript("renderMarkdownBase64('$base64Md');", null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
