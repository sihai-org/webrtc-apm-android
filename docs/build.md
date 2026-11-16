# 构建与发行

## 固定输入

`upstream.lock.json` 锁定 WebRTC 与 depot_tools commit；WebRTC 自己的 DEPS 锁定第三方和工具链。Gradle Wrapper、AGP、Kotlin 及 Actions revision 固定在仓库。runner 使用 ubuntu-24.04；GitHub 更新系统镜像时仍可能需要构建环境适配，固定输入不等于承诺所有环境下逐字节相同。

不在仓库提交完整 WebRTC checkout、算法副本或 native 二进制。`native/` 复制到 checkout 的 `apm_android/`，通过官方 GN target 链接 APM、JNI 和静态运行库。只导出 JNI_OnLoad。

构建工作目录必须专用于本脚本。脚本在其 `.gn` 设置独立 target 为 root，使编译和许可证依赖分析使用同一个图；下一次 sync 前仅撤回这一构建入口覆盖，不重置用户的包装仓库，也不修改算法源码。

## Linux native 构建

```bash
python3 scripts/build_native.py --work /tmp/apm-build --abi arm64-v8a
python3 scripts/build_native.py --work /tmp/apm-build --abi armeabi-v7a
python3 scripts/build_native.py --work /tmp/apm-build --abi x86_64
```

三个 ABI 可使用各自工作目录并行构建。首次需要访问 Google 源码、CIPD、工具链及系统依赖，耗时与磁盘以实际 CI 为准。不要把 ABI 或 Android API 改名包装成另一架构。

native 构建会先核对配置 schema 与固定上游头文件，再调用 GN / Siso，产出 `.so`、调试文件、GN 参数、上游锁文件及依赖许可证。保留 protobuf，以支持 AEC dump。

## 生成一致性

```bash
python3 scripts/generate_config.py --check
python3 -m unittest discover -s scripts -p 'test_*.py'
# 升级时才运行并审阅变更：
python3 scripts/generate_config.py --upstream /path/to/webrtc/src --update-schema
```

schema 属于上游头文件的生成投影；Kotlin 配置类、C++ 数组映射及文档表格由它生成。不得分别手改这些文件制造多份事实。

## AAR 与独立消费者

将各 ABI 构建目录放在 `incoming/` 下，导入并检查 ELF 架构、导出符号、动态依赖和上游版本：

```bash
python3 scripts/package_native.py incoming
./gradlew :apm:assembleRelease :apm:lintRelease
mkdir -p dist
cp apm/build/outputs/aar/apm-release.aar dist/webrtc-apm-android-0.1.0.aar
python3 scripts/verify_aar.py dist/webrtc-apm-android-0.1.0.aar
./gradlew :consumer:assembleRelease :consumer:assembleReleaseAndroidTest :consumer:lintRelease
./gradlew :consumer:connectedReleaseAndroidTest
```

consumer 通过文件依赖引用 `dist/` 中的版本 AAR，不使用 project dependency，防止测试绕过真实打包产物。consumer 使用本机/CI 的 debug key 签名其 release 测试包，不涉及业务 App 签名。

测试 APK 与目标 App 共用 Kotlin runtime，因此 consumer 的测试专用保留规则保存 runtime 和受测公开 API；不把这些规则注入使用方 AAR。Error Prone 注解只作为测试编译依赖，不打包桌面编译器 API。库与测试应用均保留 GN 已完成 strip 的二进制，交付物检查再次核对 AAR 内的 native 字节与构建元数据哈希一致。

模拟器执行入口是 `scripts/emulator_test.sh`。测试没有麦克风和网络权限；验证离线 PCM、配置、dump、错误边界和合成回声。报告由 CI 上传，不能把未执行或失败解释为通过。

## GitHub Actions 与版本

手动触发 `Build and verify AAR`，默认三个 ABI。单 ABI 输入只用于定位构建问题，不产生正式发行包。正式发行基于 `v<version>` tag，版本必须与 `gradle.properties` 相同。

所有 native 作业成功后才能打包；AAR lint、独立消费编译、模拟器测试全部通过后才创建 Release。失败不会创建新 Release，也不覆盖已发布版本。修复已经发布的版本应递增版本。

Release 附件包括：

- `webrtc-apm-android-<version>.aar`；
- native 调试符号及构建参数；
- `build-info.json`、`upstream.lock.json`；
- `THIRD_PARTY_LICENSES.md`、`SHA256SUMS`。

Actions artifacts 用于短期调试和 job 交接；正式版本保存在 Releases。仓库权限保持用户已有设置。当前不启用付费 runner，不要求新增长期 secret。

## 使用方接入

下载 Release AAR 并核对 SHA256SUMS；应用中使用 `implementation(files("libs/webrtc-apm-android-<version>.aar"))`。无需完整 WebRTC SDK，不需要应用自行编译 C++。minSdk 29，Kotlin 2.2+，Java bytecode 11。JNI 类保留规则随 AAR 分发。

文件形式的 AAR 不携带 Maven 依赖元数据。使用 Kotlin Android 插件的应用通常已经提供 stdlib；纯 Java 应用还需添加 `implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")`。

本库不提供设备音频效果开关或业务 VAD 策略。完整格式、同步、资源和参考信号责任见 [API 契约](api.md)。
