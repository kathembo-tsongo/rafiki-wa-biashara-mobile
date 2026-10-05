package com.example.llama

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // Android views
    private lateinit var ggufTv: TextView
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var userActionFab: FloatingActionButton

    // Arm AI Chat inference engine
    private lateinit var engine: InferenceEngine
    private var generationJob: Job? = null

    // Conversation states
    private var isModelReady = false
    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var conversationStore: ConversationStore
    private var currentConversationId: String = UUID.randomUUID().toString()
    private val historyLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = result.data?.getStringExtra(HistoryActivity.EXTRA_CONVERSATION_ID) ?: return@registerForActivityResult
        val conversation = conversationStore.load(id) ?: return@registerForActivityResult
        messages.clear()
        messages.addAll(conversation.messages)
        messageAdapter.notifyDataSetChanged()
        currentConversationId = conversation.id
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) { com.example.llama.rafiki.Rafiki.init(this@MainActivity) }
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        com.example.llama.rafiki.PilotConsent.showIfNeeded(this)
        // View model boilerplate and state management is out of this basic sample's scope
        onBackPressedDispatcher.addCallback { Log.w(TAG, "Ignore back press for simplicity") }

        // Find views
        ggufTv = findViewById(R.id.gguf)
        messagesRv = findViewById(R.id.messages)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messageAdapter = MessageAdapter(messages, io.noties.markwon.Markwon.create(this))
        messagesRv.adapter = messageAdapter
        userInputEt = findViewById(R.id.user_input)
        userActionFab = findViewById(R.id.fab)
        conversationStore = ConversationStore(applicationContext)
        findViewById<android.widget.ImageButton>(R.id.new_chat_button).setOnClickListener {
            if (messages.isNotEmpty()) {
                conversationStore.save(messages.toList(), currentConversationId)
            }
            messages.clear()
            messageAdapter.notifyDataSetChanged()
            currentConversationId = UUID.randomUUID().toString()
            com.example.llama.rafiki.Rafiki.responder(this).resetContext()
        }
        findViewById<android.widget.ImageButton>(R.id.history_button).setOnClickListener {
            if (messages.isNotEmpty()) {
                conversationStore.save(messages.toList(), currentConversationId)
            }
            historyLauncher.launch(android.content.Intent(this, HistoryActivity::class.java))
        }

        // Pilot: long-press the history button for the researcher menu (export log, withdraw).
        findViewById<android.widget.ImageButton>(R.id.history_button).setOnLongClickListener {
            com.example.llama.rafiki.PilotTools.showMenu(this)
            true
        }

        // Arm AI Chat initialization
        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)
        }

        // Upon CTA button tapped
        userActionFab.setOnClickListener {
            if (isModelReady) {
                // If model is ready, validate input and send to engine
                handleUserInput()
            } else {
                // Otherwise, prompt user to select a GGUF metadata on the device
                getContent.launch(arrayOf("*/*"))
            }
        }
    }

    private val getContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        Log.i(TAG, "Selected file uri:\n $uri")
        uri?.let { handleSelectedModel(it) }
    }

    /**
     * Handles the file Uri from [getContent] result
     */
    private fun handleSelectedModel(uri: Uri) {
        // Update UI states
        userActionFab.isEnabled = false
        userInputEt.hint = "Parsing GGUF..."
        ggufTv.text = "Parsing metadata from selected file \n$uri"

        lifecycleScope.launch(Dispatchers.IO) {
            // Parse GGUF metadata
            Log.i(TAG, "Parsing GGUF metadata...")
            contentResolver.openInputStream(uri)?.use {
                GgufMetadataReader.create().readStructuredMetadata(it)
            }?.let { metadata ->
                // Update UI to show GGUF metadata to user
                Log.i(TAG, "GGUF parsed: \n$metadata")
                withContext(Dispatchers.Main) {
                    ggufTv.text = metadata.toString()
                }

                // Ensure the model file is available
                val modelName = metadata.filename() + FILE_EXTENSION_GGUF
                contentResolver.openInputStream(uri)?.use { input ->
                    ensureModelFile(modelName, input)
                }?.let { modelFile ->
                    loadModel(modelName, modelFile)

                    withContext(Dispatchers.Main) {
                        isModelReady = true
                        userInputEt.hint = "Type and send a message!"
                        userInputEt.isEnabled = true
                        userActionFab.setImageResource(R.drawable.outline_send_24)
                        userActionFab.isEnabled = true
                    }
                }
            }
        }
    }

    /**
     * Prepare the model file within app's private storage
     */
    private suspend fun ensureModelFile(modelName: String, input: InputStream) =
        withContext(Dispatchers.IO) {
            File(ensureModelsDirectory(), modelName).also { file ->
                // Copy the file into local storage if not yet done
                if (!file.exists()) {
                    Log.i(TAG, "Start copying file to $modelName")
                    withContext(Dispatchers.Main) {
                        userInputEt.hint = "Copying file..."
                    }

                    FileOutputStream(file).use { input.copyTo(it) }
                    Log.i(TAG, "Finished copying file to $modelName")
                } else {
                    Log.i(TAG, "File already exists $modelName")
                }
            }
        }

    /**
     * Load the model file from the app private storage
     */
    private suspend fun loadModel(modelName: String, modelFile: File) =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Loading model $modelName")
            withContext(Dispatchers.Main) {
                userInputEt.hint = "Loading model..."
            }
            engine.loadModel(modelFile.path)
            engine.setSystemPrompt(com.example.llama.rafiki.Rafiki.SYSTEM_PROMPT)
        }

    /**
     * Validate and send the user message into [InferenceEngine]
     */
    private fun handleUserInput() {
        userInputEt.text.toString().also { userMsg ->
            if (userMsg.isEmpty()) {
                Toast.makeText(this, "Input message is empty!", Toast.LENGTH_SHORT).show()
            } else {
                userInputEt.text = null
                userInputEt.isEnabled = false
                userActionFab.isEnabled = false

                // Update message states
                messages.add(Message(UUID.randomUUID().toString(), userMsg, true))
                lastAssistantMsg.clear()
                messages.add(Message(UUID.randomUUID().toString(), lastAssistantMsg.toString(), false))
                messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)

                // Replace the (last) assistant message with new text
                fun showAssistant(text: String) {
                    val messageCount = messages.size
                    check(messageCount > 0 && !messages[messageCount - 1].isUser)
                    messages.removeAt(messageCount - 1).copy(content = text).let { messages.add(it) }
                    messageAdapter.notifyItemChanged(messages.size - 1)
                }

                fun enableInput() {
                    userInputEt.isEnabled = true
                    userActionFab.isEnabled = true
                }

                generationJob = lifecycleScope.launch(Dispatchers.Default) {
                    // Rafiki: route first. Verified answers, suggestions, document passages and
                    // "I don't know" replies are instant and never touch the model.
                    val t0 = System.currentTimeMillis()
                    val reply = try {
                        com.example.llama.rafiki.Rafiki.responder(this@MainActivity).respond(userMsg)
                    } catch (e: Exception) {
                        android.util.Log.e("RafikiRouter", "routing failed", e)
                        com.example.llama.rafiki.Reply.Text("Sorry, something went wrong. Please try again.")
                    }

                    val trace = com.example.llama.rafiki.Rafiki.responder(this@MainActivity)
                        .let { it.lastTrace + ("pack" to it.packId) }
                    when (reply) {
                        is com.example.llama.rafiki.Reply.Text -> withContext(Dispatchers.Main) {
                            showAssistant(reply.text)
                            com.example.llama.rafiki.RafikiLog.write(this@MainActivity, userMsg, trace,
                                reply.text, System.currentTimeMillis() - t0)
                            enableInput()
                        }
                        is com.example.llama.rafiki.Reply.Model ->
                            engine.sendUserPrompt(reply.prompt, reply.predictLength)
                                .onCompletion {
                                    withContext(Dispatchers.Main) {
                                        // label where the answer came from
                                        com.example.llama.rafiki.FigureCheck.apply(lastAssistantMsg.toString(), reply.checkAgainst).let {
                                            lastAssistantMsg.setLength(0); lastAssistantMsg.append(it)
                                        }
                                        showAssistant(lastAssistantMsg.append(reply.footer).toString())
                                        com.example.llama.rafiki.RafikiLog.write(this@MainActivity, userMsg,
                                            trace + ("model" to "yes"), lastAssistantMsg.toString(),
                                            System.currentTimeMillis() - t0)
                                        enableInput()
                                    }
                                }.collect { token ->
                                    withContext(Dispatchers.Main) {
                                        showAssistant(lastAssistantMsg.append(token).toString())
                                    }
                                }
                    }
                }
            }
        }
    }

    /**
     * Run a benchmark with the model file
     */
    @Deprecated("This benchmark doesn't accurately indicate GUI performance expected by app developers")
    private suspend fun runBenchmark(modelName: String, modelFile: File) =
        withContext(Dispatchers.Default) {
            Log.i(TAG, "Starts benchmarking $modelName")
            withContext(Dispatchers.Main) {
                userInputEt.hint = "Running benchmark..."
            }
            engine.bench(
                pp=BENCH_PROMPT_PROCESSING_TOKENS,
                tg=BENCH_TOKEN_GENERATION_TOKENS,
                pl=BENCH_SEQUENCE,
                nr=BENCH_REPETITION
            ).let { result ->
                messages.add(Message(UUID.randomUUID().toString(), result, false))
                withContext(Dispatchers.Main) {
                    messageAdapter.notifyItemChanged(messages.size - 1)
                }
            }
        }

    /**
     * Create the `models` directory if not exist.
     */
    private fun ensureModelsDirectory() =
        File(filesDir, DIRECTORY_MODELS).also {
            if (it.exists() && !it.isDirectory) { it.delete() }
            if (!it.exists()) { it.mkdir() }
        }

    override fun onStop() {
        generationJob?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        engine.destroy()
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        private const val DIRECTORY_MODELS = "models"
        private const val FILE_EXTENSION_GGUF = ".gguf"

        private const val BENCH_PROMPT_PROCESSING_TOKENS = 512
        private const val BENCH_TOKEN_GENERATION_TOKENS = 128
        private const val BENCH_SEQUENCE = 1
        private const val BENCH_REPETITION = 3

    }
}

fun GgufMetadata.filename() = when {
    basic.name != null -> {
        basic.name?.let { name ->
            basic.sizeLabel?.let { size ->
                "$name-$size"
            } ?: name
        }
    }
    architecture?.architecture != null -> {
        architecture?.architecture?.let { arch ->
            basic.uuid?.let { uuid ->
                "$arch-$uuid"
            } ?: "$arch-${System.currentTimeMillis()}"
        }
    }
    else -> {
        "model-${System.currentTimeMillis().toHexString()}"
    }
}
