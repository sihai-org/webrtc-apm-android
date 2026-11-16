# Android API 契约

`org.sihai.apm.AudioProcessing` 拥有一个官方 APM 实例。它不录音、不播放、不连接网络，也不配置 Android 系统音效。应用负责采集、播放、路由和音量。

## 配置与能力

`ApmConfig()` 从 native 上游默认配置读取全部 50 个标量字段；`Aec3Config()` 对应全部 136 个 AEC3 字段。属性按上游结构分组，名称使用 camelCase，枚举提供 Kotlin 类型。默认全部顶层处理效果关闭；开启效果由调用方明确选择。

示例：

```kotlin
val config = ApmConfig().apply {
    echoCanceller.enabled = true
    noiseSuppression.enabled = true
    noiseSuppression.level = ApmConfig.NoiseSuppression.Level.Moderate
}
val apm = AudioProcessing(config)
```

配置是可变的设置对象，不应跨线程同时修改；创建和 `applyConfig` 会取得快照。`configuration()` 返回上游最后应用配置的独立对象。运行中 `applyConfig` 可能触发算法重置；增益、播放设备/音量及输出使用状态变化优先使用 `RuntimeSetting`。

AEC3 参数在实例创建时传入；多通道覆盖须同时提供基本配置。`Aec3Config.multichannelDefaults()` 提供官方多通道默认值。未传入时完全保留官方自动选择。高级参数经上游 `EchoCanceller3Config::Validate` 检查；若需要被修正则拒绝，不静默改写。更换 AEC3 构造配置需要新实例。配置字段与枚举完整表见 [配置映射](config-fields.md)。

JNI 还验证标量类型、有限数值、公开约束及相互冲突的音量控制器。上游废弃但仍保留的字段照常映射，不承诺它们在当前算法中有效。`size_t` 字段使用 Long，但范围以双 ARM ABI 都能表示的无符号 32 位为准。

## PCM 格式与帧

`StreamFormat(sampleRateHz, channels)` 表达流格式。帧采样点为 `floor(sampleRateHz / 100)`，大多数常用采样率对应 10ms。非 100 整数倍采样率由上游支持，但会有近似帧长及内部重采样影响，不建议作为首选。

| 接口 | 数据布局 | 上游格式约束 |
| --- | --- | --- |
| `processCapture` / `processRender`，`floatingPoint=false` | PCM16，交错通道 | 8/16/32/48kHz；输入输出格式相同；采集与参考采样率一致 |
| 同接口，`floatingPoint=true` | float32，planar，范围 [-1,1] | 8kHz…384kHz；允许输入输出不同采样率；输出通道数为 1 或等于输入 |
| `analyzeRender` | float32 planar | 只分析参考，不产生输出；不要再对同一帧调用 processRender |

ByteBuffer 必须 direct、native byte order，position 对齐采样字节，remaining 足够容纳一帧。浮点多通道布局是完整 channel 0 帧、随后 channel 1 帧……。方法从 position 读取/写入，调用结束不改变 position；output 必须可写。可使用同一 buffer 原位处理，但部分重叠区域或不同格式的原位转换被拒绝。

```kotlin
val format = StreamFormat(48000, 1)
val capture = AudioProcessing.buffer(format)
val clean = AudioProcessing.buffer(format)
val render = AudioProcessing.buffer(format)
val rendered = AudioProcessing.buffer(format)
// 填入即将播放的 render 帧：
apm.processRender(render, rendered, format)
// 将 rendered 提交播放器；真实采集到一帧后：
apm.setStreamDelayMs(measuredDelayMs)
apm.processCapture(capture, clean, format)
```

`processRender` 的输出才是交给播放器的数据；参考不能从网络收包或两秒延迟队列的入口提前提交。保持帧连续、有序，并根据真实录放缓冲估计延迟。处理本身不等待 10ms；以音频设备的实际节奏驱动。

AGC 的 analog/input-volume 模式只返回推荐硬件音量，不会代替应用修改 Android 麦克风增益。调用方必须先提交当前 analog level，再处理采集帧并读取推荐值；没有硬件音量控制能力时选择合适的数字模式。

## 线程与生命周期

每实例所有公开方法在同一锁下串行，关闭不能与正在处理的 native 操作交叉；可从采集和播放线程调用，但不会并行进入该实例。处理期间不得由其他线程修改输入输出 buffer。

`initialize()` 保留配置并重置流状态，用于开始新的音频流；不是每帧或每句调用。格式可通过 `ProcessingFormats` 预先初始化，也可随处理调用更新。采集/播放格式变化可能导致上游重新配置。

`close()` 幂等；关闭后调用其他实例操作抛 `IllegalStateException`。必须显式关闭或使用 `use`。库不依赖 finalizer。停止采集/播放线程后再关闭是推荐应用顺序。

## 错误、统计与诊断

- 无效包装参数抛 `IllegalArgumentException`；上游负错误码抛带原始 `status` 的 `ApmException`。
- 上游 `-13` 表示参数被调整的非致命警告，以返回码保留；不能当作无错误忽略。
- `setRuntimeSetting(queued=true)` 对应 `PostRuntimeSetting`，返回 false 表示队列未接受，调用方决定处理方式；默认对应 `SetRuntimeSetting`。
- `statistics()` 保留所有上游可选统计，缺少测量返回 null，不把缺失伪装成零。多方拉取统计会影响上游聚合窗口，应用应统一统计读取职责。
- `linearAecOutput` 要在构造时提供 `Aec3Config.filter.exportLinearAecOutput=true`（若提供多通道覆盖，也须开启同一字段），并开启 `ApmConfig.echoCanceller.exportLinearAecOutput`。该上游版本不会替调用方联动两层开关；包装层拒绝缺少底层配置的组合，避免 native 崩溃。底层已配置后可以用 applyConfig 切换外层导出开关。返回 16kHz planar float，每个处理通道 160 个采样；false 表示当前没有输出。
- `startAecDump` 接受 File 或 ParcelFileDescriptor。复制 fd，调用方仍拥有原 descriptor；native 工作队列随实例持有，detach/close 等待必要的写入收尾。
- AEC dump 包含音频，应写入调用方控制的临时位置，用后删除；库不自动录制、上传或持续保存。

高级接口范围及 C++ 扩展边界见 [能力清单](capabilities.md)。
