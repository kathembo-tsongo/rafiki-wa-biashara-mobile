package com.example.llama.rafiki

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Researcher menu for the pilot (long-press the history button): export the study log, or withdraw. */
object PilotTools {
    private fun logFile(a: Activity) = File(a.filesDir, "rafiki_log.jsonl")

    fun showMenu(a: Activity) {
        val f = logFile(a)
        val n = if (f.exists()) f.readLines().count { it.isNotBlank() } else 0
        AlertDialog.Builder(a)
            .setTitle("Rafiki pilot")
            .setMessage(
                "Mshiriki / Participant: ${PilotConsent.participant(a).ifEmpty { "-" }}\n" +
                "Ridhaa / Consent: ${if (PilotConsent.consented(a)) "ndiyo / yes" else "hapana / no"}\n" +
                "Maswali yaliyohifadhiwa / Logged questions: $n")
            .setPositiveButton("Tuma kumbukumbu / Export log") { _, _ -> export(a) }
            .setNeutralButton("Jiondoe / Withdraw") { _, _ -> confirmWithdraw(a) }
            .setNegativeButton("Funga / Close", null)
            .show()
    }

    private fun export(a: Activity) {
        val src = logFile(a)
        if (!src.exists() || src.length() == 0L) {
            Toast.makeText(a, "Hakuna kumbukumbu bado / No log yet", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
            val code = PilotConsent.participant(a).ifEmpty { "NA" }
            val name = "rafiki_log_${code}_$stamp.txt"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = a.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("could not create the file in Downloads")
            a.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            a.contentResolver.update(uri, values, null, null)
            Toast.makeText(a, "Imehifadhiwa Downloads / Saved to Downloads: $name", Toast.LENGTH_LONG).show()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            a.startActivity(Intent.createChooser(send, "Tuma kumbukumbu / Send log"))
        } catch (e: Exception) {
            android.util.Log.e("PilotTools", "export failed", e)
            Toast.makeText(a, "Imeshindikana / Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmWithdraw(a: Activity) {
        AlertDialog.Builder(a)
            .setTitle("Jiondoe / Withdraw")
            .setMessage("Hii itasimamisha kuhifadhi na kufuta kumbukumbu za simu hii.\n\n" +
                "This stops logging and deletes this phone's log.")
            .setPositiveButton("Ndiyo, jiondoe / Yes, withdraw") { _, _ ->
                PilotConsent.withdraw(a)
                Toast.makeText(a, "Umejiondoa / You have withdrawn", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Hapana / No", null)
            .show()
    }
}
