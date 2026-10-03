package com.example.llama.rafiki

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln

/**
 * The phone's question router -- a line-for-line port of route() in query_pack.py.
 *
 * All word lists and thresholds come from the knowledge pack's config tables, which
 * build_pack.py writes from query_pack.py. So the Python and phone routers share one
 * source, and a routing fix ships as a new pack rather than a new app.
 *
 * Plain Kotlin behind [PackStore], so it runs both on Android and in a JVM parity test.
 */

/** Minimal read-only database access; values are String, Long, Double, ByteArray or null. */
interface PackStore {
    fun rows(sql: String, vararg args: String): List<List<Any?>>
}

data class Hit(
    val docid: Long, val score: Double, val norm: Double, val coverage: Double, val focusTf: Long,
    var kb: String = "", var source: String = "", var body: String = "",
)

data class FollowUp(val confidence: String, val focus: String?, val hits: List<Hit>, val words: List<String>)

data class RouteResult(
    var kind: String = "",
    var topic: String = "",
    var alsoMatched: List<String> = emptyList(),
    var confidence: String = "",
    var focus: String = "",
    var hits: List<Hit> = emptyList(),
    var answer: String = "",
    var followup: FollowUp? = null,
    var suggest: String = "",
    var clarified: String = "",
)

class Router(private val db: PackStore) {

    // ---------------------------------------------------------------- config from the pack
    private fun list(key: String) =
        db.rows("SELECT item FROM config_list WHERE key = ? ORDER BY pos", key).map { it[0] as String }

    private fun num(key: String) =
        (db.rows("SELECT value FROM config_num WHERE key = ?", key).single()[0] as Number).toDouble()

    private fun str(key: String) =
        db.rows("SELECT value FROM config_str WHERE key = ?", key).single()[0] as String

    private val stopwords = list("stopwords").toHashSet()
    private val smallTalk = list("small_talk").toHashSet()
    private val factualWords = list("factual_words").toHashSet()
    private val referential = list("referential")
    private val appInfo = Regex(str("app_info_regex"))
    private val clarification = Regex(str("clarification_regex"))
    private val k1 = num("k1")
    private val b = num("b")
    private val strongNorm = num("strong_norm")
    private val strongCoverage = num("strong_coverage")
    private val strongFocusTf = num("strong_focus_tf")
    private val partialNorm = num("partial_norm")
    private val partialCoverage = num("partial_coverage")
    private val cannedSearchNorm = num("canned_search_norm")
    private val cannedSearchCoverage = num("canned_search_coverage")
    private val minSpecificIdf = num("min_specific_idf")

    // The keyword table is small and read on every question: load it once.
    private data class Kw(val topic: String, val priority: Long, val keyword: String)
    private val keywords = db.rows(
        "SELECT topic, priority, keyword FROM topic_keywords ORDER BY priority, rowid"
    ).map { Kw(it[0] as String, it[1] as Long, it[2] as String) }
    private val generalTopics =
        db.rows("SELECT topic FROM general_topics").map { it[0] as String }.toHashSet()
    private val digestKeywords =
        db.rows("SELECT keyword FROM digest_override_keywords").map { it[0] as String }

    val asOf: String = db.rows("SELECT value FROM meta WHERE key = 'as_of'").single()[0] as String

    // ---------------------------------------------------------------- text helpers
    private val wordRe = Regex("[a-z0-9]+")

    fun words(text: String): List<String> = wordRe.findAll(text.lowercase()).map { it.value }.toList()

    fun queryTerms(query: String, limit: Int = 12): List<String> {
        val terms = ArrayList<String>()
        val seen = HashSet<String>()
        for (t in words(query)) {
            if (t.length < 2 || t in stopwords || t in seen) continue
            seen.add(t)
            terms.add(t)
        }
        return terms.take(limit)
    }

    private fun isSmallTalk(query: String): Boolean {
        val w = words(query)
        return w.isNotEmpty() && w.all { it in smallTalk }
    }

    private fun isReferential(query: String): Boolean {
        val q = query.lowercase()
        return referential.any { it in q }
    }

    private fun isFactual(query: String): Boolean =
        words(query).any { it in factualWords || it.trimEnd('s') in factualWords }

    // ---------------------------------------------------------------- verified answers
    fun cannedMatches(query: String): List<String> {
        val q = query.lowercase()
        val qNs = q.replace(" ", "")
        for (noSpace in listOf(false, true)) {
            val best = LinkedHashMap<String, Pair<Int, Long>>()  // topic -> (keyword length, priority)
            for (k in keywords) {
                val hit = if (noSpace) k.keyword.replace(" ", "") in qNs else k.keyword in q
                if (hit && (k.topic !in best || k.keyword.length > best.getValue(k.topic).first)) {
                    best[k.topic] = k.keyword.length to k.priority
                }
            }
            if (best.isNotEmpty()) {
                return best.keys.sortedWith(
                    compareBy<String>({ it in generalTopics }, { -best.getValue(it).first }, { best.getValue(it).second })
                )
            }
        }
        return emptyList()
    }

    /** Returns (answer in the operator's language, English text, Kiswahili text). */
    /** Where a verified answer was checked: (source, url, date checked), or null if none is
     *  recorded or the pack was built before sources existed. */
    fun cannedSource(topic: String): Triple<String, String, String>? = try {
        db.rows("SELECT source, url, checked FROM canned_sources WHERE topic = ?", topic).firstOrNull()
            ?.let { Triple(it[0] as String, it[1] as String, it[2] as String) }
    } catch (e: Exception) { null }

    fun cannedAnswer(topic: String, lang: String): Triple<String, String?, String?> {
        val row = db.rows("SELECT answer_en, answer_sw FROM canned WHERE topic = ?", topic).single()
        val en = row[0] as String?
        val sw = row[1] as String?
        val answer = if (lang == "sw") (sw.orEmptyNull() ?: en) else (en.orEmptyNull() ?: sw)
        return Triple(answer ?: "", en, sw)
    }

    private fun String?.orEmptyNull(): String? = if (this.isNullOrEmpty()) null else this

    private fun uncoveredTerms(query: String, topic: String, answerText: String): List<String> {
        val covered = words(answerText).toHashSet()
        for (k in keywords) if (k.topic == topic) covered.addAll(words(k.keyword))
        fun isCovered(t: String) = t in covered || t.trimEnd('s') in covered || (t + "s") in covered
        return queryTerms(query).filter { !isCovered(it) }
    }

    // ---------------------------------------------------------------- search (BM25 over FTS4)
    private class MatchInfo(val nDocs: Long, val avgLen: Long, val docLen: Long, val perTerm: List<Pair<Long, Long>>)

    /** matchinfo('pcnalx') for a one-column table: tf is hits in this row, df is rows with a hit. */
    private fun parseMatchinfo(blob: ByteArray, nTerms: Int): MatchInfo {
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val v = LongArray(blob.size / 4) { buf.getInt(it * 4).toLong() and 0xFFFFFFFFL }
        val per = (0 until nTerms).map { i -> v[5 + 3 * i] to v[7 + 3 * i] }
        return MatchInfo(v[2], if (v[3] == 0L) 1L else v[3], v[4], per)
    }

    private fun idf(nDocs: Long, df: Long) = ln((nDocs - df + 0.5) / (df + 0.5) + 1.0)

    private fun termIdfs(terms: List<String>, table: String = "chunks_fts"): LinkedHashMap<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (t in terms) {
            val row = db.rows("SELECT matchinfo($table, 'pcnalx') FROM $table WHERE $table MATCH ? LIMIT 1", t)
            out[t] = if (row.isNotEmpty()) {
                val mi = parseMatchinfo(row[0][0] as ByteArray, 1)
                idf(mi.nDocs, mi.perTerm[0].second)
            } else 0.0
        }
        return out
    }

    private data class SearchResult(val hits: List<Hit>, val idfs: Map<String, Double>, val focus: String?)

    private fun search(terms: List<String>, topK: Int, focusIn: String? = null, table: String = "chunks_fts"): SearchResult {
        if (terms.isEmpty()) return SearchResult(emptyList(), emptyMap(), null)
        val rows = db.rows(
            "SELECT docid, matchinfo($table, 'pcnalx') FROM $table WHERE $table MATCH ?",
            terms.joinToString(" OR "),
        )
        if (rows.isEmpty()) return SearchResult(emptyList(), terms.associateWith { 0.0 }, focusIn)
        val first = parseMatchinfo(rows[0][1] as ByteArray, terms.size)
        // A word the pack has never seen gets the HIGHEST weight: it is the question's most
        // specific word ("Mweya"), and a chunk without it must not look like full coverage.
        val idfs = first.perTerm.map { idf(first.nDocs, it.second) }
        val totalIdf = idfs.sum().let { if (it == 0.0) 1.0 else it }
        val bestPossible = idfs.sumOf { it * (k1 + 1) }.let { if (it == 0.0) 1.0 else it }
        val focus = if (focusIn != null && focusIn in terms) focusIn
                    else terms[terms.indices.maxByOrNull { idfs[it] }!!]
        val fi = terms.indexOf(focus)

        val hits = rows.map { row ->
            val mi = parseMatchinfo(row[1] as ByteArray, terms.size)
            var score = 0.0
            var covered = 0.0
            mi.perTerm.forEachIndexed { i, (tf, _) ->
                if (tf > 0) {
                    score += idfs[i] * tf * (k1 + 1) / (tf + k1 * (1 - b + b * mi.docLen.toDouble() / mi.avgLen))
                    covered += idfs[i]
                }
            }
            Hit(row[0] as Long, score, score / bestPossible, covered / totalIdf, mi.perTerm[fi].first)
        }.sortedByDescending { it.score }
        return SearchResult(hits.take(topK), terms.zip(idfs).toMap(), focus)
    }

    private fun retrieve(terms: List<String>, topK: Int, focus: String? = null): SearchResult {
        val r = search(terms, topK, focus)
        for (h in r.hits) {
            val row = db.rows("SELECT kb, source, body FROM chunks WHERE id = ?", h.docid.toString()).single()
            h.kb = (row[0] as String?) ?: ""
            h.source = (row[1] as String?) ?: ""
            h.body = row[2] as String
        }
        return r
    }

    private fun confidence(hits: List<Hit>): String {
        val h = hits.firstOrNull() ?: return "WEAK"
        if (h.focusTf >= strongFocusTf && h.norm >= strongNorm && h.coverage >= strongCoverage) return "STRONG"
        if (h.focusTf >= 1 && h.norm >= partialNorm && h.coverage >= partialCoverage) return "PARTIAL"
        return "WEAK"
    }

    private fun knownAnywhere(terms: List<String>): Boolean {
        for (t in terms) for (table in listOf("chunks_fts", "canned_fts")) {
            if (db.rows("SELECT 1 FROM $table WHERE $table MATCH ? LIMIT 1", t).isNotEmpty()) return true
        }
        return false
    }

    // ---------------------------------------------------------------- the router
    fun route(query: String, lang: String = "en", prevTopic: String? = null,
              prevQuery: String? = null, k: Int = 3): RouteResult {
        val r = RouteResult()
        val terms = queryTerms(query)
        val lower = query.lowercase()

        if (isSmallTalk(query)) return r.apply { kind = "CHAT" }
        if (prevQuery != null && clarification.find(lower)?.range?.first == 0) {
            // route the previous question and the clarification together, as one question
            val combined = "$prevQuery ${clarification.replace(lower, "")}"
            return route(combined, lang, null, null, k).apply { clarified = combined }
        }
        if (appInfo.containsMatchIn(lower)) return r.apply { kind = "APP_INFO" }
        if (terms.isNotEmpty() && !knownAnywhere(terms)) return r.apply { kind = "NOT_UNDERSTOOD" }
        if ((prevTopic != null || prevQuery != null) && isReferential(query)) {
            return r.apply { kind = "FOLLOW_UP"; topic = prevTopic ?: "" }
        }

        val matches = cannedMatches(query)
        if (matches.isNotEmpty()) {
            val t = matches[0]
            val (answerText, en, sw) = cannedAnswer(t, lang)
            r.kind = "VERIFIED"; r.topic = t; r.alsoMatched = matches.drop(1); r.answer = answerText
            val extra = uncoveredTerms(query, t, "${en ?: ""} ${sw ?: ""}")
            val idfs = termIdfs(extra)
            val specific = extra.filter { idfs.getValue(it) >= minSpecificIdf }
            if (specific.isNotEmpty()) {
                val res = retrieve(terms, k, focus = specific.maxByOrNull { idfs.getValue(it) })
                r.followup = FollowUp(confidence(res.hits), res.focus, res.hits, specific)
            }
            return r
        }

        // Verified answers are all regulatory, so only factual questions are searched against
        // them -- and a search match is only ever a SUGGESTION the operator confirms.
        val factual = isFactual(query)
        if (factual) {
            val c = search(terms, 1, table = "canned_fts").hits
            if (c.isNotEmpty() && c[0].focusTf >= 1 && c[0].norm >= cannedSearchNorm &&
                c[0].coverage >= cannedSearchCoverage) {
                r.suggest = db.rows("SELECT topic FROM canned WHERE rowid = ?", c[0].docid.toString())
                    .single()[0] as String
            }
        }

        val res = retrieve(terms, k)
        val level = confidence(res.hits)
        r.hits = res.hits; r.focus = res.focus ?: ""; r.confidence = level
        r.kind = when {
            r.suggest.isNotEmpty() -> "SUGGEST"
            factual -> if (digestKeywords.any { it in lower }) "DIGEST" else "FACTUAL $level"
            else -> "ADVISORY"
        }
        return r
    }

    /** Same format as query_pack.py --parity, so phone and laptop results can be diffed. */
    fun parityLine(query: String, r: RouteResult): String {
        val f = r.followup
        val top = r.hits.firstOrNull()?.docid?.toString() ?: f?.hits?.firstOrNull()?.docid?.toString() ?: ""
        return listOf("P", query, r.kind, r.topic, r.suggest, r.confidence, r.focus,
                      f?.confidence ?: "", top).joinToString("|")
    }
}
