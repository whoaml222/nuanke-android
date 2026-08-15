# 暖刻

暖刻是一款面向个人使用的 Android 自律工具：为容易分心的应用设置单次时长和每日累计时长，在达到限制后给出温和提醒、返回桌面，并在冷静期内拦截再次进入；同时提供番茄钟式专注计时和本地统计。

## 设计边界

- 普通应用软拦截，不使用 Device Owner、Root、恢复出厂设置或日常 ADB。
- 辅助功能服务只看前台应用包名，不读取页面节点、聊天、文字、图片或键盘内容。
- 学习记录和使用统计只保存在手机本地，无账号、广告、埋点或第三方分析。
- 不做后台更新轮询。只有你在设置页点“检查更新”时，应用才访问公开的 GitHub Releases。
- 更新 APK 必须通过 SHA-256 和签名证书校验，安装仍由 Android 系统让你确认。

## 开发状态

项目处于早期可运行版本开发阶段。目标环境为 Android 16 / API 36，最低支持 Android 9 / API 28。

详细说明见 [架构](docs/ARCHITECTURE.md)、[隐私](docs/PRIVACY.md)、[更新协议](docs/UPDATES.md)、[发布签名](docs/RELEASING.md) 和 [vivo 设置](docs/VIVO_SETUP.md)。

## 本地构建

```powershell
$env:JAVA_HOME='path-to-jdk-17'
./gradlew.bat testDebugUnitTest assembleDebug
```

## 许可证

MIT
