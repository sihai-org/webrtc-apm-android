# webrtc-apm-android

将 Google WebRTC Audio Processing Module 独立包装成 Android AAR，使用官方 AEC3、NS、AGC 等实现。无需 PeerConnection、完整 WebRTC Android SDK 或服务端。

当前处于首个版本构建验收阶段，尚未声明实机声学效果。正式产物以通过验证的 [Releases](https://github.com/sihai-org/webrtc-apm-android/releases) 为准。

```kotlin
val config = ApmConfig().apply {
    echoCanceller.enabled = true
    noiseSuppression.enabled = true
}
AudioProcessing(config).use { apm ->
    // 持续提交即将播放的参考 PCM 和麦克风 PCM；见 API 文档。
}
```

- Android API 29+；armeabi-v7a、arm64-v8a、x86_64。
- PCM16 interleaved / float32 planar，多通道与上游支持的格式转换。
- 完整配置映射、运行时设置、可选统计和 AEC dump。
- 单一 JNI 动态库，隐藏内部符号；不替应用控制录音、播放或音量。

导航：[项目地图](PROJECT.md) · [API 与示例](docs/api.md) · [能力与边界](docs/capabilities.md) · [构建和发布](docs/build.md)

本仓库构建和包装代码采用 BSD-3-Clause；WebRTC 及依赖按各自许可证分发，发行包附带依赖许可清单。项目不隶属于 Google，也不改变上游算法。
