# cnb2api Android

将 `lwjlwjlwjlwj/cnb2api` 的 CNB 反向代理和 ToolForge 的 XYML 工具调用回退合并到一个 Android APK 中。

## 功能

- 前台服务内嵌本地 HTTP 网关，默认监听 `0.0.0.0:7863`
- 兼容 `GET /healthz`、`GET /pool`、`GET /v1/models`、`POST /v1/chat/completions`
- 独立 CSRF 会话凭证池，TTL、并发扩容、失败淘汰和实时维护日志
- 默认启用 ToolForge XYML prompt fallback，将模型文本解析为 OpenAI 标准 `tool_calls`
- 支持 XYML、QNML、XML 和常见 JSON tool-call 输出；应用只转换和转发，永不执行工具
- UI 仅保留启动服务、停止服务、设置三个操作入口
- 日志区实时显示请求、凭证池、上游响应和错误；长按日志可复制全部内容

## 构建

使用 Android Studio 打开本目录，等待 Gradle 同步后执行 `app > assembleDebug`。也可以在安装了 Android SDK 和 Gradle 的环境中运行：

```bash
gradle :app:assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

本仓库附带已构建 APK：`artifacts/cnb2api-android-debug.apk`。

编译、环境部署、推送和 Release 上传流程见 [`BUILD_RELEASE.md`](BUILD_RELEASE.md)。

## 使用

1. 安装并打开 APK，在“设置”中按需配置端口、API Key、模型和凭证池。
2. 点击“启动服务”，等待日志出现 `listening on` 和 `pool ready`。
3. 在同一局域网客户端将 Base URL 设置为 `http://手机IP:7863/v1`。
4. 如果配置了 API Key，客户端的 Bearer token 必须与设置一致。

示例健康检查：

```bash
curl http://手机IP:7863/healthz
```

## 与上游的差异

Android 版本使用 Java 标准库重写了 Go HTTP 层和 ToolForge 必要的 XYML 注入/解析逻辑，没有把 Python、Docker 或第三方运行时打包进 APK。为保证工具调用协议正确，带 `tools` 的请求默认先完整聚合上游 SSE，再返回标准 `tool_calls`；普通请求的流式响应也会在上游结束后分块回放，因此网络上不是逐 token 透传。

CNB 上游的原生 `tools` 仍然受限，建议保持“启用 ToolForge XYML 回退”。工具执行必须由接入的客户端完成，再将 `tool` 消息回传给本地网关。

## 许可

本工程保留上游 cnb2api 和 ToolForge 的 MIT 使用方式；请同时遵守 CNB 平台服务条款，仅用于学习和研究。
