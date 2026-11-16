package org.sihai.apm

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One ~10ms frame. Float PCM is planar; PCM16 is interleaved. */
data class StreamFormat(val sampleRateHz: Int, val channels: Int) {
    init {
        require(sampleRateHz in 8000..384000)
        require(channels > 0 && channels.toLong() * (sampleRateHz / 100) * 4 <= Int.MAX_VALUE)
    }
    val frames: Int get() = sampleRateHz / 100
    val samples: Int get() = frames * channels
}

data class ProcessingFormats(
    val captureInput: StreamFormat,
    val captureOutput: StreamFormat = captureInput,
    val renderInput: StreamFormat = captureInput,
    val renderOutput: StreamFormat = renderInput,
)

class ApmException(val status: Int) : IllegalStateException("WebRTC APM status $status")

/** Optional statistics remain null when upstream has no measurement. */
data class ApmStatistics(
    val voiceDetected: Boolean?,
    val echoReturnLossDb: Double?,
    val echoReturnLossEnhancementDb: Double?,
    val divergentFilterFraction: Double?,
    val delayMedianMs: Int?,
    val delayStandardDeviationMs: Int?,
    val residualEchoLikelihood: Double?,
    val residualEchoLikelihoodRecentMax: Double?,
    val delayMs: Int?,
)

sealed class RuntimeSetting protected constructor(internal val type: Int, internal val value: Double, internal val second: Int = 0) {
    class CapturePreGain(gain: Float) : RuntimeSetting(1, gain.toDouble()) { init { require(gain.isFinite() && gain >= 0) } }
    class CompressionGainDb(gain: Int) : RuntimeSetting(2, gain.toDouble()) { init { require(gain in 0..90) } }
    class CaptureFixedPostGainDb(gain: Float) : RuntimeSetting(3, gain.toDouble()) { init { require(gain.isFinite() && gain in 0f..90f) } }
    class PlayoutVolume(volume: Int) : RuntimeSetting(4, volume.toDouble()) { init { require(volume >= 0) } }
    /** Only meaningful when a native custom render processor is installed. */
    class CustomRenderPayload(payload: Float) : RuntimeSetting(5, payload.toDouble()) { init { require(payload.isFinite()) } }
    class PlayoutDevice(id: Int, maxVolume: Int) : RuntimeSetting(6, id.toDouble(), maxVolume) { init { require(maxVolume >= 0) } }
    class CapturePostGain(gain: Float) : RuntimeSetting(7, gain.toDouble()) { init { require(gain.isFinite() && gain >= 0) } }
    class CaptureOutputUsed(used: Boolean) : RuntimeSetting(8, if (used) 1.0 else 0.0)
}

/**
 * Owns one upstream APM. All calls are serialized against close and configuration.
 * Buffers must be direct, native-order, and contain one frame at their position.
 * Positions are unchanged. Callers must not mutate a buffer during a call.
 */
class AudioProcessing(
    config: ApmConfig = ApmConfig(),
    aec3Config: Aec3Config? = null,
    multichannelAec3Config: Aec3Config? = null,
    residualEchoDetector: Boolean = false,
) : Closeable {
    private var handle = NativeBridge.create(config.values.copyOf(), aec3Config?.values?.copyOf(),
        multichannelAec3Config?.values?.copyOf(), residualEchoDetector)

    init { check(handle != 0L) { "APM creation failed" } }

    companion object {
        val upstreamRevision: String get() = NativeBridge.revision()
        const val STREAM_PARAMETER_WARNING = -13
        fun buffer(format: StreamFormat, floatingPoint: Boolean = false): ByteBuffer =
            ByteBuffer.allocateDirect(format.samples * if (floatingPoint) 4 else 2).order(ByteOrder.nativeOrder())
    }

    private fun alive(): Long { check(handle != 0L) { "APM is closed" }; return handle }
    private fun status(code: Int): Int {
        if (code < 0 && code != STREAM_PARAMETER_WARNING) throw ApmException(code)
        return code
    }
    private fun checkBuffer(buffer: ByteBuffer, format: StreamFormat, bytes: Int, writable: Boolean) {
        require(buffer.isDirect && buffer.order() == ByteOrder.nativeOrder())
        require(buffer.position() % bytes == 0 && buffer.remaining() >= format.samples * bytes)
        require(!writable || !buffer.isReadOnly)
    }

    /** Retains algorithm settings while resetting stream state. */
    @Synchronized fun initialize(formats: ProcessingFormats? = null): Int {
        val packed = formats?.let { intArrayOf(it.captureInput.sampleRateHz, it.captureInput.channels,
            it.captureOutput.sampleRateHz, it.captureOutput.channels, it.renderInput.sampleRateHz,
            it.renderInput.channels, it.renderOutput.sampleRateHz, it.renderOutput.channels) }
        return status(NativeBridge.initialize(alive(), packed))
    }
    @Synchronized fun applyConfig(config: ApmConfig) { NativeBridge.applyConfig(alive(), config.values.copyOf()) }
    @Synchronized fun configuration(): ApmConfig = ApmConfig(NativeBridge.getConfig(alive()))

    @Synchronized fun processCapture(input: ByteBuffer, output: ByteBuffer, inputFormat: StreamFormat,
        outputFormat: StreamFormat = inputFormat, floatingPoint: Boolean = false): Int =
        process(0, input, output, inputFormat, outputFormat, floatingPoint)
    @Synchronized fun processRender(input: ByteBuffer, output: ByteBuffer, inputFormat: StreamFormat,
        outputFormat: StreamFormat = inputFormat, floatingPoint: Boolean = false): Int =
        process(1, input, output, inputFormat, outputFormat, floatingPoint)
    @Synchronized fun analyzeRender(input: ByteBuffer, format: StreamFormat): Int =
        process(2, input, null, format, format, true)

    private fun process(direction: Int, input: ByteBuffer, output: ByteBuffer?,
        source: StreamFormat, destination: StreamFormat, floating: Boolean): Int {
        alive()
        val bytes = if (floating) 4 else 2
        checkBuffer(input, source, bytes, false)
        output?.let { checkBuffer(it, destination, bytes, true) }
        if (!floating) {
            require(source.sampleRateHz in setOf(8000, 16000, 32000, 48000))
            require(source == destination) { "PCM16 requires matching input/output formats; use float for conversion" }
        } else require(destination.channels == 1 || destination.channels == source.channels)
        return status(NativeBridge.process(handle, direction, floating, input, input.position(), source.sampleRateHz,
            source.channels, output, output?.position() ?: 0, destination.sampleRateHz, destination.channels))
    }

    @Synchronized fun setStreamDelayMs(delayMs: Int): Int = status(NativeBridge.setStream(alive(), 0, delayMs))
    @Synchronized fun streamDelayMs(): Int = NativeBridge.getStream(alive(), 0)
    @Synchronized fun setAnalogLevel(level: Int) {
        require(level in 0..255); NativeBridge.setStream(alive(), 1, level)
    }
    @Synchronized fun recommendedAnalogLevel(): Int = NativeBridge.getStream(alive(), 1)
    @Synchronized fun setKeyPressed(pressed: Boolean) { NativeBridge.setStream(alive(), 2, if (pressed) 1 else 0) }
    @Synchronized fun setOutputMuted(muted: Boolean) { NativeBridge.setStream(alive(), 3, if (muted) 1 else 0) }
    /** queued=true exposes PostRuntimeSetting's queue-full result. */
    @Synchronized fun setRuntimeSetting(setting: RuntimeSetting, queued: Boolean = false): Boolean =
        NativeBridge.runtime(alive(), setting.type, setting.value, setting.second, queued)

    @Synchronized fun statistics(hasRemoteTracks: Boolean? = null): ApmStatistics {
        val v = NativeBridge.statistics(alive(), hasRemoteTracks?.let { if (it) 1 else 0 } ?: -1)
        fun optional(i: Int) = v[i].takeUnless { it.isNaN() }
        return ApmStatistics(optional(0)?.let { it != 0.0 }, optional(1), optional(2), optional(3),
            optional(4)?.toInt(), optional(5)?.toInt(), optional(6), optional(7), optional(8)?.toInt())
    }
    /** Planar float, 160 samples per processing output channel, always 16kHz. */
    @Synchronized fun linearAecOutput(output: ByteBuffer): Boolean {
        val channels = NativeBridge.getStream(alive(), 5)
        if (channels <= 0) return false
        checkBuffer(output, StreamFormat(16000, channels), 4, true)
        return NativeBridge.linearOutput(handle, output, output.position(), channels)
    }
    @Synchronized fun processingSampleRateHz(): Int = NativeBridge.getStream(alive(), 4)
    @Synchronized fun processingChannels(): Int = NativeBridge.getStream(alive(), 5)
    @Synchronized fun splitSampleRateHz(): Int = NativeBridge.getStream(alive(), 6)
    @Synchronized fun inputChannels(): Int = NativeBridge.getStream(alive(), 7)
    @Synchronized fun outputChannels(): Int = NativeBridge.getStream(alive(), 8)
    @Synchronized fun renderChannels(): Int = NativeBridge.getStream(alive(), 9)

    /** Duplicates the descriptor: the caller retains ownership of its descriptor. */
    @Synchronized fun startAecDump(descriptor: ParcelFileDescriptor, maxBytes: Long = -1): Boolean {
        require(maxBytes == -1L || maxBytes > 0)
        return NativeBridge.startDump(alive(), descriptor.fd, maxBytes)
    }
    @Synchronized fun startAecDump(file: File, maxBytes: Long = -1): Boolean =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or
            ParcelFileDescriptor.MODE_WRITE_ONLY).use { startAecDump(it, maxBytes) }
    @Synchronized fun stopAecDump() { NativeBridge.stopDump(alive()) }
    @Synchronized override fun close() {
        if (handle != 0L) { NativeBridge.destroy(handle); handle = 0 }
    }
}
