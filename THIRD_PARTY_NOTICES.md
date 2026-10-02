# 第三方许可与来源

本项目新增代码采用 MIT 许可。第三方组件按各自原许可证分发；本仓库 LICENSE 不改变第三方条款。

## Android 与界面

Capacitor 及其插件、Vite、fflate 等依赖版本与完整性记录见 package.json 和 pnpm-lock.yaml；移动平台许可汇总在 licenses/mobile-dependencies.txt。AndroidX 和 Gradle 使用其上游许可，平台工程来自 Capacitor 脚手架。Android SDK/JDK 属于开发者自行安装的构建工具，未纳入仓库。

## 手机内置引擎

MySQL Community Server 8.0.45 与 mysqldump 来自官方 docker.io/library/mysql:8.0.45 镜像，保留 GPLv2 及上游附加许可。PRoot 5.1.107.96、libtalloc 2.4.3、libandroid-shmem 0.7 来自 Termux 官方包仓库，各组件原许可保留在 vendor/offline/licenses.txt。

PRoot 的 libtalloc SONAME 字符串按 scripts/prepare-offline-runtime.py 作等长替换，并作为 APK 原生库打包。MySQL 在独立进程运行，App 通过本机 Unix socket 使用协议客户端访问。Oracle Linux 用户空间还包含多种 GPL/LGPL 和其他许可组件，许可文本保留于运行时及汇总文件。

## 固定版本与源码

- offline-runtime-lock.json：两个架构的官方镜像摘要、层摘要与 Termux 包哈希。
- licenses/upstream-source-records.json：MySQL、PRoot、talloc、android-shmem 的源码归档地址与 SHA256。
- scripts/prepare-offline-runtime.py：下载校验、裁剪和打包步骤。
- scripts/prepare-offline-notices.py：收集上述上游源码、许可与运行时许可证。

源码地址：[MySQL](https://github.com/mysql/mysql-server/tree/mysql-8.0.45)、[Termux PRoot](https://github.com/termux/proot/tree/v5.1.107.96)、[talloc](https://www.samba.org/ftp/talloc/)、[android-shmem](https://github.com/termux/libandroid-shmem/tree/v0.7)。Oracle Linux 源码包来自 https://oss.oracle.com/ol9/SRPMS/，MySQL 源码包来自 https://repo.mysql.com/。

公开源码和发行 APK 是两种交付。分发 APK 时需要保留对应许可，并按组件条款提供完整对应源码，不能只把项目 MIT 许可证套用到整个 APK。来源原则可参考 [GNU GPL 源码说明](https://www.gnu.org/licenses/gpl-faq.en.html#DistributeWithSourceOnInternet)。
