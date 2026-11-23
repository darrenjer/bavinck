package com.bavinck.audiobook

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var speechRate = 1.0f
    private var speechPitch = 1.0f
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var prefs: SharedPreferences
    private val downloadExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // World English Bible (public domain), keyed like "ROM 8:28". Loaded once from assets.
    private val bibleVerses = HashMap<String, String>(32_000)
    @Volatile private var bibleLoaded = false

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        callback.onReceiveValue(uris)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        tts = TextToSpeech(this, this)
        // Load the Bible in the background so the first verse tap is instant
        Thread { ensureBibleLoaded() }.start()

        webView = WebView(this)
        setContentView(webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                fileChooserLauncher.launch(Intent.createChooser(intent, "Select Chapter Pack (.json)"))
                return true
            }
        }
        webView.addJavascriptInterface(AndroidTTSBridge(), "AndroidTTS")
        webView.addJavascriptInterface(BookDownloadBridge(), "AndroidNet")
        webView.addJavascriptInterface(BibleBridge(), "AndroidBible")

        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                applySavedVoice()
                isTtsReady = true
                // Let the page know the voice list can now be loaded
                runOnUiThread {
                    webView.evaluateJavascript("window.onNativeVoicesReady && window.onNativeVoicesReady()", null)
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        runOnUiThread {
                            webView.evaluateJavascript("window.onNativeSpeechStart('$utteranceId')", null)
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        runOnUiThread {
                            webView.evaluateJavascript("window.onNativeSpeechDone('$utteranceId')", null)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        runOnUiThread {
                            webView.evaluateJavascript("window.onNativeSpeechError('$utteranceId')", null)
                        }
                    }
                })
            }
        }
    }

    private fun availableVoices(): List<Voice> =
        (tts?.voices ?: emptySet())
            .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            .sortedWith(compareBy({ it.locale.displayName }, { it.isNetworkConnectionRequired }, { it.name }))

    private fun applySavedVoice() {
        val savedName = prefs.getString(KEY_VOICE, null) ?: return
        availableVoices().firstOrNull { it.name == savedName }?.let { tts?.voice = it }
    }

    inner class AndroidTTSBridge {
        @JavascriptInterface
        fun isAvailable(): Boolean = isTtsReady

        @JavascriptInterface
        fun speak(text: String, utteranceId: String) {
            if (!isTtsReady) return
            tts?.setSpeechRate(speechRate)
            tts?.setPitch(speechPitch)
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }

        /** JSON list of installed voices: [{name, label}], English voices first. */
        @JavascriptInterface
        fun getVoices(): String {
            val voices = availableVoices()
            val english = voices.filter { it.locale.language == "en" }
            val list = english.ifEmpty { voices }
            val countPerLocale = mutableMapOf<String, Int>()
            val array = JSONArray()
            for (voice in list) {
                // Short region tag such as "US", "UK", "AU" (falls back to the language name)
                val region = when (val country = voice.locale.country) {
                    "GB" -> "UK"
                    "" -> voice.locale.displayLanguage
                    else -> country
                }
                val n = (countPerLocale[region] ?: 0) + 1
                countPerLocale[region] = n
                val online = if (voice.isNetworkConnectionRequired) " (online)" else ""
                array.put(
                    JSONObject()
                        .put("name", voice.name)
                        .put("label", "Voice $n · $region$online")
                )
            }
            return array.toString()
        }

        @JavascriptInterface
        fun getSelectedVoice(): String = prefs.getString(KEY_VOICE, "") ?: ""

        /** Pass an empty name to go back to the phone's default voice. */
        @JavascriptInterface
        fun setVoice(name: String) {
            if (name.isEmpty()) {
                prefs.edit { remove(KEY_VOICE) }
                tts?.voice = tts?.defaultVoice
                tts?.setLanguage(Locale.US)
                return
            }
            val voice = availableVoices().firstOrNull { it.name == name } ?: return
            tts?.voice = voice
            prefs.edit { putString(KEY_VOICE, name) }
        }

        @JavascriptInterface
        fun stop() {
            tts?.stop()
        }

        @JavascriptInterface
        fun setRate(rate: Float) {
            speechRate = rate
            tts?.setSpeechRate(rate)
        }

        @JavascriptInterface
        fun setPitch(pitch: Float) {
            speechPitch = pitch
            tts?.setPitch(pitch)
        }
    }

    /**
     * Lets the page download chapter pages from the book's website. Done natively because the
     * page is loaded from file:// and the browser would block a direct cross-site request.
     * Only addresses under [BOOK_BASE_URL] are allowed.
     */
    inner class BookDownloadBridge {
        @JavascriptInterface
        fun fetchPage(url: String, requestId: Int) {
            if (!url.startsWith(BOOK_BASE_URL) || ".." in url) {
                deliverPage(requestId, false, "This address is not allowed: $url")
                return
            }
            downloadExecutor.execute {
                try {
                    deliverPage(requestId, true, downloadText(url))
                } catch (e: Exception) {
                    deliverPage(requestId, false, e.message ?: "Network error")
                }
            }
        }
    }

    /** Reads assets/web_bible.txt (lines like "ROM 8:28 And we know...") into memory, once. */
    @Synchronized
    private fun ensureBibleLoaded() {
        if (bibleLoaded) return
        assets.open(BIBLE_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val firstSpace = line.indexOf(' ')
                val secondSpace = if (firstSpace >= 0) line.indexOf(' ', firstSpace + 1) else -1
                if (secondSpace > 0) {
                    bibleVerses[line.substring(0, secondSpace)] = line.substring(secondSpace + 1)
                }
            }
        }
        bibleLoaded = true
    }

    /** Lets the page look up verse text from the built-in World English Bible. */
    inner class BibleBridge {
        /**
         * Returns a JSON array like [{"v":28,"t":"..."},{"v":29,"t":"..."}] for verses
         * [verseFrom]..[verseTo] of one chapter. Book codes are the ones used in web_bible.txt
         * (e.g. GEN, PSA, ROM, 1CO). Missing verses are skipped; at most 60 verses are returned.
         */
        @JavascriptInterface
        fun lookup(book: String, chapter: Int, verseFrom: Int, verseTo: Int): String {
            ensureBibleLoaded()
            val result = JSONArray()
            val last = minOf(maxOf(verseFrom, verseTo), verseFrom + 59)
            for (verse in verseFrom..last) {
                val text = bibleVerses["$book $chapter:$verse"] ?: continue
                result.put(JSONObject().put("v", verse).put("t", text))
            }
            return result.toString()
        }
    }

    private fun downloadText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) BookReader")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("The website replied with error $code")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun deliverPage(requestId: Int, ok: Boolean, body: String) {
        val js = "window.onNativeFetchDone && window.onNativeFetchDone($requestId, $ok, ${JSONObject.quote(body)})"
        runOnUiThread {
            if (!isDestroyed) webView.evaluateJavascript(js, null)
        }
    }

    companion object {
        private const val PREFS_NAME = "reader_settings"
        private const val KEY_VOICE = "tts_voice"
        private const val BOOK_BASE_URL = "https://www.uecf.net/books/wonderful-works-of-god-bavinck/"
        private const val BIBLE_ASSET = "web_bible.txt"
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        downloadExecutor.shutdownNow()
        super.onDestroy()
    }
}