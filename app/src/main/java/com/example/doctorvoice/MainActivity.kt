package com.example.doctorvoice

import android.content.Intent
import com.example.doctorvoice.BuildConfig

import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button

import android.speech.RecognizerIntent
import android.provider.MediaStore
import android.graphics.Bitmap

import android.Manifest
import android.content.pm.PackageManager

import android.widget.ImageView
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.crashlytics.buildtools.reloc.org.apache.commons.codec.binary.Base64
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.IOException
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Locale


class MainActivity : AppCompatActivity() {

    private val REQUEST_PERMISSION_CODE = 100

    private val REQUEST_IMAGE_CAPTURE = 1
    private val REQUEST_SPEECH_INPUT = 2

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )


    private lateinit var imageView: ImageView
    private lateinit var translateSpeakButton: Button
    private lateinit var voiceSpinner: Spinner

    private lateinit var promptInput: EditText
    private lateinit var responseText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var speakButton: Button
    private lateinit var languageSpinner: Spinner
    private var selectedImageBase64: String? = null
    private var tts: TextToSpeech? = null
    private var selectedLocale: Locale = Locale.US
    private var voiceList: List<Voice> = listOf()

    private val selectImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            imageView.setImageURI(it)
            selectedImageBase64 = encodeImageToBase64(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        translateSpeakButton = findViewById(R.id.translateSpeakButton)

        promptInput = findViewById(R.id.promptInput)
        imageView = findViewById(R.id.imagePreview)
        responseText = findViewById(R.id.responseText)
        progressBar = findViewById(R.id.progressBar)
        speakButton = findViewById(R.id.speakButton)
        languageSpinner = findViewById(R.id.languageSpinner)

        // Init TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                setupVoiceSpinner()
            }
        }
        translateSpeakButton.setOnClickListener {
            val text = responseText.text.toString()
            translateToUrdu(text) { urduText ->
                tts?.language = Locale("ur", "PK")
                tts?.voices?.find { it.locale.language == "ur" }?.let { tts?.voice = it }
                tts?.speak(urduText, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }




        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(adapter: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = languageSpinner.selectedItem.toString()
                setTTSLanguage(selected)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }


        findViewById<Button>(R.id.selectImageButton).setOnClickListener {
            selectImageLauncher.launch("image/*")
        }

        findViewById<Button>(R.id.sendButton).setOnClickListener {
            val prompt = promptInput.text.toString()
            selectedImageBase64?.let {
                sendToGroq(prompt, it)
            } ?: Toast.makeText(this, "Select an image first", Toast.LENGTH_SHORT).show()
        }


        findViewById<Button>(R.id.voiceInputButton).setOnClickListener {
            if (checkAndRequestPermissions()) {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                intent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                startActivityForResult(intent, REQUEST_SPEECH_INPUT)
            }
        }

        findViewById<Button>(R.id.cameraButton).setOnClickListener {

         if(checkAndRequestPermissions()) {
             val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
             if (cameraIntent.resolveActivity(packageManager) != null) {
                 startActivityForResult(cameraIntent, REQUEST_IMAGE_CAPTURE)
             }
         }
        }


        speakButton.setOnClickListener {
            val text = responseText.text.toString()
            if (text.isNotEmpty()) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            }


        }

        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(adapterView: AdapterView<*>, view: View?, position: Int, id: Long) {
                tts?.voice = voiceList[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK) {
            val imageBitmap = data?.extras?.get("data") as Bitmap
            imageView.setImageBitmap(imageBitmap)
            selectedImageBase64 = encodeBitmapToBase64(imageBitmap)
        } else if (requestCode == REQUEST_SPEECH_INPUT && resultCode == RESULT_OK) {
            val spokenText = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)
            promptInput.setText(spokenText)
        }
    }

    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
        val byteArray = stream.toByteArray()
        return android.util.Base64.encodeToString(byteArray, android.util.Base64.NO_WRAP)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_PERMISSION_CODE) {
            val deniedPermissions = permissions.zip(grantResults.toTypedArray())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }

            if (deniedPermissions.isNotEmpty()) {
                Toast.makeText(this, "Permissions denied: ${deniedPermissions.joinToString { it.first }}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun checkAndRequestPermissions(): Boolean {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        return if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), REQUEST_PERMISSION_CODE)
            false
        } else {
            true
        }
    }



    private fun setTTSLanguage(language: String) {
        selectedLocale = if (language == "Urdu") Locale("ur", "PK") else Locale.US
        val result = tts?.setLanguage(selectedLocale)

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Toast.makeText(this, "Selected language not supported", Toast.LENGTH_SHORT).show()
        } else {
            // Try best available voice
            tts?.voices?.find { it.locale == selectedLocale }?.let { voice ->
                tts?.voice = voice
            }
        }
    }


    private fun setupVoiceSpinner() {
        voiceList = tts?.voices?.filter { it.locale.language == "en" }?.toList() ?: listOf()
        val voiceNames = voiceList.map { it.name }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voiceNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        voiceSpinner.adapter = adapter

        // Set default voice
        if (voiceList.isNotEmpty()) {
            tts?.voice = voiceList[0]
        }
    }
    private fun translateToUrdu(text: String, callback: (String) -> Unit) {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ur&dt=t&q=${URLEncoder.encode(text, "UTF-8")}"
        val client = OkHttpClient()

        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { callback("ترجمہ ناکام ہو گیا") }
            }

            override fun onResponse(call: Call, response: Response) {
                val json = JSONArray(response.body?.string())
                val translated = json.getJSONArray(0).getJSONArray(0).getString(0)
                runOnUiThread { callback(translated)
                    findViewById<TextView>(R.id.urduTranslationText).text = translated
                }
            }
        })
    }

    private fun encodeImageToBase64(uri: Uri): String {
        val inputStream = contentResolver.openInputStream(uri)
        val bytes = inputStream?.readBytes()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun sendToGroq(prompt: String, base64Image: String) {
        val apiKey =GROQ_API_KEY


        val url = "https://api.groq.com/openai/v1/chat/completions"
        val client = OkHttpClient()

        runOnUiThread {
            progressBar.visibility = View.VISIBLE
            speakButton.visibility = View.GONE
        }

        val json = JSONObject().apply {
            put("model", "meta-llama/llama-4-scout-17b-16e-instruct")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$base64Image")
                            })
                        })
                    })
                })
            })
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    responseText.text = "Error: ${e.message}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val json = JSONObject(response.body?.string() ?: "{}")
                val content = json
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?: "No response"

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    speakButton.visibility = View.VISIBLE
                    responseText.text = content
                }
            }
        })
    }

    override fun onStop() {
        tts?.shutdown()
        super.onStop()


    }
    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

