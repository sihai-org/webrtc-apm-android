# 上游能力与包装边界

能力基准是 [upstream.lock.json](../upstream.lock.json) 的官方 revision，不把其他版本的名称或特性混入当前 API。配置源来自 `api/audio/audio_processing.h` 和 `api/audio/echo_canceller3_config.h`。

| 上游能力 | Android 入口 / 实现 | 验证 |
| --- | --- | --- |
| AEC3、NS、AGC1/AGC2、HPF、前后增益、音量控制及 pipeline 配置 | ApmConfig 所有字段 | 上游解析与生成一致性、native 编译、配置回读 |
| AEC3 完整配置与多通道覆盖 | Aec3Config / multichannelDefaults；构造参数 | 136 字段映射、官方 Validate、实例创建 |
| 交错 PCM16 采集/参考处理 | processCapture / processRender | 所有 native 采样率、单/双声道透传 |
| planar float、不同输入输出格式 | floatingPoint=true | 48kHz stereo → 16kHz mono 转换 |
| 只分析播放参考 | analyzeRender | 浮点输入测试 |
| Initialize / ApplyConfig / GetConfig | initialize / applyConfig / configuration | 重置与回读 |
| analog level、delay、key、muted | 对应 set/get 方法 | native 调用与错误检查 |
| SetRuntimeSetting / PostRuntimeSetting | RuntimeSetting 与 queued 参数 | 同步/排队调用；保留队列结果 |
| 处理采样率/通道元数据 | processingSampleRateHz 等 | 消费测试 |
| 所有 AudioProcessingStats 字段 | ApmStatistics（nullable） | native 读取；缺失不转零 |
| 官方 residual echo detector | residualEchoDetector 构造参数 | 官方 detector 注入 |
| 线性 AEC 输出 | linearAecOutput | 开启并调用输出接口 |
| AEC dump | startAecDump / stopAecDump | 文件写入和关闭；protobuf 保留开启 |
| 资源安全与并发关闭 | 每实例串行调用、Closeable | concurrent close 测试 |

生成器对未知字段声明直接失败。升级时须重新解析上游并审阅 schema diff；不能仅保留旧 schema 让新增字段被丢弃。生成代码与完整字段列表见 [配置映射](config-fields.md)。

## 内置功能与 C++ 插件边界

本 AAR 包装官方内置处理能力。`BuiltinAudioProcessingBuilder` 另外允许用户注入自己实现的 EchoControlFactory、CustomProcessing、CustomAudioAnalyzer、NeuralResidualEchoEstimator，以及自定义 AecDump/Environment。这些接口接受 C++ 对象和所有权，不是内置算法开关。

当前不提供把任意 C++ 指针通过 Kotlin 传入的接口，也不实现自定义算法或神经网络模型。需要这些扩展时，应在本仓库 native 层通过相同工具链扩展并重新构建；现有官方 C++ 构建入口保留。`CustomRenderPayload` 仍可提交，但未注入自定义 render processor 时无对应消费者。

此边界不能被表述为“包含所有 WebRTC 算法”或“支持全部未来插件”。内置 AEC3 不依赖用户提供神经网络模型。上游 deprecated 的 transient suppression 和 voice-detected 字段保留接口/状态，但不保证有有效结果。

## 验证证据边界

合成回声测试验证参考和采集链路确实调用官方 AEC、输出有衰减、近端单独输入未被静音；不代表真实扬声器、麦克风、房间和双讲的效果。

发行 AAR 在 Android 10 x86_64 模拟器上进行加载及离线 PCM 消费测试。两个 ARM ABI 的编译、ELF 和打包检查不是 ARM 实机运行证明。真实录放闭环属于第二阶段。
