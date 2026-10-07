package ru.m929.offline_revda

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val historyStack = ArrayDeque<String>()
    private var currentFile: String? = null
    private var isPageLoaded = false

    @SuppressLint("SetJavaScriptEnabled")
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
        val pageBackground = if (isNight) 0xFF121212.toInt() else 0xFFFAFAFA.toInt()

        val root = findViewById<View>(R.id.root)
        root.setBackgroundColor(pageBackground)
        setupSystemBars(root, isNight)

        webView = findViewById(R.id.webView)
        // Фон до загрузки страницы, чтобы не было белой вспышки в тёмной теме
        webView.setBackgroundColor(pageBackground)
        webView.settings.apply {
            javaScriptEnabled = true // нужен для рендеринга markdown; внешний контент не загружается
            // file:///android_asset/ доступен и без этих разрешений
            allowFileAccess = false
            allowContentAccess = false
        }

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
        webView.loadUrl(TEMPLATE_URL)
    }

    /**
     * Начиная с Android 15 (targetSdk 35+) приложение всегда рисуется от края до края,
     * поэтому отступы под системные панели и вырез экрана задаём сами.
     * На Android 5.x иконки статус-бара нельзя перекрасить, поэтому там оставляем стандартное поведение.
     */
    @Suppress("DEPRECATION")
    private fun setupSystemBars(root: View, isNight: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isNight
        controller.isAppearanceLightNavigationBars = !isNight

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CURRENT, currentFile)
        // ArrayDeque итерируется от вершины стека (последний push) к дну
        outState.putStringArrayList(KEY_HISTORY, ArrayList(historyStack))
    }

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    private fun openFirstArticle() {
        val first = try {
            val json = JSONArray(readAsset("articles/index.json"))
            if (json.length() > 0) json.getJSONObject(0).getString("id") else DEFAULT_ARTICLE
        } catch (e: Exception) {
            // Повреждённый index.json не должен оставлять пустой экран
            DEFAULT_ARTICLE
        }
        openArticle(first)
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
        if (url == null) return true
        val uri = Uri.parse(url)
        return when (uri.scheme) {
            "mailto" -> {
                openExternal(
                    Intent.createChooser(Intent(Intent.ACTION_SENDTO, uri), getString(R.string.send_email)),
                    R.string.no_mail_client
                )
                true
            }
            "http", "https", "tel", "sms", "geo" -> {
                openExternal(Intent(Intent.ACTION_VIEW, uri), R.string.cannot_open_link)
                true
            }
            "file" -> {
                // Ссылки на .md (в том числе с #якорем или ?параметрами)
                val name = uri.lastPathSegment
                when {
                    name != null && name.endsWith(".md") -> {
                        openArticle(name)
                        true
                    }
                    // Якорь внутри уже загруженной страницы — пусть обрабатывает WebView
                    url.startsWith(TEMPLATE_URL) -> false
                    else -> true
                }
            }
            else -> true // неизвестные схемы (intent:, javascript: и т.п.) не открываем
        }
    }

    private fun openExternal(intent: Intent, errorRes: Int) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, errorRes, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openArticle(filename: String) {
        if (filename == currentFile) return
        // Не добавляем в историю и не открываем несуществующий файл
        if (!assetExists("articles/$filename")) {
            Toast.makeText(this, R.string.page_not_found, Toast.LENGTH_SHORT).show()
            if (currentFile == null && filename != DEFAULT_ARTICLE) openArticle(DEFAULT_ARTICLE)
            return
        }
        currentFile?.let { historyStack.push(it) }
        currentFile = filename
        if (isPageLoaded) renderMarkdownFile(filename)
    }

    private fun renderMarkdownFile(filename: String) {
        val md = try {
            readAsset("articles/$filename")
        } catch (e: Exception) {
            getString(R.string.page_not_found)
        }
        // JSONObject.quote даёт корректный JS-литерал строки (экранирует кавычки, переводы строк, U+2028/2029)
        val js = "renderMarkdown(${JSONObject.quote(md)});"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    private fun readAsset(path: String): String =
        assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun assetExists(path: String): Boolean =
        try {
            assets.open(path).close()
            true
        } catch (e: Exception) {
            false
        }

    private companion object {
        const val TEMPLATE_URL = "file:///android_asset/template.html"
        const val DEFAULT_ARTICLE = "welcome.md"
        const val KEY_CURRENT = "current_file"
        const val KEY_HISTORY = "history_stack"
    }
}
