---
name: publish-release
description: Use this skill when users request to publish an release update.
---

# Publish Release

请基于git log生成更新日志，然后创建一个新的release

## 更新日志

- 查看从上一次release tag到当前commit的修改
- 总结更新内容并生成更新日志, BUG修复和UI调整尽可能合并，确保总更新日志条目数量不超过 10条
- 日志中避免出现技术名词

使用以下格式:

```markdown
更新内容:

- xxx
- xxx

Updates:

- xxx
- xxx
```

(双语更新日志)

更新日志生成完后，请求用户确认更新日志是否合理，等到用户确认可以发布后，创建release

## 发布

使用 GitHub Actions 为目标提交构建正式签名 APK，再用 GitHub CLI 创建 Release。
不得运行本机 assembleRelease 后上传本机产物。
- 定位成功的 `Build APK` workflow run，并校验它的完整 head SHA 与目标提交一致。
- 下载该 run 的 `release-<完整提交 SHA>` artifact；核对 build-info.json 的提交、仓库、run ID 与文件 SHA-256。
- 将 CI arm64 包复制到 app/release/ 并加上版本号，然后上传；不能取来源不明或会被覆盖的 dev 包。

- 仅上传arm64版本, 上传前重命名apk，添加版本号到文件名内
- 同时上传 R8 混淆映射 CI artifact 中的 `mapping.txt`，重命名为 `mapping-<版本号>.txt`，用于还原用户反馈的崩溃堆栈（`retrace mapping-<版本号>.txt stacktrace.txt`）
- release标题使用版本号, 描述使用更新日志
- tag名字使用版本号，不带v
