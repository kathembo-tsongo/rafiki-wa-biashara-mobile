package com.example.llama.rafiki

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime

/**
 * Test log: one JSON line per question in files/rafiki_log.jsonl (pull with pull_phone_log.py).
 * Records question, answer and routing only -- no names, phone numbers or location.
 * Set ENABLED = false before the app goes to operators, unless they have consented to logging.
 */
object RafikiLog {
    const val ENABLED = true

    @Synchronized
    fun write(context: Context, question: String, trace: Map<String, String>, answer: String, ms: Long) {
        if (!ENABLED || !PilotConsent.consented(context)) return
        try {
            val o = JSONObject()
            o.put("time", LocalDateTime.now().withNano(0).toString())
            o.put("participant", PilotConsent.participant(context))
            o.put("question", question)
            for ((k, v) in trace) o.put(k, v)
            o.put("answer", answer)
            o.put("ms", ms)
            File(context.filesDir, "rafiki_log.jsonl").appendText(o.toString() + "\n")
        } catch (e: Exception) {
            android.util.Log.w("RafikiLog", "log write failed", e)
        }
    }
}
