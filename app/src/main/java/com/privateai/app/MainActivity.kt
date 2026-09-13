package com.privateai.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import android.content.*
import android.net.Uri
import android.provider.Settings
import java.io.*
import java.net.*
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.speech.tts.TextToSpeech
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS = "private_ai"
private const val KEY_BLOB = "history_blob"
private const val KEY_CONFIG = "config_blob"

data class ChatMessage(val role: String, val content: String)

class SecureStore(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val alias = "PrivateAI_AES"

    private fun key(): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
         .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
         .setUserAuthenticationRequired(false)
         .build())
        return kg.generateKey()
    }

    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val blob = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        prefs.edit().putString(name, blob).apply()
    }

    fun get(name: String): String? = runCatching {
        val str = prefs.getString(name, null) ?: return null
        val raw = Base64.decode(str, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, 12)
        val data = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, iv))
        String(cipher.doFinal(data))
    }.getOrNull()
}

class ApiClient(private val baseUrl: String, private val apiKey: String, private val socksPort: Int) {
    private fun open(url: String, method: String): HttpURLConnection {
        val u = URL(url)
        require(u.protocol.equals("http", true) || u.protocol.equals("https", true)) { "Unsupported URL scheme: ${u.protocol}" }
        val host = u.host ?: ""
        require(host.endsWith(".onion", ignoreCase = true)) {
            "Tor-only security violation: '$host' is not a .onion hidden service. Clear-net connections are strictly rejected."
        }
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        return (u.openConnection(proxy) as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 60000
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("x-api-key", apiKey)
            }
            setRequestProperty("Content-Type", "application/json")
        }
    }

    fun models(): List<String> {
        val base = baseUrl.trimEnd('/')
        val endpoints = listOf("$base/v1/models", "$base/models")
        var lastErr: Exception? = null

        for (endpoint in endpoints) {
            try {
                val c = open(endpoint, "GET")
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
                val list = (0 until arr.length()).mapNotNull {
                    arr.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank)
                }
                if (list.isNotEmpty()) return list
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw (lastErr ?: IOException("No models found"))
    }

    fun chat(model: String, messages: List<ChatMessage>, onToken: (String) -> Unit): String {
        val base = baseUrl.trimEnd('/')
        // Try Anthropic /v1/messages first, fall back to /chat/completions
        return try {
            chatAnthropicMessages(base, model, messages, onToken)
        } catch (e: Exception) {
            chatOpenAiCompletions(base, model, messages, onToken)
        }
    }

    private fun chatAnthropicMessages(base: String, model: String, messages: List<ChatMessage>, onToken: (String) -> Unit): String {
        val c = open("$base/v1/messages", "POST")
        c.doOutput = true
        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("max_tokens", 4096)
            .put("messages", JSONArray().apply {
                messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            }).toString()

        c.outputStream.use { it.write(body.toByteArray()) }
        if (c.responseCode !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            c.disconnect()
            error("HTTP ${c.responseCode}: $err")
        }

        val out = StringBuilder()
        c.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) return@forEach
                val payload = trimmed.removePrefix("data:").trim()
                if (payload == "[DONE]") return@forEach
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

    private fun chatOpenAiCompletions(base: String, model: String, messages: List<ChatMessage>, onToken: (String) -> Unit): String {
        val c = open("$base/chat/completions", "POST")
        c.doOutput = true
        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("messages", JSONArray().apply {
                messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            }).toString()

        c.outputStream.use { it.write(body.toByteArray()) }
        if (c.responseCode !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            c.disconnect()
            error("HTTP ${c.responseCode}: $err")
        }

        val out = StringBuilder()
        c.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) return@forEach
                val payload = trimmed.removePrefix("data:").trim()
                if (payload == "[DONE]") return@forEach
                runCatching {
                    val delta = JSONObject(payload).optJSONArray("choices")
                        ?.optJSONObject(0)?.optJSONObject("delta")
                        ?.optString("content").orEmpty()
                    if (delta.isNotEmpty()) {
                        out.append(delta)
                        onToken(delta)
                    }
                }
            }
        }
        c.disconnect()
        return out.toString()
    }

    fun image(model: String, prompt: String): ByteArray {
        val base = baseUrl.trimEnd('/')
        val c = open("$base/v1/images/generations", "POST")
        c.doOutput = true
        val body = JSONObject().put("model", model).put("prompt", prompt).put("response_format", "b64_json").toString()
        c.outputStream.use { it.write(body.toByteArray()) }
        if (c.responseCode !in 200..299) error("Image HTTP ${c.responseCode}")
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val data = JSONObject(text).optJSONArray("data")?.optJSONObject(0)?.optString("b64_json")
            ?: error("Image response missing b64_json")
        return Base64.decode(data, Base64.DEFAULT)
    }
}

class MainActivity : Activity() {
    private lateinit var store: SecureStore
    private lateinit var root: LinearLayout
    private lateinit var messagesBox: LinearLayout
    private lateinit var input: EditText
    private lateinit var modelSpinner: Spinner
    private lateinit var status: TextView
    private var api: ApiClient? = null
    private val messages = mutableListOf<ChatMessage>()
    private val executor = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureStore(this)
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
        buildUi()
        restoreHistory()
        loadConfig()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val title = TextView(this).apply {
            text = "Private AI  •  Tor Gateway"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }
        status = TextView(this).apply {
            text = "Not connected"
            textSize = 13f
            setPadding(0, 0, 0, 12)
        }
        modelSpinner = Spinner(this).apply {
            setPadding(0, 0, 0, 12)
        }
        messagesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(messagesBox) }
        input = EditText(this).apply {
            hint = "Enter message for AI..."
            minLines = 2
            maxLines = 5
        }
        val send = Button(this).apply { text = "Send" }
        val settings = Button(this).apply { text = "Custom API / Tor Config" }
        val speak = Button(this).apply { text = "Speak Last Reply" }
        val clear = Button(this).apply { text = "Clear History" }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(send, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(speak, LinearLayout.LayoutParams(0, -2, 1f))

        val bar2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar2.addView(settings, LinearLayout.LayoutParams(0, -2, 1f))
        bar2.addView(clear, LinearLayout.LayoutParams(0, -2, 1f))

        root.addView(title)
        root.addView(status)
        root.addView(modelSpinner)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(input)
        root.addView(bar)
        root.addView(bar2)
        setContentView(root)

        send.setOnClickListener { sendMessage() }
        speak.setOnClickListener {
            messages.lastOrNull { it.role == "assistant" }?.content?.let {
                tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "last")
            }
        }
        settings.setOnClickListener { showSettings() }
        clear.setOnClickListener {
            messages.clear()
            messagesBox.removeAllViews()
            store.put(KEY_BLOB, "[]")
            status.text = "History cleared"
        }
    }

    private fun addBubble(text: String, role: String) {
        val v = TextView(this).apply {
            this.text = if (role == "user") "You\n$text" else "AI\n$text"
            textSize = 15f
            setPadding(20, 16, 20, 16)
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
        }
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 8, 0, 8)
        }
        messagesBox.addView(v, params)
        (messagesBox.parent as? ScrollView)?.post { (messagesBox.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
    }

    private fun restoreHistory() {
        val saved = store.get(KEY_BLOB) ?: return
        runCatching {
            val arr = JSONArray(saved)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val role = obj.optString("role", "user")
                val content = obj.optString("content", "")
                if (content.isNotEmpty()) {
                    messages += ChatMessage(role, content)
                    addBubble(content, role)
                }
            }
        }
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val client = api ?: run { showSettings(); return }
        val model = modelSpinner.selectedItem?.toString().orEmpty()
        if (model.isBlank()) { status.text = "Select a model"; return }
        input.setText("")
        messages += ChatMessage("user", text)
        addBubble(text, "user")
        val aiBubble = TextView(this).apply {
            this.text = "AI\n"
            textSize = 15f
            setPadding(20, 16, 20, 16)
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
        }
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 8, 0, 8)
        }
        messagesBox.addView(aiBubble, params)

        executor.execute {
            runCatching {
                val reply = client.chat(model, messages) { token ->
                    runOnUiThread { aiBubble.append(token) }
                }
                messages += ChatMessage("assistant", reply)
                store.put(KEY_BLOB, JSONArray(messages.map { JSONObject().put("role", it.role).put("content", it.content) }).toString())
            }.onFailure { e ->
                runOnUiThread { status.text = "Error: ${e.message}" }
            }
        }
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        val url = EditText(this).apply { hint = "https://your-service.onion"; setText(store.get("url").orEmpty()) }
        val key = EditText(this).apply { hint = "Gateway API Key (optional)"; setText(store.get("key").orEmpty()); inputType = 0x00000081 }
        val port = EditText(this).apply { hint = "Tor SOCKS port (e.g. 9050)"; setText(store.get("port") ?: "9050"); inputType = 2 }
        val note = TextView(this).apply {
            text = "Strict Tor Security: Only .onion endpoints are permitted over local SOCKS proxy (127.0.0.1). Clear-net requests are strictly blocked."
            textSize = 12f
            setPadding(0, 8, 0, 0)
        }
        box.addView(url); box.addView(key); box.addView(port); box.addView(note)
        AlertDialog.Builder(this).setTitle("Custom Onion Gateway").setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Connect") { _, _ ->
                val p = port.text.toString().toIntOrNull() ?: 9050
                val targetUrl = url.text.toString().trim()
                val targetKey = key.text.toString().trim()
                store.put("url", targetUrl)
                store.put("key", targetKey)
                store.put("port", p.toString())
                
                try {
                    api = ApiClient(targetUrl, targetKey, p)
                    status.text = "Connecting via Tor SOCKS..."
                    executor.execute {
                        runCatching { api!!.models() }.onSuccess { models ->
                            runOnUiThread {
                                modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
                                status.text = "Connected • ${models.size} model(s) available"
                            }
                        }.onFailure { e ->
                            runOnUiThread { status.text = "Connection failed: ${e.message}" }
                        }
                    }
                } catch (e: Exception) {
                    status.text = "Config error: ${e.message}"
                }
            }.show()
    }

    private fun loadConfig() {
        val url = store.get("url").orEmpty()
        if (url.isNotBlank()) {
            val key = store.get("key").orEmpty()
            val port = store.get("port")?.toIntOrNull() ?: 9050
            runCatching {
                api = ApiClient(url, key, port)
                executor.execute {
                    runCatching { api!!.models() }.onSuccess { models ->
                        runOnUiThread {
                            modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
                            status.text = "Connected • ${models.size} model(s) available"
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        executor.shutdownNow()
        super.onDestroy()
    }
}
