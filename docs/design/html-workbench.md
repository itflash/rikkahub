# HTML 创作工作台 — 详细设计

> 面向实现者（Codex）。本文自包含：背景、已定决策、要改的文件、新增类的签名、分阶段任务与验收标准。
> 路径若无特殊说明，均相对 `app/src/main/java/me/rerere/rikkahub/`。

---

## 0. 实现约束（先读）

1. **遵守仓库根目录 `AGENTS.md`**：MD3 Expressive（`MaterialExpressiveTheme`、`CardGroup`、大圆角、tonal 容器、整行可点）、Koin 注入、现有代码风格与注释密度。
2. **UI 文案直接写中文字符串**（`Text("新项目")`），不需要加 `strings.xml` 和多语言。
3. **不改无关代码**，不做顺手重构，不升级依赖（唯一新增依赖：app 模块引入已有的 `libs.jsoup`）。
4. **不改数据库表结构**，不需要 Room migration。
5. 按 **Phase 1 → 4** 顺序实现。每个 Phase 结束都要能通过：
   ```bash
   ./gradlew assembleDebug
   ./gradlew test
   ```
   每个 Phase 单独提交一个 commit，提交信息沿用仓库风格（`feat(workbench): ...`）。
6. 原有功能必须保持可用：普通聊天、设置、工作区页面（含 rootfs 终端和 shell 工具）。
7. 遇到本文与代码现状不符的情况，以代码现状为准做最小调整，并在提交说明里写清楚。

---

## 1. 背景与目标

把 RikkaHub 做成一个手机上的 **HTML 创作工作台**，相当于简化版的 Claude Code / Codex 桌面端：

> 打开 App → 项目列表 → 新建项目（一个目录） → 在项目里和 AI 对话，AI 直接往目录里写 HTML/CSS/JS → 预览 → 导出 → 用系统分享面板发到微信等。

### 已确定的决策（不要更改）

| 项 | 决定 |
|---|---|
| 首页 | 工作台（项目列表）作为首页。原来的聊天、设置作为次级入口 |
| 项目 | 直接复用现有 **Workspace**（`filesDir/workspaces/<id>/files/`），不新建表 |
| Linux 环境 | 不需要 proot/rootfs。AI 只用纯文件读写工具；工作区装了 rootfs 时，原有能力照旧 |
| 导出格式 | ① 长截图 PNG ② 单文件 HTML（资源内联）③ 整个项目打包 zip。**不做 PDF** |
| 分享 | 系统分享面板（`ACTION_SEND` / `ACTION_SEND_MULTIPLE`），**不接微信 SDK** |
| 保存 | 「保存到本机」和「分享」是两个独立按钮 |
| 外部资源 | 提示词要求 AI 不用 CDN / 网络字体；导出单文件 HTML 时，把仍然存在的外部资源下载并内联 |
| 长截图 | 自动判断：普通长页截一张，太长就切成多张，幻灯片式按每屏一张 |
| 布局 | 第一版只做手机布局，宽屏也用同一套，不做分栏 |
| 助手 | 项目对话使用当前选中的助手，不新建专用助手 |

### 关键现状

- `WorkspaceRepository`（`data/repository/WorkspaceRepository.kt`）已有纯 Kotlin 的文件 API，FILES 区不依赖 rootfs：
  - `create(name)`、`rename(id, name)`、`delete(id)`、`listFlow()`、`getById(id)`
  - `listFiles(id, area, path)`、`readText(id, path)`、`writeText(id, path, text, overwrite): WorkspaceFileEntry`
  - `resolveFile(id, area, path): File`、`moveFile(id, source, target, overwrite)`、`deleteFile(id, area, path, recursive)`、`importFile(...)`、`exportFile(...)`
  - 路径越界防护：`workspace/.../WorkspaceFileSystem.resolvePath` 做了 canonical 校验
- **AI 工具目前依赖 rootfs**：
  - `data/ai/tools/ChatToolFactory.kt` 中的 `createWorkspaceToolsIfReady` 要求 `shellStatus == READY`，否则一个 workspace 工具都不给。
  - `data/ai/tools/WorkspaceTools.kt` 里，read 走 `readTextInRootfs`/`readImageInRootfs`，write/edit 走 `writeTextInRootfs`，这些都要通过 proot shell 或 rootfs 路径映射。
  - `data/ai/transformers/WorkspaceReminderTransformer.kt` 也只在 READY 时注入提示词。
- **现有 HTML 预览加载不了相对资源**：`ui/pages/extensions/workspace/WorkspaceFileEditorPage.kt` 中的 `WorkspaceWebPreview` 使用 `loadDataWithBaseURL("https://workspace-preview.invalid/")`，相对路径的 css/js/图片都加载失败。
- **会话配置**：`Conversation.config: ConversationConfig?`（`data/model/ConversationConfig.kt`）包含 `workspaceId: Uuid?`。
  - 新会话的 `config == null`；会话落库时由 `bindConfig` 从助手拍一份快照。
  - `bindConfig` 在 `config != null` 时基本原样返回，所以**预置 config 不会被覆盖**。
  - 落库时 `config` 用 `JsonInstant`（`utils/Json.kt`，`encodeDefaults = true`，非 pretty）编码，存进 `conversationentity.config` 列。`Uuid` 序列化成带连字符的字符串，因此 JSON 中的片段是 `"workspaceId":"<uuid>"`（没有空格）。
- **FileProvider**：authority 是 `${applicationId}.fileprovider`，`file_paths.xml` 已经包含 `files-path "."` 和 `cache-path "."`。
- 分享与保存的现有代码：
  - `ui/pages/mediacreation/MediaCreationCommon.kt` 中的 `shareMedia(context, file, mimeType)`（约 286 行）和 `saveMediaToGallery(context, file, mimeType)`（约 246 行，包含 MediaStore 和 API<29 的写法）。
  - `utils/ContextUtil.kt` 中的 `Context.exportImageFile(activity, file, fileName)`。

---

## 2. 新增 / 修改文件总览

| 文件 | 类型 | Phase |
|---|---|---|
| `data/ai/tools/ChatToolFactory.kt` | 改 | 1 |
| `data/ai/tools/WorkspaceTools.kt` | 改 | 1 |
| `data/ai/transformers/WorkspaceReminderTransformer.kt` | 改 | 1 |
| `ui/components/message/tools/ToolUI.kt` 和 `WorkspaceToolUIs.kt` | 改 | 1 |
| `workspace/src/main/java/me/rerere/workspace/WorkspaceFileSystem.kt`、`WorkspaceManager.kt` | 改（加 `mkdirs`） | 1 |
| `data/repository/WorkspaceRepository.kt` | 改（加 `mkdirs`、`touch`） | 1 |
| `ui/src/main/java/me/rerere/ui/webview/WebView.kt` | 改（拦截器） | 2 |
| `ui/pages/projects/preview/ProjectPreviewPage.kt` + `ProjectPreviewVM.kt` | 新 | 2 |
| `ui/pages/projects/ProjectFileServer.kt` | 新（`project.local` 映射） | 2 |
| `ui/pages/extensions/workspace/WorkspaceFileEditorPage.kt` | 改（接拦截器） | 2 |
| `ui/pages/projects/ProjectsPage.kt` + `ProjectsVM.kt` | 新 | 3 |
| `ui/pages/projects/ProjectDetailPage.kt` + `ProjectDetailVM.kt` | 新 | 3 |
| `ui/pages/projects/ProjectThumbnails.kt` | 新 | 3 |
| `RouteActivity.kt`、`ui/context/NavContext.kt`、`utils/ChatUtil.kt` | 改 | 3 |
| `ui/pages/chat/ChatPage.kt`、`ChatVM.kt`、`ChatDrawer.kt` | 改 | 3 |
| `ui/components/message/ChatMessageEditedFiles.kt`（或其调用处） | 改（「预览效果」） | 3 |
| `service/ChatService.kt` | 改（`initializeConversation` 加参数） | 3 |
| `data/db/dao/ConversationDAO.kt`、`data/repository/ConversationRepository.kt` | 改（按项目查会话） | 3 |
| `ui/pages/extensions/workspace/WorkspaceDetailPage.kt` | 改（`WorkspaceFilesPage` 改为 internal + 参数） | 3 |
| `di/ViewModelModule.kt` | 改（注册新 VM） | 2/3 |
| `app/build.gradle.kts` | 改（`implementation(libs.jsoup)`） | 4 |
| `data/export/HtmlInliner.kt` | 新 | 4 |
| `data/export/ProjectZipper.kt` | 新 | 4 |
| `ui/pages/projects/export/WebPageCapturer.kt` | 新 | 4 |
| `ui/pages/projects/export/ExportSheet.kt` + `ProjectExporter.kt` | 新 | 4 |
| `utils/ShareUtil.kt` | 新 | 4 |
| `RikkaHubApp.kt` | 改（`enableSlowWholeDocumentDraw`） | 4 |
| `app/src/test/.../data/export/HtmlInlinerTest.kt`、`ProjectZipperTest.kt` | 新 | 4 |
| `app/src/test/.../data/ai/tools/WorkspaceToolsFilesModeTest.kt` | 新 | 1 |

---

## Phase 1：AI 文件工具去掉 rootfs 依赖

### 1.1 `ChatToolFactory.kt`

把 `createWorkspaceToolsIfReady` 改成（名字可以改为 `createWorkspaceToolsFor`）：

- workspace 不存在 → `emptyList()`。
- workspace 存在 → **始终**返回文件工具；只有 `shellStatus == READY` 时才额外返回 `workspace_shell`。

```kotlin
return createWorkspaceTools(
    workspaceId, workspaceRepository, cwd,
    shellAvailable = workspace.shellStatus == WorkspaceShellStatus.READY.name,
)
```

### 1.2 `WorkspaceTools.kt`

`createWorkspaceTools` 增加参数 `shellAvailable: Boolean = true`（默认值保持现有调用不变），并引入两种模式：

- **Rootfs 模式**（`shellAvailable == true`）：行为与现在**完全一致**。
- **Files 模式**（`shellAvailable == false`）：所有工具只能访问 FILES 区，通过 `WorkspaceRepository` 的纯 Kotlin API 读写。

**路径约定**：两种模式下 AI 看到的路径都是 `/workspace/...` 形式的绝对路径，以保证 `EditedFilesList` 中 `resolveWorkspacePath` 等现有 UI 不需要改。Files 模式内部的路径转换：

```kotlin
// "/workspace" -> "", "/workspace/a/b.html" -> "a/b.html"；其他前缀抛错
private fun String.toFilesAreaPath(): String {
    val normalized = Paths.get(this).normalize().toString()
    require(normalized == "/workspace" || normalized.startsWith("/workspace/")) {
        "No Linux environment in this workspace. Only paths under /workspace are available: $this"
    }
    return normalized.removePrefix("/workspace").removePrefix("/")
}
private fun WorkspaceFileEntry.toRootfsView() =
    copy(path = if (path.isEmpty()) "/workspace" else "/workspace/$path")
```

每个工具在 Files 模式下的行为：

| 工具 | Files 模式实现 |
|---|---|
| `workspace_read_file` | 文本：`workspaceRepository.readText(id, rel)`。图片：`resolveFile(id, FILES, rel).readBytes()`，然后沿用 `readImageInRootfs` 中「`FilesManager.createChatFilesByByteArrays` → `UIMessagePart.Image`」的逻辑（抽成一个公共函数）。大小上限沿用 `MAX_READ_FILE_BYTES`。 |
| `workspace_write_file` | `workspaceRepository.writeText(id, rel, text, overwrite).toRootfsView()`。返回 JSON 用现有的 `toJson()`，格式不变。 |
| `workspace_edit_file` | 用 `readText` 读原文，`replaceText(...)` 替换，再用 `writeText(..., overwrite = true)` 写回。`DiffMetadata` 和返回 JSON 都与 Rootfs 模式一致；diff 中的文件名用 `/workspace/...` 路径。 |
| `workspace_list_files`（**新增**，两种模式都提供） | 见下 |
| `workspace_delete_file`（**新增**，两种模式都提供） | 见下 |
| `workspace_shell` | Files 模式下**不提供** |

实现上建议抽象出一个小接口，避免在每个工具里写 if/else：

```kotlin
private interface WorkspaceFileAccess {
    suspend fun readText(path: String): String
    suspend fun readBytes(path: String): ByteArray
    suspend fun writeText(path: String, text: String, overwrite: Boolean): WorkspaceFileEntry
}
// RootfsFileAccess：包装现有的 readTextInRootfs / readRootfsBuffer / writeTextInRootfs
// FilesAreaFileAccess：包装 WorkspaceRepository.readText / resolveFile / writeText + 路径转换
```

Files 模式下每次写入成功后调用 `workspaceRepository.touch(id)`（见 1.4），让项目列表的排序能反映最近修改。

**新工具 `workspace_list_files`**
- 参数：`path`（可选，默认 `/workspace`）、`recursive`（boolean，默认 `true`）。
- 实现：基于 `listFiles(id, FILES, rel)` 递归遍历。跳过以 `.` 开头的项。最多返回 **500** 条，超出时附 `"truncated": true`。
- Rootfs 模式下同样只列 `/workspace` 下的内容（实现一致，都走 FILES 区）。
- 返回：`{"path":"/workspace","entries":[{"path":"/workspace/index.html","isDirectory":false,"sizeBytes":123}, ...]}`。
- 默认不需要审批。

**新工具 `workspace_delete_file`**
- 参数：`path`（必填）、`recursive`（boolean，默认 `false`，删除目录时必须为 `true`）。
- 实现：`deleteFile(id, FILES, rel, recursive)`。不允许删除 `/workspace` 根目录。
- 返回：`{"path":..., "deleted": true}`。
- **默认需要审批。**

两个新工具都要加进 `WorkspaceToolDefaultApprovals`（`list = false`、`delete = true`），这样工作区设置页里已有的「工具审批」开关会自动显示它们；如果开关列表是写死的，补上这两项。

工具描述（description）中的「using the assistant's bound workspace Rootfs」改为不依赖 rootfs 的中性表述，例如「Read a file in the bound workspace. Paths must be absolute; the workspace files area is `/workspace`.」

### 1.3 工具 UI（`ui/components/message/tools/`）

在 `ToolUI.kt` 的 `renderers` 中注册两个新工具，写法参照 `WorkspaceToolUIs.kt` 中已有的 `ReadFileToolUI`：
- `ListFilesToolUI`：标题「列出文件」加路径；展开后显示文件列表（最多显示 50 行）。
- `DeleteFileToolUI`：标题「删除文件」加路径；审批状态的展示方式和现有需要审批的工具一致。

### 1.4 workspace 模块与仓库

- `WorkspaceFileSystem` 和 `WorkspaceManager` 新增 `mkdirs(root, path, area = FILES): WorkspaceFileEntry`，必须经过 `resolvePath` 校验，已存在时直接返回。
- `WorkspaceRepository` 新增：
  - `suspend fun mkdirs(id: String, path: String): WorkspaceFileEntry`
  - `suspend fun touch(id: String)`：把 `lastAccessAt` 设为当前时间。DAO 没有对应方法时，用 `getById` + `copy` + `upsert`。

### 1.5 `WorkspaceReminderTransformer.kt`

- 去掉「仅在 READY 时注入」的限制。
- READY 时：保留现有的 `buildWorkspacePrompt` 和 `buildAgentsPrompt`，在末尾追加下文的 **HTML 创作约定**。
- 非 READY 时：注入下面的 Files 模式提示，不读取 rootfs 和 distro。AGENTS.md 只读 `/workspace/AGENTS.md`，并改用 `workspaceRepository.readText`；`try/catch` 的写法和现有 `readRootfsText` 一样，失败时返回 null。

Files 模式提示词（英文，原样使用）：

```
<workspace>
You are working in a project folder named "<name>". It is a plain file area, there is NO shell and NO Linux environment.
- The project root is `/workspace`. All tool paths must be absolute and inside `/workspace` (for example `/workspace/index.html`).
- Available tools:
  - `workspace_list_files`: list files in the project.
  - `workspace_read_file`: read a file.
  - `workspace_write_file`: create or overwrite a file.
  - `workspace_edit_file`: make precise edits to an existing file. Prefer it over rewriting whole files.
  - `workspace_delete_file`: delete a file or folder.
- Current working directory: `<cwd>`.   (只有 cwd 非空时才输出这一行)
</workspace>
```

**HTML 创作约定**（两种模式都追加，放在 `</workspace>` 之后）：

```
<html_project_guidelines>
This project is a web page that the user previews on a phone and shares to chat apps such as WeChat.
- The entry page is `/workspace/index.html`. Put CSS, JS, images and other assets in the project and reference them with relative paths (e.g. `css/style.css`).
- Do NOT load anything from external CDNs or web font services (Google Fonts, jsDelivr, unpkg, cdnjs, Tailwind CDN, etc.). They are often unreachable for the user. If a library is needed, write it into the project as a local file; prefer plain CSS/JS. Use inline SVG for icons. Use system font stacks.
- Design mobile-first for a 390 CSS px wide viewport. Always include `<meta name="viewport" content="width=device-width, initial-scale=1">`.
- For normal content pages, let the page scroll naturally; do not lock the whole page to `100vh` with `overflow: hidden`.
- For slide decks, make each slide a direct child `<section>` of `<body>` (or `<main>`) with height `100vh`, so each slide can be exported as one image.
- After creating or changing files, briefly tell the user what changed. The user previews the result in the app.
</html_project_guidelines>
```

更新 `app/src/test/.../WorkspaceReminderTransformerTest.kt`：原来测试「非 READY 时不注入」的用例改为断言注入的是 Files 模式提示，并补一个断言两种模式都包含 `<html_project_guidelines>`。

### 1.6 Phase 1 测试

新建 `WorkspaceToolsFilesModeTest`。测试搭建方式参考现有的 workspace / tools 测试（`SkillsToolsTest`、`MemoryToolsTest`）：用临时目录构造真实的 `WorkspaceManager`；如果构造 `WorkspaceRepository` 太重，就让 `FilesAreaFileAccess` 依赖一个可替换的接口，单独测它和路径转换。至少覆盖：

- `toFilesAreaPath`：`/workspace` 映射为 `""`；`/workspace/a/../b.html` 映射为 `b.html`；`/etc/passwd`、`/workspace/../x`、`/tmp/a` 都抛错。
- 写入后再读取，内容一致；`overwrite = false` 且文件已存在时抛错。
- edit 后内容正确，并且 diff 非空。
- list 递归、跳过隐藏文件、超过上限时截断。
- delete 删除目录时必须传 `recursive`；不能删除根目录。

### 1.7 Phase 1 验收

- 新建一个**未安装 rootfs** 的工作区，在助手设置里绑定它，然后在对话中让 AI「做一个带 style.css 和 script.js 的个人主页」。AI 应成功写入 3 个文件；聊天里的工具卡片和「已编辑文件」列表正常显示，并且能点开。
- 已安装 rootfs 的工作区：`workspace_shell` 依然可用，其他行为不变。

---

## Phase 2：项目预览（支持相对资源）

### 2.1 `WebView.kt`（ui 模块）

- `WebViewState` 构造函数新增参数 `val requestInterceptor: ((Uri) -> WebResourceResponse?)? = null`；两个 `rememberWebViewState` 重载都透传这个参数（加到参数列表末尾，带默认值）。
- `MyWebViewClient.shouldInterceptRequest` 中，先调用 `state.requestInterceptor?.invoke(request.url)`，返回 null 时再走 `WebViewLocalAssets.intercept`，最后走 `super`。

### 2.2 `ui/pages/projects/ProjectFileServer.kt`

```kotlin
const val PROJECT_HOST = "project.local"

/** https://project.local/<path> → 项目 files/ 下的文件 */
class ProjectFileServer(private val filesRoot: File) {
    fun urlOf(path: String): String          // 对每一段做 Uri.encode，保留 '/'
    fun intercept(uri: Uri): WebResourceResponse?
}
```

`intercept` 的规则：
- host 不是 `project.local` 时返回 `null`，交给后续处理（外部 http 资源会照常联网加载）。
- 取 `uri.path`，去掉开头的 `/`，做 URL decode；路径为空时当作 `index.html`；以 `/` 结尾时补 `index.html`。
- 解析文件：`File(filesRoot, rel).canonicalFile`，并要求结果仍在 `filesRoot.canonicalPath` 下（**越界返回 403**）。不存在返回 404。目录返回 404。
- MIME：先用 `MimeTypeMap.getSingleton().getMimeTypeFromExtension`，再按下表补全：`html/htm → text/html`、`css → text/css`、`js/mjs → text/javascript`、`json → application/json`、`svg → image/svg+xml`、`woff2 → font/woff2`、`woff → font/woff`、`ttf → font/ttf`、`wasm → application/wasm`；其他用 `application/octet-stream`。文本类 encoding 设为 `utf-8`。
- Response headers：`Cache-Control: no-store`（保证刷新后拿到最新内容）、`Access-Control-Allow-Origin: *`。
- 可以通过 `WorkspaceRepository.resolveFile(id, FILES, "")` 拿到 `filesRoot`（需要在 VM 里异步获取），或者直接按 `filesDir/workspaces/<root>/files` 拼路径，前提是和 `WorkspaceManager` 的布局一致。推荐前者。

### 2.3 `ProjectPreviewPage` / `ProjectPreviewVM`

路由：`Screen.ProjectPreview(id: String, path: String? = null, conversationId: String? = null)`。
- `path` 为 null 时：有 `index.html` 就用它，否则用递归找到的第一个 `.html`（按路径排序）。都没有时显示空状态：「还没有页面，去和 AI 对话生成一个吧」，并提供按钮返回。
- `conversationId`：从对话页进入时传入，「让 AI 修复」会把消息发回这个对话。

布局：

```
┌─────────────────────────────────────────┐
│ ←   index.html ▾          ⟳   ⤴   ⋮     │  TopAppBar
├─────────────────────────────────────────┤
│                                         │
│              WebView                    │
│                                         │
├─────────────────────────────────────────┤
│ ⚠ 2 个脚本错误          [ 让 AI 修复 ]  │  只在有错误时显示，tonal 容器
└─────────────────────────────────────────┘
```

- **入口文件下拉 ▾**：列出项目内所有 `.html` 文件（递归、跳过隐藏文件），选中后切换。
- **⟳**：`webView.reload()`，同时清空已收集的错误。
- **⤴**：打开导出面板（Phase 4；在 Phase 2 时可以先放一个 TODO 按钮，或先不显示）。
- **⋮**：「在浏览器中打开」（用 FileProvider 生成 content uri，`ACTION_VIEW` + `text/html`；有的浏览器不支持相对资源，可以接受）、「查看 Console」（复用 `ui/pages/webview/WebViewPage.kt` 中的 console 日志 sheet；如果它是 private，就抽成 internal 的 composable 来复用，不要复制一份）。
- WebView 设置：开启 JavaScript、DOM storage，并设置 `useWideViewPort` 与 `loadWithOverviewMode`，和 `WebViewPage` 保持一致。
- **自动刷新**：用 `LifecycleEventEffect(Lifecycle.Event.ON_RESUME)` 在页面回到前台时执行 reload（首次进入除外）。
- **错误收集**：在 `WebViewState.consoleMessages` 中过滤 `MessageLevel.ERROR`，每次 reload 后重新计数，按 `message + sourceId + lineNumber` 去重。
- **让 AI 修复**：
  - 生成以下格式的消息文本：
    ```
    预览 index.html 时出现了以下脚本错误，请帮我修复：
    1. script.js 第 12 行：Uncaught TypeError: Cannot read properties of null (reading 'addEventListener')
    2. ...
    ```
    `sourceId` 要去掉 `https://project.local/` 前缀，只保留项目内的相对路径。
  - 选择目标会话：优先用 `conversationId`；没有时用该项目最近更新的会话（Phase 3 中的 DAO 查询）；也没有时新建一个 `Uuid`。
  - 跳转：`navigator.navigate(Screen.Chat(id = target, text = message, workspaceId = projectId))`。**只把内容填进输入框，不自动发送**，让用户确认后自己发送。`Screen.Chat.text` 现在已经是预填文本的语义，实现时要确认这一点。
  - 如果是从对话页进入的预览（back stack 上一层就是这个 Chat），应该 `popBackStack()` 回到对话页并把文本填进去，而不是再压入一个新的 Chat。实现方式：给 `Screen.Chat` 加 `text` 参数，然后用 `navigate(..., popUpTo(该 Chat, inclusive = true))` 替换栈顶，效果等价即可。
- **缩略图**：在 `ON_STOP` 和 `onDispose` 时调用 `ProjectThumbnails.capture(webView, projectId)`（见 3.6）。它只截可见区域，异步执行，失败时忽略。

### 2.4 `WorkspaceFileEditorPage` 的预览

`WorkspaceWebPreview` 不再用 `loadDataWithBaseURL`，改用 `ProjectFileServer` 和 `https://project.local/<当前文件路径>` 加载（仅限 FILES 区；LINUX 区维持原样）。这样就修复了相对资源无法加载的问题。

### 2.5 Phase 2 验收

- 可以临时通过 `WorkspaceDetailPage` 中 html 文件的菜单进入 `ProjectPreview`（Phase 3 会正式接入）。预览时 `style.css`、`script.js` 和图片都能生效。
- 修改文件后切回 App，页面会自动刷新。
- 在页面里制造一个 JS 报错：底部出现提示条，点击后跳到对话页，输入框里已填好错误描述。
- 请求 `https://project.local/../../databases/xxx` 返回 403。

---

## Phase 3：项目页面、首页与导航

### 3.1 路由（`RouteActivity.kt` 中的 `Screen`）

```kotlin
@Serializable data object Projects : Screen
@Serializable data class ProjectDetail(val id: String, val tab: Int = 0) : Screen
@Serializable data class ProjectPreview(val id: String, val path: String? = null, val conversationId: String? = null) : Screen

data class Chat(
    val id: String,
    val text: String? = null,
    val files: List<String> = emptyList(),
    val nodeId: String? = null,
    val workspaceId: String? = null,   // 新增：只在新建会话时生效
) : Screen
```

在 `entryProvider` 中注册三个新页面，写法与现有 `entry<Screen.WorkspaceDetail>` 一致。

### 3.2 首页与返回栈

- `AppRoutes()`：`startScreen` 改为 `Screen.Projects`。现有的 `create_new_conversation_on_start` / `lastConversationId` 逻辑不再决定首页，相关代码删除即可（偏好项保留，不影响其他地方）。
- `handleIntent` 中的外部 intent（通知点击打开会话、分享文本进来等）照旧导航到 Chat。由于下一条规则，栈底永远是 Projects。
- `ui/context/NavContext.kt`：

  ```kotlin
  fun clearAndNavigate(screen: Screen) {
      backStack.clear()
      if (screen != Screen.Projects) backStack.add(Screen.Projects)
      backStack.add(screen)
  }
  ```

  这样「新对话」「从通知进入」「navigateToChatPage」之后按返回键都能回到项目列表。检查 `clearAndNavigate` 的所有调用方，确认没有调用方依赖「栈里只有一个元素」。
- `utils/ChatUtil.kt` 的 `navigateToChatPage` 增加可选参数 `workspaceId: Uuid? = null` 并透传给 `Screen.Chat`。

### 3.3 会话与项目绑定

**ChatService**
```kotlin
suspend fun initializeConversation(conversationId: Uuid, workspaceId: Uuid? = null)
```
- 在 `ensureInitialized` 的「新建对话」分支中，如果 `workspaceId != null`：
  ```kotlin
  conversation.copy(
      config = <基于当前设置拍的快照>.copy(workspaceId = workspaceId),
      workspaceCwd = "/workspace",
  )
  ```
  快照的生成方式：先用 `Conversation.bindConfig(settings)` 得到完整 config（它是 public 的），再 `copy(workspaceId = workspaceId)`。这样模型、推理等级等设置都和正常落库时一致。
- 已经存在的会话（`loadConversation` 返回非 null）**完全不受影响**，传入的 `workspaceId` 被忽略。
- 新会话的 `session.initialize` 可能已经被页面以外的地方触发过（例如 `getConversationFlow` 会提前 `getOrCreate`）。实现时确认 `initialize` 只执行一次，并且 ChatVM 在第一次调用时就带上 `workspaceId`。
- `ChatVM` 新增构造参数 `workspaceId: String?`（在 `di/ViewModelModule.kt` 中通过 `params` 传入），调用 `initializeConversation` 的地方把它带上。

**按项目查会话**（`ConversationDAO`）：
```kotlin
@Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE config LIKE '%\"workspaceId\":\"' || :workspaceId || '\"%' ORDER BY update_at DESC")
fun getConversationsOfWorkspace(workspaceId: String): Flow<List<LightConversationEntity>>
```
- 使用 `LightConversationEntity`，**不要 `SELECT *`**，因为 `nodes` 列很大。
- 写测试或手动核对 JSON 格式：`JsonInstant.encodeToString(ConversationConfig(workspaceId = uuid))` 的结果中应包含 `"workspaceId":"<uuid>"`。
- `ConversationRepository` 中加对应的包装方法，返回 `Flow<List<...>>`，并按现有方法的方式转换成 UI 模型。
- 项目页的会话卡片只显示**标题和相对时间**，不显示消息条数（`Light` 实体没有这个字段，第一版不做）。

### 3.4 首页 `ProjectsPage` + `ProjectsVM`

```
┌─────────────────────────────────────────┐
│ 工作台                        💬    ⚙️   │  LargeTopAppBar / TopAppBar
├─────────────────────────────────────────┤
│ ┌───────────────┐  ┌───────────────┐    │
│ │  [缩略图]     │  │      旅        │    │  两列 LazyVerticalGrid
│ │               │  │  (首字母占位)  │    │  卡片 aspect 约 3:4，大圆角
│ ├───────────────┤  ├───────────────┤    │
│ │ 个人主页    ⋮ │  │ 旅行攻略    ⋮ │    │
│ │ 3 分钟前      │  │ 昨天          │    │
│ └───────────────┘  └───────────────┘    │
│                                         │
│                        ╭─────────────╮  │
│                        │ ＋ 新项目   │  │  ExtendedFloatingActionButton
│                        ╰─────────────╯  │
└─────────────────────────────────────────┘
```

- 数据来源：`WorkspaceRepository.listFlow()`，即**所有工作区都是项目**（包括以前建的）。按 `lastAccessAt ?: updatedAt` 倒序排列。
- 卡片：
  - 上半部分显示缩略图 `filesDir/project_thumbs/<id>.png`（Coil 加载，用文件修改时间做缓存 key，避免一直显示旧图）。没有缩略图时，用 tonal 容器加名称首字母大字作为占位。
  - 下半部分显示名称（`titleSmallEmphasized`）和相对时间。
  - 点击整张卡片 → `ProjectDetail(id)`。
  - 卡片右下角 ⋮ → 「重命名」（输入框对话框）和「删除」（确认对话框，文案为「删除项目「xx」？项目内的文件会一并删除，相关对话保留。」）。
- 空状态：图标加「还没有项目」，再加一个「新建项目」按钮。
- 顶栏右侧：💬 打开普通聊天（`navigateToChatPage(navigator)`，即一个新的普通会话）；⚙️ 打开设置（`Screen.Setting`，以现有 Screen 名为准）。
- FAB「＋ 新项目」：弹出对话框，只有一个名称输入框，默认值为「未命名项目」；如果重名，自动加后缀 ` 2`、` 3`，因为 `create` 遇到重名会抛 `require` 异常。创建成功后：
  1. `navigate(ProjectDetail(id))`
  2. 紧接着 `navigate(Chat(Uuid.random(), workspaceId = id))`，并自动聚焦输入框（如果 ChatPage 已有聚焦能力就复用；没有的话，在 `Screen.Chat` 加一个 `focusInput` 布尔参数，可选）。
- 重命名和删除调用 `WorkspaceRepository.rename/delete`；失败时（比如重名）用 toast 提示。删除时同时删掉缩略图文件。

### 3.5 项目页 `ProjectDetailPage` + `ProjectDetailVM`

```
┌─────────────────────────────────────────┐
│ ←  个人主页                👁   ⤴   ⋮   │
│   [ 对话 ]   [ 文件 ]                    │  PrimaryTabRow / SecondaryTabRow
├─────────────────────────────────────────┤
│ 对话 Tab:                                │
│ ┌─────────────────────────────────────┐ │
│ │ 做一个个人主页              3 分钟前 │ │  CardGroup 风格的列表
│ ├─────────────────────────────────────┤ │
│ │ 改成深色主题                昨天     │ │
│ └─────────────────────────────────────┘ │
│                        ╭─────────────╮  │
│                        │ ✎ 新对话    │  │
│                        ╰─────────────╯  │
└─────────────────────────────────────────┘
```

- 进入页面时调用 `workspaceRepository.touch(id)`。
- 顶栏：
  - ← 返回。
  - 标题为项目名。
  - 👁 进入 `ProjectPreview(id)`。
  - ⤴ 打开导出面板（Phase 4）。
  - ⋮ 中有「重命名」「删除」（删除后返回首页），以及「高级设置」→ `Screen.WorkspaceDetail(id)`，用于 rootfs、工具审批等原有功能。
- **对话 Tab**：
  - 使用 3.3 的查询结果。点击某个会话 → `Screen.Chat(id)`。长按或 ⋮ → 删除会话（复用现有的删除会话方法）。
  - FAB「✎ 新对话」→ `Screen.Chat(Uuid.random(), workspaceId = id)`。
  - 空状态：「还没有对话」，加一个「开始创作」按钮，效果同 FAB。
- **文件 Tab**：
  - 复用 `WorkspaceDetailPage.kt` 中的 `WorkspaceFilesPage`：把 `private` 改为 `internal`，新增参数 `showAreaSwitcher: Boolean = true`；在项目页中传 `false`，并且只使用 FILES 区。
  - 文件列表数据：可以复用 `WorkspaceDetailVM` 的文件相关状态和方法，用 Koin 按 `id` 取它。如果耦合太重，就在 `ProjectDetailVM` 里实现最小的一套：当前路径、列表、返回上级、导入、删除、导出、分享。
  - 顶部显示面包屑：`项目根 / css / ...`，每一段都可点击跳转。
  - FAB「＋ 新建」展开为三项（用 `FloatingActionButtonMenu`，或者底部弹出菜单）：
    - 「新建文件夹」：弹出名称对话框，调用 `workspaceRepository.mkdirs(id, 当前路径/名称)`。
    - 「新建文件」：弹出名称对话框，调用 `writeText(id, 当前路径/名称, "", overwrite = false)`。
    - 「从手机导入」：复用现有的导入逻辑。
  - 在文件条目的 ⋮ 菜单中**加一项「重命名」**，调用 `moveFile(id, old, 同目录/新名, overwrite = false)`。这一项会同时出现在原来的 WorkspaceDetailPage 中，这是可以接受的。
  - 点击 `.html`/`.htm` 文件 → `ProjectPreview(id, path)`；点击其他文件 → `Screen.WorkspaceFileEditor(id, "FILES", path)`。

### 3.6 `ProjectThumbnails.kt`

```kotlin
object ProjectThumbnails {
    fun file(context: Context, projectId: String): File  // filesDir/project_thumbs/<id>.png
    suspend fun capture(webView: WebView, projectId: String)  // 主线程 draw 可见区域 → 缩到 360px 宽 → IO 线程写 PNG
    fun delete(context: Context, projectId: String)
}
```
- 先在主线程创建 `Bitmap.createBitmap(view.width, view.height)`，用 `view.draw(canvas)` 画出来，再缩放到 360 px 宽，最后在 IO 线程保存为 PNG。
- WebView 宽或高为 0、或者页面还没加载完成时，直接跳过。
- 项目删除时，同时调用 `delete` 删除缩略图。

### 3.7 对话页（`ChatPage`）在项目会话中的表现

判断条件：`val projectId = conversation.config?.workspaceId`，并且该 workspace 存在。

- **顶栏**：
  - 标题显示为「项目名 · 会话标题」，会话标题为空时只显示项目名。
  - 左侧的抽屉按钮换成 ← 返回（调用 `popBackStack`；如果栈里上一层不是该项目页，就 `navigate(ProjectDetail(projectId))`）。
  - 右侧增加 👁，点击后进入 `ProjectPreview(projectId, conversationId = 当前会话)`。
- **抽屉**：项目会话中禁止手势滑出（`gesturesEnabled = false`），也不显示抽屉按钮。
- **「新对话」按钮**（顶栏上已有的新建会话按钮）：在项目会话里创建新会话时要带上 `workspaceId`。
- **「预览效果」按钮**：
  - 在 assistant 消息中，如果本轮出现了**成功的** `workspace_write_file` 或 `workspace_edit_file` 调用（也就是 `EditedFilesList` 有内容时），就在 `EditedFilesList` 下方显示一个 `FilledTonalButton`（「👁 预览效果」）。
  - 点击后进入 `ProjectPreview(projectId, path = 本轮最后写入的 .html 文件（如果有）, conversationId)`。
  - 只在项目会话里显示。
- **灵感快捷词**：项目会话在**没有任何消息**时，在输入框上方显示四个 `SuggestionChip`。点击后把对应文本**填入**输入框（不发送）。

  | 芯片 | 填入的文本 |
  |---|---|
  | 个人主页 | 帮我做一个手机上好看的个人主页：头像、简介、技能标签和联系方式，风格简洁现代。 |
  | 活动海报 | 帮我做一张活动海报页面：活动名称、时间地点、亮点介绍和报名提示，适合分享到朋友圈。 |
  | 幻灯片 | 帮我做一份 5 页的竖屏幻灯片，每页一个 section，主题是：（在这里填写主题） |
  | H5 小游戏 | 帮我做一个能在手机上玩的 H5 小游戏，触屏操作，有计分和重新开始。 |

  输入框的状态对象应该已经有设置文本的方法；如果没有，就沿用 `Screen.Chat.text` 预填文本的那套机制。
- **抽屉入口**：在普通聊天里，给 `ChatDrawer.kt` 的 `DrawerActions` 加一个「工作台」按钮，点击后 `navigator.clearAndNavigate(Screen.Projects)`。

### 3.8 Phase 3 验收

1. 冷启动直接进入项目列表。新建项目「测试」后会自动进入项目页，并打开一个新对话，输入框获得焦点。
2. 对话结束后返回项目页，「对话」Tab 中能看到这个会话；「文件」Tab 中能看到 AI 写的文件。新建文件夹、新建文件、重命名、导入都能正常使用。
3. 对话中能看到「预览效果」按钮，点击后进入预览；预览里的「让 AI 修复」会回到这个对话。
4. 退出预览后回到首页，项目卡片上显示缩略图。
5. 返回键路径：预览 → 对话 → 项目页 → 项目列表 → 退出 App。从通知点进对话、从普通聊天新建对话之后，按返回键也都能回到项目列表。
6. 普通聊天（从 💬 进入）的抽屉、助手切换、设置都和原来一致。

---

## Phase 4：导出与分享

### 4.0 导出面板（`ui/pages/projects/export/ExportSheet.kt`）

入口有两个：预览页的 ⤴（导出当前入口页），以及项目页的 ⤴（导出 `index.html`；如果没有 `index.html`，就用第一个 html 文件）。导出面板是一个 `ModalBottomSheet`，按三个状态切换：

```
① 选择                         ② 生成中                     ③ 完成
┌──────────────────────────┐  ┌──────────────────────────┐  ┌──────────────────────────┐
│ 导出 index.html          │  │                          │  │ 幻灯片模式 · 3 张        │
│ ┌──────┐┌──────┐┌──────┐ │  │   ◠ 正在截图 2/3         │  │ [▢][▢][▢]  缩略图横条     │
│ │ 🖼   ││ </>  ││ 🗜   │ │  │                          │  │ ⚠ 1 个外部资源下载失败， │
│ │ 长图 ││ HTML ││ zip  │ │  │        [ 取消 ]          │  │   已保留原链接           │
│ └──────┘└──────┘└──────┘ │  │                          │  │                          │
│ 发微信/朋友圈直接看       │  │                          │  │ [ 保存到本机 ] [ 分享 ]  │
│        [ 生成 ]          │  │                          │  │                          │
└──────────────────────────┘  └──────────────────────────┘  └──────────────────────────┘
```

- 每种格式下面的一句说明：
  - 长图：「适合直接发微信、朋友圈」
  - HTML：「单个文件，对方点开就能看」
  - zip：「整个项目打包，可继续编辑」
- 生成过程放在 VM（`ProjectExporter`）里跑，运行在 `viewModelScope`；点「取消」时 cancel 这个 job，并删掉已经生成的临时文件。
- 完成态：
  - 长图显示张数和模式（「长页面 · 1 张」/「长页面 · 已切成 3 张」/「幻灯片模式 · 5 张」/「仅当前屏」），并显示缩略图横条。
  - HTML 和 zip 显示文件名和大小。
  - 有警告时逐行显示。
  - 「保存到本机」和「分享」两个按钮都可以多次点击，不会重新生成。
- 输出位置：`cacheDir/project_export/<项目名>-<yyyyMMdd-HHmm>.<ext>`。长图有多张时依次命名为 `-1.png`、`-2.png` 等。每次生成前清空这个目录。项目名中的非法文件名字符（`/\:*?"<>|`）替换为 `_`。

```kotlin
sealed interface ExportResult {
    val files: List<File>
    val warnings: List<String>
    data class Images(override val files: List<File>, val mode: CaptureMode, override val warnings: List<String>) : ExportResult
    data class Html(val file: File, override val warnings: List<String>) : ExportResult { override val files get() = listOf(file) }
    data class Zip(val file: File, override val warnings: List<String> = emptyList()) : ExportResult { override val files get() = listOf(file) }
}
enum class CaptureMode { SINGLE, SPLIT, SLIDES, VIEWPORT_ONLY }
```

### 4.1 单文件 HTML（`data/export/HtmlInliner.kt`）

`app/build.gradle.kts` 中加入 `implementation(libs.jsoup)`（版本目录里已经有）。

```kotlin
fun interface ResourceDownloader {
    /** 返回 null 表示失败；实现方负责超时与大小限制 */
    suspend fun download(url: String): Downloaded?
}
data class Downloaded(val bytes: ByteArray, val contentType: String?)

class HtmlInliner(
    private val downloader: ResourceDownloader,
    private val maxResourceBytes: Long = 5L * 1024 * 1024,
    private val maxTotalBytes: Long = 30L * 1024 * 1024,
) {
    data class Result(val html: String, val inlinedCount: Int, val failedUrls: List<String>, val skippedForSize: List<String>)
    suspend fun inline(projectRoot: File, entryPath: String): Result
}
```

处理规则：
- 用 jsoup 解析入口文件，输出时 `outputSettings.prettyPrint(false)`，尽量保持原格式。
- 引用的解析：
  - 相对路径以**当前文件**所在目录为基准，CSS 文件里的引用以那个 CSS 文件的位置为基准。
  - 带 `?query` 或 `#hash` 时，先去掉再查找文件。
  - 解析出的文件必须在 `projectRoot` 里面（用 canonical 路径检查），否则当作失败处理，保留原链接。
  - 以 `http://`、`https://` 或 `//` 开头的 URL（`//` 按 https 处理）通过 `downloader` 下载。
  - `data:`、`blob:`、`javascript:`、`#xxx`、`mailto:` 原样保留。
- 要处理的元素：
  - `<link rel="stylesheet" href>` 换成 `<style>`，内容是处理过的 CSS；原来的 `media` 属性保留到 `<style media>` 上。
  - `<link rel="icon" | "apple-touch-icon" | "shortcut icon">` 的 href 转成 data URI。
  - `<link rel="preload" | "modulepreload" | "preconnect" | "dns-prefetch">`：本地的 preload 删掉；外部的 preconnect 和 dns-prefetch 也删掉。
  - `<script src>`：内容内联到 `<script>` 里，保留 `type`、`defer`、`async` 以外的属性，并删掉 `src`。内联时要把内容中的 `</script` 转义成 `<\/script`。
  - `<img src>`、`<img srcset>`、`<source src|srcset>`、`<video src|poster>`、`<audio src>`、`<input type=image src>`：都转成 data URI。`srcset` 要按逗号拆开后逐项处理。
  - 元素的 `style="...url(...)..."` 属性和 `<style>` 块中的内容按 CSS 规则处理。
- CSS 处理（用正则，不需要完整的 CSS 解析器）：
  - `@import url("x.css") ...;` 和 `@import "x.css" ...;`：递归内联被导入文件的内容。外部 CSS 下载后同样递归处理，其中的相对 URL 以**那个外部 CSS 的 URL** 为基准解析。用一个已访问集合防止循环导入。如果 import 带 media 条件，用 `@media <cond> { ... }` 包起来。
  - `url(...)`（支持带引号和不带引号）：转成 data URI。
- data URI 的 MIME：本地文件按扩展名判断（规则和 2.2 一样，扩展名映射可以放在一个纯 Kotlin 的 `MimeTypes.kt` 里供两处共用；不要用 `android.webkit.MimeTypeMap`，否则 JVM 单测跑不了）；外部资源优先使用 `contentType`。
- 限制：
  - 单个资源超过 `maxResourceBytes`，或者累计超过 `maxTotalBytes` 时，保留原链接，并记入 `skippedForSize`。
  - 下载失败的记入 `failedUrls`，同样保留原链接。
  - 同一个 URL 只下载一次（缓存 `Map<String, Downloaded?>`）。
- 不处理的内容：`<a href>`、`<form action>`、JS 里的 `fetch`/`import()` 和字符串里的路径、`<iframe src>`。
- **必须是纯 Kotlin 实现，不能依赖 Android API**，以保证可以在 JVM 上做单元测试。

在 app 侧实现 `OkHttpResourceDownloader(okHttpClient)`：
- 从 Koin 拿现有的 `OkHttpClient`，调用 `newBuilder()` 设置 `callTimeout(15s)`。
- `User-Agent` 设为现代 Chrome Android 的 UA，这样 Google Fonts 返回的是 woff2。
- 先检查响应头的 `Content-Length`，超过上限的直接放弃；读取 body 时也要边读边计数，超过上限就中止。
- 非 2xx 状态码返回 `null`。

生成结果的警告文案：
- `N 个外部资源下载失败，已保留原链接（断网时可能无法显示）`
- `N 个资源过大未内联`

### 4.2 zip（`data/export/ProjectZipper.kt`）

```kotlin
object ProjectZipper {
    fun zip(projectRoot: File, rootFolderName: String, output: File)
}
```
- 递归打包 `projectRoot` 下的所有内容，zip 里的条目路径是 `<rootFolderName>/<相对路径>`，统一用 `/` 分隔。
- 跳过名字以 `.` 开头的文件和目录（例如 `.DS_Store`、`.git`），也跳过符号链接。
- 空目录要写入一个目录条目。
- 写法参照 `data/sync/BackupManager.kt` 中的 `ZipOutputStream` 用法，必须是纯 Kotlin 实现。

### 4.3 长截图（`ui/pages/projects/export/WebPageCapturer.kt`）

全局准备：在 `RikkaHubApp.onCreate` 中，于任何 WebView 创建之前调用 `WebView.enableSlowWholeDocumentDraw()`（注释里写明原因：项目导出长图需要绘制整页）。

```kotlin
class WebPageCapturer(private val activity: Activity) {
    suspend fun capture(
        server: ProjectFileServer,
        entryPath: String,
        outputDir: File,
        baseName: String,
        onProgress: (current: Int, total: Int) -> Unit,
    ): CaptureResult   // files + mode + warnings
}
```

流程：

1. **创建离屏 WebView**（主线程）：
   - 按 `BitmapComposer` 的做法，把 WebView 挂到 `activity.window.decorView` 的一个容器里，设置 `alpha = 0f`，不响应触摸，完成后在 `finally` 中移除并 `destroy()`。
   - 尺寸：宽 = `390 * density` px，高 = `844 * density` px。设置 `layoutParams` 后手动 `measure`/`layout`。
   - WebView 设置和预览页一致（开启 JS），`WebViewClient` 中通过 `server.intercept` 提供本地文件。
   - 关闭滚动条：`isVerticalScrollBarEnabled = false`。
2. **等待页面加载**：
   - 等 `onPageFinished`，超时 15 s。超时也继续往下走，并加一条警告。
   - 然后执行 JS `document.fonts.ready`，用 `evaluateJavascript` 轮询一个标志位，最多等 3 s。
3. **触发懒加载**：用 JS 每次 `scrollBy(0, innerHeight)` 一屏，间隔 150 ms，直到滚到底部或者达到 50 屏上限。然后 `scrollTo(0, 0)`，再等 800 ms。
4. **判断页面类型**：执行一段 JS，返回 JSON：
   ```js
   (() => {
     const vh = innerHeight, de = document.documentElement, body = document.body;
     const pageH = Math.max(de.scrollHeight, body ? body.scrollHeight : 0);
     const snap = [de, body].some(e => e && /(y|both|block)/.test(getComputedStyle(e).scrollSnapType));
     const containers = [body, document.querySelector('main')].filter(Boolean);
     let slides = [];
     for (const c of containers) {
       const kids = [...c.children].filter(k => { const h = k.getBoundingClientRect().height; return Math.abs(h - vh) <= vh * 0.05; });
       const covered = kids.reduce((s, k) => s + k.getBoundingClientRect().height, 0);
       if (kids.length >= 2 && covered >= pageH * 0.8) { slides = kids; break; }
     }
     if (snap && slides.length === 0 && body) slides = [...body.children].filter(k => k.getBoundingClientRect().height >= vh * 0.9);
     const rects = slides.map(k => { const r = k.getBoundingClientRect(); return { top: r.top + scrollY, height: r.height }; });
     const lockedOverflow = pageH <= vh + 2 && [de, body].some(e => e && getComputedStyle(e).overflowY === 'hidden')
       && (body ? body.scrollHeight > vh + 2 || [...body.querySelectorAll('*')].some(n => n.scrollHeight > n.clientHeight + 2 && getComputedStyle(n).overflowY === 'hidden') : false);
     return JSON.stringify({ pageH, vh, slides: rects, locked: lockedOverflow });
   })()
   ```
   这段 JS 只是参考实现，可以调整，但三类页面的判断标准要保持一致：
   - `slides.length >= 2` → **SLIDES**：每个 section 截一张。截图方式：`scrollTo(0, top)`，等 300 ms，然后按视口高度截一张。
   - 页面高度不超过视口高度，并且存在溢出隐藏 → **VIEWPORT_ONLY**：只截当前这一屏，并加警告「该页面只能截取当前屏（可能是用脚本翻页的幻灯片）」。
   - 其他情况视为普通长页：
     - `pageH ≤ 6000`（CSS px）→ **SINGLE**，截成一张整页图。
     - `pageH > 6000` → **SPLIT**，每 4000 CSS px 切一段，最后一段取剩余高度。
5. **出图**：
   - 整页绘制：把 WebView 的 layout 高度设成 `pageH * density`，然后 `measure`/`layout`，等两帧，再用 `draw(canvas)` 画到 Bitmap。分段时，Canvas 先 `translate(0, -segmentTop * density)`，然后绘制 `segmentHeight` 高的 Bitmap。
   - 输出倍率：目标宽度 1170 px（约 3x）。输出 Bitmap 的高度 = `segmentHeightCss * 3`；超过 16384 px 时改用 2x（宽 780 px）。缩放用 `Canvas.scale`。
   - 每张图生成后立刻 `compress(PNG)` 写入文件，然后 `recycle()`，不要在内存里同时持有多张大图。
   - 每张图完成后调用 `onProgress(i, total)`。
   - 最多 9 张：超过时只保留前 9 张，并加警告「共 N 屏，微信一次最多发 9 张，已保留前 9 张」。
6. `position: fixed` 的元素在长图中只会出现在一个位置，这是可以接受的，不做处理。
7. 整个流程在主线程调度，可以用 `withContext(Dispatchers.Main)`；只有写 PNG 文件放到 `Dispatchers.IO`。取消时要保证 WebView 被移除并销毁。

### 4.4 分享与保存（`utils/ShareUtil.kt`）

```kotlin
fun shareFiles(context: Context, files: List<File>, mimeType: String)
suspend fun saveToDownloads(context: Context, file: File, mimeType: String, subDir: String = "RikkaHub"): Uri?
suspend fun saveImagesToGallery(context: Context, files: List<File>)   // Pictures/RikkaHub
```

- `shareFiles`：
  - 通过 FileProvider 生成 uri。单个文件用 `ACTION_SEND` 加 `EXTRA_STREAM`；多个文件用 `ACTION_SEND_MULTIPLE` 加 `putParcelableArrayListExtra(EXTRA_STREAM, uris)`。
  - 加上 `FLAG_GRANT_READ_URI_PERMISSION`；设置 `ClipData`，把所有 uri 都放进去，确保接收方拿到读取权限。
  - 最后用 `Intent.createChooser(intent, null)` 发起。
- 把 `MediaCreationCommon.shareMedia` 改成调用 `shareFiles(context, listOf(file), mimeType)`，保持原有调用方不变。
- `saveImagesToGallery`：写法参照 `saveMediaToGallery`。API 29+ 用 MediaStore，`RELATIVE_PATH = Pictures/RikkaHub`；更低版本写入公共目录，需要申请权限，逻辑和现有代码一致。也可以把 `saveMediaToGallery` 泛化后复用，但不要改变它现有的行为。
- `saveToDownloads`：
  - API 29+：用 `MediaStore.Downloads.EXTERNAL_CONTENT_URI`，`RELATIVE_PATH = Download/RikkaHub`。
  - 更低版本：写到 `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)/RikkaHub/`，权限处理和 `saveMediaToGallery` 一致。
- MIME：图片用 `image/png`，HTML 用 `text/html`，zip 用 `application/zip`。
- 保存成功后 toast「已保存到 相册/RikkaHub」或「已保存到 下载/RikkaHub」；失败时 toast 显示错误信息。

### 4.5 Phase 4 测试（JVM）

`HtmlInlinerTest`（用临时目录构造项目，`ResourceDownloader` 用 fake 实现）：
- `index.html` 中 `<link href="css/style.css">` 被替换成 `<style>`；`style.css` 里的 `url(../img/a.png)` 按 CSS 文件自身位置解析，变成 `data:image/png;base64,...`。
- `@import "base.css";` 被递归内联；两个文件互相 import 时不会死循环。
- `<script src="js/app.js">` 被内联，内容中的 `</script>` 被正确转义。
- `<img srcset="a.png 1x, b.png 2x">` 两项都被转换。
- 外部资源：
  - fake downloader 返回成功时内联。
  - 返回 null 时保留原链接，并出现在 `failedUrls` 中。
  - 返回 6 MB 时保留原链接，并出现在 `skippedForSize` 中。
  - 同一个 URL 只请求一次。
- 外部 CSS 中的相对 `url(fonts/x.woff2)` 按外部 CSS 的 URL 解析成绝对 URL 后再下载。
- 越界路径（`../../secret.txt`）不会被读取。
- `<a href="page2.html">` 保持不变。

`ProjectZipperTest`：
- 条目路径带根目录名；隐藏文件被跳过；空目录有对应条目；解压后内容一致。

### 4.6 Phase 4 验收

1. 预览页 → ⤴ → HTML → 生成 → 分享 → 选择微信「文件传输助手」。在微信里打开这个 html，样式和脚本正常。
2. zip 导出后分享到微信，能正常收到；「保存到本机」后在「下载/RikkaHub」里能找到。
3. 长图：
   - 普通页面（约 3000 px）导出 1 张。
   - 超长页面（约 15000 px）切成 4 张。
   - 5 个 section 的幻灯片导出 5 张。
   - 用 JS 翻页的幻灯片只截当前屏，并显示提示。
   - 「保存到本机」后相册里能看到这些图。
4. 一个引用了 Tailwind CDN 和 Google Fonts 的页面，导出 HTML 后在断网状态下打开，样式仍然正常；字体如果下载失败，会显示对应的警告。
5. 生成过程中点击取消，没有残留文件，App 也不会崩溃。

---

## 5. 总体手动回归清单（全部 Phase 完成后）

1. 冷启动进入项目列表 → 新建项目「测试」→ 自动打开对话，输入框获得焦点。
2. 点击灵感快捷词「个人主页」后文本被填入输入框；发送后 AI 写出 `index.html`、`style.css`、`script.js`，整个过程不需要安装 rootfs。
3. 文件 Tab 能看到这些文件；新建文件夹、新建文件、重命名、导入都正常。
4. 「预览效果」按钮能打开预览，样式生效。让 AI 改个颜色后切回预览页，页面已自动刷新。故意制造一个 JS 报错，「让 AI 修复」后对话的输入框里填好了错误描述。
5. 返回首页，项目卡片显示缩略图。
6. 三种导出格式都能分享到微信，「保存到本机」也正常。
7. 返回键路径正确；普通聊天、设置、工作区（包括 rootfs 终端和 `workspace_shell`）的功能都没有变化。
8. `./gradlew assembleDebug`、`./gradlew test`、`./gradlew lint` 都通过（lint 不能新增 error）。

---

## 6. 不在本次范围

- 宽屏 / 平板的分栏布局。
- PDF 导出、微信 SDK。
- 专用的「HTML 创作」助手。
- 项目模板市场、多人协作、Git。
- 后台生成断开问题（另行处理）。
