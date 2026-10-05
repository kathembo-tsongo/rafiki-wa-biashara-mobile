package com.example.llama.rafiki

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Researcher menu for the pilot (long-press the history button): export the study log + readable report, or withdraw. */
object PilotTools {
    private fun logFile(a: Activity) = File(a.filesDir, "rafiki_log.jsonl")

    private fun consentStatus(a: Activity): String = when {
        PilotConsent.consented(a) -> "ndiyo / yes"
        PilotConsent.withdrawnTime(a).isNotEmpty() ->
            "amejiondoa / withdrawn ${PilotConsent.withdrawnTime(a).replace("T", " ")}"
        else -> "hapana / no"
    }

    fun showMenu(a: Activity) {
        val f = logFile(a)
        val n = if (f.exists()) f.readLines().count { it.isNotBlank() } else 0
        AlertDialog.Builder(a)
            .setTitle("Rafiki pilot")
            .setMessage(
                "Mshiriki / Participant: ${PilotConsent.participant(a).ifEmpty { "-" }}\n" +
                "Ridhaa / Consent: ${consentStatus(a)}\n" +
                "Maswali yaliyohifadhiwa / Logged questions: $n")
            .setPositiveButton("Tuma kumbukumbu / Export log") { _, _ -> export(a) }
            .setNeutralButton(if (PilotConsent.consented(a)) "Jiondoe / Withdraw" else "Jiunge tena / Rejoin") { _, _ ->
                if (PilotConsent.consented(a)) confirmWithdraw(a) else PilotConsent.rejoin(a)
            }
            .setNegativeButton("Funga / Close", null)
            .show()
    }

    /** Saves bytes as a new file in Downloads (MediaStore: no permission needed) and returns its Uri. */
    private fun saveToDownloads(a: Activity, name: String, mime: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = a.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("could not create $name in Downloads")
        a.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        a.contentResolver.update(uri, values, null, null)
        return uri
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
            val raw = src.readBytes()
            val rows = src.readLines().filter { it.trim().startsWith("{") }
                .mapNotNull { try { JSONObject(it) } catch (e: Exception) { null } }
            val report = saveToDownloads(a, "rafiki_report_${code}_$stamp.html", "text/html",
                reportHtml(rows, code).toByteArray())
            val log = saveToDownloads(a, "rafiki_log_${code}_$stamp.txt", "text/plain", raw)
            Toast.makeText(a, "Imehifadhiwa Downloads / Saved to Downloads: report + log", Toast.LENGTH_LONG).show()
            val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(report, log))
                putExtra(Intent.EXTRA_SUBJECT, "Rafiki log $code $stamp")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            a.startActivity(Intent.createChooser(send, "Tuma kumbukumbu / Send log"))
        } catch (e: Exception) {
            android.util.Log.e("PilotTools", "export failed", e)
            Toast.makeText(a, "Imeshindikana / Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ readable report
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun md(s: String): String {
        var t = esc(s)
        t = Regex("""\*\*(.+?)\*\*""").replace(t) { "<b>${it.groupValues[1]}</b>" }
        t = Regex("""(?<!\*)\*(?!\s)(.+?)(?<!\s)\*(?!\*)""").replace(t) { "<i>${it.groupValues[1]}</i>" }
        return t.replace("\n---\n", "\n<hr>").replace("\n", "<br>")
    }

    private fun label(route: String): Pair<String, String> = when {
        route.startsWith("VERIFIED") -> "Verified" to "#1b7f3b"
        route.startsWith("SUGGEST") -> "Suggestion" to "#8a6d00"
        route.startsWith("DOCUMENTS") || route.startsWith("FACTUAL") -> "Documents" to "#1f5fa8"
        route.startsWith("DIGEST") -> "Verified facts (model)" to "#5b4bb7"
        route.startsWith("ADVISORY") -> "Advice (model)" to "#7a4fa0"
        route.startsWith("COMPLAINT") -> "Complaint" to "#b3261e"
        else -> (route.ifEmpty { "?" }) to "#666666"
    }

    private fun secs(ms: Long) = if (ms < 1000) "$ms ms" else String.format("%.1f s", ms / 1000.0)
    private fun median(xs: List<Long>) = if (xs.isEmpty()) 0L else xs.sorted()[xs.size / 2]

    private fun reportHtml(rows: List<JSONObject>, code: String): String {
        val model = rows.filter { it.optString("model") == "yes" }.map { it.optLong("ms") }
        val instant = rows.filter { it.optString("model") != "yes" }.map { it.optLong("ms") }
        val types = rows.groupingBy { label(it.optString("route")).first }.eachCount().entries.sortedByDescending { it.value }
        val langs = rows.groupingBy { if (it.optString("lang") == "sw") "Kiswahili" else "English" }.eachCount()
        val sb = StringBuilder()
        sb.append("""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Rafiki log $code</title><style>
body{font-family:system-ui,Roboto,sans-serif;max-width:860px;margin:16px auto;padding:0 12px;color:#222;background:#fafafa}
h1{font-size:20px}h2{font-size:16px;margin-top:24px;border-bottom:2px solid #ddd}
table{border-collapse:collapse;background:#fff;font-size:14px;margin:6px 12px 6px 0;display:inline-table;vertical-align:top}
td,th{border:1px solid #ddd;padding:4px 8px;text-align:left}th{background:#f0f0f0}
.q{background:#1f5fa8;color:#fff;padding:8px 12px;border-radius:12px 12px 2px 12px;max-width:80%;margin:14px 0 4px auto;width:fit-content}
.a{background:#fff;border:1px solid #ddd;padding:10px 12px;border-radius:12px 12px 12px 2px;max-width:88%;font-size:14px}
.m{font-size:12px;color:#555;margin:4px 0 0}.b{color:#fff;padding:1px 7px;border-radius:8px;font-size:11px}
hr{border:0;border-top:1px solid #eee}.c{border-left:4px solid #b3261e;padding-left:6px}
</style></head><body>""")
        sb.append("<h1>Rafiki wa Biashara — conversation log</h1>")
        val first = rows.firstOrNull()?.optString("time")?.replace("T", " ") ?: ""
        val last = rows.lastOrNull()?.optString("time")?.replace("T", " ") ?: ""
        sb.append("<p>Participant <b>${esc(code)}</b> · ${rows.size} questions · $first – $last</p>")
        sb.append("<table><tr><th>Answer type</th><th>n</th></tr>")
        types.forEach { sb.append("<tr><td>${esc(it.key)}</td><td>${it.value}</td></tr>") }
        sb.append("</table><table><tr><th>Language</th><th>n</th></tr>")
        langs.forEach { (k, v) -> sb.append("<tr><td>$k</td><td>$v</td></tr>") }
        sb.append("</table><table><tr><th>Response time</th><th>Median</th><th>n</th></tr>")
        sb.append("<tr><td>Instant</td><td>${secs(median(instant))}</td><td>${instant.size}</td></tr>")
        sb.append("<tr><td>Model</td><td>${secs(median(model))}</td><td>${model.size}</td></tr></table>")
        sb.append("<h2>Conversation</h2>")
        var pack = ""
        for (r in rows) {
            val (name, colour) = label(r.optString("route"))
            val meta = mutableListOf(r.optString("time").substringAfter("T"), "<span class=b style=\"background:$colour\">${esc(name)}</span>")
            r.optString("topic").takeIf { it.isNotEmpty() }?.let { meta.add("topic: ${esc(it)}") }
            r.optString("suggest").takeIf { it.isNotEmpty() }?.let { meta.add("offered: ${esc(it)}") }
            meta.add(if (r.optString("lang") == "sw") "Kiswahili" else "English")
            meta.add(secs(r.optLong("ms")))
            if (r.optString("pack") != pack) { pack = r.optString("pack"); meta.add("pack ${esc(pack)}") }
            val cls = if (r.optString("route").startsWith("COMPLAINT")) " class=c" else ""
            sb.append("<div$cls><div class=q>${esc(r.optString("question"))}</div>")
            sb.append("<div class=a>${md(r.optString("answer"))}</div>")
            sb.append("<p class=m>${meta.joinToString(" · ")}</p></div>")
        }
        sb.append("</body></html>")
        return sb.toString()
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
