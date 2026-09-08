package com.example.rag

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.ai.client.generativeai.Chat
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private var pdfText: String = ""
    private var pdfChunks = listOf<String>()
    private lateinit var generativeModel: GenerativeModel

    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var btnSummary: Button
    private var chatSession: Chat? = null

    private val messageList = mutableListOf<Message>()
    private lateinit var adapter: MessageAdapter
    private lateinit var recyclerView: RecyclerView

    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                val spokenText = matches[0]
                val etQuestion = findViewById<EditText>(R.id.etQuestion)
                etQuestion.setText(spokenText)
                etQuestion.setSelection(spokenText.length)
            }
        }
    }

    private val pdfPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            progressBar.visibility = android.view.View.VISIBLE
            addAiMessage("Lecture du PDF en cours...")
            lireFichierPdf(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val navigationBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val imeInset = insets.getInsets(WindowInsetsCompat.Type.ime())
            val statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars())

            val bottomPadding = maxOf(navigationBarInset.bottom, imeInset.bottom)

            v.setPadding(
                statusBarInset.left,
                statusBarInset.top,
                statusBarInset.right,
                bottomPadding
            )

            insets
        }

        recyclerView = findViewById(R.id.recyclerView)
        progressBar = findViewById(R.id.progressBar)
        btnSummary = findViewById(R.id.btnSummary)
        adapter = MessageAdapter(messageList)
        recyclerView.adapter = adapter
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        recyclerView.layoutManager = layoutManager

        val etQuestion = findViewById<EditText>(R.id.etQuestion)
        val btnSend = findViewById<Button>(R.id.btnSend)
        val btnSelectPdf = findViewById<Button>(R.id.btnSelectPdf)
        val btnClear = findViewById<Button>(R.id.btnClear)
        val btnMic = findViewById<ImageButton>(R.id.btnMic)

        generativeModel = GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = BuildConfig.GCP_API_KEY
        )

        addAiMessage("Bonjour ! Importez un PDF, puis posez-moi vos questions.")

        btnSelectPdf.setOnClickListener {
            pdfPickerLauncher.launch("application/pdf")
        }

        btnMic.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Parlez dans la langue de votre choix...")
            }

            try {
                speechLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "La reconnaissance vocale n'est pas disponible sur cet appareil", Toast.LENGTH_SHORT).show()
            }
        }

        btnClear.setOnClickListener {
            messageList.clear()
            adapter.notifyDataSetChanged()

            if (pdfText.isNotEmpty()) {
                chatSession = generativeModel.startChat()
                addAiMessage("Conversation effacée. Le document est toujours en mémoire.")
            } else {
                chatSession = null
                btnSummary.visibility = android.view.View.GONE
                addAiMessage("Conversation effacée. Importez un PDF pour commencer.")
            }
        }

        btnSummary.setOnClickListener {
            if (pdfText.isNotEmpty()) {
                val summaryQuestion = "Fais-moi un résumé structuré de ce document en 3 points précis : 1. Sujet principal, 2. Points clés, 3. Conclusion."

                messageList.add(Message("📊 Résumé du document demandé", isUser = true))
                adapter.notifyItemInserted(messageList.size - 1)
                recyclerView.scrollToPosition(messageList.size - 1)

                askGemini(summaryQuestion, useFullText = true)
            }
        }

        btnSend.setOnClickListener {
            val userQuestion = etQuestion.text.toString()

            if (pdfText.isEmpty()) {
                Toast.makeText(this, "Veuillez d'abord importer un PDF", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (userQuestion.isNotEmpty()) {
                messageList.add(Message(userQuestion, isUser = true))
                adapter.notifyItemInserted(messageList.size - 1)
                recyclerView.scrollToPosition(messageList.size - 1)
                etQuestion.text.clear()

                askGeminiWithRag(userQuestion)
            }
        }
    }

    private fun addAiMessage(text: String) {
        messageList.add(Message(text, isUser = false))
        adapter.notifyItemInserted(messageList.size - 1)
        recyclerView.scrollToPosition(messageList.size - 1)
    }

    private fun splitTextIntoChunks(text: String, chunkSize: Int = 1500): List<String> {
        val chunks = mutableListOf<String>()
        var startIndex = 0
        while (startIndex < text.length) {
            val endIndex = minOf(startIndex + chunkSize, text.length)
            chunks.add(text.substring(startIndex, endIndex))
            startIndex = endIndex
        }
        return chunks
    }

    private fun lireFichierPdf(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            var extractedText = ""
            try {
                val inputStream: InputStream? = contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val reader = PdfReader(inputStream)
                    for (i in 1..reader.numberOfPages) {
                        extractedText += PdfTextExtractor.getTextFromPage(reader, i) + "\n"
                    }
                    reader.close()
                    inputStream.close()
                }
            } catch (e: Exception) {
                Log.e("RAG_DEBUG", "Erreur lecture PDF : ${e.message}")
            }

            withContext(Dispatchers.Main) {
                progressBar.visibility = android.view.View.GONE

                if (extractedText.isNotEmpty()) {
                    pdfText = extractedText
                    pdfChunks = splitTextIntoChunks(pdfText)

                    val systemPrompt = """
                        Tu es un assistant expert. Tu dois répondre aux questions en te basant UNIQUEMENT sur les extraits de documents fournis. 
                        Si la réponse n'est pas dans le document, dis "Je ne trouve pas l'information".
                    """.trimIndent()

                    generativeModel = GenerativeModel(
                        modelName = "gemini-1.5-flash",
                        apiKey = BuildConfig.GCP_API_KEY,
                        systemInstruction = content { text(systemPrompt) }
                    )

                    chatSession = generativeModel.startChat()
                    btnSummary.visibility = android.view.View.VISIBLE

                    addAiMessage("✅ PDF chargé ! (${pdfText.length} caractères, ${pdfChunks.size} segments analysés). Tu peux cliquer sur 'Résumer' ou poser tes questions.")
                } else {
                    addAiMessage("❌ Impossible de lire ce document.")
                }
            }
        }
    }

    private fun askGeminiWithRag(question: String) {
        val relevantChunk = pdfChunks.maxByOrNull { chunk ->
            question.split(" ").count { word ->
                word.length > 3 && chunk.contains(word, ignoreCase = true)
            }
        } ?: pdfChunks.firstOrNull() ?: pdfText

        val targetedPrompt = """
            --- EXTRAIT DU DOCUMENT CONCERNÉ ---
            $relevantChunk
            -------------------------------------
            Question de l'utilisateur : $question
        """.trimIndent()

        askGemini(targetedPrompt, useFullText = false)
    }

    private fun askGemini(promptToSend: String, useFullText: Boolean) {
        messageList.add(Message("L'IA réfléchit...", isUser = false))
        val loadingPosition = messageList.size - 1
        adapter.notifyItemInserted(loadingPosition)
        recyclerView.scrollToPosition(loadingPosition)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val finalPrompt = if (useFullText) {
                    """
                    --- DOCUMENT COMPLET ---
                    $pdfText
                    ------------------------
                    $promptToSend
                    """.trimIndent()
                } else {
                    promptToSend
                }

                val response = chatSession?.sendMessage(finalPrompt)
                val answer = response?.text ?: "Aucune réponse"

                withContext(Dispatchers.Main) {
                    messageList[loadingPosition] = Message(answer, isUser = false)
                    adapter.notifyItemChanged(loadingPosition)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    messageList[loadingPosition] = Message("Erreur : ${e.message}", isUser = false)
                    adapter.notifyItemChanged(loadingPosition)
                }
            }
        }
    }
}