package org.sihai.apm.consumer

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import org.sihai.apm.AudioProcessing
import org.sihai.apm.StreamFormat

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this).apply { setPadding(32, 32, 32, 32) }
        setContentView(label)
        label.text = runCatching {
            val format = StreamFormat(16000, 1)
            val input = AudioProcessing.buffer(format)
            val output = AudioProcessing.buffer(format)
            AudioProcessing().use { apm ->
                apm.processRender(input, output, format)
                apm.processCapture(input, output, format)
            }
            "AAR 加载及离线 PCM 处理成功\nWebRTC ${AudioProcessing.upstreamRevision}\n\n此页面不采集麦克风，也不证明实机消回声效果。"
        }.getOrElse { "AAR 验证失败：${it.message}" }
    }
}
