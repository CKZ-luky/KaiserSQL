# 从源码构建安卓离线版

## 环境

- Node.js 24 或更高版本，pnpm 11.25.0。
- Python 3.11 或更高版本。运行时准备脚本仅使用 Python 标准库。
- JDK 21、Android SDK Platform 36、Android SDK Build-Tools 36.0.0。
- 开发阶段需要网络下载固定版本依赖；用户安装后的启动和实验不需要网络。

建议将仓库克隆到短路径。Windows 中文路径可使用提供的构建脚本，它会检查并创建指向本仓库的临时目录联接，不移动项目。

## 安装前端依赖和生成界面

```sh
git clone https://github.com/CKZ-luky/KaiserSQL.git
cd KaiserSQL
npm install --global pnpm@11.25.0
pnpm install --frozen-lockfile --ignore-scripts
pnpm lab:build
pnpm android:sync
pnpm lab:build
```

第二次构建确保离线 flavor 的界面与关于页许可文件按本项目方式生成。Capacitor 同步会更新插件的本地依赖路径；不要手工复制其他机器的 node_modules。

## 准备手机内置引擎

```sh
python scripts/prepare-offline-runtime.py --abi arm64-v8a
```

此步骤从固定哈希的官方 MySQL OCI 镜像和 Termux 包中准备运行时。无需 Docker 守护进程、Root 或在宿主机执行 Linux 客体程序。下载包进入 `.runtime/offline/cache/`，资产进入 `.runtime/offline/android-assets/`，原生程序进入 `.runtime/offline/jniLibs/`。

仓库已保留许可材料。需要重新收集许可及 MySQL、PRoot、talloc、android-shmem 上游源码时，执行：

```sh
python scripts/prepare-offline-notices.py --abi arm64-v8a
pnpm lab:build
```

源码收集会额外下载较大的 MySQL 源码归档。固定版本和哈希见 `offline-runtime-lock.json`、`licenses/upstream-source-records.json`。二进制发行还应同时保留对应系统组件的源码材料，见第三方说明。

## Windows 构建

将 JAVA_HOME 指向 JDK 21；ANDROID_HOME 指向自己的 SDK。Android Studio 安装的运行环境路径可按实际位置设置：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
./scripts/build-android.ps1 -Abi arm64-v8a
```

输出：`outputs/app/Pocket-MySQL-offline-arm64-v8a-debug.apk`。

## Linux/macOS 构建

准备同样的 JDK 与 Android SDK 后：

```sh
./android/gradlew -p android --no-daemon -PlabAbi=arm64-v8a assembleOfflineDebug
```

输出：`android/app/build/outputs/apk/offline/debug/app-offline-debug.apk`。本次发布整理以 Windows 构建环境为验证目标；Linux/macOS 命令尚未在本项目中实机执行。

## 模拟器与测试

x86_64 模拟器需要同架构运行时和 APK，不要混用 ARM64 文件：

```sh
python scripts/prepare-offline-runtime.py --abi x86_64
./android/gradlew -p android --no-daemon -PlabAbi=x86_64 assembleOfflineDebug assembleOfflineDebugAndroidTest
adb install -r android/app/build/outputs/apk/offline/debug/app-offline-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/offline/debug/app-offline-debug-androidTest.apk
adb shell am instrument -w -e class com.pocketmysql.practice.OfflineEngineTest com.pocketmysql.lab.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.pocketmysql.practice.OfflineUiTest com.pocketmysql.lab.test/androidx.test.runner.AndroidJUnitRunner
```

测试使用专门的实验项目，并会操作测试界面；在专用模拟器运行。验证范围见 VERIFICATION.md。

Debug APK 只适合侧载测试。不同开发者生成的调试签名可能不同，不能保证覆盖安装发布者的 APK。卸载会删除本机内容，先备份实验。正式应用商店发布需自己的签名和真实设备验收；签名密钥不应提交源码仓库。

## 可选的 GitHub 自动构建

`examples/ci/frontend-build.yml` 提供前端依赖安装和生产构建示例，官方 Actions 固定到提交 SHA。当前仓库没有启用自动工作流；仓库维护者可在 GitHub 页面把它保存为 `.github/workflows/frontend-build.yml` 后启用。

该示例只构建界面，不生成包含 MySQL 运行时的 APK，也不运行安卓引擎测试。
