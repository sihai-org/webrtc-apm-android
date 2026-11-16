package org.sihai.apm

import java.nio.ByteBuffer

internal object NativeBridge {
    init { System.loadLibrary("webrtc_apm_jni") }
    external fun defaultConfig(kind: Int): DoubleArray
    external fun create(config: DoubleArray, aec3: DoubleArray?, multichannel: DoubleArray?, residualEchoDetector: Boolean): Long
    external fun destroy(handle: Long)
    external fun initialize(handle: Long, formats: IntArray?): Int
    external fun applyConfig(handle: Long, config: DoubleArray)
    external fun getConfig(handle: Long): DoubleArray
    external fun process(handle: Long, direction: Int, floating: Boolean,
        input: ByteBuffer, inputOffset: Int, inputRate: Int, inputChannels: Int,
        output: ByteBuffer?, outputOffset: Int, outputRate: Int, outputChannels: Int): Int
    external fun setStream(handle: Long, setting: Int, value: Int): Int
    external fun getStream(handle: Long, setting: Int): Int
    external fun runtime(handle: Long, type: Int, value: Double, second: Int, queued: Boolean): Boolean
    external fun statistics(handle: Long, remoteTracks: Int): DoubleArray
    external fun linearOutput(handle: Long, output: ByteBuffer, offset: Int, channels: Int): Boolean
    external fun startDump(handle: Long, fd: Int, maxBytes: Long): Boolean
    external fun stopDump(handle: Long)
    external fun revision(): String
}
