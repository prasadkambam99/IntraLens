package com.nmx.intradaylens.data

import android.util.Xml
import com.nmx.intradaylens.engine.Candle
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Headline(val title: String, val source: String, val link: String, val publishedMs: Long)

/**
 * Free public data, no account or API key:
 *  - Prices: Yahoo Finance chart endpoint (NSE symbols end with ".NS", NIFTY 50 = "^NSEI").
 *  - News: Google News RSS search.
 * NOTE: these are unofficial endpoints; prices can be a few minutes delayed.
 */
object MarketApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun yahooSymbol(symbol: String) = if (symbol.startsWith("^") || symbol.contains(".")) symbol else "$symbol.NS"

    /** [range] e.g. "60d" or "1d"; 5-minute bars are available for the last 60 days. */
    fun candles(symbol: String, range: String, interval: String = "5m"): List<Candle> =
        chart(symbol, "range=$range&interval=$interval")

    /** 1-minute bars are only kept by Yahoo for ~30 days, max 7 days per request. */
    fun candlesBetween(symbol: String, fromSec: Long, toSec: Long, interval: String = "1m"): List<Candle> =
        chart(symbol, "period1=$fromSec&period2=$toSec&interval=$interval")

    private fun chart(symbol: String, query: String): List<Candle> {
        val ys = URLEncoder.encode(yahooSymbol(symbol), "UTF-8")
        var lastError: Exception? = null
        for (host in listOf("query1", "query2")) {
            try {
                val url = "https://$host.finance.yahoo.com/v8/finance/chart/$ys?$query&includePrePost=false"
                val body = get(url)
                return parseChart(body)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No data for $symbol")
    }

    private fun parseChart(body: String): List<Candle> {
        val root = JSONObject(body).getJSONObject("chart")
        val results = root.optJSONArray("result") ?: throw IllegalStateException(
            root.optJSONObject("error")?.optString("description") ?: "No result"
        )
        val r = results.getJSONObject(0)
        val ts = r.optJSONArray("timestamp") ?: return emptyList()
        val q = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val o = q.getJSONArray("open"); val h = q.getJSONArray("high")
        val l = q.getJSONArray("low"); val c = q.getJSONArray("close")
        val v = q.optJSONArray("volume")
        val out = ArrayList<Candle>(ts.length())
        for (i in 0 until ts.length()) {
            if (o.isNull(i) || h.isNull(i) || l.isNull(i) || c.isNull(i)) continue
            out += Candle(
                ts.getLong(i), o.getDouble(i), h.getDouble(i), l.getDouble(i), c.getDouble(i),
                if (v == null || v.isNull(i)) 0L else v.getLong(i)
            )
        }
        return out
    }

    fun news(query: String, max: Int = 12): List<Headline> {
        val q = URLEncoder.encode("$query share price", "UTF-8")
        val xml = get("https://news.google.com/rss/search?q=$q+when:2d&hl=en-IN&gl=IN&ceid=IN:en")
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(xml.reader())
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)
        val out = mutableListOf<Headline>()
        var inItem = false
        var title = ""; var link = ""; var date = 0L; var source = ""
        var tag = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT && out.size < max) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    if (tag == "item") { inItem = true; title = ""; link = ""; date = 0L; source = "" }
                }
                XmlPullParser.TEXT -> if (inItem) {
                    val t = parser.text
                    when (tag) {
                        "title" -> title += t
                        "link" -> link += t
                        "source" -> source += t
                        "pubDate" -> date = runCatching { fmt.parse(t.trim())?.time ?: 0L }.getOrDefault(0L)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "item") {
                        inItem = false
                        val clean = if (source.isNotBlank()) title.removeSuffix(" - $source") else title
                        out += Headline(clean.trim(), source.trim(), link.trim(), date)
                    }
                    tag = ""
                }
            }
            parser.next()
        }
        return out.sortedByDescending { it.publishedMs }
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "*/*").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IllegalStateException("Empty response")
        }
    }
}
