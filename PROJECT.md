# WebRTC APM Android

本项目将 Google WebRTC 的 Audio Processing Module 包装为独立 Android AAR。应用提交采集与播放参考 PCM，库执行上游算法；不建立 PeerConnection，不拥有麦克风或播放器，不接入业务服务端。

第一阶段已获确认：固定官方源码和工具链，在 GitHub Actions 构建、测试并以 GitHub Releases 发布版本。第二阶段真实录放闭环及第三阶段业务接入不属于本阶段。

## 约束与知识归属

- 算法由上游维护；本仓库只维护构建、接口适配、资源生命周期和验证。
- `upstream.lock.json` 唯一记录上游 revision；升级须同时检查能力映射与回归。
- `docs/api.md` 维护 PCM、配置、线程、错误和生命周期契约；`docs/capabilities.md` 维护上游能力对应及明确边界。
- `docs/build.md` 维护构建、检查、版本产物和发布流程。
- `native/` 是复制进官方 checkout 的 GN/JNI 接入层；不复制算法实现。
- `apm/` 是 Android library；`consumer/` 是仅依赖产物的验证应用。
- `scripts/` 维护获取、生成、构建和产物验证入口；`.github/workflows/` 负责调度。
- `schema/` 保存从固定上游头文件提取的配置描述，生成物不得手工修改；CI 校验上游覆盖。
- Android 最低 API 29；发行 ABI 为 armeabi-v7a、arm64-v8a、x86_64（模拟器验证）。
- 自有 Kotlin 接口保留 PCM16 与 planar float、多通道及上游格式约束；不以测试场景限制核心库。
- 默认配置跟随固定上游，不自动开启算法或系统音效。

## 验证与当前状态

项目正在初始化。尚无通过构建或运行的发行产物；完成后实际构建和验收记录以对应 Actions run / Release 为准。

入口：`python3 scripts/generate_config.py --help`（能力生成与一致性）；`python3 scripts/build_native.py --help`（Linux native 构建）；`./gradlew :apm:assembleRelease`（AAR）；consumer 仪器测试验证已生成 AAR，不能替代实机声学验收。
