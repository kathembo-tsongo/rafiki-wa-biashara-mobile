package com.example.llama.rafiki

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns a routed question into what the operator sees. Only three routes ever reach the
 * model -- FACTUAL STRONG (answer ONLY from the passages), DIGEST (answer from the
 * verified facts in the system prompt) and ADVISORY (general advice, labelled as such).
 * Everything else is answered instantly, with no model and nothing that can be invented.
 *
 * Plain Kotlin (no Android imports), so it can be tested off the phone.
 */
sealed class Reply {
    /** Shown as-is, instantly. */
    data class Text(val text: String) : Reply()

    /** Sent to the model; [footer] is appended when generation finishes. */
    data class Model(val prompt: String, val footer: String, val predictLength: Int = 300) : Reply()
}

class Responder(private val router: Router) {

    // Conversation state -- one operator, one conversation.
    private var prevTopic: String? = null
    private var prevQuery: String? = null
    private var lastSources: List<String> = emptyList()
    private var pending: Pending? = null

    private class Pending(val topic: String, val route: RouteResult, val question: String, val lang: String)

    fun reset() {
        prevTopic = null; prevQuery = null; lastSources = emptyList(); pending = null
    }

    // ---------------------------------------------------------------- entry point
    fun respond(question: String, now: ZonedDateTime = ZonedDateTime.now()): Reply {
        val q = question.trim()

        // A reply to "Is your question about ...? Reply 1 or 2" from the previous turn.
        pending?.let { p ->
            pending = null
            when (q.lowercase()) {
                "1", "yes", "ndiyo", "ndio" -> return verified(p.topic, p.lang)
                "2", "no", "hapana" -> return documents(p.route, p.question, p.lang)
            }
            // anything else: the operator moved on -- route it as a new question
        }

        val lang = detectLang(q)
        val r = router.route(q, lang, prevTopic, prevQuery)
        if (r.topic.isNotEmpty()) prevTopic = r.topic
        if (r.kind !in setOf("CHAT", "APP_INFO", "NOT_UNDERSTOOD", "FOLLOW_UP")) prevQuery = r.clarified.ifEmpty { q }

        return when {
            r.kind == "CHAT" -> Reply.Text(chat(q, lang))
            r.kind == "APP_INFO" -> Reply.Text(clock(now, lang))
            r.kind == "NOT_UNDERSTOOD" -> Reply.Text(
                "Sorry, I didn't understand. Please ask in English or Kiswahili.\n\n" +
                "Samahani, sijaelewa. Tafadhali uliza kwa Kiingereza au Kiswahili.")
            r.kind == "FOLLOW_UP" -> Reply.Text(followUp(lang))
            r.kind == "VERIFIED" -> verified(r.topic, lang, r, q)
            r.kind == "SUGGEST" -> {
                pending = Pending(r.suggest, r, q, lang)
                Reply.Text(suggest(r.suggest, lang))
            }
            r.kind.startsWith("FACTUAL") -> documents(r, q, lang)
            r.kind == "DIGEST" -> {
                lastSources = emptyList()
                Reply.Model(digestPrompt(q, lang), footer(lang,
                    "✅ *Based on verified figures (accurate as of ${asOf()}). Confirm with ${office(q, lang)} for your own case.*",
                    "✅ *Kulingana na takwimu zilizothibitishwa (sahihi hadi ${asOf()}). Thibitisha na ${office(q, lang)} kwa hali yako.*"))
            }
            else -> advisory(r, q, lang)  // ADVISORY
        }
    }

    // ---------------------------------------------------------------- routes
    private fun verified(topic: String, lang: String, r: RouteResult? = null, q: String = ""): Reply {
        prevTopic = topic
        lastSources = emptyList()
        val (answer, _, sw) = router.cannedAnswer(topic, lang)
        val sb = StringBuilder(answer)
        if (lang == "sw" && sw.isNullOrEmpty()) sb.insert(0, "*(Jibu hili bado halipo kwa Kiswahili.)*\n\n")

        // The question also asked about something specific that this answer doesn't cover.
        r?.followup?.let { f ->
            val words = f.words.joinToString(", ") { "**$it**" }
            val top = f.hits.firstOrNull()
            sb.append("\n\n")
            if (f.confidence != "WEAK" && top != null) {
                lastSources = listOf(top.source)
                sb.append(pick(lang,
                    "📄 *You also asked about $words. A related document: ${title(top.source)}*",
                    "📄 *Uliuliza pia kuhusu $words. Hati inayohusiana: ${title(top.source)}*"))
            } else {
                sb.append(pick(lang,
                    "⚠️ *I don't have verified information on $words specifically. Check with ${office(q, lang)}.*",
                    "⚠️ *Sina taarifa iliyothibitishwa kuhusu $words hasa. Wasiliana na ${office(q, lang)}.*"))
            }
        }
        // A follow-up means the verified answer covers only part of the question: say which part.
        val t = topicTitle(topic, lang)
        sb.append(if (r?.followup == null) footer(lang,
            "✅ *Verified answer · ${stampEn(topic)}*",
            "✅ *Jibu lililothibitishwa · ${stampSw(topic)}*")
        else footer(lang,
            "✅ *Verified only for: $t · ${stampEn(topic)}*",
            "✅ *Imethibitishwa kwa: $t pekee · ${stampSw(topic)}*"))
        return Reply.Text(sb.toString())
    }

    private fun suggest(topic: String, lang: String): String {
        val t = topicTitle(topic, lang)
        return pick(lang,
            "I have a verified answer that may match your question: **$t**\n\n" +
            "Reply **1** to see it, or **2** for what the documents say.",
            "Nina jibu lililothibitishwa ambalo huenda linajibu swali lako: **$t**\n\n" +
            "Jibu **1** kuliona, au **2** kuona hati zinasema nini.")
    }

    /** The strict factual route: STRONG -> model from passages only; PARTIAL -> passages; WEAK -> office. */
    private fun documents(r: RouteResult, q: String, lang: String): Reply {
        lastSources = r.hits.map { it.source }.distinct()
        return when (r.confidence) {
            "STRONG" -> {
                val used = r.hits.take(2)
                lastSources = used.map { it.source }.distinct()
                Reply.Model(passagePrompt(q, used, lang), footer(lang,
                    "📄 *From: ${lastSources.joinToString("; ") { title(it) }}. Confirm with ${office(q, lang)} before acting.*",
                    "📄 *Chanzo: ${lastSources.joinToString("; ") { title(it) }}. Thibitisha na ${office(q, lang)} kabla ya kuchukua hatua.*"))
            }
            "PARTIAL" -> {
                val sb = StringBuilder(pick(lang,
                    "I found documents that may only partly answer this:",
                    "Nimepata hati ambazo huenda zinajibu swali hili kwa sehemu tu:"))
                for (h in r.hits.take(2)) sb.append("\n\n> ${excerpt(h.body, focus = r.focus)}\n\n— *${title(h.source)}*")
                sb.append("\n\n").append(pick(lang,
                    "For a definite answer, check with ${office(q, lang)}.",
                    "Kwa jibu la uhakika, wasiliana na ${office(q, lang)}."))
                Reply.Text(sb.toString())
            }
            else -> {
                lastSources = emptyList()
                Reply.Text(pick(lang,
                    "I don't have verified information on this yet. For a reliable answer, contact ${office(q, lang)}.",
                    "Bado sina taarifa iliyothibitishwa kuhusu hili. Kwa jibu la kuaminika, wasiliana na ${office(q, lang)}."))
            }
        }
    }

    private fun advisory(r: RouteResult, q: String, lang: String): Reply {
        val background = r.hits.firstOrNull()?.takeIf { r.confidence == "STRONG" }
        lastSources = listOfNotNull(background?.source)
        return Reply.Model(advisoryPrompt(q, background, lang), footer(lang,
            "💡 *General advice, not verified information.*",
            "💡 *Ushauri wa jumla, si taarifa iliyothibitishwa.*"))
    }

    private fun followUp(lang: String): String =
        if (lastSources.isEmpty()) pick(lang,
            "My last answer didn't come from a stored document. Which topic would you like to know more about?",
            "Jibu langu la mwisho halikutoka kwenye hati iliyohifadhiwa. Ungependa kujua zaidi kuhusu mada gani?")
        else pick(lang, "My last answer came from:", "Jibu langu la mwisho lilitoka kwenye:") +
            lastSources.joinToString("") { "\n- ${title(it)}" } + "\n\n" + pick(lang,
            "These documents are stored in the app, so they work offline. Ask me about any part of them.",
            "Hati hizi zimehifadhiwa kwenye programu, kwa hivyo zinafanya kazi bila mtandao. Niulize kuhusu sehemu yoyote.")

    private fun chat(q: String, lang: String): String {
        val thanks = router.words(q).any { it.startsWith("thank") || it == "asante" }
        return when {
            thanks -> pick(lang, "You're welcome! Ask me anything else about your business.",
                                 "Karibu! Niulize swali lingine lolote kuhusu biashara yako.")
            else -> pick(lang,
                "Hello! I'm Rafiki wa Biashara. Ask me about registering a business, taxes, licences, NSSF, loans, or growing your business.",
                "Habari! Mimi ni Rafiki wa Biashara. Niulize kuhusu kusajili biashara, kodi, leseni, NSSF, mikopo, au kukuza biashara yako.")
        }
    }

    private fun clock(now: ZonedDateTime, lang: String): String {
        val time = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        val date = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH))
        return pick(lang, "It's $time on $date (from your phone's clock).",
                          "Ni $time, tarehe ${now.format(DateTimeFormatter.ofPattern("d/M/yyyy"))} (kutoka saa ya simu yako).")
    }

    // ---------------------------------------------------------------- model prompts
    // These travel with each question: the engine only accepts a system prompt once, at load.
    private fun language(lang: String) = if (lang == "sw") "Kiswahili" else "English"

    private fun passagePrompt(q: String, hits: List<Hit>, lang: String): String =
        "Answer the question using ONLY the document extracts below. If they do not contain the answer, " +
        "say so in one sentence. Do not add any figure, fee, rate, date, phone number or website that is not " +
        "in the extracts. Answer in ${language(lang)}, in at most 120 words, as short numbered steps where possible.\n\n" +
        "Extracts:\n" + hits.mapIndexed { i, h -> "[${i + 1}] (${title(h.source)}) ${excerpt(h.body, 700)}" }
            .joinToString("\n") + "\n\nQuestion: $q"

    private fun digestPrompt(q: String, lang: String): String =
        "Answer using the verified facts in your instructions, exactly as stated. If they do not cover the " +
        "question, say so in one sentence. Answer in ${language(lang)}, in at most 120 words.\n\nQuestion: $q"

    private fun advisoryPrompt(q: String, background: Hit?, lang: String): String =
        "Give practical, general business advice to a small business owner in Kenya. Answer in ${language(lang)}, " +
        "as 3 to 5 short points, in at most 120 words. Do not state specific prices, fees, interest rates, tax " +
        "rates or laws, and do not state facts about specific named businesses, places or people. If the " +
        "question is not about business, answer briefly and kindly.\n\n" +
        (background?.let { "Background (from a stored document): ${excerpt(it.body, 600)}\n\n" } ?: "") +
        "Question: $q"

    // ---------------------------------------------------------------- helpers
    private fun pick(lang: String, en: String, sw: String) = if (lang == "sw") sw else en

    private fun footer(lang: String, en: String, sw: String) = "\n\n---\n" + pick(lang, en, sw)

    // Verified footer stamp: the answer's own source and check date when the pack has one,
    // otherwise the pack's as-of date.
    private fun stampEn(topic: String): String = router.cannedSource(topic)
        ?.let { "Source: ${it.first} · checked ${fmtDate(it.third)}" } ?: "accurate as of ${asOf()}"
    private fun stampSw(topic: String): String = router.cannedSource(topic)
        ?.let { "Chanzo: ${it.first} · kimekaguliwa ${fmtDate(it.third)}" } ?: "sahihi hadi ${asOf()}"
    private fun fmtDate(iso: String): String = try {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
    } catch (e: Exception) { iso }

    private fun asOf(): String = try {
        LocalDate.parse(router.asOf).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
    } catch (e: Exception) { router.asOf }

    /** A verified answer's own heading (its first **bold** phrase), else the topic id tidied up. */
    private fun topicTitle(topic: String, lang: String): String {
        val answer = router.cannedAnswer(topic, lang).first
        Regex("\\*\\*(.+?)\\*\\*").find(answer)?.let { return it.groupValues[1].trimEnd(':', ' ') }
        return topic.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private val acronyms = setOf("kra", "nssf", "sha", "vat", "paye", "msme", "msmes", "sme", "cbk", "knbs",
        "ifc", "unctad", "icta", "yedf", "agpo", "tveta", "afcfta", "faq", "sw", "pin", "brs", "kebs", "kephis")

    /** "kb2_tax_kra/kra_offences_penalties.txt" -> "KRA offences penalties" */
    fun title(source: String): String {
        val base = source.substringAfterLast('/').substringBeforeLast('.')
        val w = base.split('_', '-', ' ').filter { it.isNotEmpty() }
            .map { if (it.lowercase() in acronyms) it.uppercase() else it.lowercase() }
        return w.joinToString(" ").replaceFirstChar { it.uppercase() }.ifEmpty { source }
    }

    private fun excerpt(body: String, max: Int = 300, focus: String = ""): String {
        val s = body.replace(Regex("\\s+"), " ").trim()
        if (s.length <= max) return s
        // Start near the first occurrence of the focus term, so the reader sees why the passage matched.
        var start = 0
        if (focus.isNotEmpty()) {
            val i = s.indexOf(focus, ignoreCase = true)
            if (i > max / 3) start = s.lastIndexOf(' ', i - max / 3).let { if (it < 0) 0 else it + 1 }
        }
        val end = (start + max).coerceAtMost(s.length)
        val cut = if (end < s.length) (s.lastIndexOf(' ', end).takeIf { it > start + max / 2 } ?: end) else end
        return (if (start > 0) "…" else "") + s.substring(start, cut) + (if (cut < s.length) "…" else "")
    }

    /** Where to send the operator when the app can't answer with confidence. */
    fun office(q: String, lang: String): String {
        val w = router.words(q).toSet()
        fun any(vararg k: String) = k.any { it in w }
        return when {
            any("pharmacy", "chemist", "dawa") -> pick(lang, "the Pharmacy and Poisons Board", "Pharmacy and Poisons Board (PPB)")
            any("nssf") -> pick(lang, "your nearest NSSF office", "ofisi ya NSSF iliyo karibu nawe")
            any("pochi", "mpesa", "safaricom", "paybill", "till") ->
                pick(lang, "Safaricom (a Safaricom shop or M-PESA agent)", "Safaricom (duka la Safaricom au wakala wa M-PESA)")
            any("shif", "sha", "nhif") -> pick(lang, "the Social Health Authority (SHA)", "Mamlaka ya Afya ya Jamii (SHA)")
            any("trademark", "trademarks", "patent", "patents") ->
                pick(lang, "the Kenya Industrial Property Institute (KIPI)", "Kenya Industrial Property Institute (KIPI)")
            any("copyright", "copyrights") ->
                pick(lang, "the Kenya Copyright Board (KECOBO)", "Kenya Copyright Board (KECOBO)")
            any("kra", "tax", "taxes", "vat", "paye", "itax", "etims", "pin", "tcc", "turnover", "kodi", "ushuru") ->
                pick(lang, "KRA (a KRA service office, or iTax)", "KRA (ofisi ya huduma ya KRA, au iTax)")
            any("permit", "licence", "license", "licenses", "licences", "leseni", "kibali", "county", "kaunti") ->
                pick(lang, "your county government's business licensing office", "ofisi ya leseni za biashara ya serikali ya kaunti yako")
            any("register", "registration", "company", "sole", "partnership", "usajili", "sajili", "kusajili") ->
                pick(lang, "the Business Registration Service (via eCitizen or a Huduma Centre)",
                           "Huduma ya Usajili wa Biashara (kupitia eCitizen au Kituo cha Huduma)")
            any("export", "exports", "exporting", "import", "imports") ->
                pick(lang, "KEPROBA (Kenya Export Promotion and Branding Agency)", "KEPROBA (Shirika la Kukuza Mauzo ya Nje)")
            any("employee", "employees", "employer", "wage", "wages", "salary", "leave", "termination", "mfanyakazi", "wafanyakazi") ->
                pick(lang, "your county labour office", "ofisi ya kazi ya kaunti yako")
            any("loan", "loans", "lender", "lenders", "credit", "sacco", "bank", "mkopo", "mikopo") ->
                pick(lang, "your bank or SACCO", "benki yako au SACCO")
            else -> pick(lang, "a Huduma Centre", "Kituo cha Huduma")
        }
    }

    private val swMarkers = setOf("na", "ya", "kwa", "ni", "je", "gani", "nini", "vipi", "naweza", "ninahitaji",
        "jinsi", "wapi", "lini", "kuna", "hii", "hiyo", "sana", "mimi", "yangu", "nataka", "tafadhali", "habari",
        "asante", "ninawezaje", "nitasajili", "kupata", "kuanza", "kufungua", "biashara", "mkopo", "leseni", "kodi")
    private val enMarkers = setOf("the", "is", "how", "what", "do", "i", "my", "for", "to", "and", "can", "of",
        "a", "in", "are", "does", "should", "which", "where", "when", "hello", "hi", "thanks", "thank")

    /** Kiswahili if it has more Kiswahili than English marker words ("pochi la biashara" alone doesn't flip it). */
    fun detectLang(q: String): String {
        val w = router.words(q)
        return if (w.count { it in swMarkers } > w.count { it in enMarkers }) "sw" else "en"
    }
}
