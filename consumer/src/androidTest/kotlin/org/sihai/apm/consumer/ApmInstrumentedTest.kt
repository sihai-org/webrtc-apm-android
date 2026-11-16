package org.sihai.apm.consumer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.sihai.apm.*
import java.io.File
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10

@RunWith(AndroidJUnit4::class)
class ApmInstrumentedTest {
    @Test fun nativeDefaultsConfigRoundtripAndCopies() {
        val config = ApmConfig()
        assertFalse(config.echoCanceller.enabled)
        AudioProcessing(config).use { apm ->
            assertFalse(apm.linearAecOutput(AudioProcessing.buffer(StreamFormat(16000, 1), true)))
        }
        val copy = config.copy()
        copy.echoCanceller.enabled = true
        copy.noiseSuppression.enabled = true
        assertFalse(config.echoCanceller.enabled)
        AudioProcessing(copy, Aec3Config(), Aec3Config.multichannelDefaults(), true).use { apm ->
            assertTrue(apm.configuration().echoCanceller.enabled)
            assertTrue(apm.configuration().noiseSuppression.enabled)
            apm.applyConfig(config)
            assertFalse(apm.configuration().echoCanceller.enabled)
        }
        val invalid = Aec3Config().apply { delay.downSamplingFactor = 0 }
        assertThrows(IllegalArgumentException::class.java) { AudioProcessing(aec3Config = invalid).close() }
    }

    @Test fun pcm16PreservesUnprocessedSignalAtAllNativeRatesAndStereo() {
        for (rate in listOf(8000, 16000, 32000, 48000)) for (channels in listOf(1, 2)) {
            val format = StreamFormat(rate, channels)
            val input = AudioProcessing.buffer(format)
            val output = AudioProcessing.buffer(format)
            for (i in 0 until format.samples) input.putShort(i * 2, ((i * 73 % 12000) - 6000).toShort())
            AudioProcessing().use { apm ->
                apm.initialize(ProcessingFormats(format))
                assertEquals(0, apm.processRender(input, output, format))
                assertEquals(0, apm.processCapture(input, output, format))
                for (i in 0 until format.samples) assertEquals(input.getShort(i * 2), output.getShort(i * 2))
                assertEquals(0, input.position())
                apm.initialize()
            }
        }
    }

    @Test fun planarFloatSupportsResamplingAndDownmix() {
        val source = StreamFormat(48000, 2)
        val destination = StreamFormat(16000, 1)
        val input = AudioProcessing.buffer(source, true)
        val output = AudioProcessing.buffer(destination, true)
        for (i in 0 until source.samples) input.putFloat(i * 4, 0.1f)
        AudioProcessing().use { apm ->
            apm.initialize(ProcessingFormats(source, destination, source, destination))
            repeat(20) {
                apm.analyzeRender(input, source)
                apm.processCapture(input, output, source, destination, true)
            }
            assertTrue(output.getFloat(100 * 4) in 0.05f..0.15f)
            assertEquals(1, apm.outputChannels())
        }
    }

    @Test fun invalidBufferAndClosedInstanceFailWithoutNativeCrash() {
        val format = StreamFormat(16000, 1)
        val apm = AudioProcessing()
        val valid = AudioProcessing.buffer(format)
        assertThrows(IllegalArgumentException::class.java) {
            apm.processCapture(ByteBuffer.allocate(320), valid, format)
        }
        assertThrows(IllegalArgumentException::class.java) {
            apm.processCapture(valid, valid.asReadOnlyBuffer(), format)
        }
        apm.close(); apm.close()
        assertThrows(IllegalStateException::class.java) { apm.processCapture(valid, valid, format) }
    }

    @Test fun runtimeSettingsStatisticsDumpAndConcurrentClose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "test.aecdump")
        val format = StreamFormat(16000, 1)
        val audio = AudioProcessing.buffer(format)
        val config = ApmConfig().apply { echoCanceller.enabled = true; echoCanceller.exportLinearAecOutput = true }
        try {
            AudioProcessing(config).use { apm ->
                assertTrue(apm.startAecDump(file, 1000000))
                apm.setAnalogLevel(128)
                apm.setKeyPressed(false)
                apm.setOutputMuted(false)
                apm.setRuntimeSetting(RuntimeSetting.PlayoutDevice(1, 15))
                assertTrue(apm.setRuntimeSetting(RuntimeSetting.PlayoutVolume(5), true))
                repeat(100) {
                    apm.processRender(audio, audio, format)
                    apm.setStreamDelayMs(0)
                    apm.processCapture(audio, audio, format)
                }
                apm.statistics()
                apm.linearAecOutput(AudioProcessing.buffer(StreamFormat(16000, apm.processingChannels()), true))
                apm.stopAecDump()
            }
            assertTrue(file.length() > 0)
        } finally { file.delete() }
        val apm = AudioProcessing()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try { repeat(1000) { apm.processCapture(audio, audio, format) } }
            catch (error: IllegalStateException) {
                if (error.message != "APM is closed") failure.set(error)
            }
            catch (error: Throwable) { failure.set(error) }
        }
        worker.start(); apm.close(); worker.join()
        failure.get()?.let { throw AssertionError(it) }
    }

    @Test fun noiseSuppressionAndFixedDigitalGainAffectSamples() {
        val format = StreamFormat(16000, 1)
        val input = AudioProcessing.buffer(format)
        val output = AudioProcessing.buffer(format)
        val noise = Random(912)
        var before = 0.0
        var after = 0.0
        val ns = ApmConfig().apply {
            noiseSuppression.enabled = true
            noiseSuppression.level = ApmConfig.NoiseSuppression.Level.High
        }
        AudioProcessing(ns).use { apm ->
            repeat(500) { frame ->
                for (i in 0 until 160) input.putShort(i * 2, (noise.nextInt(2000) - 1000).toShort())
                apm.processCapture(input, output, format)
                if (frame >= 400) for (i in 0 until 160) {
                    before += input.getShort(i * 2).toDouble().let { it * it }
                    after += output.getShort(i * 2).toDouble().let { it * it }
                }
            }
        }
        assertTrue("NS must reduce stationary noise energy", after < before * 0.5)
        val gain = ApmConfig().apply {
            gainController2.enabled = true
            gainController2.fixedDigital.gainDb = 6f
        }
        AudioProcessing(gain).use { apm ->
            for (i in 0 until 160) input.putShort(i * 2, 1000)
            repeat(20) { apm.processCapture(input, output, format) }
            assertTrue("AGC2 fixed +6dB gain must be applied", output.getShort(100).toInt() in 1800..2200)
        }
    }

    @Test fun syntheticEchoIsAttenuatedWhileNearEndOnlyIsRetained() {
        val format = StreamFormat(16000, 1)
        val render = AudioProcessing.buffer(format)
        val capture = AudioProcessing.buffer(format)
        val output = AudioProcessing.buffer(format)
        val random = Random(731)
        val history = ShortArray(480)
        var cursor = 0
        var rawEnergy = 0.0
        var cleanEnergy = 0.0
        val config = ApmConfig().apply { echoCanceller.enabled = true }
        AudioProcessing(config).use { apm ->
            repeat(3000) { frame ->
                for (i in 0 until 160) {
                    val sample = (random.nextInt(12000) - 6000).toShort()
                    val echo = (history[cursor] * 0.5).toInt().toShort()
                    history[cursor] = sample
                    cursor = (cursor + 1) % history.size
                    render.putShort(i * 2, sample)
                    capture.putShort(i * 2, echo)
                }
                apm.processRender(render, render, format)
                apm.setStreamDelayMs(30)
                apm.processCapture(capture, output, format)
                if (frame >= 2000) for (i in 0 until 160) {
                    rawEnergy += capture.getShort(i * 2).toDouble().let { it * it }
                    cleanEnergy += output.getShort(i * 2).toDouble().let { it * it }
                }
            }
        }
        val reductionDb = 10 * log10(rawEnergy / cleanEnergy.coerceAtLeast(1.0))
        println("Synthetic far-end-only echo attenuation: $reductionDb dB")
        assertTrue("AEC must attenuate known linear echo by >10dB, got $reductionDb", reductionDb > 10)
        var nearEnergy = 0.0
        var retained = 0.0
        render.clear()
        for (i in 0 until 160) render.putShort(i * 2, 0)
        AudioProcessing(config).use { apm ->
            repeat(100) {
                for (i in 0 until 160) capture.putShort(i * 2, (random.nextInt(12000) - 6000).toShort())
                apm.processRender(render, render, format)
                apm.setStreamDelayMs(0)
                apm.processCapture(capture, output, format)
                for (i in 0 until 160) {
                    nearEnergy += capture.getShort(i * 2).toDouble().let { v -> v * v }
                    retained += output.getShort(i * 2).toDouble().let { v -> v * v }
                }
            }
        }
        assertTrue("Near-end-only input must not be muted", retained > nearEnergy * 0.1)
    }
}
