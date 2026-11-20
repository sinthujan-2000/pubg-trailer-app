package com.trailer.app

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : Activity() {
    private var videoUri: Uri? = null
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var makeBtn: Button
    private lateinit var r30: RadioButton
    private lateinit var rPortrait: RadioButton

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 80, 48, 48) }
        fun tv(t: String) = TextView(this).apply { text = t; textSize = 18f; setPadding(0, 32, 0, 8) }
        root.addView(tv("🎬 PUBG சினிமா ட்ரெய்லர்").apply { textSize = 24f })
        val pick = Button(this).apply { text = "1. வீடியோவைத் தேர்ந்தெடு" }
        pick.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "video/*" }, 1)
        }
        root.addView(pick)
        root.addView(tv("2. ட்ரெய்லர் நீளம்"))
        val g1 = RadioGroup(this)
        r30 = RadioButton(this).apply { text = "30 விநாடி"; isChecked = true; id = View.generateViewId() }
        val r60 = RadioButton(this).apply { text = "60 விநாடி"; id = View.generateViewId() }
        g1.addView(r30); g1.addView(r60); root.addView(g1)
        root.addView(tv("3. சைஸ்"))
        val g2 = RadioGroup(this)
        rPortrait = RadioButton(this).apply { text = "9:16 (Reels / Shorts)"; isChecked = true; id = View.generateViewId() }
        val rLand = RadioButton(this).apply { text = "16:9 (YouTube)"; id = View.generateViewId() }
        g2.addView(rPortrait); g2.addView(rLand); root.addView(g2)
        makeBtn = Button(this).apply { text = "4. ட்ரெய்லர் உருவாக்கு" }
        makeBtn.setOnClickListener { make() }
        root.addView(makeBtn)
        bar = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        root.addView(bar)
        status = tv("வீடியோவைத் தேர்ந்தெடுங்க.")
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1 && res == RESULT_OK && data?.data != null) {
            videoUri = data.data
            status.text = "வீடியோ தேர்ந்தெடுக்கப்பட்டது ✅"
        }
    }

    private fun busy(on: Boolean, msg: String) {
        bar.visibility = if (on) View.VISIBLE else View.GONE
        makeBtn.isEnabled = !on
        status.text = msg
    }

    private fun make() {
        val uri = videoUri ?: run { status.text = "முதல்ல வீடியோவைத் தேர்ந்தெடுங்க."; return }
        val targetSec = if (r30.isChecked) 30 else 60
        val ratio = if (rPortrait.isChecked) 9f / 16f else 16f / 9f
        busy(true, "ஹைலைட்களைத் தேடுறேன்... (நீளமான வீடியோவுக்கு சில நிமிஷம் ஆகும்)")
        Thread {
            try {
                val durMs = duration(uri)
                val loud = loudness(uri)
                val clips = pick(loud, durMs, targetSec)
                runOnUiThread { export(uri, clips, ratio) }
            } catch (e: Exception) {
                runOnUiThread { busy(false, "பிழை: ${e.message}") }
            }
        }.start()
    }

    private fun duration(uri: Uri): Long {
        val r = MediaMetadataRetriever()
        r.setDataSource(this, uri)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        r.release()
        return d
    }

    // ஒவ்வொரு விநாடிக்கும் ஆடியோ சத்த அளவு (RMS)
    private fun loudness(uri: Uri): DoubleArray {
        val ex = MediaExtractor()
        ex.setDataSource(this, uri, null)
        var track = -1
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/")) { track = i; break }
        }
        if (track < 0) throw Exception("வீடியோவில் ஆடியோ இல்லை")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val secs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) (fmt.getLong(MediaFormat.KEY_DURATION) / 1_000_000).toInt() + 2 else 7200
        val sum = DoubleArray(secs); val cnt = LongArray(secs)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        while (!outDone) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(10000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10000)
            if (o >= 0) {
                val out = codec.getOutputBuffer(o)!!
                val s = (info.presentationTimeUs / 1_000_000).toInt().coerceIn(0, secs - 1)
                val sb = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                var k = 0
                while (sb.hasRemaining() && k < info.size / 2) { val v = sb.get().toDouble(); sum[s] += v * v; cnt[s]++; k++ }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        return DoubleArray(secs) { if (cnt[it] > 0) sqrt(sum[it] / cnt[it]) else 0.0 }
    }

    // அதிக சத்தம் உள்ள இடங்களைத் தேர்ந்தெடு; build-up ஆக சத்தம் கூடும் வரிசையில் அடுக்கு
    private fun pick(loud: DoubleArray, durMs: Long, targetSec: Int): List<Pair<Long, Long>> {
        val clipLen = 5
        val n = targetSec / clipLen
        val chosen = ArrayList<Int>()
        for (i in loud.indices.sortedByDescending { loud[it] }) {
            if (chosen.size >= n) break
            if (i * 1000L < durMs && chosen.none { abs(it - i) < clipLen + 1 }) chosen.add(i)
        }
        if (chosen.isEmpty()) throw Exception("ஹைலைட் கிடைக்கல")
        chosen.sortBy { loud[it] }
        return chosen.map { s ->
            val start = maxOf(0L, (s - 3) * 1000L)
            Pair(start, minOf(durMs, start + clipLen * 1000L))
        }.filter { it.second - it.first >= 1000 }
    }

    private fun export(uri: Uri, clips: List<Pair<Long, Long>>, ratio: Float) {
        busy(true, "ட்ரெய்லரை உருவாக்குறேன்... பொறுமையா இருங்க")
        val items = clips.map { (s, e) ->
            val mi = MediaItem.Builder().setUri(uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s).setEndPositionMs(e).build()
            ).build()
            EditedMediaItem.Builder(mi).setEffects(
                Effects(emptyList(), listOf(Presentation.createForAspectRatio(ratio, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)))
            ).build()
        }
        val comp = Composition.Builder(EditedMediaItemSequence(items)).build()
        val out = File(cacheDir, "trailer.mp4")
        if (out.exists()) out.delete()
        val t = Transformer.Builder(this).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                try {
                    val cv = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "trailer_${System.currentTimeMillis()}.mp4")
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PubgTrailer")
                    }
                    val dst = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
                    contentResolver.openOutputStream(dst)!!.use { o -> out.inputStream().use { it.copyTo(o) } }
                    busy(false, "முடிஞ்சது! 🎉 Gallery → Movies/PubgTrailer-ல் பாருங்க.")
                } catch (e: Exception) { busy(false, "சேமிக்கும்போது பிழை: ${e.message}") }
            }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                busy(false, "பிழை: ${exportException.message}")
            }
        }).build()
        t.start(comp, out.absolutePath)
    }
}
