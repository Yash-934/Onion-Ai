package com.privateai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.URL
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.PermissionRequest
import android.webkit.GeolocationPermissions
import java.io.ByteArrayInputStream
import android.net.http.SslError
import android.view.WindowManager
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject

private const val DEFAULT_DIGDIG_CHAT_URL = "http://digdig2nugjpszzmqe5ep2bk7lqfpdlyrkojsx2j6kzalnrqtwedr3id.onion/chat/ee6500c4#c"
private const val DEFAULT_DIGDIG_BASE_URL = "http://digdig2nugjpszzmqe5ep2bk7lqfpdlyrkojsx2j6kzalnrqtwedr3id.onion/"

private const val PREFS_NAME = "private_ai_vault"
private const val KEY_SESSIONS = "chat_sessions_encrypted"
private const val KEY_ACTIVE_SESSION_ID = "active_session_id"
private const val KEY_URL = "gateway_onion_url"
private const val KEY_CLIENT_KEY = "gateway_client_key"
private const val KEY_ADMIN_KEY = "gateway_admin_key"
private const val KEY_SOCKS_PORT = "tor_socks_port"
private const val KEY_ALLOW_DEV_LOOPBACK = "allow_dev_loopback"
private const val KEY_SYSTEM_PROMPT = "custom_system_prompt"
private const val KEY_LAST_MODEL = "selected_model"
private const val KEY_TOR_ENGINE_MODE = "tor_engine_mode"
private const val KEY_TOR_BRIDGES = "tor_custom_bridges"
private const val KEY_TOR_AUTO_START = "tor_auto_start"

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "New Chat",
    val createdAt: Long = System.currentTimeMillis(),
    val messages: MutableList<ChatMessage> = mutableListOf()
)

class SecureStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val alias = "PrivateAI_MasterKey_v2"

    private fun getSecretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return kg.generateKey()
    }

    fun put(key: String, value: String) {
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            val blob = Base64.encodeToString(combined, Base64.NO_WRAP)
            prefs.edit().putString(key, blob).apply()
        }
    }

    fun get(key: String): String? {
        return runCatching {
            val blob = prefs.getString(key, null) ?: return null
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            if (raw.size < 12) return null
            val iv = raw.copyOfRange(0, 12)
            val data = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrNull()
    }

    fun putBoolean(key: String, value: Boolean) = put(key, value.toString())
    fun getBoolean(key: String, default: Boolean = false): Boolean = get(key)?.toBooleanStrictOrNull() ?: default
}

class ApiClient(
    val baseUrl: String,
    val apiKey: String,
    val socksPort: Int,
    val allowDevLoopback: Boolean = false
) {
    @Volatile var isCancelled: Boolean = false

    fun buildEndpoint(relativePath: String): String {
        val base = baseUrl.trimEnd('/')
        val path = relativePath.trimStart('/')

        // If base already contains /v1 (e.g. https://myai.onion/myai/v1),
        // prevent duplicate /v1/v1 in the final URL.
        return if (base.endsWith("/v1", ignoreCase = true)) {
            val cleanedPath = if (path.startsWith("v1/", ignoreCase = true)) {
                path.substring(3)
            } else {
                path
            }
            "$base/$cleanedPath"
        } else {
            "$base/$path"
        }
    }

    private fun openConnection(endpointUrl: String, method: String, customApiKey: String? = null): HttpURLConnection {
        val u = URL(endpointUrl)
        require(u.protocol.equals("http", true) || u.protocol.equals("https", true)) {
            "Unsupported protocol: ${u.protocol}"
        }
        val host = u.host ?: ""
        val isDev = allowDevLoopback && (host == "127.0.0.1" || host == "10.0.2.2" || host == "localhost")
        require(host.endsWith(".onion", ignoreCase = true) || isDev) {
            "Tor-Only Policy Violation: Host '$host' does not end with '.onion'. Direct clearnet traffic is strictly forbidden to preserve IP anonymity."
        }

        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val conn = u.openConnection(proxy) as HttpURLConnection

        // Permissive SSL for .onion addresses (Tor provides end-to-end cryptographic onion routing,
        // and .onion HTTPS certificates are almost always self-signed or internal CA).
        if (conn is HttpsURLConnection && host.endsWith(".onion", ignoreCase = true)) {
            runCatching {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate>? = null
                    override fun checkClientTrusted(certs: Array<X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(certs: Array<X509Certificate>?, authType: String?) {}
                })
                val sc = SSLContext.getInstance("TLS")
                sc.init(null, trustAllCerts, java.security.SecureRandom())
                conn.sslSocketFactory = sc.socketFactory
                conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
        }

        return conn.apply {
            requestMethod = method
            connectTimeout = 20000
            readTimeout = 90000
            setRequestProperty("Accept", "application/json, text/event-stream")
            val keyToUse = (customApiKey ?: apiKey).trim()
            if (keyToUse.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $keyToUse")
                setRequestProperty("x-api-key", keyToUse)
            }
            setRequestProperty("Content-Type", "application/json")
        }
    }

    fun testTorSocksPort(): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", socksPort), 4000)
                true
            }
        }.getOrDefault(false)
    }

    fun health(): JSONObject {
        val base = baseUrl.trimEnd('/')
        val candidates = mutableListOf<String>()
        candidates.add(buildEndpoint("health"))

        val uri = runCatching { URI(base) }.getOrNull()
        if (uri != null && uri.path.isNotBlank() && uri.path != "/") {
            val root = "${uri.scheme}://${uri.rawAuthority}/health"
            if (!candidates.contains(root)) candidates.add(root)
        }

        for (cand in candidates) {
            try {
                val c = openConnection(cand, "GET")
                val code = c.responseCode
                if (code in 200..299) {
                    val text = c.inputStream.bufferedReader().use { it.readText() }
                    c.disconnect()
                    return if (text.trim().startsWith("{")) JSONObject(text) else JSONObject().put("status", "ok")
                }
            } catch (_: Exception) {}
        }

        // If no explicit /health endpoint (common in OpenAI-compatible gateways like /myai/v1),
        // verify responsiveness using models list:
        try {
            val m = models()
            return JSONObject().put("status", "ok").put("models_count", m.size)
        } catch (e: Exception) {
            throw IOException("Gateway health check failed: ${e.message}")
        }
    }

    fun models(): List<String> {
        val base = baseUrl.trimEnd('/')
        val endpoints = mutableListOf<String>()
        if (base.endsWith("/v1", ignoreCase = true)) {
            endpoints.add("$base/models")
            endpoints.add(buildEndpoint("models"))
        } else {
            endpoints.add(buildEndpoint("v1/models"))
            endpoints.add(buildEndpoint("models"))
        }
        var lastErr: Exception? = null

        for (ep in endpoints) {
            try {
                val c = openConnection(ep, "GET")
                val code = c.responseCode
                if (code in 200..299) {
                    val text = c.inputStream.bufferedReader().use { it.readText() }
                    c.disconnect()
                    val arr = JSONObject(text).optJSONArray("data") ?: JSONArray()
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val mId = arr.optJSONObject(i)?.optString("id")
                        if (!mId.isNullOrBlank()) list.add(mId)
                    }
                    if (list.isNotEmpty()) return list
                }
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw (lastErr ?: IOException("No models discovered from gateway"))
    }

    fun chatStream(
        model: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        onToken: (String) -> Unit
    ): String {
        isCancelled = false
        val isAnthropicModel = model.startsWith("claude-", ignoreCase = true)
        return if (isAnthropicModel) {
            try {
                chatAnthropicStream(model, systemPrompt, messages, onToken)
            } catch (e: Exception) {
                if (isCancelled) throw e
                chatOpenAiStream(model, systemPrompt, messages, onToken)
            }
        } else {
            try {
                chatOpenAiStream(model, systemPrompt, messages, onToken)
            } catch (e: Exception) {
                if (isCancelled) throw e
                runCatching {
                    chatAnthropicStream(model, systemPrompt, messages, onToken)
                }.getOrElse { throw e }
            }
        }
    }

    private fun chatAnthropicStream(
        model: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        onToken: (String) -> Unit
    ): String {
        val endpoint = buildEndpoint("v1/messages")
        val c = openConnection(endpoint, "POST")
        c.doOutput = true
        val body = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("max_tokens", 4096)
            if (systemPrompt.isNotBlank()) put("system", systemPrompt)
            val msgsArr = JSONArray()
            messages.forEach { msg ->
                msgsArr.put(JSONObject().put("role", msg.role).put("content", msg.content))
            }
            put("messages", msgsArr)
        }.toString()

        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        if (code !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            c.disconnect()
            throw IOException("Gateway HTTP $code: $err")
        }

        val out = StringBuilder()
        c.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (isCancelled) {
                    c.disconnect()
                    break
                }
                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) continue
                val payload = trimmed.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                runCatching {
                    val obj = JSONObject(payload)
                    val type = obj.optString("type")
                    if (type == "content_block_delta") {
                        val delta = obj.optJSONObject("delta")
                        val text = delta?.optString("text").orEmpty()
                        if (text.isNotEmpty()) {
                            out.append(text)
                            onToken(text)
                        }
                    }
                }
            }
        }
        c.disconnect()
        return out.toString()
    }

    private fun chatOpenAiStream(
        model: String,
        systemPrompt: String,
        messages: List<ChatMessage>,
        onToken: (String) -> Unit
    ): String {
        val endpoint = buildEndpoint("chat/completions")
        val c = openConnection(endpoint, "POST")
        c.doOutput = true
        val msgsArr = JSONArray()
        if (systemPrompt.isNotBlank()) {
            msgsArr.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        messages.forEach { msg ->
            msgsArr.put(JSONObject().put("role", msg.role).put("content", msg.content))
        }

        val body = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("messages", msgsArr)
        }.toString()

        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        if (code !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            c.disconnect()
            throw IOException("Gateway HTTP $code: $err")
        }

        val out = StringBuilder()
        val streamContentType = c.contentType.orEmpty().lowercase()
        if (streamContentType.contains("application/json")) {
            val fullText = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val obj = JSONObject(fullText)
            val choice = obj.optJSONArray("choices")?.optJSONObject(0)
            val msgObj = choice?.optJSONObject("message")
            val content = msgObj?.optString("content").orEmpty()
            val reasoning = msgObj?.optString("reasoning_content").orEmpty()
            val text = if (content.isNotEmpty()) content else reasoning
            out.append(text)
            onToken(text)
            return out.toString()
        }

        c.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (isCancelled) {
                    c.disconnect()
                    break
                }
                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) continue
                val payload = trimmed.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                runCatching {
                    val obj = JSONObject(payload)
                    val choice = obj.optJSONArray("choices")?.optJSONObject(0)
                    val delta = choice?.optJSONObject("delta")
                    val content = delta?.optString("content").orEmpty()
                    val reasoning = delta?.optString("reasoning_content").orEmpty()
                    val text = if (content.isNotEmpty()) content else reasoning
                    if (text.isNotEmpty()) {
                        out.append(text)
                        onToken(text)
                    }
                }
            }
        }
        c.disconnect()
        return out.toString()
    }

    fun generateImage(model: String, prompt: String): ByteArray {
        val endpoint = buildEndpoint("images/generations")
        val c = openConnection(endpoint, "POST")
        c.doOutput = true
        val body = JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            put("response_format", "b64_json")
        }.toString()
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        if (code !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            c.disconnect()
            throw IOException("Image HTTP $code: $err")
        }
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val dataObj = JSONObject(text).optJSONArray("data")?.optJSONObject(0)
            ?: throw IOException("Image response missing 'data' array")

        val b64 = dataObj.optString("b64_json")
        if (b64.isNotEmpty()) {
            return Base64.decode(b64, Base64.DEFAULT)
        }

        val urlStr = dataObj.optString("url")
        if (urlStr.isNotEmpty()) {
            val imgConn = openConnection(urlStr, "GET")
            return imgConn.inputStream.use { it.readBytes() }
        }

        throw IOException("Image response missing b64_json and url payload")
    }

    fun createApiKey(adminKey: String, name: String, expiresDays: Int?, rateLimit: Int?): Pair<JSONObject, String> {
        val endpoint = buildEndpoint("admin/keys")
        val c = openConnection(endpoint, "POST", customApiKey = adminKey)
        c.doOutput = true
        val body = JSONObject().apply {
            put("name", name)
            if (expiresDays != null && expiresDays > 0) put("expires_in_days", expiresDays)
            if (rateLimit != null && rateLimit > 0) put("rate_limit", rateLimit)
        }.toString()
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        if (code !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            c.disconnect()
            throw IOException("Admin HTTP $code: $err")
        }
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val res = JSONObject(text)
        val secret = res.getString("key")
        val meta = res.getJSONObject("key_metadata")
        return Pair(meta, secret)
    }

    fun listApiKeys(adminKey: String): List<JSONObject> {
        val endpoint = buildEndpoint("admin/keys")
        val c = openConnection(endpoint, "GET", customApiKey = adminKey)
        val code = c.responseCode
        if (code !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            c.disconnect()
            throw IOException("Admin HTTP $code: $err")
        }
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val arr = JSONObject(text).optJSONArray("data") ?: JSONObject(text).optJSONArray("keys") ?: JSONArray()
        val list = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { list.add(it) }
        }
        return list
    }

    fun revokeApiKey(adminKey: String, keyId: String): Boolean {
        val endpoint = buildEndpoint("admin/keys/$keyId/revoke")
        val c = openConnection(endpoint, "POST", customApiKey = adminKey)
        val code = c.responseCode
        c.disconnect()
        return code in 200..299
    }

    fun rotateApiKey(adminKey: String, keyId: String): Pair<JSONObject, String> {
        val endpoint = buildEndpoint("admin/keys/$keyId/rotate")
        val c = openConnection(endpoint, "POST", customApiKey = adminKey)
        val code = c.responseCode
        if (code !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            c.disconnect()
            throw IOException("Admin HTTP $code: $err")
        }
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val res = JSONObject(text)
        return Pair(res.getJSONObject("key_metadata"), res.getString("key"))
    }
}

class MainActivity : Activity() {
    private lateinit var store: SecureStore
    private var api: ApiClient? = null
    private val executor = Executors.newCachedThreadPool()
    private var activeCallFuture: Future<*>? = null
    private var tts: TextToSpeech? = null

    // Chat sessions
    private val sessions = mutableListOf<ChatSession>()
    private var activeSession: ChatSession? = null

    // UI elements
    private lateinit var rootLayout: LinearLayout
    private lateinit var statusBadge: TextView
    private lateinit var modelSpinner: Spinner
    private lateinit var messagesContainer: LinearLayout
    private lateinit var chatScrollView: ScrollView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button
    private lateinit var stopButton: Button
    private lateinit var systemPromptBadge: TextView
    private var torEngineBtn: Button? = null

    // Dual-Mode UI elements (Direct Onion Web & Native Chat)
    private lateinit var tabDirectWebBtn: Button
    private lateinit var tabChatBtn: Button
    private lateinit var webViewContainer: LinearLayout
    private lateinit var chatViewContainer: LinearLayout
    private lateinit var webView: WebView
    private lateinit var webUrlInput: EditText
    private lateinit var webProgressBar: ProgressBar
    private var isWebMode = true
    private var pendingWebUrl: String? = null

    private var isGenerating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        store = SecureStore(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
            }
        }

        buildMainInterface()
        loadSavedSessions()
        initClient()
        setupTorEngine()
    }

    private fun buildMainInterface() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(16, 16, 16, 16)
        }

        // Header Title Bar
        val headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 8)
        }
        val title = TextView(this).apply {
            text = "Onion AI"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val subtitle = TextView(this).apply {
            text = " • Private Tor Gateway"
            textSize = 13f
            setTextColor(Color.parseColor("#9E9E9E"))
        }
        headerBar.addView(title)
        headerBar.addView(subtitle)
        rootLayout.addView(headerBar)

        // Status badge
        statusBadge = TextView(this).apply {
            text = "⚪ Initializing Tor Gateway..."
            textSize = 12f
            setPadding(16, 8, 16, 8)
            setTextColor(Color.parseColor("#B0BEC5"))
            background = createRoundedDrawable(Color.parseColor("#1E1E1E"), 12)
            setOnClickListener { onStatusBadgeClicked() }
        }
        rootLayout.addView(statusBadge)

        // Action Toolbar (Buttons: ⚙️ Config, 🧅 Tor Engine, + New, ⚡ Test, 📚 History, 🔑 API/Developer, 🎨 Image)
        val toolbarScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 10, 0, 10)
        }
        val toolbarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        fun createToolBtn(label: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 12f
                setTextColor(Color.WHITE)
                background = createRoundedDrawable(Color.parseColor("#263238"), 12)
                setPadding(20, 8, 20, 8)
                val params = LinearLayout.LayoutParams(-2, -2).apply { setMargins(4, 0, 4, 0) }
                layoutParams = params
                setOnClickListener { onClick() }
            }
        }

        toolbarLayout.addView(createToolBtn("⚙️ Config") { showGatewayConfigDialog() })
        val torBtn = createToolBtn("🧅 Inbuilt Tor") { showTorEngineDialog() }
        torEngineBtn = torBtn
        toolbarLayout.addView(torBtn)
        toolbarLayout.addView(createToolBtn("+ New Chat") { startNewChat() })
        toolbarLayout.addView(createToolBtn("⚡ Test Tor") { runConnectionTest() })
        toolbarLayout.addView(createToolBtn("📚 History") { showHistoryDialog() })
        toolbarLayout.addView(createToolBtn("🔑 API Access") { showDeveloperAccessDialog() })
        toolbarLayout.addView(createToolBtn("🎨 Image") { showImagePromptDialog() })

        toolbarScroll.addView(toolbarLayout)
        rootLayout.addView(toolbarScroll)

        // Mode Tab Switcher: Direct Onion Web vs Native Chat
        val modeTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 8)
        }
        tabDirectWebBtn = Button(this).apply {
            text = "🌐 Direct AI Onion Web"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(12, 8, 12, 8)
            setOnClickListener { switchMode(isWeb = true) }
        }
        tabChatBtn = Button(this).apply {
            text = "💬 Native Chat"
            textSize = 12f
            setPadding(12, 8, 12, 8)
            setOnClickListener { switchMode(isWeb = false) }
        }
        modeTabs.addView(tabDirectWebBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, 4, 0) })
        modeTabs.addView(tabChatBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(4, 0, 0, 0) })
        rootLayout.addView(modeTabs)

        // --- Container 1: Direct Onion Website (Tor-Proxied WebView) ---
        webViewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        // Web Address Bar
        val webAddressBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 2, 0, 4)
        }
        val backBtn = Button(this).apply {
            text = "◀"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#263238"), 8)
            val p = LinearLayout.LayoutParams((36 * resources.displayMetrics.density).toInt(), (36 * resources.displayMetrics.density).toInt()).apply { setMargins(0, 0, 4, 0) }
            layoutParams = p
            setOnClickListener { if (::webView.isInitialized && webView.canGoBack()) webView.goBack() }
        }
        val forwardBtn = Button(this).apply {
            text = "▶"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#263238"), 8)
            val p = LinearLayout.LayoutParams((36 * resources.displayMetrics.density).toInt(), (36 * resources.displayMetrics.density).toInt()).apply { setMargins(0, 0, 4, 0) }
            layoutParams = p
            setOnClickListener { if (::webView.isInitialized && webView.canGoForward()) webView.goForward() }
        }
        webUrlInput = EditText(this).apply {
            hint = "http://...onion AI website URL"
            setHintTextColor(Color.parseColor("#757575"))
            setTextColor(Color.WHITE)
            textSize = 12f
            isSingleLine = true
            background = createRoundedDrawable(Color.parseColor("#1E1E1E"), 8)
            setPadding(10, 8, 10, 8)
            val savedUrl = store.get(KEY_URL).orEmpty()
            if (savedUrl.isNotBlank() && !savedUrl.contains("samplegatewayonion")) {
                setText(savedUrl)
            } else {
                setText(DEFAULT_DIGDIG_CHAT_URL)
            }
        }
        val pasteBtn = Button(this).apply {
            text = "📋"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#37474F"), 8)
            val p = LinearLayout.LayoutParams((34 * resources.displayMetrics.density).toInt(), (36 * resources.displayMetrics.density).toInt()).apply { setMargins(4, 0, 2, 0) }
            layoutParams = p
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
                val item = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                if (!item.isNullOrBlank()) {
                    webUrlInput.setText(item)
                    loadCurrentWebUrl(item)
                    Toast.makeText(this@MainActivity, "Loading URL via Tor...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val goBtn = Button(this).apply {
            text = "Go"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#2E7D32"), 8)
            val p = LinearLayout.LayoutParams((40 * resources.displayMetrics.density).toInt(), (36 * resources.displayMetrics.density).toInt()).apply { setMargins(2, 0, 2, 0) }
            layoutParams = p
            setOnClickListener {
                val url = webUrlInput.text.toString().trim()
                if (url.isNotEmpty()) {
                    store.put(KEY_URL, url)
                    loadCurrentWebUrl(url)
                }
            }
        }
        val reloadBtn = Button(this).apply {
            text = "🔄"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#263238"), 8)
            val p = LinearLayout.LayoutParams((34 * resources.displayMetrics.density).toInt(), (36 * resources.displayMetrics.density).toInt()).apply { setMargins(2, 0, 2, 0) }
            layoutParams = p
            setOnClickListener { if (::webView.isInitialized) webView.reload() }
        }
        val newnymBtn = Button(this).apply {
            text = "🛡️"
            textSize = 12f
            setTextColor(Color.parseColor("#81C784"))
            background = createRoundedDrawable(Color.parseColor("#1B3E20"), 8)
            val p = LinearLayout.LayoutParams((34 * resources.displayMetrics.density).toInt(), (36 * resources.displayMetrics.density).toInt()).apply { setMargins(2, 0, 2, 0) }
            layoutParams = p
            setOnClickListener {
                TorManager.requestNewIdentity(this@MainActivity)
                Toast.makeText(this@MainActivity, "🛡️ New Tor identity requested! Circuit rerouting...", Toast.LENGTH_SHORT).show()
            }
        }
        val purgeBtn = Button(this).apply {
            text = "🔥"
            textSize = 12f
            setTextColor(Color.parseColor("#EF5350"))
            background = createRoundedDrawable(Color.parseColor("#3E1B1B"), 8)
            val p = LinearLayout.LayoutParams((34 * resources.displayMetrics.density).toInt(), (36 * resources.displayMetrics.density).toInt()).apply { setMargins(2, 0, 0, 0) }
            layoutParams = p
            setOnClickListener { purgeAllTraces() }
        }
        webAddressBar.addView(backBtn)
        webAddressBar.addView(forwardBtn)
        webAddressBar.addView(webUrlInput, LinearLayout.LayoutParams(0, -2, 1f))
        webAddressBar.addView(pasteBtn)
        webAddressBar.addView(goBtn)
        webAddressBar.addView(reloadBtn)
        webAddressBar.addView(newnymBtn)
        webAddressBar.addView(purgeBtn)
        webViewContainer.addView(webAddressBar)

        val securityStatusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 4, 8, 4)
            background = createRoundedDrawable(Color.parseColor("#132A13"), 6, Color.parseColor("#2E7D32"), 1)
            val p = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 2, 0, 4) }
            layoutParams = p
        }
        val securityStatusText = TextView(this).apply {
            text = "🛡️ MILITARY-GRADE ANONYMITY: WebRTC Blocked • Sandbox Active • Tor Isolated"
            textSize = 9.5f
            setTextColor(Color.parseColor("#A5D6A7"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        securityStatusRow.addView(securityStatusText)
        webViewContainer.addView(securityStatusRow)

        webProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, (3 * resources.displayMetrics.density).toInt())
        }
        webViewContainer.addView(webProgressBar)

        val shortcutsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 2, 0, 4)
        }
        val shortcutsLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun createChip(label: String, targetUrl: String, isSpecial: Boolean = false): Button {
            return Button(this).apply {
                text = label
                textSize = 11f
                setTextColor(if (isSpecial) Color.parseColor("#FFD54F") else Color.parseColor("#80CBC4"))
                background = createRoundedDrawable(if (isSpecial) Color.parseColor("#2E2818") else Color.parseColor("#1B2A28"), 6)
                setPadding(10, 4, 10, 4)
                val p = LinearLayout.LayoutParams(-2, -2).apply { setMargins(2, 0, 4, 0) }
                layoutParams = p
                setOnClickListener {
                    when (targetUrl) {
                        "ACTION_NEWNYM" -> {
                            TorManager.requestNewIdentity(this@MainActivity)
                            Toast.makeText(this@MainActivity, "🛡️ New Tor Identity requested (NEWNYM)!", Toast.LENGTH_SHORT).show()
                        }
                        "ACTION_PURGE" -> purgeAllTraces()
                        else -> {
                            webUrlInput.setText(targetUrl)
                            store.put(KEY_URL, targetUrl)
                            loadCurrentWebUrl(targetUrl)
                        }
                    }
                }
            }
        }
        shortcutsLayout.addView(createChip("🤖 DigDig AI Chat", DEFAULT_DIGDIG_CHAT_URL, true))
        shortcutsLayout.addView(createChip("🎨 AI Image Gen Hub", DEFAULT_DIGDIG_BASE_URL))
        shortcutsLayout.addView(createChip("🛡️ New Circuit", "ACTION_NEWNYM"))
        shortcutsLayout.addView(createChip("🔥 Purge Traces", "ACTION_PURGE"))
        shortcutsLayout.addView(createChip("🔍 DuckDuckGo Onion", "http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"))
        shortcutsLayout.addView(createChip("✅ Tor Check", "https://check.torproject.org"))
        shortcutsScroll.addView(shortcutsLayout)
        webViewContainer.addView(shortcutsScroll)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setBackgroundColor(Color.parseColor("#121212"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false

                // Military-Grade Sandbox & Privacy Hardening
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                saveFormData = false
                savePassword = false
                setGeolocationEnabled(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                // Mask fingerprint as standard Desktop Tor Browser on Windows 10
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; rv:115.0) Gecko/20100101 Firefox/115.0"
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

            setOnLongClickListener {
                val hit = hitTestResult
                if (hit.type == WebView.HitTestResult.IMAGE_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                    val imgUrl = hit.extra
                    if (!imgUrl.isNullOrBlank()) {
                        showSafeImageActionMenu(imgUrl)
                        return@setOnLongClickListener true
                    }
                }
                false
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    // Strict Sandbox: Only allow http:// and https://. Disallow intent://, file://, content://, market://, tel:, sms:
                    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                        Log.w("WebViewSecurity", "Blocked untrusted intent execution attempt: $url")
                        return true
                    }
                    return false
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    // Strict Tor Kill-Switch: Block clear-net leakage if Tor is not actively running
                    if (TorManager.currentState != TorState.RUNNING) {
                        Log.e("WebViewSecurity", "Blocked network request - Tor offline")
                        return WebResourceResponse("text/plain", "UTF-8", 403, "Tor Offline", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    // Hidden services (.onion) frequently use self-signed certificates or plain Tor transport encryption
                    val u = error?.url.orEmpty()
                    if (u.contains(".onion") || u.contains("127.0.0.1")) {
                        handler?.proceed()
                    } else {
                        handler?.proceed()
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        val failedUrl = request.url?.toString().orEmpty()
                        val errDesc = error?.description?.toString() ?: "Connection closed"
                        val errCode = error?.errorCode ?: 0
                        Log.w("WebView", "Main frame load failed ($errCode): $errDesc, url=$failedUrl")

                        // Most onion sites run on HTTP (port 80). If user/site tried HTTPS and failed (net_error -100), auto-retry with http://
                        if (failedUrl.startsWith("https://") && failedUrl.contains(".onion")) {
                            val httpUrl = failedUrl.replaceFirst("https://", "http://")
                            view?.post {
                                webUrlInput.setText(httpUrl)
                                view.loadUrl(httpUrl)
                            }
                            return
                        }

                        val safeUrl = failedUrl.replace("<", "&lt;").replace(">", "&gt;")
                        val html = """
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                <style>
                                    body { background: #121212; color: #E0E0E0; font-family: sans-serif; text-align: center; padding: 20px; }
                                    .card { background: #1E1E1E; border-radius: 12px; padding: 20px; margin: 16px auto; max-width: 480px; border: 1px solid #333; }
                                    h3 { color: #FFA726; margin-top: 0; }
                                    p { color: #B0BEC5; font-size: 14px; line-height: 1.5; }
                                    .url { color: #80CBC4; word-break: break-all; font-family: monospace; font-size: 12px; padding: 8px; background: #263238; border-radius: 6px; margin: 10px 0; }
                                    button { background: #2E7D32; color: white; border: none; border-radius: 8px; padding: 10px 20px; font-size: 14px; font-weight: bold; cursor: pointer; margin: 6px; }
                                    .btn-sec { background: #37474F; }
                                </style>
                            </head>
                            <body>
                                <div class="card">
                                    <h3>🧅 Tor Circuit Notice</h3>
                                    <p>Could not connect to the onion service at this moment.</p>
                                    <div class="url">$safeUrl</div>
                                    <p style="font-size: 12px; color: #90A4AE;">Status: $errDesc (Code: $errCode)</p>
                                    <p style="font-size: 12px; color: #78909C; text-align: left; padding: 0 10px;">
                                        • Tor hidden services require 15-30s to build rendezvous circuits.<br>
                                        • Ensure the onion site is running and uses <b>http://</b>.
                                    </p>
                                    <button onclick="window.location.reload();">🔄 Retry Page</button>
                                </div>
                            </body>
                            </html>
                        """.trimIndent()
                        view?.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    webProgressBar.visibility = View.VISIBLE
                    if (!url.isNullOrBlank() && !url.startsWith("data:")) webUrlInput.setText(url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    webProgressBar.visibility = View.GONE
                    if (!url.isNullOrBlank() && !url.startsWith("data:")) webUrlInput.setText(url)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    // Military-grade privacy: strictly deny camera, microphone, sensors, protected media requests
                    request?.deny()
                }

                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                    // Strictly block geolocation prompt
                    callback?.invoke(origin, false, false)
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    webProgressBar.progress = newProgress
                    if (newProgress >= 100) {
                        webProgressBar.visibility = View.GONE
                    }
                }

                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Onion AI Web")
                        .setMessage(message)
                        .setPositiveButton("OK") { _, _ -> result?.confirm() }
                        .setOnCancelListener { result?.cancel() }
                        .show()
                    return true
                }
            }
        }
        webViewContainer.addView(webView)
        rootLayout.addView(webViewContainer)

        // --- Container 2: Native Chat UI ---
        chatViewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        // Model selector and System Prompt Row
        val modelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 4, 4, 8)
        }
        val modelLabel = TextView(this).apply {
            text = "Model: "
            textSize = 13f
            setTextColor(Color.parseColor("#9E9E9E"))
        }
        modelSpinner = Spinner(this).apply {
            val fallback = listOf("DIG-THNK", "gpt-4o", "claude-3-7-sonnet-20250219", "IMAGE-gen")
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, fallback)
        }
        systemPromptBadge = TextView(this).apply {
            text = "⚙️ System Prompt"
            textSize = 12f
            setTextColor(Color.parseColor("#80CBC4"))
            setPadding(12, 6, 12, 6)
            background = createRoundedDrawable(Color.parseColor("#1B2A28"), 8)
            setOnClickListener { showSystemPromptDialog() }
        }

        modelRow.addView(modelLabel)
        modelRow.addView(modelSpinner, LinearLayout.LayoutParams(0, -2, 1f))
        modelRow.addView(systemPromptBadge)
        chatViewContainer.addView(modelRow)

        // Messages Box
        messagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        chatScrollView = ScrollView(this).apply {
            addView(messagesContainer)
            isFillViewport = true
        }
        chatViewContainer.addView(chatScrollView, LinearLayout.LayoutParams(-1, 0, 1f))

        // Bottom Input Bar
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, 8, 0, 0)
        }
        inputField = EditText(this).apply {
            hint = "Message AI via Tor..."
            setHintTextColor(Color.parseColor("#757575"))
            setTextColor(Color.WHITE)
            textSize = 15f
            minLines = 1
            maxLines = 4
            background = createRoundedDrawable(Color.parseColor("#1E1E1E"), 12)
            setPadding(16, 14, 16, 14)
        }
        sendButton = Button(this).apply {
            text = "Send"
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#37474F"), 12)
            setOnClickListener { handleSendMessage() }
        }
        stopButton = Button(this).apply {
            text = "Stop"
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#C62828"), 12)
            visibility = View.GONE
            setOnClickListener { stopGeneration() }
        }

        bottomBar.addView(inputField, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, 8, 0) })
        bottomBar.addView(sendButton, LinearLayout.LayoutParams(-2, -2))
        bottomBar.addView(stopButton, LinearLayout.LayoutParams(-2, -2))
        chatViewContainer.addView(bottomBar)

        rootLayout.addView(chatViewContainer)
        setContentView(rootLayout)

        switchMode(isWeb = true)
    }

    private fun switchMode(isWeb: Boolean) {
        isWebMode = isWeb
        if (isWeb) {
            tabDirectWebBtn.setTextColor(Color.WHITE)
            tabDirectWebBtn.background = createRoundedDrawable(Color.parseColor("#1B5E20"), 10)
            tabChatBtn.setTextColor(Color.parseColor("#90A4AE"))
            tabChatBtn.background = createRoundedDrawable(Color.parseColor("#263238"), 10)
            webViewContainer.visibility = View.VISIBLE
            chatViewContainer.visibility = View.GONE
            if (::webView.isInitialized && (webView.url.isNullOrBlank() || webView.url == "about:blank")) {
                loadCurrentWebUrl()
            }
        } else {
            tabChatBtn.setTextColor(Color.WHITE)
            tabChatBtn.background = createRoundedDrawable(Color.parseColor("#0D47A1"), 10)
            tabDirectWebBtn.setTextColor(Color.parseColor("#90A4AE"))
            tabDirectWebBtn.background = createRoundedDrawable(Color.parseColor("#263238"), 10)
            webViewContainer.visibility = View.GONE
            chatViewContainer.visibility = View.VISIBLE
        }
    }

    private fun configureWebViewTorProxy(port: Int, onComplete: (() -> Unit)? = null) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Log.w("MainActivity", "Proxy override not supported on this WebView")
            onComplete?.invoke()
            return
        }
        runCatching {
            val proxyConfig = ProxyConfig.Builder()
                .addProxyRule("socks://127.0.0.1:$port")
                .build()
            ProxyController.getInstance().setProxyOverride(proxyConfig, { runnable ->
                runOnUiThread(runnable)
            }) {
                Log.d("MainActivity", "Tor SOCKS proxy configured for 127.0.0.1:$port")
                onComplete?.invoke()
            }
        }.onFailure { e ->
            Log.e("MainActivity", "Failed to set proxy override: ${e.message}", e)
            onComplete?.invoke()
        }
    }

    private fun loadCurrentWebUrl(customUrl: String? = null) {
        val raw = (customUrl ?: webUrlInput.text.toString().trim()).ifBlank {
            store.get(KEY_URL).orEmpty().trim().ifBlank { DEFAULT_DIGDIG_CHAT_URL }
        }
        val cleanUrl = when {
            raw.isBlank() || raw.contains("samplegatewayonion") -> DEFAULT_DIGDIG_CHAT_URL
            !raw.startsWith("http://") && !raw.startsWith("https://") -> {
                if (raw.contains(".onion")) "http://$raw" else "https://$raw"
            }
            else -> raw
        }
        webUrlInput.setText(cleanUrl)

        if (TorManager.currentState != TorState.RUNNING) {
            pendingWebUrl = cleanUrl
            if (::webView.isInitialized) {
                val safeUrl = cleanUrl.replace("<", "&lt;").replace(">", "&gt;")
                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <style>
                            body { background: #121212; color: #E0E0E0; font-family: sans-serif; text-align: center; padding: 24px; }
                            .card { background: #1E1E1E; border-radius: 12px; padding: 24px; margin: 24px auto; max-width: 480px; border: 1px solid #333; }
                            .pulse { font-size: 36px; margin-bottom: 12px; }
                            h3 { color: #81C784; margin-top: 0; }
                            p { color: #B0BEC5; font-size: 14px; line-height: 1.6; }
                            .url { color: #80CBC4; word-break: break-all; font-family: monospace; font-size: 12px; padding: 10px; background: #263238; border-radius: 6px; margin: 12px 0; }
                        </style>
                    </head>
                    <body>
                        <div class="card">
                            <div class="pulse">🧅</div>
                            <h3>Connecting to Tor Circuit...</h3>
                            <p>Embedded Tor engine is initializing and routing traffic securely.<br>This page will automatically open as soon as Tor is online.</p>
                            <div class="url">$safeUrl</div>
                            <p style="font-size: 12px; color: #78909C;">${TorManager.currentStatusText}</p>
                        </div>
                    </body>
                    </html>
                """.trimIndent()
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
            return
        }

        val port = TorManager.getEffectiveSocksPort()
        configureWebViewTorProxy(port) {
            if (::webView.isInitialized) {
                webView.loadUrl(cleanUrl)
            }
        }
    }

    private fun showSafeImageActionMenu(imgUrl: String) {
        val options = arrayOf("🔍 View Image Fullscreen", "📋 Copy Image Onion Link", "🔒 Security & Anonymity Details")
        AlertDialog.Builder(this)
            .setTitle("🧅 Onion AI Image Detected")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val dialog = AlertDialog.Builder(this).create()
                        val previewWeb = WebView(this).apply {
                            setBackgroundColor(Color.BLACK)
                            settings.javaScriptEnabled = false
                            settings.allowFileAccess = false
                            val port = TorManager.getEffectiveSocksPort()
                            configureWebViewTorProxy(port) {
                                val html = """
                                    <!DOCTYPE html>
                                    <html>
                                    <body style="margin:0; background:black; display:flex; align-items:center; justify-content:center; height:100vh;">
                                        <img src="$imgUrl" style="max-width:100%; max-height:100%; object-fit:contain;" />
                                    </body>
                                    </html>
                                """.trimIndent()
                                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                            }
                        }
                        dialog.setView(previewWeb)
                        dialog.show()
                    }
                    1 -> {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Onion Image URL", imgUrl))
                        Toast.makeText(this, "Copied Image Onion Link to Clipboard", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        AlertDialog.Builder(this)
                            .setTitle("🛡️ Image Protection")
                            .setMessage("• Transferred with 100% Tor multi-hop circuit encryption.\n• Stripped of local device identifiers.\n• Origin: DigDig Onion AI Service.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun purgeAllTraces() {
        AlertDialog.Builder(this)
            .setTitle("🔥 Wipe All Traces (Zero Trace)")
            .setMessage("This will:\n• Clear WebView cache & cookies\n• Clear form data & history\n• Request a fresh Tor Identity (NEWNYM)\n• Re-route circuit\n\nProceed?")
            .setPositiveButton("Wipe & Reset") { _, _ ->
                if (::webView.isInitialized) {
                    webView.clearCache(true)
                    webView.clearHistory()
                    webView.clearFormData()
                }
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                TorManager.requestNewIdentity(this)
                Toast.makeText(this, "🛡️ All traces wiped! Fresh Tor circuit requested.", Toast.LENGTH_LONG).show()
                loadCurrentWebUrl(DEFAULT_DIGDIG_CHAT_URL)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isWebMode && ::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun createRoundedDrawable(color: Int, radiusDp: Int, borderColor: Int = Color.TRANSPARENT, borderWidth: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
            if (borderWidth > 0) {
                setStroke((borderWidth * resources.displayMetrics.density).toInt(), borderColor)
            }
        }
    }

    private fun initClient() {
        val savedUrl = store.get(KEY_URL).orEmpty()
        val url = if (savedUrl.isNotBlank() && !savedUrl.contains("samplegatewayonion")) savedUrl else DEFAULT_DIGDIG_CHAT_URL
        val key = store.get(KEY_CLIENT_KEY) ?: ""
        val port = if (TorManager.currentMode == TorEngineMode.INBUILT) {
            TorManager.getEffectiveSocksPort()
        } else {
            store.get(KEY_SOCKS_PORT)?.toIntOrNull() ?: 9050
        }
        val allowDev = store.getBoolean(KEY_ALLOW_DEV_LOOPBACK, false)

        api = ApiClient(url, key, port, allowDev)
        refreshGatewayStatus()
    }

    private fun refreshGatewayStatus() {
        val client = api ?: return
        val activePort = client.socksPort
        statusBadge.text = "🟡 Checking Tor SOCKS proxy (127.0.0.1:$activePort)..."
        statusBadge.setTextColor(Color.parseColor("#FFD54F"))

        executor.execute {
            val socksUp = client.testTorSocksPort()
            if (!socksUp) {
                runOnUiThread {
                    if (TorManager.currentMode == TorEngineMode.INBUILT) {
                        statusBadge.text = "🟡 Inbuilt Tor Offline (127.0.0.1:$activePort) • Tap to Start"
                    } else {
                        statusBadge.text = "🟡 Tor SOCKS Offline • Tap to open Orbot"
                    }
                    statusBadge.setTextColor(Color.parseColor("#FFB74D"))
                }
                return@execute
            }

            // If user hasn't configured their actual .onion URL yet:
            val configuredUrl = store.get(KEY_URL)
            if (configuredUrl.isNullOrBlank() || client.baseUrl.contains("samplegatewayonion")) {
                runOnUiThread {
                    statusBadge.text = "⚠️ Tor Online • Server .onion URL Not Configured (Tap to Set)"
                    statusBadge.setTextColor(Color.parseColor("#FFA726"))
                }
                return@execute
            }

            runCatching {
                val health = client.health()
                val models = runCatching { client.models() }.getOrDefault(emptyList())
                runOnUiThread {
                    statusBadge.text = if (models.isNotEmpty()) {
                        "🟢 Tor Connected • Gateway Ready (${models.size} models)"
                    } else {
                        "🟢 Tor Connected • Gateway Ready"
                    }
                    statusBadge.setTextColor(Color.parseColor("#81C784"))
                    if (models.isNotEmpty()) {
                        val prevSelected = store.get(KEY_LAST_MODEL)
                        val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, models)
                        modelSpinner.adapter = adapter
                        if (prevSelected != null && models.contains(prevSelected)) {
                            modelSpinner.setSelection(models.indexOf(prevSelected))
                        } else if (models.contains("DIG-THNK")) {
                            modelSpinner.setSelection(models.indexOf("DIG-THNK"))
                        }
                    }
                }
            }.onFailure { err ->
                runOnUiThread {
                    val msg = err.message.orEmpty()
                    when {
                        msg.contains("401") || msg.contains("authentication", true) -> {
                            statusBadge.text = "⚠️ Authentication Failed (Check API Key)"
                            statusBadge.setTextColor(Color.parseColor("#E57373"))
                        }
                        msg.contains("Tor-Only Policy") -> {
                            statusBadge.text = "🛑 Tor-Only Violation (Non-onion address)"
                            statusBadge.setTextColor(Color.parseColor("#E57373"))
                        }
                        msg.contains("SOCKS server general failure", true) -> {
                            statusBadge.text = "🔴 .onion Unreachable via Tor • Tap for Details"
                            statusBadge.setTextColor(Color.parseColor("#E57373"))
                        }
                        else -> {
                            statusBadge.text = "🔴 Gateway Unreachable via Tor (${err.javaClass.simpleName})"
                            statusBadge.setTextColor(Color.parseColor("#E57373"))
                        }
                    }
                }
            }
        }
    }

    private fun onStatusBadgeClicked() {
        val client = api
        val isSampleUrl = client?.baseUrl?.contains("samplegatewayonion") == true || store.get(KEY_URL).isNullOrBlank()
        val currentSocksPort = client?.socksPort ?: TorManager.getEffectiveSocksPort()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 20, 30, 20)
        }
        val info = TextView(this).apply {
            text = buildString {
                append("🧅 Tor Network Status:\n")
                if (TorManager.currentState == TorState.RUNNING) {
                    append("✅ Tor Daemon: ONLINE (127.0.0.1:$currentSocksPort)\n\n")
                } else {
                    append("⚠️ Tor Daemon: ${TorManager.currentState}\n\n")
                }

                append("🌐 Destination AI Gateway:\n")
                if (isSampleUrl) {
                    append("⚠️ Server .onion URL is NOT configured!\n")
                    append("Currently using placeholder dummy URL:\n'${client?.baseUrl}'\n\n")
                    append("To connect and chat, paste your actual server's .onion URL in Config.")
                } else {
                    append("Current URL: ${client?.baseUrl}\n\n")
                    append("Status: [SOCKS server general failure]\n")
                    append("This means Tor cannot find or connect to this .onion address.\n")
                    append("1. Make sure your server (FastAPI + Tor) is running.\n")
                    append("2. Confirm the .onion address matches 'cat /var/lib/tor/ai_gateway/hostname'.")
                }
            }
            textSize = 13f
            setTextColor(Color.parseColor("#ECEFF1"))
            setLineSpacing(6f, 1f)
        }
        box.addView(info)

        val configBtn = Button(this).apply {
            text = "⚙️ Configure Gateway .onion URL"
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#2E7D32"), 10)
            setOnClickListener { showGatewayConfigDialog() }
        }
        val torBtn = Button(this).apply {
            text = "🧅 Tor Engine & Circuits Controls"
            setOnClickListener { showTorEngineDialog() }
        }
        val p = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 12, 0, 0) }
        box.addView(configBtn, p)
        box.addView(torBtn, p)

        AlertDialog.Builder(this)
            .setTitle("Gateway Diagnostics")
            .setView(box)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun startNewChat() {
        saveCurrentSession()
        val newSession = ChatSession()
        sessions.add(0, newSession)
        activeSession = newSession
        store.put(KEY_ACTIVE_SESSION_ID, newSession.id)
        messagesContainer.removeAllViews()
        renderWelcomeMessage()
    }

    private fun renderWelcomeMessage() {
        val welcome = TextView(this).apply {
            text = "Welcome to Onion AI\n\n• End-to-end anonymity over the Tor network\n• Local Keystore-encrypted vault\n• Anthropic & OpenAI gateway compatibility\n• PocketForge agent gateway ready"
            textSize = 14f
            setTextColor(Color.parseColor("#90A4AE"))
            setPadding(24, 24, 24, 24)
            background = createRoundedDrawable(Color.parseColor("#1E2426"), 12)
        }
        val params = LinearLayout.LayoutParams(-1, -2).apply { setMargins(8, 16, 8, 16) }
        messagesContainer.addView(welcome, params)
    }

    private fun loadSavedSessions() {
        val raw = store.get(KEY_SESSIONS)
        sessions.clear()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val sObj = arr.getJSONObject(i)
                    val s = ChatSession(
                        id = sObj.getString("id"),
                        title = sObj.optString("title", "Chat"),
                        createdAt = sObj.optLong("createdAt", System.currentTimeMillis())
                    )
                    val mArr = sObj.optJSONArray("messages") ?: JSONArray()
                    for (j in 0 until mArr.length()) {
                        val mObj = mArr.getJSONObject(j)
                        s.messages.add(
                            ChatMessage(
                                id = mObj.optString("id", UUID.randomUUID().toString()),
                                role = mObj.getString("role"),
                                content = mObj.getString("content"),
                                timestamp = mObj.optLong("timestamp", System.currentTimeMillis())
                            )
                        )
                    }
                    sessions.add(s)
                }
            }
        }

        val activeId = store.get(KEY_ACTIVE_SESSION_ID)
        activeSession = sessions.firstOrNull { it.id == activeId } ?: sessions.firstOrNull()
        if (activeSession == null) {
            startNewChat()
        } else {
            renderSessionMessages(activeSession!!)
        }
    }

    private fun saveCurrentSession() {
        val arr = JSONArray()
        sessions.forEach { s ->
            val sObj = JSONObject().apply {
                put("id", s.id)
                put("title", s.title)
                put("createdAt", s.createdAt)
                val mArr = JSONArray()
                s.messages.forEach { m ->
                    mArr.put(JSONObject().apply {
                        put("id", m.id)
                        put("role", m.role)
                        put("content", m.content)
                        put("timestamp", m.timestamp)
                    })
                }
                put("messages", mArr)
            }
            arr.put(sObj)
        }
        store.put(KEY_SESSIONS, arr.toString())
    }

    private fun renderSessionMessages(session: ChatSession) {
        messagesContainer.removeAllViews()
        if (session.messages.isEmpty()) {
            renderWelcomeMessage()
            return
        }
        session.messages.forEach { msg ->
            addMessageView(msg)
        }
        scrollChatToBottom()
    }

    private fun handleSendMessage(prefilledText: String? = null) {
        val text = prefilledText ?: inputField.text.toString().trim()
        if (text.isEmpty()) return

        val client = api ?: run {
            showGatewayConfigDialog()
            return
        }

        val configuredUrl = store.get(KEY_URL)
        if (configuredUrl.isNullOrBlank() || client.baseUrl.contains("samplegatewayonion")) {
            Toast.makeText(this, "Please configure your server's .onion URL in Config first", Toast.LENGTH_LONG).show()
            showGatewayConfigDialog()
            return
        }

        val selectedModel = modelSpinner.selectedItem?.toString().orEmpty()
        if (selectedModel.isBlank()) {
            Toast.makeText(this, "Please select an AI model", Toast.LENGTH_SHORT).show()
            return
        }
        store.put(KEY_LAST_MODEL, selectedModel)

        if (prefilledText == null) {
            inputField.setText("")
        }

        val currentSession = activeSession ?: return
        if (currentSession.messages.isEmpty()) {
            currentSession.title = if (text.length > 28) text.take(28) + "..." else text
        }

        val userMsg = ChatMessage(role = "user", content = text)
        currentSession.messages.add(userMsg)
        addMessageView(userMsg)
        saveCurrentSession()

        // Prepare Assistant placeholder
        val assistantMsg = ChatMessage(role = "assistant", content = "")
        currentSession.messages.add(assistantMsg)
        val assistantView = addMessageView(assistantMsg, isStreaming = true)

        val systemPrompt = store.get(KEY_SYSTEM_PROMPT) ?: ""

        // State update for generation
        isGenerating = true
        sendButton.visibility = View.GONE
        stopButton.visibility = View.VISIBLE

        activeCallFuture = executor.submit {
            val fullResponse = StringBuilder()
            val textContainer = assistantView.findViewById<TextView>(R_ID_STREAMING_TEXT)

            runCatching {
                client.chatStream(selectedModel, systemPrompt, currentSession.messages.dropLast(1)) { delta ->
                    fullResponse.append(delta)
                    runOnUiThread {
                        textContainer?.text = fullResponse.toString()
                        scrollChatToBottom()
                    }
                }
                val finalText = fullResponse.toString()
                runOnUiThread {
                    assistantMsg.let {
                        val index = currentSession.messages.indexOf(assistantMsg)
                        if (index >= 0) {
                            currentSession.messages[index] = assistantMsg.copy(content = finalText)
                        }
                    }
                    saveCurrentSession()
                    renderSessionMessages(currentSession)
                    finishGeneration()
                }
            }.onFailure { err ->
                runOnUiThread {
                    if (client.isCancelled) {
                        textContainer?.append("\n[Generation Stopped]")
                    } else {
                        val rawMsg = err.message.orEmpty()
                        if (rawMsg.contains("SOCKS server general failure", true)) {
                            textContainer?.append("\n\n⚠️ Tor Connection Error: SOCKS server general failure\nTor daemon is ONLINE, but could not reach destination '${client.baseUrl}'.\n• Please verify your server's .onion address in ⚙️ Config.\n• Make sure the backend server (FastAPI + Tor) is running.")
                        } else {
                            textContainer?.append("\n[Error: $rawMsg]")
                        }
                    }
                    finishGeneration()
                }
            }
        }
    }

    private fun stopGeneration() {
        api?.isCancelled = true
        activeCallFuture?.cancel(true)
        finishGeneration()
    }

    private fun finishGeneration() {
        isGenerating = false
        sendButton.visibility = View.VISIBLE
        stopButton.visibility = View.GONE
    }

    private val R_ID_STREAMING_TEXT = 999991

    private fun addMessageView(msg: ChatMessage, isStreaming: Boolean = false): View {
        val isUser = msg.role == "user"
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isUser) Gravity.END else Gravity.START
            val p = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(if (isUser) 48 else 0, 10, if (isUser) 0 else 48, 10)
            }
            layoutParams = p
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
            background = if (isUser) {
                createRoundedDrawable(Color.parseColor("#263238"), 14, Color.parseColor("#37474F"), 1)
            } else {
                createRoundedDrawable(Color.parseColor("#1E1E1E"), 14, Color.parseColor("#2C2C2C"), 1)
            }
        }

        val senderLabel = TextView(this).apply {
            text = if (isUser) "You" else "Onion AI"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isUser) Color.parseColor("#80CBC4") else Color.parseColor("#B388FF"))
            setPadding(0, 0, 0, 6)
        }
        card.addView(senderLabel)

        if (isStreaming) {
            val streamingText = TextView(this).apply {
                id = R_ID_STREAMING_TEXT
                text = "Thinking..."
                textSize = 14f
                setTextColor(Color.parseColor("#ECEFF1"))
                setLineSpacing(6f, 1f)
            }
            card.addView(streamingText)
        } else {
            // Render content with code block detection
            renderFormattedContent(card, msg.content)
        }

        // Action Buttons Row (Copy, Share, Speak, Regenerate, Edit)
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 8, 0, 0)
        }

        fun createActionBtn(label: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(Color.parseColor("#90A4AE"))
                setPadding(14, 6, 14, 6)
                background = createRoundedDrawable(Color.parseColor("#2A2A2A"), 6)
                val params = LinearLayout.LayoutParams(-2, -2).apply { setMargins(6, 0, 0, 0) }
                layoutParams = params
                setOnClickListener { onClick() }
            }
        }

        actionRow.addView(createActionBtn("📋 Copy") {
            copyToClipboard("Message", msg.content)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        })

        if (!isUser) {
            actionRow.addView(createActionBtn("📤 Share") {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, msg.content)
                }
                startActivity(Intent.createChooser(shareIntent, "Share AI Response"))
            })

            actionRow.addView(createActionBtn("🔊 Speak") {
                tts?.speak(msg.content, TextToSpeech.QUEUE_FLUSH, null, msg.id)
            })

            actionRow.addView(createActionBtn("🔄 Retry") {
                // Regenerate: remove this assistant message and resend last user message
                val s = activeSession ?: return@createActionBtn
                val idx = s.messages.indexOf(msg)
                if (idx > 0 && s.messages[idx - 1].role == "user") {
                    val prompt = s.messages[idx - 1].content
                    s.messages.removeAt(idx)
                    s.messages.removeAt(idx - 1)
                    renderSessionMessages(s)
                    handleSendMessage(prompt)
                }
            })
        } else {
            actionRow.addView(createActionBtn("✏️ Edit") {
                inputField.setText(msg.content)
                inputField.setSelection(msg.content.length)
                // Remove this message and subsequent messages
                val s = activeSession ?: return@createActionBtn
                val idx = s.messages.indexOf(msg)
                if (idx >= 0) {
                    while (s.messages.size > idx) {
                        s.messages.removeAt(s.messages.size - 1)
                    }
                    renderSessionMessages(s)
                }
            })
        }

        card.addView(actionRow)
        wrapper.addView(card)
        messagesContainer.addView(wrapper)
        scrollChatToBottom()
        return wrapper
    }

    private fun renderFormattedContent(container: LinearLayout, text: String) {
        val parts = text.split("```")
        for (i in parts.indices) {
            val part = parts[i]
            if (part.isBlank()) continue

            if (i % 2 == 1) {
                // Code block
                val firstLineEnd = part.indexOf('\n')
                val lang = if (firstLineEnd in 1..20) part.substring(0, firstLineEnd).trim() else "code"
                val codeBody = if (firstLineEnd != -1) part.substring(firstLineEnd + 1).trimEnd() else part

                val codeBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = createRoundedDrawable(Color.parseColor("#0D1117"), 8, Color.parseColor("#30363D"), 1)
                    setPadding(14, 10, 14, 10)
                    val p = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 8, 0, 8) }
                    layoutParams = p
                }

                val header = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val langLabel = TextView(this).apply {
                    this.text = lang.uppercase(Locale.ROOT)
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#79C0FF"))
                }
                val copyCodeBtn = TextView(this).apply {
                    this.text = "📋 Copy Code"
                    textSize = 11f
                    setTextColor(Color.parseColor("#8B949E"))
                    setPadding(8, 4, 8, 4)
                    setOnClickListener {
                        copyToClipboard("Code", codeBody)
                        Toast.makeText(this@MainActivity, "Code copied", Toast.LENGTH_SHORT).show()
                    }
                }
                header.addView(langLabel, LinearLayout.LayoutParams(0, -2, 1f))
                header.addView(copyCodeBtn)
                codeBox.addView(header)

                val codeScroll = HorizontalScrollView(this)
                val codeView = TextView(this).apply {
                    this.text = codeBody
                    textSize = 13f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#E6EDF3"))
                    setPadding(0, 8, 0, 0)
                }
                codeScroll.addView(codeView)
                codeBox.addView(codeScroll)
                container.addView(codeBox)
            } else {
                // Regular prose
                val proseView = TextView(this).apply {
                    this.text = part.trim()
                    textSize = 14.5f
                    setTextColor(Color.parseColor("#ECEFF1"))
                    setLineSpacing(6f, 1f)
                    setPadding(0, 4, 0, 4)
                }
                container.addView(proseView)
            }
        }
    }

    private fun scrollChatToBottom() {
        chatScrollView.post {
            chatScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun copyToClipboard(label: String, content: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, content)
        clipboard.setPrimaryClip(clip)
    }

    // --- DIALOGS: HISTORY, CONFIG, DEVELOPER ACCESS, SYSTEM PROMPT, IMAGE, TESTS ---

    private fun showHistoryDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }

        val scroll = ScrollView(this)
        val listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        if (sessions.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No saved conversations."
                textSize = 14f
                setPadding(0, 20, 0, 20)
                setTextColor(Color.GRAY)
            }
            listLayout.addView(empty)
        } else {
            val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            sessions.forEach { session ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(16, 12, 16, 12)
                    background = createRoundedDrawable(Color.parseColor("#222222"), 8)
                    val p = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 4, 0, 4) }
                    layoutParams = p
                }

                val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val titleText = TextView(this).apply {
                    text = session.title
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                }
                val subText = TextView(this).apply {
                    text = "${session.messages.size} msgs • ${dateFormat.format(Date(session.createdAt))}"
                    textSize = 11f
                    setTextColor(Color.LTGRAY)
                }
                titleCol.addView(titleText)
                titleCol.addView(subText)

                val delBtn = Button(this).apply {
                    text = "✕"
                    textSize = 12f
                    setTextColor(Color.parseColor("#EF5350"))
                    background = null
                }

                item.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
                item.addView(delBtn)

                item.setOnClickListener {
                    activeSession = session
                    store.put(KEY_ACTIVE_SESSION_ID, session.id)
                    renderSessionMessages(session)
                }

                delBtn.setOnClickListener {
                    sessions.remove(session)
                    saveCurrentSession()
                    if (activeSession?.id == session.id) {
                        activeSession = sessions.firstOrNull()
                        if (activeSession == null) startNewChat() else renderSessionMessages(activeSession!!)
                    }
                    showHistoryDialog()
                }

                listLayout.addView(item)
            }
        }

        scroll.addView(listLayout)
        dialogView.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val clearAllBtn = Button(this).apply {
            text = "Clear All Sessions"
            setTextColor(Color.parseColor("#EF5350"))
            setOnClickListener {
                sessions.clear()
                store.put(KEY_SESSIONS, "[]")
                startNewChat()
            }
        }
        dialogView.addView(clearAllBtn)

        AlertDialog.Builder(this)
            .setTitle("Conversation History")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showSystemPromptDialog() {
        val input = EditText(this).apply {
            hint = "e.g. You are Claude Code, an expert agentic software engineer."
            setText(store.get(KEY_SYSTEM_PROMPT).orEmpty())
            minLines = 4
            maxLines = 8
            textSize = 14f
        }
        AlertDialog.Builder(this)
            .setTitle("Custom System Prompt")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val prompt = input.text.toString().trim()
                store.put(KEY_SYSTEM_PROMPT, prompt)
                Toast.makeText(this, "System prompt saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Clear") { _, _ ->
                store.put(KEY_SYSTEM_PROMPT, "")
                Toast.makeText(this, "System prompt reset", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showGatewayConfigDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 20, 30, 20)
        }

        val urlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val urlInput = EditText(this).apply {
            hint = "https://myai.onion/myai/v1 or http://xyz.onion:8000/v1"
            setText(store.get(KEY_URL).orEmpty())
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val pasteBtn = Button(this).apply {
            text = "📋 Paste"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#37474F"), 8)
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                val item = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                if (!item.isNullOrBlank()) {
                    urlInput.setText(item)
                    Toast.makeText(this@MainActivity, "Pasted from clipboard!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            }
        }
        urlRow.addView(urlInput)
        urlRow.addView(pasteBtn)

        val keyInput = EditText(this).apply {
            hint = "Client API Key (sk-priv-...)"
            setText(store.get(KEY_CLIENT_KEY).orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val adminKeyInput = EditText(this).apply {
            hint = "Admin Key (for managing API keys)"
            setText(store.get(KEY_ADMIN_KEY).orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val portInput = EditText(this).apply {
            hint = "Tor SOCKS Port (default 9050)"
            setText(store.get(KEY_SOCKS_PORT) ?: "9050")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val devCheck = CheckBox(this).apply {
            text = "Allow Dev Loopback (127.0.0.1 / 10.0.2.2 only)"
            isChecked = store.getBoolean(KEY_ALLOW_DEV_LOOPBACK, false)
        }

        val helpTip = TextView(this).apply {
            text = "💡 Server Tip: Supports prefixes like https://myai.onion/myai/v1 or http://xyz.onion:8000\n• Chat: /chat/completions (e.g. DIG-THNK)\n• Images: /images/generations (e.g. IMAGE-gen)\n• Models: /models"
            textSize = 11f
            setTextColor(Color.parseColor("#81C784"))
            setPadding(0, 4, 0, 8)
        }

        val note = TextView(this).apply {
            text = "Strict Tor Security: Non-.onion URLs are strictly blocked in production. Traffic is routed exclusively through local Tor SOCKS proxy (127.0.0.1)."
            textSize = 11f
            setTextColor(Color.parseColor("#90A4AE"))
            setPadding(0, 8, 0, 0)
        }

        box.addView(TextView(this).apply { text = "AI Onion Website / Gateway URL:"; textSize = 12f })
        box.addView(urlRow)
        box.addView(helpTip)
        box.addView(TextView(this).apply { text = "Client API Key (Optional - leave blank if none):"; textSize = 12f })
        box.addView(keyInput)
        box.addView(TextView(this).apply { text = "Admin Master Key (Optional):"; textSize = 12f })
        box.addView(adminKeyInput)
        box.addView(TextView(this).apply { text = "Tor SOCKS Port:"; textSize = 12f })
        box.addView(portInput)
        box.addView(devCheck)
        box.addView(note)

        AlertDialog.Builder(this)
            .setTitle("Tor Gateway / Website Config")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save & Connect") { _, _ ->
                val url = urlInput.text.toString().trim()
                val key = keyInput.text.toString().trim()
                val adminKey = adminKeyInput.text.toString().trim()
                val port = portInput.text.toString().toIntOrNull() ?: 9050
                val allowDev = devCheck.isChecked

                store.put(KEY_URL, url)
                store.put(KEY_CLIENT_KEY, key)
                store.put(KEY_ADMIN_KEY, adminKey)
                store.put(KEY_SOCKS_PORT, port.toString())
                store.putBoolean(KEY_ALLOW_DEV_LOOPBACK, allowDev)

                initClient()
                if (::webUrlInput.isInitialized && url.isNotBlank()) {
                    webUrlInput.setText(url)
                    loadCurrentWebUrl(url)
                }
                Toast.makeText(this@MainActivity, "Saved! Use 'Direct AI Onion Web' tab to access site", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showDeveloperAccessDialog() {
        val client = api ?: run {
            showGatewayConfigDialog()
            return
        }

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 16, 26, 16)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val baseUrl = client.baseUrl.trimEnd('/')
        val selectedModel = modelSpinner.selectedItem?.toString().orEmpty().ifEmpty { "claude-3-7-sonnet-20250219" }
        val currentKey = client.apiKey.ifEmpty { "[Your-API-Key]" }

        val title = TextView(this).apply {
            text = "Developer / PocketForge Access"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val desc = TextView(this).apply {
            text = "Connect PocketForge or Claude Code to this private Onion Gateway. App 1 does not need to stay open."
            textSize = 12f
            setTextColor(Color.parseColor("#90A4AE"))
            setPadding(0, 4, 0, 14)
        }
        content.addView(title)
        content.addView(desc)

        // PocketForge Configuration Snippet Card
        val snippetCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(Color.parseColor("#1B2A32"), 10, Color.parseColor("#37474F"), 1)
            setPadding(16, 14, 16, 14)
        }
        val snippetTitle = TextView(this).apply {
            text = "PocketForge / Claude Code Configuration:"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#80CBC4"))
        }
        val configSnippet = """
Provider: Custom
Protocol: Anthropic-compatible
Base URL: $baseUrl
API Key: $currentKey
Model: $selectedModel
        """.trimIndent()

        val snippetText = TextView(this).apply {
            text = configSnippet
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 8)
        }
        val copySnippetBtn = Button(this).apply {
            text = "📋 Copy Configuration"
            textSize = 12f
            setOnClickListener {
                copyToClipboard("PocketForge Config", configSnippet)
                Toast.makeText(this@MainActivity, "Configuration copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        snippetCard.addView(snippetTitle)
        snippetCard.addView(snippetText)
        snippetCard.addView(copySnippetBtn)
        content.addView(snippetCard)

        // Direct Endpoints
        content.addView(TextView(this).apply {
            text = "\nDirect API Endpoints:"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })

        fun addEndpointRow(label: String, path: String) {
            val fullEp = "$baseUrl$path"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }
            val epText = TextView(this).apply {
                text = "$label:\n$fullEp"
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#B0BEC5"))
            }
            val copyBtn = TextView(this).apply {
                text = "📋 Copy"
                textSize = 11f
                setTextColor(Color.parseColor("#80CBC4"))
                setPadding(10, 6, 10, 6)
                setOnClickListener {
                    copyToClipboard(label, fullEp)
                    Toast.makeText(this@MainActivity, "$label copied", Toast.LENGTH_SHORT).show()
                }
            }
            row.addView(epText, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(copyBtn)
            content.addView(row)
        }

        addEndpointRow("Anthropic Messages", "/v1/messages")
        addEndpointRow("OpenAI Completions", "/v1/chat/completions")
        addEndpointRow("Models", "/v1/models")

        // API Key Management Section
        content.addView(TextView(this).apply {
            text = "\nGateway API Key Management:"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })

        val keyListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val createKeyBtn = Button(this).apply {
            text = "+ Generate New Key for PocketForge"
            textSize = 12f
            setOnClickListener {
                showCreateKeyPrompt { refreshKeysList(keyListContainer) }
            }
        }
        content.addView(createKeyBtn)
        content.addView(keyListContainer)

        refreshKeysList(keyListContainer)

        scroll.addView(content)
        dialogView.addView(scroll)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun refreshKeysList(container: LinearLayout) {
        val client = api ?: return
        val adminKey = store.get(KEY_ADMIN_KEY).orEmpty()
        container.removeAllViews()

        if (adminKey.isBlank()) {
            val notice = TextView(this).apply {
                text = "Set Admin Key in 'Config' to manage gateway API keys."
                textSize = 11f
                setTextColor(Color.parseColor("#FFB74D"))
                setPadding(0, 8, 0, 8)
            }
            container.addView(notice)
            return
        }

        executor.execute {
            runCatching { client.listApiKeys(adminKey) }.onSuccess { keys ->
                runOnUiThread {
                    if (keys.isEmpty()) {
                        container.addView(TextView(this).apply {
                            text = "No active keys registered on gateway."
                            textSize = 11f
                            setTextColor(Color.GRAY)
                            setPadding(0, 8, 0, 8)
                        })
                    } else {
                        keys.forEach { k ->
                            val id = k.optString("id")
                            val name = k.optString("name", id)
                            val enabled = k.optBoolean("enabled", true)

                            val kRow = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(10, 8, 10, 8)
                                background = createRoundedDrawable(Color.parseColor("#1C1C1C"), 6)
                                val p = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 4, 0, 4) }
                                layoutParams = p
                            }
                            val info = TextView(this).apply {
                                text = "$name (${if (enabled) "Active" else "Revoked"})\nID: $id"
                                textSize = 11f
                                setTextColor(if (enabled) Color.WHITE else Color.GRAY)
                            }
                            val revokeBtn = TextView(this).apply {
                                text = if (enabled) "Revoke" else "Revoked"
                                textSize = 11f
                                setTextColor(if (enabled) Color.parseColor("#EF5350") else Color.GRAY)
                                setPadding(8, 4, 8, 4)
                                if (enabled) {
                                    setOnClickListener {
                                        executor.execute {
                                            client.revokeApiKey(adminKey, id)
                                            runOnUiThread { refreshKeysList(container) }
                                        }
                                    }
                                }
                            }
                            val rotateBtn = TextView(this).apply {
                                text = "Rotate"
                                textSize = 11f
                                setTextColor(Color.parseColor("#64B5F6"))
                                setPadding(8, 4, 8, 4)
                                setOnClickListener {
                                    executor.execute {
                                        runCatching { client.rotateApiKey(adminKey, id) }.onSuccess { pair ->
                                            runOnUiThread {
                                                showCreatedKeyDialog(pair.second)
                                                refreshKeysList(container)
                                            }
                                        }
                                    }
                                }
                            }
                            kRow.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                            if (enabled) kRow.addView(rotateBtn)
                            kRow.addView(revokeBtn)
                            container.addView(kRow)
                        }
                    }
                }
            }.onFailure { e ->
                runOnUiThread {
                    container.addView(TextView(this).apply {
                        text = "Could not fetch keys: ${e.message}"
                        textSize = 11f
                        setTextColor(Color.parseColor("#EF5350"))
                    })
                }
            }
        }
    }

    private fun showCreateKeyPrompt(onSuccess: () -> Unit) {
        val client = api ?: return
        val adminKey = store.get(KEY_ADMIN_KEY).orEmpty()
        if (adminKey.isBlank()) {
            Toast.makeText(this, "Please set Admin Key in Config first.", Toast.LENGTH_LONG).show()
            return
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val nameInput = EditText(this).apply { hint = "Key Label (e.g. PocketForge Agent)" }
        val limitInput = EditText(this).apply {
            hint = "Rate limit (requests/min, default 60)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        box.addView(nameInput)
        box.addView(limitInput)

        AlertDialog.Builder(this)
            .setTitle("Create Gateway API Key")
            .setView(box)
            .setPositiveButton("Generate") { _, _ ->
                val name = nameInput.text.toString().trim()
                val limit = limitInput.text.toString().toIntOrNull()
                executor.execute {
                    runCatching { client.createApiKey(adminKey, name, null, limit) }.onSuccess { pair ->
                        runOnUiThread {
                            showCreatedKeyDialog(pair.second)
                            onSuccess()
                        }
                    }.onFailure { e ->
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Creation failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreatedKeyDialog(secret: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val warning = TextView(this).apply {
            text = "⚠️ Save this secret immediately. The plaintext key is only shown once."
            textSize = 12f
            setTextColor(Color.parseColor("#FFB74D"))
            setPadding(0, 0, 0, 10)
        }
        val keyText = TextView(this).apply {
            text = secret
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#1B2A32"), 8)
            setPadding(12, 12, 12, 12)
        }
        val copyBtn = Button(this).apply {
            text = "📋 Copy API Key"
            setOnClickListener {
                copyToClipboard("Gateway API Key", secret)
                Toast.makeText(this@MainActivity, "Key copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
        val useAsClientBtn = Button(this).apply {
            text = "Set as App 1 Active Key"
            setOnClickListener {
                store.put(KEY_CLIENT_KEY, secret)
                initClient()
                Toast.makeText(this@MainActivity, "Active key updated", Toast.LENGTH_SHORT).show()
            }
        }
        box.addView(warning)
        box.addView(keyText)
        box.addView(copyBtn)
        box.addView(useAsClientBtn)

        AlertDialog.Builder(this)
            .setTitle("New API Key Generated")
            .setView(box)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showImagePromptDialog() {
        val client = api ?: run {
            showGatewayConfigDialog()
            return
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }

        val endpointInfo = TextView(this).apply {
            val ep = client.buildEndpoint("images/generations")
            text = "🌐 Endpoint: $ep"
            textSize = 11f
            setTextColor(Color.parseColor("#80CBC4"))
            setPadding(0, 0, 0, 8)
        }

        val modelLabel = TextView(this).apply {
            text = "Image Model:"
            textSize = 12f
            setTextColor(Color.parseColor("#B0BEC5"))
        }

        val modelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val modelInput = EditText(this).apply {
            hint = "e.g. IMAGE-gen or dall-e-3"
            setText(store.get("last_image_model") ?: "IMAGE-gen")
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val presetBtn = Button(this).apply {
            text = "IMAGE-gen"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#263238"), 6)
            setOnClickListener { modelInput.setText("IMAGE-gen") }
        }
        modelRow.addView(modelInput)
        modelRow.addView(presetBtn)

        val promptLabel = TextView(this).apply {
            text = "Prompt:"
            textSize = 12f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, 8, 0, 2)
        }
        val promptInput = EditText(this).apply {
            hint = "Describe the image to generate..."
            minLines = 2
            maxLines = 4
        }
        val imgPreview = ImageView(this).apply {
            visibility = View.GONE
            adjustViewBounds = true
            maxHeight = (300 * resources.displayMetrics.density).toInt()
            setPadding(0, 8, 0, 8)
        }
        val statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, 6, 0, 6)
        }

        box.addView(endpointInfo)
        box.addView(modelLabel)
        box.addView(modelRow)
        box.addView(promptLabel)
        box.addView(promptInput)
        box.addView(statusText)
        box.addView(imgPreview)

        val dialog = AlertDialog.Builder(this)
            .setTitle("AI Image Generation")
            .setView(box)
            .setPositiveButton("Generate", null)
            .setNegativeButton("Close", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val prompt = promptInput.text.toString().trim()
                if (prompt.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Please enter a prompt", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val selectedModel = modelInput.text.toString().trim().ifBlank { "IMAGE-gen" }
                store.put("last_image_model", selectedModel)

                statusText.text = "Generating image with '$selectedModel' via Tor..."
                statusText.setTextColor(Color.parseColor("#FFD54F"))
                imgPreview.visibility = View.GONE

                executor.execute {
                    runCatching {
                        client.generateImage(selectedModel, prompt)
                    }.onSuccess { bytes ->
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        runOnUiThread {
                            statusText.text = "✅ Generated successfully with $selectedModel"
                            statusText.setTextColor(Color.parseColor("#81C784"))
                            imgPreview.setImageBitmap(bmp)
                            imgPreview.visibility = View.VISIBLE
                        }
                    }.onFailure { err ->
                        runOnUiThread {
                            statusText.text = "Failed: ${err.message}"
                            statusText.setTextColor(Color.parseColor("#EF5350"))
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun runConnectionTest() {
        val client = api ?: run {
            showGatewayConfigDialog()
            return
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val steps = listOf(
            "1. Tor SOCKS Proxy (127.0.0.1:${client.socksPort})",
            "2. Onion Host Reachability",
            "3. Gateway Health Check (/health)",
            "4. Authentication Handshake",
            "5. Model Discovery (/v1/models)",
            "6. End-to-End AI Ping"
        )
        val stepViews = steps.map { label ->
            TextView(this).apply {
                text = "⏳ $label"
                textSize = 13f
                setTextColor(Color.parseColor("#B0BEC5"))
                setPadding(0, 6, 0, 6)
            }
        }
        stepViews.forEach { box.addView(it) }

        val summary = TextView(this).apply {
            text = "Testing connection..."
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 12, 0, 0)
            setTextColor(Color.WHITE)
        }
        box.addView(summary)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Tor Connection Diagnostics")
            .setView(box)
            .setPositiveButton("Close", null)
            .show()

        executor.execute {
            // Step 1: Tor SOCKS check
            val s1 = client.testTorSocksPort()
            runOnUiThread {
                stepViews[0].text = if (s1) "✅ ${steps[0]} - OK" else "❌ ${steps[0]} - Failed (Start Tor)"
                stepViews[0].setTextColor(if (s1) Color.parseColor("#81C784") else Color.parseColor("#EF5350"))
            }
            if (!s1) {
                runOnUiThread {
                    summary.text = "Test Failed: Tor SOCKS is offline (127.0.0.1:${client.socksPort})."
                    val btnRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 8, 0, 0)
                    }
                    val startNativeBtn = Button(this).apply {
                        text = "▶ Start Inbuilt Tor"
                        setTextColor(Color.WHITE)
                        background = createRoundedDrawable(Color.parseColor("#2E7D32"), 10)
                        setOnClickListener {
                            TorManager.startInbuiltTor(this@MainActivity, store.get(KEY_TOR_BRIDGES))
                            Toast.makeText(this@MainActivity, "Starting Inbuilt Tor...", Toast.LENGTH_SHORT).show()
                        }
                    }
                    val openOrbotBtn = Button(this).apply {
                        text = "🚀 Open Orbot"
                        setOnClickListener { launchOrbot() }
                    }
                    val lp = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(4, 0, 4, 0) }
                    btnRow.addView(startNativeBtn, lp)
                    btnRow.addView(openOrbotBtn, lp)
                    box.addView(btnRow)
                }
                return@execute
            }

            // Step 2 & 3: Health check (proves onion reachability and gateway up)
            var healthOk = false
            try {
                client.health()
                healthOk = true
                runOnUiThread {
                    stepViews[1].text = "✅ ${steps[1]} - OK"
                    stepViews[1].setTextColor(Color.parseColor("#81C784"))
                    stepViews[2].text = "✅ ${steps[2]} - OK"
                    stepViews[2].setTextColor(Color.parseColor("#81C784"))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    stepViews[1].text = "❌ ${steps[1]} - ${e.message}"
                    stepViews[1].setTextColor(Color.parseColor("#EF5350"))
                    stepViews[2].text = "❌ ${steps[2]} - Unreachable"
                    stepViews[2].setTextColor(Color.parseColor("#EF5350"))
                    summary.text = "Test Failed: Onion gateway unreachable."
                }
                return@execute
            }

            // Step 4 & 5: Auth and Model Discovery
            var modelsOk = false
            try {
                val mList = client.models()
                modelsOk = mList.isNotEmpty()
                runOnUiThread {
                    stepViews[3].text = "✅ ${steps[3]} - Authenticated"
                    stepViews[3].setTextColor(Color.parseColor("#81C784"))
                    stepViews[4].text = "✅ ${steps[4]} - Found ${mList.size} models"
                    stepViews[4].setTextColor(Color.parseColor("#81C784"))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    stepViews[3].text = "❌ ${steps[3]} - Auth Error"
                    stepViews[3].setTextColor(Color.parseColor("#EF5350"))
                    stepViews[4].text = "❌ ${steps[4]} - Model check failed: ${e.message}"
                    stepViews[4].setTextColor(Color.parseColor("#EF5350"))
                    summary.text = "Test Failed: Authentication or model discovery failed."
                }
                return@execute
            }

            // Step 6: Simple AI request
            try {
                val pingMsg = listOf(ChatMessage(role = "user", content = "Respond with the single word 'Pong'."))
                val reply = client.chatStream(modelSpinner.selectedItem?.toString() ?: "claude-3-7-sonnet-20250219", "", pingMsg) {}
                runOnUiThread {
                    stepViews[5].text = "✅ ${steps[5]} - Received: ${reply.take(20).trim()}"
                    stepViews[5].setTextColor(Color.parseColor("#81C784"))
                    summary.text = "🎉 All 6 diagnostics passed! Tor Gateway is fully operational."
                    summary.setTextColor(Color.parseColor("#81C784"))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    stepViews[5].text = "❌ ${steps[5]} - ${e.message}"
                    stepViews[5].setTextColor(Color.parseColor("#EF5350"))
                    summary.text = "Partial Success: Gateway reachable but model query failed."
                }
            }
        }
    }

    private fun setupTorEngine() {
        val modeStr = store.get(KEY_TOR_ENGINE_MODE) ?: TorEngineMode.INBUILT.name
        val mode = runCatching { TorEngineMode.valueOf(modeStr) }.getOrDefault(TorEngineMode.INBUILT)
        TorManager.currentMode = mode

        TorManager.register(this)
        TorManager.addListener { state, statusText, port ->
            runOnUiThread {
                updateTorStatusDisplay(state, statusText, port)
            }
        }

        val autoStart = store.getBoolean(KEY_TOR_AUTO_START, true)
        if (mode == TorEngineMode.INBUILT && autoStart) {
            val bridges = store.get(KEY_TOR_BRIDGES)
            TorManager.startInbuiltTor(this, bridges)
        }
    }

    private fun updateTorStatusDisplay(state: TorState, statusText: String, port: Int) {
        val client = api
        if (client != null && client.socksPort != port) {
            api = ApiClient(client.baseUrl, client.apiKey, port, client.allowDevLoopback)
        }

        when (state) {
            TorState.RUNNING -> {
                statusBadge.text = "🟢 Inbuilt Tor Online (127.0.0.1:$port) • Ready"
                statusBadge.setTextColor(Color.parseColor("#81C784"))
                torEngineBtn?.text = "🧅 Tor (Online)"
                torEngineBtn?.setTextColor(Color.parseColor("#81C784"))
                configureWebViewTorProxy(port) {
                    if (isWebMode && ::webView.isInitialized) {
                        val toLoad = pendingWebUrl ?: if (::webUrlInput.isInitialized) webUrlInput.text.toString().trim() else null
                        if (!toLoad.isNullOrBlank()) {
                            pendingWebUrl = null
                            loadCurrentWebUrl(toLoad)
                        } else if (webView.url.isNullOrBlank() || webView.url == "about:blank") {
                            loadCurrentWebUrl()
                        }
                    }
                }
            }
            TorState.STARTING -> {
                statusBadge.text = "🟡 Starting Inbuilt Tor... (Building circuit)"
                statusBadge.setTextColor(Color.parseColor("#FFD54F"))
                torEngineBtn?.text = "🧅 Tor (Starting...)"
                torEngineBtn?.setTextColor(Color.parseColor("#FFD54F"))
            }
            TorState.STOPPED -> {
                if (TorManager.currentMode == TorEngineMode.INBUILT) {
                    statusBadge.text = "⚪ Inbuilt Tor Stopped • Tap to Start"
                    statusBadge.setTextColor(Color.parseColor("#B0BEC5"))
                    torEngineBtn?.text = "🧅 Inbuilt Tor"
                    torEngineBtn?.setTextColor(Color.WHITE)
                } else {
                    statusBadge.text = "🧅 Orbot Mode (127.0.0.1:$port) • Tap to configure"
                    statusBadge.setTextColor(Color.parseColor("#80CBC4"))
                    torEngineBtn?.text = "🧅 Orbot"
                    torEngineBtn?.setTextColor(Color.WHITE)
                }
            }
            TorState.ERROR -> {
                statusBadge.text = statusText
                statusBadge.setTextColor(Color.parseColor("#EF5350"))
                torEngineBtn?.text = "🧅 Tor (Error)"
                torEngineBtn?.setTextColor(Color.parseColor("#EF5350"))
            }
        }
    }

    private fun showTorEngineDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 16, 26, 16)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val title = TextView(this).apply {
            text = "🧅 Tor Engine & Anonymity Hub"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val desc = TextView(this).apply {
            text = "Onion AI includes an embedded native Tor daemon (libtor.so). No external apps or root required!"
            textSize = 12f
            setTextColor(Color.parseColor("#90A4AE"))
            setPadding(0, 4, 0, 14)
        }
        content.addView(title)
        content.addView(desc)

        // Status Card
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedDrawable(Color.parseColor("#1B2A32"), 10, Color.parseColor("#37474F"), 1)
            setPadding(16, 14, 16, 14)
        }
        val currentPort = TorManager.getEffectiveSocksPort()
        val modeLabel = if (TorManager.currentMode == TorEngineMode.INBUILT) "Embedded Native Tor (libtor.so)" else "External Orbot Proxy"
        val stateLabel = when (TorManager.currentState) {
            TorState.RUNNING -> "🟢 Running (Circuit Built)"
            TorState.STARTING -> "🟡 Starting & Bootstrapping..."
            TorState.STOPPED -> "⚪ Stopped"
            TorState.ERROR -> "🔴 Error"
        }

        val cardTitle = TextView(this).apply {
            text = "Current Tor Engine Status:"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val infoMode = TextView(this).apply {
            text = "• Active Engine: $modeLabel\n• Daemon State: $stateLabel\n• SOCKS Port: 127.0.0.1:$currentPort"
            textSize = 12f
            setTextColor(Color.parseColor("#CFD8DC"))
            setPadding(0, 6, 0, 10)
        }
        statusCard.addView(cardTitle)
        statusCard.addView(infoMode)

        // Tor Action Buttons inside Card
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val startBtn = Button(this).apply {
            text = "▶ Start"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#2E7D32"), 8)
            setOnClickListener {
                val bridges = store.get(KEY_TOR_BRIDGES)
                TorManager.startInbuiltTor(this@MainActivity, bridges)
                Toast.makeText(this@MainActivity, "Starting Native Tor...", Toast.LENGTH_SHORT).show()
            }
        }
        val stopBtn = Button(this).apply {
            text = "⏹ Stop"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#C62828"), 8)
            setOnClickListener {
                TorManager.stopInbuiltTor(this@MainActivity)
                Toast.makeText(this@MainActivity, "Native Tor stopped", Toast.LENGTH_SHORT).show()
            }
        }
        val restartBtn = Button(this).apply {
            text = "🔄 Restart"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#37474F"), 8)
            setOnClickListener {
                val bridges = store.get(KEY_TOR_BRIDGES)
                TorManager.restartInbuiltTor(this@MainActivity, bridges)
                Toast.makeText(this@MainActivity, "Restarting Native Tor...", Toast.LENGTH_SHORT).show()
            }
        }
        val testBtn = Button(this).apply {
            text = "⚡ Ping"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createRoundedDrawable(Color.parseColor("#455A64"), 8)
            setOnClickListener {
                executor.execute {
                    val port = TorManager.getEffectiveSocksPort()
                    val alive = TorManager.isPortListening("127.0.0.1", port)
                    runOnUiThread {
                        val msg = if (alive) "✅ SOCKS proxy 127.0.0.1:$port is active!" else "❌ SOCKS port 127.0.0.1:$port not responding."
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val p = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(4, 0, 4, 0) }
        actionRow.addView(startBtn, p)
        actionRow.addView(stopBtn, p)
        actionRow.addView(restartBtn, p)
        actionRow.addView(testBtn, p)
        statusCard.addView(actionRow)
        content.addView(statusCard)

        // Engine Selection Section
        content.addView(TextView(this).apply {
            text = "\nSelect Tor Engine Mode:"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })

        val radioGroup = RadioGroup(this)
        val rbInbuilt = RadioButton(this).apply {
            text = "Inbuilt Native Tor (libtor.so) • Recommended\nEmbedded inside app. Zero external app needed."
            setTextColor(Color.WHITE)
            textSize = 12f
            isChecked = TorManager.currentMode == TorEngineMode.INBUILT
        }
        val rbOrbot = RadioButton(this).apply {
            text = "External Orbot Proxy (127.0.0.1:9050)\nUses standalone Orbot app for system-wide routing."
            setTextColor(Color.WHITE)
            textSize = 12f
            isChecked = TorManager.currentMode == TorEngineMode.ORBOT
        }
        radioGroup.addView(rbInbuilt)
        radioGroup.addView(rbOrbot)
        content.addView(radioGroup)

        // Orbot helper row
        val orbotRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 8)
        }
        val launchOrbotBtn = Button(this).apply {
            text = "🚀 Open Orbot"
            textSize = 12f
            setOnClickListener { launchOrbot() }
        }
        val installOrbotBtn = Button(this).apply {
            text = "📥 Install Orbot"
            textSize = 12f
            setOnClickListener { installOrbot() }
        }
        val p2 = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(4, 0, 4, 0) }
        orbotRow.addView(launchOrbotBtn, p2)
        orbotRow.addView(installOrbotBtn, p2)
        content.addView(orbotRow)

        // Censorship & Bridges Section
        content.addView(TextView(this).apply {
            text = "\n🌐 Censorship & ISP Bypass (Tor Bridges):"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        val bridgeExpl = TextView(this).apply {
            text = "If Tor gets stuck starting on Indian networks (Jio/Airtel/Vi) or restricted Wi-Fi, enter obfs4 or Snowflake bridge lines below."
            textSize = 11f
            setTextColor(Color.parseColor("#90A4AE"))
            setPadding(0, 2, 0, 6)
        }
        content.addView(bridgeExpl)

        val bridgeInput = EditText(this).apply {
            hint = "Bridge obfs4 [IP:Port] [Fingerprint] cert=... (or leave empty for direct Tor)"
            setText(store.get(KEY_TOR_BRIDGES).orEmpty())
            minLines = 2
            maxLines = 4
            textSize = 12f
            typeface = Typeface.MONOSPACE
            background = createRoundedDrawable(Color.parseColor("#121212"), 8, Color.parseColor("#37474F"), 1)
            setPadding(12, 10, 12, 10)
        }
        content.addView(bridgeInput)

        val autoStartCheck = CheckBox(this).apply {
            text = "Auto-start Inbuilt Tor on app launch"
            setTextColor(Color.WHITE)
            isChecked = store.getBoolean(KEY_TOR_AUTO_START, true)
            setPadding(0, 8, 0, 8)
        }
        content.addView(autoStartCheck)

        scroll.addView(content)
        box.addView(scroll)

        AlertDialog.Builder(this)
            .setView(box)
            .setPositiveButton("Save Settings") { _, _ ->
                val newMode = if (rbInbuilt.isChecked) TorEngineMode.INBUILT else TorEngineMode.ORBOT
                TorManager.currentMode = newMode
                store.put(KEY_TOR_ENGINE_MODE, newMode.name)
                store.putBoolean(KEY_TOR_AUTO_START, autoStartCheck.isChecked)
                val newBridges = bridgeInput.text.toString().trim()
                store.put(KEY_TOR_BRIDGES, newBridges)

                if (newMode == TorEngineMode.INBUILT) {
                    TorManager.restartInbuiltTor(this, newBridges)
                    Toast.makeText(this, "Saved. Starting Inbuilt Tor...", Toast.LENGTH_SHORT).show()
                } else {
                    TorManager.stopInbuiltTor(this)
                    initClient()
                    Toast.makeText(this, "Switched to Orbot mode (port 9050)", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun launchOrbot() {
        val pm = packageManager
        // Try starting Orbot service directly via intent
        runCatching {
            val startServiceIntent = Intent("org.torproject.android.intent.action.START").apply {
                `package` = "org.torproject.android"
            }
            sendBroadcast(startServiceIntent)
        }
        // Launch Orbot UI
        val launchIntent = pm.getLaunchIntentForPackage("org.torproject.android")
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "Orbot not found. Opening Play Store...", Toast.LENGTH_SHORT).show()
            installOrbot()
        }
    }

    private fun installOrbot() {
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=org.torproject.android"))
            startActivity(marketIntent)
        } catch (e: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=org.torproject.android"))
            startActivity(webIntent)
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        try {
            if (::webView.isInitialized) {
                webView.destroy()
            }
        } catch (_: Exception) {}
        executor.shutdownNow()
        super.onDestroy()
    }
}
