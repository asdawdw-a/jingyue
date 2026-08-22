# 净阅（com.pure.jingyue）

一款专注**优质网页文本阅读体验**的 Android 阅读器：**聚合检索 → 正文提取 → 纯净排版 → 无缝连读**。零第三方依赖（纯 Android 框架 + WebView + Kotlin）。

> **立场**：本软件只是一个解析与排版工具（可完全理解为"定制版浏览器"）——不提供任何内容、没有自己的服务器，配置与显示内容完全由用户决定。杜绝任何盗版：不包含、不存储、不指向任何侵权内容，仅为通用网页文本（新闻/博客/长文等）提供"去干扰、纯净排版、连续阅读"的体验；任何盗版传播或牟利行为均与本源无关并坚决反对。

## 核心功能
- **搜索找书**：输入书名 → 多引擎聚合检索候选 → 算法自动校验（可读性/免费度打分，按域名去重）→ 点源直进第一章
- **纯净阅读**：正文 **容器化提取**（只取正文容器，菜单/导航/公告天然排除）+ 格式适配（乱码/实体/反转码斜杠/URL/版权声明清洗），自包含页面渲染，固定字号 17px 起
- **无缝连读**：滑到底自动拼接下一章正文（流式），顶部上滑接上一章；滚动 / 仿真翻页两种模式
- **阅读设置**（☰ → 阅读设置）：字号 12~26 · 字体（系统/宋体/楷体/黑体）· 行距 · 边距 · 主题（白/米黄/浅绿/夜间黑）· 翻页（滚动/仿真）· 自动翻页 · 保持亮屏；目录与阅读页同主题联动
- **书架**：平放卡片（书名/来源/进度/上次章节）、最近打开置顶、长按管理、点书**精确续读（到章节内位置）**
- **进度**：章节 + 章节内百分比实时记录，重开精确恢复
- **书签**：☰ 添加/取消；目录 ★ 标记；状态栏标注
- **目录**：分组（每 50 章）、章节搜索、当前章高亮、音量键 +/- 翻章
- **历史 / 导入源**（☰）：历史列表、自定义书源导入
- **搜索配置**（搜索页内）：引擎勾选、广度（页数）、校验条数，附说明
- **网络请求限流**：全局并发与间隔控制（每域名并发 ≤2 / 间隔 ≥350ms）；**广告拦截**：网络层域名拦截 + 页面注入

## 架构
```
MainActivity (单 Activity + WebView)
 ├─ SourceValidator.kt   检索聚合与校验：搜索/目录/正文处理/限速/类型校验/免费度
 ├─ TextCleaner.kt       容器定位提取 + 格式适配 + 强标记过滤 + 标题清洗
 ├─ Stores.kt            书架/历史/书签/进度（SharedPreferences JSON）
 ├─ assets/reader.js     阅读渲染（配置驱动、流式 JS 桥）
 ├─ assets/readability.js Mozilla Readability（网页直读路径）
 └─ assets/adblock.js    页面级广告拦截
```
阅读链路：搜索聚合 → 校验 → extractToc → openChapter（按需获取）→ 容器提取 → 清洗 → `buildStreamHtml`（自包含文档 + 无缝追加/前插 + 进度上报）→ 阅读设置注入 `window.__RCFG__`。

## 设计说明：可用性监测
候选锚点的可用性采用**四分位 + 随机数**监测：
- 对候选的章节目录按序列取**四分位锚点（25% / 50% / 75%）**，并叠加**随机抽样的章节位置**；
- 逐锚点探测正文的可获取性与结构完整性（可解析、非占位）；
- 综合各锚点结果计算可信度，可用性高的候选排在前面；非完全可用的候选仍会出现在列表后段，仅下调排序，不影响展示。
（说明：算法只判断"该页面是否为可解析的正文"，不涉及任何特定站点规则。）

## 构建
```
JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
gradle --no-daemon :app:assembleDebug    # 调试
gradle --no-daemon :app:assembleRelease # 签名版
```
- compileSdk 34 / minSdk 26 / Kotlin 1.9.24 / AGP 8.2.2 / JDK 21
- 签名：`reader.keystore`（alias: purereader），release 输出 `app/build/outputs/apk/release/app-release.apk`

## 测试环境
设备 PKB110（Android 版），adb 安装：`adb install -r app-release.apk`

## 开源与许可
- **Apache License 2.0**（见 [LICENSE](LICENSE)）；第三方组件 [Mozilla Readability](https://github.com/mozilla/readability)（Apache-2.0）保留原始版权声明
- **重要声明**：本项目是研究/学习用途的通用工具——**不内置任何书源数据库、不存储任何版权内容**，来源仅为搜索引擎公开结果或用户自行导入。请阅读 [DISCLAIMER.md](DISCLAIMER.md)：禁止商用、禁止用于传播侵权内容，使用者自担一切责任。
- 仓库**不含**签名密钥（`reader.keystore` 等一律被 `.gitignore` 排除），如你 clone 后自行构建正式包，请生成自己的密钥。
