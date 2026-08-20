package com.jamesm92.nomadportal.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import com.jamesm92.nomadportal.data.calling.CallRepository
import com.jamesm92.nomadportal.data.calling.CallStatusValue
import com.jamesm92.nomadportal.permissions.hasRecordAudioPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "CallAudioEngine"

// Opus/VoIP tuning -- see the nomadportal-android-competitor-research
// memory for how these were chosen. 20ms matches LXST's own
// LATENCY_LOW profile; RNS.Link.MDU (~431 bytes on this app's real
// interfaces) comfortably fits one Opus frame at this bitrate with
// room to spare, so no fragmentation/RNS.Resource is ever needed.
private const val SAMPLE_RATE_HZ = 48000
private const val FRAME_DURATION_MS = 20
private const val SAMPLES_PER_FRAME = SAMPLE_RATE_HZ * FRAME_DURATION_MS / 1000 // 960
private const val PCM_FRAME_BYTES = SAMPLES_PER_FRAME * 2 // 16-bit PCM
private const val OPUS_BITRATE = 24_000
private const val CODEC_MIME_OPUS = MediaFormat.MIMETYPE_AUDIO_OPUS

// Codec-type header byte prepended to every frame handed to
// send_call_audio_frame / stripped from every frame pop_call_audio_frame
// returns -- see call_manager.py's module doc comment for the full
// byte-value table (0x00 Raw/0x01 Opus/0x02 Codec2/0xFF Null). This
// engine only ever sends Opus.
private const val CODEC_HEADER_OPUS: Byte = 0x01

private const val CODEC_DEQUEUE_TIMEOUT_US = 10_000L // 10ms
// How long pop_call_audio_frame blocks the playback thread per call
// when nothing's arrived yet -- short enough that stop() (which relies
// on this thread noticing `running` has flipped between blocking
// calls) reacts promptly.
private const val PLAYBACK_POP_TIMEOUT_S = 0.2

/**
 * Owns the entire audio path for an ESTABLISHED voice call: capture ->
 * Opus encode -> send, and receive -> Opus decode -> playback. Starts/
 * stops itself automatically as [callRepository]'s call state
 * transitions into/out of [CallStatusValue.ESTABLISHED] -- nothing
 * else needs to drive its lifecycle (constructed once in
 * NomadPortalApp alongside the other app-wide singletons).
 *
 * Deliberately Kotlin-only: python-core's call_manager.py has zero
 * codec knowledge and zero Java interop (there's no precedent for
 * Python calling into Android APIs anywhere in this codebase) -- it
 * only relays already-encoded opaque bytes over the RNS Link via
 * send_call_audio_frame/pop_call_audio_frame. See the
 * nomadportal-android-competitor-research memory for why this split
 * was chosen (lxst-the-package isn't installable under Chaquopy).
 *
 * Capture and playback each run on their own dedicated [Thread] (not a
 * coroutine) -- both are long-lived, tightly-paced, blocking-I/O loops
 * (AudioRecord.read()/AudioTrack.write() block in real time; the
 * playback loop also blocks inside the Python call itself via
 * queue.Queue.get(timeout=...)), the same "plain background Thread"
 * shape call_manager.py's own timeout/announce-loop jobs already use
 * on the Python side.
 *
 * v1 scope, deliberately: no mute button, no speakerphone/earpiece
 * toggle, no adaptive bitrate. See this feature's own plan for the
 * full list of what's intentionally deferred, not forgotten.
 */
class CallAudioEngine(
    private val context: Context,
    private val callRepository: CallRepository,
    private val scope: CoroutineScope,
) {
    private val orchestrator by lazy {
        Python.getInstance().getModule("nomadportal_core.orchestrator")
    }
    private val audioManager: AudioManager by lazy {
        context.getSystemService(AudioManager::class.java)
    }

    @Volatile private var running = false
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null

    // Proactively stopped (not just left for the loop's own `running`
    // check) so a blocked AudioRecord.read()/AudioTrack.write() call
    // unblocks immediately when a call ends, rather than stop()'s
    // join() having to wait out whatever's currently in flight.
    @Volatile private var activeAudioRecord: AudioRecord? = null
    @Volatile private var activeAudioTrack: AudioTrack? = null

    // Diagnostics-only counters (Columba interop debugging, Aug 2026):
    // real evidence needed on whether a cross-implementation peer is
    // even sending frames this engine can decode, rather than guessing
    // from AudioTrack underrun counts alone. Not used for any control
    // flow -- safe to leave in.
    @Volatile private var rxFrameCount = 0
    @Volatile private var rxDecodedCount = 0
    @Volatile private var rxInputBufferUnavailableCount = 0
    private var lastPlaybackStatsLogAtMs = 0L

    init {
        scope.launch {
            callRepository.callState()
                .map { it.status == CallStatusValue.ESTABLISHED }
                .distinctUntilChanged()
                .collect { established -> if (established) start() else stop() }
        }
    }

    @Synchronized
    private fun start() {
        if (running) return
        running = true
        rxFrameCount = 0
        rxDecodedCount = 0
        rxInputBufferUnavailableCount = 0
        lastPlaybackStatsLogAtMs = 0L
        Log.i(TAG, "Call established -- starting audio engine")
        // Required for correct VOICE_COMMUNICATION-source routing/AEC,
        // not optional polish (source.android.com/docs/core/audio/
        // implement-pre-processing).
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        captureThread = Thread(::captureLoop, "CallAudioCapture").apply { isDaemon = true; start() }
        playbackThread = Thread(::playbackLoop, "CallAudioPlayback").apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun stop() {
        if (!running) return
        running = false
        Log.i(TAG, "Call ended -- stopping audio engine")
        runCatching { activeAudioRecord?.stop() }
        runCatching { activeAudioTrack?.stop() }
        captureThread?.join(500)
        playbackThread?.join(800) // pop_call_audio_frame's own timeout can hold this thread up to PLAYBACK_POP_TIMEOUT_S
        captureThread = null
        playbackThread = null
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    // ------------------------------------------------------------------
    // Capture: mic -> Opus encode -> send_call_audio_frame
    // ------------------------------------------------------------------

    private fun captureLoop() {
        if (!hasRecordAudioPermission(context)) {
            // Graceful degrade, not a failed call -- see
            // CallAudioPermission.kt's own doc comment.
            Log.w(TAG, "RECORD_AUDIO not granted -- this call is receive-only")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "AudioRecord.getMinBufferSize failed ($minBuffer) -- skipping capture")
            return
        }

        // Real lint finding (MissingPermission), not a suppressed one:
        // the hasRecordAudioPermission(context) guard above already makes
        // this call safe, but it's a custom wrapper in a different file
        // — lint's MissingPermission check only recognizes a direct
        // ContextCompat.checkSelfPermission(...) == PERMISSION_GRANTED
        // test in the same function as the AudioRecord.Builder() call it
        // guards, not one behind an indirection like that. Duplicating
        // the check inline here (rather than @SuppressLint) is the real
        // fix, not a false-positive exception — it's also a genuine
        // extra safety margin against the (narrow but real) window where
        // the permission could be revoked between the early-return check
        // above and this actual construction.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted -- this call is receive-only")
            return
        }

        val audioRecord = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, PCM_FRAME_BYTES * 4))
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord construction failed: $e")
            return
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord failed to initialize")
            audioRecord.release()
            return
        }
        activeAudioRecord = audioRecord

        // Best-effort -- not every device exposes these, and a call
        // must work fine without them (VOICE_COMMUNICATION often gets
        // AEC "for free" via the platform's own default effects chain,
        // but that isn't guaranteed on every device/OEM).
        val echoCanceler = if (AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(audioRecord.audioSessionId)?.apply { enabled = true } }.getOrNull()
        } else null
        val noiseSuppressor = if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(audioRecord.audioSessionId)?.apply { enabled = true } }.getOrNull()
        } else null

        val encoder = try {
            createEncoder()
        } catch (e: Exception) {
            Log.w(TAG, "Opus encoder setup failed: $e")
            activeAudioRecord = null
            audioRecord.release()
            echoCanceler?.release()
            noiseSuppressor?.release()
            return
        }

        val pcmBuffer = ByteArray(PCM_FRAME_BYTES)
        try {
            audioRecord.startRecording()
            encoder.start()
            while (running) {
                val read = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                if (read <= 0) continue
                encodeAndSend(encoder, pcmBuffer, read)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Capture loop ended abnormally: $e")
        } finally {
            activeAudioRecord = null
            runCatching { audioRecord.stop() }
            audioRecord.release()
            echoCanceler?.release()
            noiseSuppressor?.release()
            runCatching { encoder.stop() }
            encoder.release()
        }
    }

    private fun createEncoder(): MediaCodec {
        val format = MediaFormat.createAudioFormat(CODEC_MIME_OPUS, SAMPLE_RATE_HZ, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, OPUS_BITRATE)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        val codec = MediaCodec.createEncoderByType(CODEC_MIME_OPUS)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    // Synchronous (not async-callback) MediaCodec usage, deliberately:
    // this thread's own AudioRecord.read() already paces frames in
    // real time, so async mode's main benefit (not needing to pick a
    // dequeue timeout yourself) doesn't apply here.
    private fun encodeAndSend(encoder: MediaCodec, pcm: ByteArray, length: Int) {
        val inputIndex = encoder.dequeueInputBuffer(CODEC_DEQUEUE_TIMEOUT_US)
        if (inputIndex >= 0) {
            val inputBuffer = encoder.getInputBuffer(inputIndex)
            inputBuffer?.clear()
            inputBuffer?.put(pcm, 0, length)
            encoder.queueInputBuffer(inputIndex, 0, length, System.nanoTime() / 1000, 0)
        }
        val info = MediaCodec.BufferInfo()
        var outputIndex = encoder.dequeueOutputBuffer(info, CODEC_DEQUEUE_TIMEOUT_US)
        while (outputIndex >= 0) {
            val outputBuffer = encoder.getOutputBuffer(outputIndex)
            if (outputBuffer != null && info.size > 0) {
                val frame = ByteArray(info.size + 1)
                frame[0] = CODEC_HEADER_OPUS
                outputBuffer.get(frame, 1, info.size)
                sendAudioFrame(frame)
            }
            encoder.releaseOutputBuffer(outputIndex, false)
            outputIndex = encoder.dequeueOutputBuffer(info, 0)
        }
    }

    private fun sendAudioFrame(frame: ByteArray) {
        try {
            orchestrator.callAttr("send_call_audio_frame", frame)
        } catch (e: Exception) {
            Log.w(TAG, "send_call_audio_frame failed: $e")
        }
    }

    // ------------------------------------------------------------------
    // Playback: pop_call_audio_frame -> Opus decode -> speaker
    // ------------------------------------------------------------------

    private fun playbackLoop() {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "AudioTrack.getMinBufferSize failed ($minBuffer) -- no playback this call")
            return
        }

        val audioTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, PCM_FRAME_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack construction failed: $e")
            return
        }
        activeAudioTrack = audioTrack

        val decoder = try {
            createDecoder()
        } catch (e: Exception) {
            Log.w(TAG, "Opus decoder setup failed: $e")
            activeAudioTrack = null
            audioTrack.release()
            return
        }

        try {
            audioTrack.play()
            decoder.start()
            while (running) {
                val frame = popAudioFrame()
                if (frame == null) {
                    maybeLogPlaybackStats() // heartbeat even while nothing's arriving -- distinguishes "zero frames ever" from "frames arriving but not decoding" in logcat
                    continue // timeout -- loop and re-check `running`
                }
                decodeAndPlay(decoder, audioTrack, frame)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Playback loop ended abnormally: $e")
        } finally {
            activeAudioTrack = null
            runCatching { audioTrack.stop() }
            audioTrack.release()
            runCatching { decoder.stop() }
            decoder.release()
        }
    }

    private fun popAudioFrame(): ByteArray? =
        try {
            orchestrator.callAttr("pop_call_audio_frame", PLAYBACK_POP_TIMEOUT_S)
                ?.toJava(ByteArray::class.java)
        } catch (e: Exception) {
            // Covers both a genuine bridge error and Chaquopy's None
            // handling for an empty queue, whichever shape it takes --
            // either way, "no frame this tick" is the right response.
            null
        }

    // A real on-device failure this fixes: Android's Codec2 Opus decoder
    // (C2SoftOpusDec) requires codec-specific-data at configure() time --
    // without it, the very first internal "config" work item fails
    // immediately (logcat: "C2SoftOpusDec: process encountered error in
    // GetOpusHeaderBuffers"), before any real frame is ever fed to it.
    // This is the container-oriented csd-0/csd-1/csd-2 convention
    // Android's MediaCodec API expects even for a raw, non-file live
    // stream like this one (confirmed against AOSP's
    // frameworks/av/media/module/foundation/OpusHeader.cpp,
    // GetOpusHeaderBuffers' "legacy" 3-buffer format) -- csd-0 is a
    // minimal RFC 7845 SS5.1 Opus identification header describing this
    // stream (mono/48kHz, matching encodeAndSend's own PCM format);
    // csd-1/csd-2 (codec delay / seek pre-roll, both in nanoseconds) are
    // zeroed since this app doesn't do any of its own sample trimming or
    // seeking -- a real encoder's own internal lookahead isn't relayed
    // over the wire in this v1, so there may be a few dozen milliseconds
    // of imperfectly-trimmed audio right at call start, not a
    // correctness issue for a continuous live stream.
    private fun createDecoder(): MediaCodec {
        val format = MediaFormat.createAudioFormat(CODEC_MIME_OPUS, SAMPLE_RATE_HZ, 1).apply {
            setByteBuffer("csd-0", buildOpusIdentificationHeader())
            setByteBuffer("csd-1", buildZeroU64Csd())
            setByteBuffer("csd-2", buildZeroU64Csd())
        }
        val codec = MediaCodec.createDecoderByType(CODEC_MIME_OPUS)
        codec.configure(format, null, null, 0)
        return codec
    }

    private fun buildOpusIdentificationHeader(): ByteBuffer {
        val buf = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("OpusHead".toByteArray(Charsets.US_ASCII)) // magic, 8 bytes
        buf.put(1.toByte())          // version
        buf.put(1.toByte())          // channel count (mono)
        buf.putShort(0.toShort())    // pre-skip
        buf.putInt(SAMPLE_RATE_HZ)   // input sample rate
        buf.putShort(0.toShort())    // output gain
        buf.put(0.toByte())          // channel mapping family (0 == mono/stereo direct)
        buf.flip()
        return buf
    }

    private fun buildZeroU64Csd(): ByteBuffer {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(0L)
        buf.flip()
        return buf
    }

    private fun decodeAndPlay(decoder: MediaCodec, audioTrack: AudioTrack, frame: ByteArray) {
        rxFrameCount++
        // First several frames logged individually (size + codec-header
        // byte) -- real interop debugging data: confirms whether a
        // cross-implementation peer (e.g. Columba's own from-scratch
        // LXST-kt) is sending frames at all, and whether its codec
        // header byte convention actually matches this app's assumption
        // (0x01 == Opus, verbatim from LXST/Primitives/Telephony.py --
        // see call_manager.py's own doc comment). After the first few,
        // only a periodic summary logs, to avoid spamming logcat for
        // the rest of a real call.
        if (rxFrameCount <= 10) {
            Log.i(TAG, "RX frame #$rxFrameCount: size=${frame.size} header=0x${"%02x".format(frame.getOrNull(0))}")
        }
        maybeLogPlaybackStats()

        if (frame.isEmpty() || frame[0] != CODEC_HEADER_OPUS) {
            // Either an empty frame, or a codec-header byte this engine
            // doesn't decode (this app only ever sends 0x01/Opus, but a
            // different real implementation could legitimately send
            // something else -- e.g. Codec2 -- that this app doesn't
            // support yet). Dropped, not fed to the decoder.
            return
        }
        val payload = frame.copyOfRange(1, frame.size)

        val inputIndex = decoder.dequeueInputBuffer(CODEC_DEQUEUE_TIMEOUT_US)
        if (inputIndex >= 0) {
            val inputBuffer = decoder.getInputBuffer(inputIndex)
            inputBuffer?.clear()
            inputBuffer?.put(payload)
            decoder.queueInputBuffer(inputIndex, 0, payload.size, System.nanoTime() / 1000, 0)
        } else {
            // The decoder's input queue had no free buffer within
            // CODEC_DEQUEUE_TIMEOUT_US -- this frame is silently
            // dropped (not queued at all), which would explain
            // AudioTrack starvation if it happens often: real evidence
            // needed via rxInputBufferUnavailableCount, not an
            // assumption.
            rxInputBufferUnavailableCount++
        }
        val info = MediaCodec.BufferInfo()
        var outputIndex = decoder.dequeueOutputBuffer(info, CODEC_DEQUEUE_TIMEOUT_US)
        while (outputIndex >= 0) {
            val outputBuffer = decoder.getOutputBuffer(outputIndex)
            if (outputBuffer != null && info.size > 0) {
                val pcm = ByteArray(info.size)
                outputBuffer.get(pcm)
                audioTrack.write(pcm, 0, pcm.size)
                rxDecodedCount++
            }
            decoder.releaseOutputBuffer(outputIndex, false)
            outputIndex = decoder.dequeueOutputBuffer(info, 0)
        }
    }

    private fun maybeLogPlaybackStats() {
        val now = System.currentTimeMillis()
        if (now - lastPlaybackStatsLogAtMs < 2000) return
        lastPlaybackStatsLogAtMs = now
        Log.i(
            TAG,
            "Playback stats: received=$rxFrameCount decoded=$rxDecodedCount " +
                "inputBufferUnavailable=$rxInputBufferUnavailableCount",
        )
    }
}
