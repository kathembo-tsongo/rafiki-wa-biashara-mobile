package com.example.llama.rafiki

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.time.LocalDateTime

/**
 * Pilot-study consent, asked once on first launch. Logging (RafikiLog) runs only after "Nakubali / I agree".
 * The participant code links log lines to a consent form kept by the researcher -- never a name or phone number.
 * DRAFT wording: replace it with the text your ethics committee approves.
 */
object PilotConsent {
    const val RESEARCHER_CONTACT = ""   // e.g. "Kathembo Tsongo Dieudonne, Strathmore University, <email or phone>"

    private const val TEXT_SW =
        "Utafiti wa majaribio wa Rafiki wa Biashara\n\n" +
        "Programu hii inajaribiwa kama sehemu ya utafiti wa Chuo Kikuu cha Strathmore. Ukikubali, programu " +
        "itahifadhi maswali unayouliza na majibu inayotoa, kwenye simu hii, ili mtafiti aweze kuchunguza jinsi " +
        "inavyofanya kazi. Haihifadhi jina lako, nambari yako ya simu wala mahali ulipo.\n\n" +
        "Kushiriki ni kwa hiari: ukikataa, bado unaweza kutumia programu kikamilifu. Unaweza kujiondoa wakati " +
        "wowote kwa kumwambia mtafiti."
    private const val TEXT_EN =
        "Rafiki wa Biashara pilot study\n\n" +
        "This app is being tested as part of a Strathmore University research study. If you agree, the app will " +
        "save the questions you ask and the answers it gives, on this phone, so the researcher can study how well " +
        "it works. It does not save your name, phone number or location.\n\n" +
        "Taking part is voluntary: if you say no, you can still use the app fully. You can withdraw at any time " +
        "by telling the researcher."

    private fun prefs(c: Context) = c.getSharedPreferences("rafiki_pilot", Context.MODE_PRIVATE)
    fun consented(c: Context) = prefs(c).getBoolean("consent", false)
    fun participant(c: Context) = prefs(c).getString("participant", "") ?: ""

    /** Shows the consent dialog if this phone has not answered it yet. */
    fun showIfNeeded(activity: Activity) {
        if (prefs(activity).contains("consent")) return
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val text = TextView(activity).apply {
            this.text = TEXT_SW + "\n\n———\n\n" + TEXT_EN +
                (if (RESEARCHER_CONTACT.isNotEmpty()) "\n\nMawasiliano / Contact: $RESEARCHER_CONTACT" else "")
            textSize = 15f
        }
        val code = EditText(activity).apply {
            hint = "Nambari ya mshiriki / Participant code (e.g. P01)"
            setText(participant(activity).takeIf { it != "UNSET" } ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(text)
            addView(code)
        }
        AlertDialog.Builder(activity)
            .setView(ScrollView(activity).apply { addView(box) })
            .setCancelable(false)
            .setPositiveButton("Nakubali / I agree") { _, _ ->
                prefs(activity).edit()
                    .putBoolean("consent", true)
                    .putString("participant", code.text.toString().trim().uppercase().ifEmpty { "UNSET" })
                    .putString("consent_time", LocalDateTime.now().withNano(0).toString())
                    .apply()
            }
            .setNegativeButton("Hapana / No thanks") { _, _ ->
                prefs(activity).edit().putBoolean("consent", false)
                    .putString("consent_time", LocalDateTime.now().withNano(0).toString()).apply()
            }
            .show()
    }

    fun withdrawnTime(c: Context) = prefs(c).getString("withdrawn_time", "") ?: ""

    /** Rejoin after a withdrawal or a "no": show the consent screen again. */
    fun rejoin(activity: Activity) {
        prefs(activity).edit().remove("consent").remove("withdrawn_time").apply()
        showIfNeeded(activity)
    }

    /** Withdrawal: stop logging and delete this phone's log (for a future settings option). */
    fun withdraw(c: Context) {
        prefs(c).edit().putBoolean("consent", false)
            .putString("withdrawn_time", LocalDateTime.now().withNano(0).toString()).apply()
        File(c.filesDir, "rafiki_log.jsonl").delete()
    }
}
