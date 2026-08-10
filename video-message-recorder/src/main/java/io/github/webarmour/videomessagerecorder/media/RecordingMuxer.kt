package io.github.webarmour.videomessagerecorder.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import kotlin.math.max

internal class RecordingMuxer(
    outputFile: File,
    private val originUs: Long,
) : AutoCloseable {

    enum class Track { VIDEO, AUDIO }

    private data class PendingSample(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    private val muxer = MediaMuxer(
        outputFile.absolutePath,
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
    )

    private val trackIndexes = mutableMapOf<Track, Int>()
    private val pending = mutableMapOf(
        Track.VIDEO to ArrayDeque<PendingSample>(),
        Track.AUDIO to ArrayDeque(),
    )

    private var started = false
    private var closed = false

    @Synchronized
    fun setTrackFormat(track: Track, format: MediaFormat) {
        check(!closed) { "Muxer is already closed" }
        if (trackIndexes.containsKey(track)) return

        trackIndexes[track] = muxer.addTrack(format)

        if (trackIndexes.size == Track.entries.size) {
            muxer.start()
            started = true
            flushPendingLocked(Track.VIDEO)
            flushPendingLocked(Track.AUDIO)
        }
    }

    @Synchronized
    fun writeSample(
        track: Track,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
    ) {
        if (closed || info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            return
        }

        val normalizedPtsUs = max(0L, info.presentationTimeUs - originUs)

        if (!started) {
            val duplicate = buffer.duplicate().apply {
                position(info.offset)
                limit(info.offset + info.size)
            }
            val bytes = ByteArray(info.size)
            duplicate.get(bytes)
            pending.getValue(track).addLast(
                PendingSample(
                    data = bytes,
                    presentationTimeUs = normalizedPtsUs,
                    flags = info.flags,
                )
            )
            return
        }

        writeDirectLocked(track, buffer, info, normalizedPtsUs)
    }

    private fun flushPendingLocked(track: Track) {
        val trackIndex = trackIndexes.getValue(track)
        val queue = pending.getValue(track)

        while (queue.isNotEmpty()) {
            val sample = queue.removeFirst()
            val buffer = ByteBuffer.wrap(sample.data)
            val info = MediaCodec.BufferInfo().apply {
                set(0, sample.data.size, sample.presentationTimeUs, sample.flags)
            }
            muxer.writeSampleData(trackIndex, buffer, info)
        }
    }

    private fun writeDirectLocked(
        track: Track,
        buffer: ByteBuffer,
        sourceInfo: MediaCodec.BufferInfo,
        normalizedPtsUs: Long,
    ) {
        val trackIndex = trackIndexes[track] ?: return
        val sample = buffer.duplicate().apply {
            position(sourceInfo.offset)
            limit(sourceInfo.offset + sourceInfo.size)
        }.slice()
        val info = MediaCodec.BufferInfo().apply {
            set(
                0,
                sourceInfo.size,
                normalizedPtsUs,
                sourceInfo.flags,
            )
        }
        muxer.writeSampleData(trackIndex, sample, info)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true

        runCatching {
            if (started) muxer.stop()
        }
        runCatching { muxer.release() }

        pending.values.forEach { it.clear() }
    }
}
