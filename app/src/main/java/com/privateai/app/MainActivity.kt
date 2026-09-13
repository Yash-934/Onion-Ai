package com.privateai.app

import android.app.Activity
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
        val raw = Base64.decode(prefs.getString(name, null), Base64.NO_WRAP)
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
        require(u.protocol == "http" || u.protocol == "https") { "Unsupported URL scheme" }
        require(u.host.endsWith(".onion", true)) { "Tor-only mode: endpoint must be a .onion host" }
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        return (u.openConnection(proxy) as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 60000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
    }

    fun models(): List<String> {
        val c = open(baseUrl.trimEnd('/') + "/models", "GET")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
    }

    fun chat(model: String, messages: List<ChatMessage>, onToken: (String) -> Unit): String {
        val c = open(baseUrl.trimEnd('/') + "/chat/completions", "POST")
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
                if (!line.startsWith("data:")) return@forEach
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") return@forEach
                runCatching {
                    val delta = JSONObject(payload).optJSONArray("choices")
                        ?.optJSONObject(0)?.optJSONObject("delta")
                        ?.optString("content").orEmpty()
                    if (delta.isNotEmpty()) { out.append(delta); onToken(delta) }
                }
            }
        }
        c.disconnect()
        return out.toString()
    }

    fun image(model: String, prompt: String): ByteArray {
        val c = open(baseUrl.trimEnd('/') + "/images/generations", "POST")
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
        loadConfig()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20,20,20,20) }
        val title = TextView(this).apply {
            text = "Private AI  •  Tor-only"
            textSize = 22f; typeface = Typeface.DEFAULT_BOLD
        }
        status = TextView(this).apply { text = "Not connected"; textSize = 12f }
        modelSpinner = Spinner(this)
        messagesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(messagesBox) }
        input = EditText(this).apply { hint = "Message"; minLines = 2; maxLines = 6 }
        val send = Button(this).apply { text = "Send" }
        val settings = Button(this).apply { text = "Custom API / Privacy" }
        val speak = Button(this).apply { text = "Speak last reply" }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(send, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(speak, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(title); root.addView(status); root.addView(modelSpinner)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(input); root.addView(bar); root.addView(settings)
        setContentView(root)
        send.setOnClickListener { sendMessage() }
        speak.setOnClickListener {
            messages.lastOrNull { it.role == "assistant" }?.content?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "last") }
        }
        settings.setOnClickListener { showSettings() }
    }

    private fun addBubble(text: String, role: String) {
        val v = TextView(this).apply {
            this.text = if (role == "user") "You\n$text" else "AI\n$text"
            textSize = 16f; setPadding(16,14,16,14)
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
        }
        messagesBox.addView(v)
        (messagesBox.parent as? ScrollView)?.post { (messagesBox.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val client = api ?: run { showSettings(); return }
        val model = modelSpinner.selectedItem?.toString().orEmpty()
        if (model.isBlank()) { status.text = "Select a model"; return }
        input.setText("")
        messages += ChatMessage("user", text); addBubble(text, "user")
        val aiBubble = TextView(this).apply { text = "AI\n"; textSize = 16f; setPadding(16,14,16,14) }
        messagesBox.addView(aiBubble)
        executor.execute {
            runCatching {
                val reply = client.chat(model, messages) { token ->
                    runOnUiThread { aiBubble.append(token) }
                }
                messages += ChatMessage("assistant", reply)
                store.put(KEY_BLOB, JSONArray(messages.map { JSONObject().put("role", it.role).put("content", it.content) }).toString())
            }.onFailure { e -> runOnUiThread { status.text = "Error: ${e.message}" } }
        }
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20,10,20,10) }
        val url = EditText(this).apply { hint = "https://your-service.onion"; setText(store.get("url").orEmpty()) }
        val key = EditText(this).apply { hint = "API key (optional)"; setText(store.get("key").orEmpty()); inputType = 0x00000081 }
        val port = EditText(this).apply { hint = "Tor SOCKS port"; setText(store.get("port") ?: "9050"); inputType = 2 }
        val note = TextView(this).apply {
            text = "Strict mode: only .onion endpoints are accepted and requests use SOCKS at 127.0.0.1. No clearnet fallback."
        }
        box.addView(url); box.addView(key); box.addView(port); box.addView(note)
        AlertDialog.Builder(this).setTitle("Custom API").setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Connect") { _, _ ->
                val p = port.text.toString().toIntOrNull() ?: 9050
                store.put("url", url.text.toString().trim()); store.put("key", key.text.toString())
                store.put("port", p.toString())
                api = ApiClient(url.text.toString().trim(), key.text.toString(), p)
                status.text = "Connecting via Tor…"
                executor.execute {
                    runCatching { api!!.models() }.onSuccess { models ->
                        runOnUiThread {
                            modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
                            status.text = "Connected • ${models.size} model(s)"
                        }
                    }.onFailure { e -> runOnUiThread { status.text = "Connection failed: ${e.message}" } }
                }
            }.show()
    }

    private fun loadConfig() {
        val url = store.get("url").orEmpty()
        if (url.isNotBlank()) {
            api = ApiClient(url, store.get("key").orEmpty(), store.get("port")?.toIntOrNull() ?: 9050)
            executor.execute {
                runCatching { api!!.models() }.onSuccess { models ->
                    runOnUiThread {
                        modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
                        status.text = "Connected • ${models.size} model(s)"
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
