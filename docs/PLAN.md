# Inkpad Android：移植总体思路

写于 2026-11-09。这是动手前的总方案，供用户审阅。定下来之后再拆成像 Web 版 `docs/PORTING.md` 那样逐里程碑的施工图。

参考代码（只读）：

| 代号 | 仓库 | 本机位置 | 用途 |
| --- | --- | --- | --- |
| B | `Inkpad-gnome`（Python + GTK） | 无本机副本；`Inkpad Web/Web/ref/B` 有一份旧版 | **上游**：逻辑、常量、文案以它为准 |
| A | `Inkpad-iOS`（Swift） | `IOS apps/Note` | 触摸与手写笔交互、`async` 同步控制流、窄屏布局、测试用例 |
| W | `Inkpad-Web`（JavaScript） | `Inkpad Web/Web` | `docs/PORTING.md` 的平台对照表；白板大坐标的处理、zip 原样拷贝、测试数据 |

---

## 1. 目标

1. **功能对等**：三端已有的功能都移植，只为某个平台专门做的部分除外（见 §9）。
2. **互通**：同一种 `.inkpad` 文件、同一套 Google Drive 同步协议，逐字节兼容。在任何一端写的笔记都能在安卓上接着写，反之亦然；两边同时改按笔画合并。
3. **原生手感**：手写笔低延迟、压感、防手掌误触，和 iPad 版同一水准。
4. **界面复刻**：同样的颜色、图标（Lucide 路径数据）、布局，不用 Material 默认样式代替。

## 2. 路线选择

| 路线 | 做法 | 优点 | 缺点 |
| --- | --- | --- | --- |
| ① PWA | 在安卓 Chrome 里打开 Web 版，“添加到主屏幕” | 现在就能用，零开发 | 浏览器的笔延迟和压感有限；数据在浏览器里，清数据就没了 |
| ② 套壳（TWA / WebView） | 把 Web 版包成 APK | 工作量小 | 手感上限与 ① 相同；WebView 里 Google 登录受限 |
| **③ 原生 Kotlin（推荐）** | 照 iOS 版的做法，把 B/A 的逻辑逐函数翻译成 Kotlin | 低延迟书写（前缓冲渲染、笔迹预测）、真实文件系统、与 iOS 版同等的手感 | 工作量最大（预计 1.5–2 万行） |

**建议走 ③**。① 在开发期间就能当作临时方案使用，不需要额外工作。

## 3. 技术选型

| 方面 | 选择 | 说明 |
| --- | --- | --- |
| 语言 | Kotlin | 与 Swift 的结构几乎一一对应，A 的代码可以近乎直译 |
| 界面外壳 | Jetpack Compose | 对应 A 的 SwiftUI：文稿库、对话框、设置、弹出面板 |
| 画布 | 自定义 `View`（硬件加速）+ `androidx.graphics:graphics-core` 的前缓冲渲染 | 对应 A 的 UIKit 画布。正在写的笔画走前缓冲（落笔到出墨约一帧），写完画进页面缓存 |
| 笔迹预测 | `androidx.input` 的 `MotionEventPredictor` | 对应 A 的 `predictedTouches` |
| 绘图 | `android.graphics.Canvas` / `Path` / `Paint`；正片叠底用 `BlendMode.MULTIPLY` | 与 cairo、CoreGraphics 一一对应 |
| PDF 显示 | 系统 `PdfRenderer`；文字查找在 Android 15 以上用系统接口，更早的版本用 pdfium | 风险 R2 |
| PDF 导出 | 系统 `PdfDocument`（Canvas 直接输出矢量，绘制代码可以原样复用）；把原 PDF 页以矢量嵌入要用 PDFBox-Android（Apache-2.0） | 风险 R2 |
| zip | 自己写（翻译 W 的 `zip.js` / A 的 `Zip.swift`） | 要支持条目原样拷贝、读 B 写的 zip64 本地头，`java.util.zip` 做不到 |
| 哈希 | JDK 自带的 MD5、SHA-256、CRC-32 | 支持流式计算，不用自己写 |
| 网络 | `HttpURLConnection` 或 OkHttp | 不用 Google 官方客户端库，保持体积小 |
| 并发 | Kotlin 协程 | A 的 `async/await` 同步代码可以几乎逐行翻译 |
| 最低系统版本 | Android 10（API 29）起，建议 Android 12（API 31） | 待用户决定（§10） |
| 构建 | Gradle + Android Studio | 开工前需要安装（§10） |

## 4. 代码结构

```
app/src/main/java/io/github/inkpad/
  core/       纯逻辑，不依赖安卓：geom、pack、zip、items、page、document（撤销、墓碑、合并、读写）、
              cover、settings、i18n、icons
  sync/       同步算法（翻译 A 的 Sync.swift）、Drive 接口、Google 登录、同步调度
  library/    文稿库：Store（文件夹即目录、废纸篓、收藏、移动撤销）+ Compose 界面
  editor/     CanvasView（布局、滚动缩放、页面缓存）、Input（笔/手指/鼠标状态机）、Tools、
              编辑器外壳、侧栏、小地图
  pdf/        PdfRenderer 封装、后台渲染队列、矢量导出
  io/         导入导出、存档 zip、Goodnotes
  ui/         主题（Adwaita 颜色）、Lucide 图标、弹出面板、对话框、提示条
app/src/test/ 纯逻辑的 JVM 单元测试（core、sync、goodnotes），不需要模拟器
```

`core/` 和 `sync/` 不引用任何 `android.*` 类，这样能在普通 JVM 上快速测试，和 W 的 `node --test` 一样。

## 5. 平台对应

### 5.1 输入

| 用途 | iOS（A） | 安卓 |
| --- | --- | --- |
| 区分笔/手指/鼠标 | `UITouch.type` | `MotionEvent.getToolType()`：`STYLUS`、`FINGER`、`MOUSE`、`ERASER` |
| 压力 | `force` | `getPressure()`（0–1，各厂商标定不同，需要像 A 那样识别“压力恒定的笔”并改用速度笔锋） |
| 一帧内合并的采样 | `coalescedTouches` | `getHistoricalX/Y/Pressure` |
| 预测笔迹 | `predictedTouches` | `MotionEventPredictor` |
| 橡皮端 | 双击 Pencil | `TOOL_TYPE_ERASER`（S Pen 等的橡皮端），以及笔身按键 `BUTTON_STYLUS_PRIMARY` |
| 悬停 | `UIHoverGestureRecognizer` | `ACTION_HOVER_MOVE`（S Pen、多数安卓手写笔都支持） |
| 手掌被系统取消 | `touchesCancelled` | `ACTION_CANCEL` 或带 `FLAG_CANCELED` 的 `ACTION_POINTER_UP`（Android 13 起） |
| 防误触 | 笔按着时忽略手指 | 同一套逻辑：笔按着或停笔 `palm_ms` 内的手指一律忽略 |
| 滚动与缩放 | `UIScrollView` | 不用系统滚动控件，照 B/W 自己管理滚动量和缩放（W 的 `canvas.js`、`input.js` 是现成的蓝本） |

一个教训要直接带过来：iPad 版刚修的“第一笔丢失”，起因是把手掌和笔放进同一个手势里一起结束。安卓版的状态机要从一开始就让笔的落下接管一切，手掌的抬起或被取消都不影响正在写的笔画。

### 5.2 白板大坐标

安卓的 `Canvas` 变换矩阵是单精度浮点，和浏览器一样有 W 的风险 R3：白板内容在 5×10⁷ 附近，直接平移会让笔画抖动。照 W 的做法，所有绘制函数接收原点偏移，先用双精度减掉原点再交给 `Canvas`。

### 5.3 存储

| 方面 | 做法 |
| --- | --- |
| 文稿库 | 应用自己的目录 `filesDir/Inkpad`，目录结构与 B 完全相同（`.trash`、`.deleted`、`.library.json`、`.sync.json`），这样 `Store` 和同步算法能逐行翻译 |
| 用户能否看到文件 | 设置里提供“导出整个文稿库”和“导入存档”；另外可以选做“把文稿库放到用户选的文件夹”（系统的文件夹选择器），但那条路读写慢，放到后期 |
| 原子写入 | 写到 `.tmp` 再 `renameTo`，和 B 相同 |
| 文件签名 | `lastModified/length`，与 A 相同 |
| 凭据与令牌 | `EncryptedSharedPreferences`（对应 A 的钥匙串） |
| 偏好 | DataStore，键名照用 B 的 `settings.py` |

### 5.4 Google 登录

先试与 A、B 相同的做法：用同一个“桌面应用”类型的 OAuth 客户端，在应用内开一个一次性的本机环回服务器接收回跳，授权页用 Chrome 自定义标签页打开（不能用 WebView，Google 会拒绝）。这样用户导入同一个 `google_client.json` 就能看到同一批笔记。

如果 Google 拒绝安卓上的环回回跳，退路是在同一个 Cloud 项目里建“Android”类型的客户端，用 Google 自己的授权接口。Web 版已经实测过，同一项目下不同客户端对 `drive.file` 创建的文件互相可见。这是风险 R1，要在第一个里程碑里实测。

## 6. 移植方式

和 W 的方案一样，分三类：

- **直译**（约占一半）：`geom`、`pack`、`zip`、`items/page/document`（撤销、墓碑、合并、读写）、`Store`、同步算法、Goodnotes、页面操作、自动保存、同步调度。以 B 为准，控制流对照 A（`Sync.swift`、`AppModel.swift` 的 `EditorSession`），常量一个不改。
- **重写**：画布与页面缓存、输入状态机、PDF 显示与导出、Google 登录、界面控件。设计参考 W 的 `PORTING.md` §7（它已经把“没有 UIKit/GTK 时怎么做”想过一遍），交互细节参考 A。
- **不移植**：见 §9。

## 7. 验证方式

| 层 | 怎么测 |
| --- | --- |
| 纯逻辑 | JVM 单元测试，复用现成的测试数据：W 的 `tests/fixtures/*.json`（由 B 的 Python 生成）、A 的 55 个涂抹样本、A 的 `ModelTests` 用例 |
| 文件互读 | B 写的 `.inkpad`（W 的 `b-sample.inkpad`）安卓能读；安卓写的 B 能读 |
| 同步 | 翻译 A 的 `SyncTests` 15 个用例 + 内存版假云盘；再照 A 的 `CompatTests`，让 B 自己的 `gdrive.sync()` 和安卓的同步代码通过一个目录版云盘互相同步 |
| Goodnotes | A 的 `GoodnotesTests` |
| 界面与手感 | 模拟器走一遍功能清单；真机（带笔的安卓平板）试书写、防误触、延迟 |

## 8. 里程碑

| 编号 | 内容 | 类型 | 验收 |
| --- | --- | --- | --- |
| M0 | 安装工具、建工程、三个小试验：环回登录能否在安卓上用（R1）、前缓冲书写的延迟、PdfRenderer 渲染与查找 | 试验 | 三个试验都有结论，不通过先停下来商量 |
| M1 | `core`：几何、点压缩、zip、哈希 | 直译 | 与 B 的测试数据逐值一致 |
| M2 | 文档模型：元素、页面、撤销、墓碑、合并、读写 | 直译 | 与 B 双向互读，合并结果相同 |
| M3 | `Store` 与同步算法 | 直译 | `SyncTests` 15 项通过，与 B 互通测试通过 |
| M4 | 渲染：13 种纸张、封面、各种笔、白板大坐标 | 直译 + 重写 | 对照页与 B 截图逐格比对 |
| M5 | 画布：滚动、缩放、页面缓存、左右翻页 | 重写 | 200 页笔记流畅滚动 |
| M6 | 输入与工具：笔、橡皮、套索、形状、文本、便签、胶带、激光笔、尺子 | 直译 + 重写 | 功能清单逐项可用 |
| M7 | 编辑器外壳、文稿库界面、设置 | 重写 | 界面与另外三端一致 |
| M8 | PDF 导入、显示、查找、矢量导出；PNG；Goodnotes | 重写 + 直译 | 导出的 PDF 放大不糊、文字可选 |
| M9 | Google 登录与 Drive 传输、同步调度 | 重写 + 直译 | 与另外三端用同一个云端文件夹互相同步 |
| M10 | 真机手感调校、窄屏（手机）布局、性能走查 | 调校 | 用户在真机上确认 |

M1–M3 全是有现成测试兜底的翻译，做完时文件格式和同步就已经可信，之后再做界面。

## 9. 不移植或改成安卓的做法

| 项目 | 来源 | 处理 |
| --- | --- | --- |
| Apple Pencil 双击、Pencil Pro 捏合工具盘 | A | 不移植；改为支持笔身按键（可在设置里选“按住按键时是橡皮擦”） |
| 主屏幕快捷操作 | A | 可选：用安卓的应用快捷方式做“新建笔记本 / 最近打开” |
| 全屏丢笔画的规避代码 | B | 不移植（针对特定硬件） |
| 更改文稿库位置 | B | 后期可选（§5.3） |
| 7 天续签脚本 | A | 不需要：安卓可以直接安装 APK |

## 10. 已定事项（用户 2026-11-09 确认）

| 事项 | 决定 |
| --- | --- |
| 路线 | 原生 Kotlin（§2 的 ③） |
| 测试设备 | vivo Pad5 Pro + vivo Pencil 3（见 §11） |
| 最低系统版本 | Android 12（API 31）；编译用本机已装的最新 SDK |
| 发布方式 | 测试阶段只给用户本人和信任的朋友，直接装 APK；不上架 |
| 许可证 | GPL-3.0-or-later（与 B、W 相同）。A 的仓库已加例外：Inkpad 的其它版本可以以 GPL 使用 A 的代码 |
| 开发工具 | Android Studio 已安装：JDK 在 `/Applications/Android Studio.app/Contents/jbr`，SDK 在 `~/Library/Android/sdk` |
| 仓库 | `LoveProgramming-cc/Inkpad-Android`，本机即本文件夹 |
| 提交署名 | **不加任何 AI 合作者 / 贡献者署名**；作者 `Koen Tsao <96685107+LoveProgramming-cc@users.noreply.github.com>` |

## 11. 测试设备要点

资料来自公开评测与 vivo 官方问答，未在真机上核实的标为“待测”。

- **vivo Pad5 Pro**：天玑 9400；13 英寸 LCD，3096×2064；最高 144 Hz 刷新；触控采样率 240 Hz 或 480 Hz（来源不一）；OriginOS 5（基于 Android 15）。
  - 144 Hz 意味着一帧不到 7 ms：正在写的笔画必须走前缓冲，页面缓存的重建不能占主线程。
  - 整屏约 640 万像素，页面缓存的像素预算（§5、`MAX_PIXELS 12 000 000`）沿用即可。
- **vivo Pencil 3**：16384 级压感，支持倾斜，侧锋书写；交互有轻捏、双击、滑动（切换工具、截屏、翻页）。
  - 压感通过标准的 `MotionEvent.getPressure()` 读取；它的取值范围和曲线**待测**，M0 用输入日志记录一段真实笔迹再定换算。
  - 轻捏、双击、滑动是 vivo 系统自己的功能，**待测**它们是否以标准事件交给应用（例如按键事件）；如果拿不到，就不做对应功能，而不是依赖厂商私有接口。
  - 悬停（`ACTION_HOVER_MOVE`）是否支持**待测**；支持就显示笔尖标记，不支持也不影响书写。
  - 倾斜（`AXIS_TILT`）三端都没有用到，暂不使用。

## 12. 进度

| 里程碑 | 状态 |
| --- | --- |
| 工程骨架 | 完成：Gradle 9.9.0、Android Gradle 插件 9.5.1、Kotlin 2.4.21、Compose；`core`（纯 Kotlin）+ `app` 两个模块；`./gradlew :app:assembleDebug` 能生成 APK |
| M0 试验 | 未做：需要在 vivo Pad5 Pro 上进行 |
| M1 | 进行中：几何（`Geom.kt`）、点压缩（`Pack.kt`）、JSON 读取（`Json.kt`）完成，10 项测试与 B 的测试数据逐值一致；zip 未做；哈希直接用 JDK |
