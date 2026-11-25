# 第三方库登记表

本表登记 CalendarBoard 整改各阶段引入的第三方库。新增依赖时须同步更新本表，
记录名称、锁定版本、许可证、用途与引入阶段；P0 阶段本身仅使用 Python 标准库，
尚未引入第三方库。

| 名称 | 版本 | 许可证 | 用途 | 引入阶段 |
| --- | --- | --- | --- | --- |
| androidx.core:core | 1.17.0 | Apache-2.0 | AndroidX 核心兼容工具（ContextCompat 等） | P3 |
| androidx.appcompat:appcompat | 1.7.0 | Apache-2.0 | AppCompat 兼容组件与主题支持 | P3 |
| com.google.android.material:material | 1.12.0 | Apache-2.0 | Material 3 组件与 DayNight 主题 | P3 |
| Material Symbols | 2024 图标集（矢量导出） | Apache-2.0 | Android 矢量图标设计来源（`res/drawable/ic_*.xml`，无运行时依赖） | P3 |
| androidx.recyclerview:recyclerview | 1.3.2 | Apache-2.0 | 列表视图组件 | P3 |
| androidx.viewpager2:viewpager2 | 1.1.0 | Apache-2.0 | 横向分页视图组件 | P3 |
| junit:junit | 4.13.2 | EPL-1.0 | JVM 单元测试框架 | P3 |
| org.json:json | 20240303 | Public Domain | JVM 单元测试中的 org.json 实现 | P3 |
| DynamicNotchKit | 1.1.0 | MIT | macOS 刘海 / 无刘海课程岛容器、展开收起与过渡动效；本地 Vendor 化并做 CLT 宏兼容 | P4 |
| KeyboardShortcuts | 1.9.4 | MIT | 全局快捷键注册、快捷键录制窗口与本地化资源 | P4 |
| chrono-node | 2.10.3 | MIT | 课程岛中文自然语言快速添加（日期/时间解析），esbuild 打包为浏览器 IIFE | P4-B |

## P4-B chrono-node 2.10.3（中文自然语言解析）

- **版本 / 许可证**：`chrono-node@2.10.3`，许可证 `MIT`（上游 `package.json` 声明 `"license": "MIT"`）。
- **用途**：`macos-widget/ui/quickadd-parser.js` 调用 `chrono.zh.hans.parse(text, refDate, { forwardDate: true })`，为 macOS 课程岛提供中文自然语言快速添加。
- **产物路径**：`macos-widget/ui/vendor/chrono.min.js`（IIFE，全局变量 `chrono`；由 esbuild 生成，禁止手改）。
- **可复现生成命令**（在 `CalendarBoard/` 目录、已安装 `chrono-node@2.10.3` 与 `esbuild` 的环境中执行）：
  ```bash
  npx esbuild node_modules/chrono-node/dist/esm/index.js \
    --bundle --minify --format=iife --global-name=chrono \
    --outfile=macos-widget/ui/vendor/chrono.min.js
  ```
- **本次实际生成命令**（使用 scratch 中已安装的 esbuild 0.28.2）：
  ```bash
  "/Users/strongintheearth/.gemini/antigravity/brain/1ddb6f91-879b-4199-ab4f-a1558b8545e9/scratch/chrono-build/node_modules/.bin/esbuild" \
    "/Users/strongintheearth/.gemini/antigravity/brain/1ddb6f91-879b-4199-ab4f-a1558b8545e9/scratch/chrono-build/node_modules/chrono-node/dist/esm/index.js" \
    --bundle --minify --format=iife --global-name=chrono \
    --outfile="macos-widget/ui/vendor/chrono.min.js"
  ```

## P4.2 DynamicNotchKit 本地化（CLT 宏兼容）

- **原项目出处**：<https://github.com/MrKai77/DynamicNotchKit>，锁定 tag `1.1.0`（commit `cd0b3e52d537db115ad3a9d89601f20e0bee8d27`）。
- **许可证**：MIT，原始许可证文本随源码保留在 `macos-widget/Vendor/DynamicNotchKit/LICENSE`（Copyright (c) 2025 Kai Azim）。
- **引入方式**：源码复制到 `macos-widget/Vendor/DynamicNotchKit`，`Package.swift` 由远程版本依赖改为 `.package(path: "Vendor/DynamicNotchKit")`；同时移除未使用的 `swift-docc-plugin` 依赖与测试 target，只保留 `DynamicNotchKit` library target（`.macOS(.v13)`）。
- **本地化原因**：本机仅有 Command Line Tools（无完整 `Xcode.app`），缺少 `libSwiftUIMacros.dylib`。仓库自带的 `MacOSX27.0.sdk` 将 `@State` 等改为外部宏，CLT 无法展开；`build.sh` 因此在 CLT-only 环境自动选择 `MacOSX26.5.sdk`。在此基础上，DynamicNotchKit 1.1.0 仍有两处宏用法不兼容，故作最小替换：
  1. `Sources/DynamicNotchKit/Utility/EnvironmentValues+Extensions.swift`：将 2 处 `@Entry` 改为经典 `EnvironmentKey` 实现（`notchStyle` / `notchSection`），行为不变。
  2. `Sources/DynamicNotchKit/Views/NotchShape.swift`：删除末尾仅供 Xcode 画布使用的 `#Preview` 代码块。
  3. `Sources/DynamicNotchKit/DynamicNotch/DynamicNotch.swift`：新增 `panelSize` 闭包属性，`initializeWindow` 优先使用；`observeScreenParameters` 隐藏态不重建窗口、沿用当前屏幕。用于消除展开末尾的窗口尺寸跳变。
  4. `Sources/DynamicNotchKit/Views/NotchView.swift`：`expandedContent()` 的展开过渡由 `.blur + .scale(y: 0.6) + .opacity` 改为 `.opacity + .offset(y: -8)`。SwiftUI 的模糊/缩放对 `NSViewRepresentable` 内的 `WKWebView` 不生效，原过渡会让网页内容“先硬切再出现”；如需恢复上游观感，改回该方法原一行即可。
  5. `Sources/DynamicNotchKit/Utility/DynamicNotchPanel.swift`：面板层级由 `.screenSaver` 降为 `.mainMenu + 3`，避免遮挡输入法候选框和系统弹出菜单。
- 除上述五处外未改动上游实现；后续若升级 DynamicNotchKit，需重新核对宏兼容性，或改回上游远程依赖。

## P4.1 依赖核实结论（DynamicNotchKit 1.1.0 / KeyboardShortcuts 1.9.4）

1. `DynamicNotch` 是 `@MainActor` 的泛型可观察对象，泛型参数依次为展开视图、左侧紧凑视图、右侧紧凑视图；初始化器参数为 `hoverBehavior`、`style`、`expanded`、`compactLeading`、`compactTrailing`，`style: .auto` 可自动适配有刘海与无刘海屏幕。
2. `expand(on:)`、`compact(on:)`、`hide()` 均为异步方法；在无刘海屏幕的 `.floating` 风格下，`compact(on:)` 会直接转为 `hide()`，因此无刘海屏幕的紧凑态必须由外层改用 `expand(on:)`，并在 `ExpandedIslandView` 中依据 `mode == .compact && isFloatingScreen` 渲染浮动小胶囊。
3. 悬停状态通过 `isHovering` 与 Combine 的 `$isHovering` 暴露，可 `removeDuplicates()` 后驱动 200ms 悬停展开、300ms 移出收起，且悬停展开不得调用 `makeKey()` 抢占键盘焦点。
4. 过渡动效通过 `DynamicNotchTransitionConfiguration` 配置，四参数为 `openingAnimation`、`closingAnimation`、`conversionAnimation`、`skipIntermediateHides`；开启“减弱动态效果”时应改用 0.20s `.easeInOut`。
5. 面板窗口可通过 `windowController?.window` 取得，窗口为无边框、非激活型 `NSPanel`，`canBecomeKey == true`；需要键盘输入时调用 `makeKey()` 即可，无需额外切换激活策略。
6. 刘海判定使用 `NSScreen.auxiliaryTopLeftArea` 与 `auxiliaryTopRightArea` 是否同时存在；启动时及 `NSApplication.didChangeScreenParametersNotification` 时需重新计算并向 Web 层同步 `notch` / `floating` 外观。
7. `DynamicNotch` 默认容器窗口尺寸（屏幕一半）不足以容纳 `860×560` 的月历视图。本仓库改为创建窗口前通过 `panelSize` 闭包一次性给出最终尺寸（内容 + 内边距 + 阴影余量，并按屏幕尺寸夹紧），水平居中贴顶；展开过程中与展开结束后都不再调用 `setFrame`，既避免裁切也避免尺寸跳变。
8. `WKWebView` 不随课程岛隐藏而销毁：使用屏幕外透明保活窗口（`alphaValue = 0`、`ignoresMouseEvents = true`、`orderBack`）承载 WebView，隐藏时调用 `attachToKeepAliveWindow()`，从而保留页面状态、会话与 JavaScript 定时器。

## Material Symbols（Android 矢量图标）

- **出处**：Google Material Symbols 图标集 <https://fonts.google.com/icons>，许可证 `Apache-2.0`。
- **用途**：Android 端 `res/drawable/ic_add.xml`、`ic_check.xml`、`ic_chevron_left.xml`、`ic_chevron_right.xml`、`ic_close.xml`、`ic_more.xml`、`ic_refresh.xml`、`ic_stat_calendar.xml` 等矢量图标以 Material Symbols 为设计来源手工导出。
- **依赖形态**：仅提交静态 VectorDrawable XML，不引入图标字体、Gradle 依赖或运行时资源，因此不影响 APK 体积与启动。
- **许可保留**：Material Symbols 的 Apache-2.0 许可与上游一致；如后续替换图标，需继续保留同许可证或在本表补充新来源。

## Lucide（Android 矢量图标）

- **出处**：Lucide 开源图标集 <https://github.com/lucide-icons/lucide>，许可证 `ISC`（完整许可证文本见 `docs/licenses/lucide.txt`）。
- **用途**：Android 端 `res/drawable/` 内的应用内小图标（`ic_add`、`ic_check`、`ic_chevron_left`、`ic_chevron_right`、`ic_close`、`ic_more`、`ic_refresh`、`ic_course`、`ic_todo`、`ic_schedule`、`ic_review`、`ic_archive`、`ic_bell`）。
- **本地改造**：线宽改为 1.75，转为 Android VectorDrawable 静态 XML，不引入运行时依赖。

## 登记完整性核对（v3.0）

| 类别 | 依赖 | 状态 |
| --- | --- | --- |
| AndroidX | `androidx.core:core`、`androidx.appcompat:appcompat`、`androidx.recyclerview:recyclerview`、`androidx.viewpager2:viewpager2` | 已登记 |
| Material | `com.google.android.material:material` | 已登记 |
| 图标 | Material Symbols（静态 VectorDrawable） | 已登记 |
| macOS 课程岛 | DynamicNotchKit 1.1.0（本地 Vendor） | 已登记 |
| macOS 快捷键 | KeyboardShortcuts 1.9.4 | 已登记 |
| 自然语言解析 | chrono-node 2.10.3（esbuild IIFE） | 已登记 |
| 测试 | junit 4.13.2、org.json 20240303 | 已登记 |

> 版本冻结说明：v3.0 未新增任何第三方依赖；上表版本与各端构建文件保持一致。
