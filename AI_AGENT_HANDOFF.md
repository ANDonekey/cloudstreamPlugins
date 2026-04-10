# AI_AGENT_HANDOFF（Cloudstream 插件交接）

## 0. 快速 SOP（Windows）
```powershell
cd F:\codx\cloudstreamplug
# 1) 切到 Java 17（自动探测，找不到就手动改）
$env:JAVA_HOME=(Get-ChildItem 'F:\tools\jdks' -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -like 'jdk-17*' } | Select-Object -First 1 -ExpandProperty FullName)
$env:GRADLE_USER_HOME='F:\tools\home\.gradle'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -version

# 2) 选择要编译/部署的插件
$plugin='SevenMMTVProvider'   # 例如 MissAVProvider / SevenMMTVProvider

# 3) 编译 + 部署
adb devices
adb shell cmd appops set com.lagradost.cloudstream3 MANAGE_EXTERNAL_STORAGE allow
.\gradlew.bat "$plugin`:make"
.\gradlew.bat "$plugin`:deployWithAdb"
```

## 1. 当前 MissAV 插件基线
- 文件：`MissAVProvider/src/main/kotlin/com/missav/MissAVProvider.kt`
- 主站：`https://missav.live`
- 语言：中文 `lang = "zh"`
- 分类：
  - 新作上市：`/dm590/cn/release`
  - 最近更新：`/dm515/cn/new`
  - 无码影片：`/dm150/cn/fc2`

## 2. Cloudflare 实战经验（重点）

### 2.1 不要过度误判挑战页
仅在出现典型文案时判定 challenge：
- `<title>Just a moment`
- `Enable JavaScript and cookies to continue`
- `cf-browser-verification`
- `challenge-error-text`

经验：如果判定太宽（例如只看少量关键词），会把正常页面误判，直接导致分类/搜索空列表。

### 2.2 先直接请求，再 WebView 兜底
推荐顺序：
1. `app.get()` 正常抓取
2. 若命中 challenge，再调用 `WebViewResolver`
3. 成功后读 `CookieManager` 中 `cf_clearance`
4. 带 `Cookie + User-Agent` 重试请求

### 2.3 关键日志一定要打
建议保留这些日志：
- `fetchPage resolved url=... blocked=... len=...`
- `getMainPage ... cards=...`
- `loadLinks candidates=... unpackedLen=...`
- `loadLinks foundAny=...`

经验：`len` 很大（如 17w/20w）通常说明拿到真实页面，不是 CF 挑战页。

### 2.4 403 不代表插件一定失败
本机 PowerShell `Invoke-WebRequest` 经常 403，但 App/WebView 里可正常访问。排查要以设备端日志为准，不要仅靠本机 curl/iwr 结论。

## 3. 列表与详情页经验

### 3.1 列表过滤规则
- 视频 URL 必须像番号：`slug` 同时满足：
  - 含 `-`
  - 含数字
- 显式过滤分类 slug（`chinese-subtitle` 等）
- 显式过滤标题噪音（如 `简体中文` / `中文字幕`）

经验：仅靠 `href` 在 `a[href]` 上扫会混入分类导航项，必须叠加番号规则。

### 3.2 标题回退顺序
建议：
1. `meta[property='og:title']`
2. `meta[name='twitter:title']`
3. JSON-LD `name`
4. 页面 `h1`
5. URL 番号兜底

并屏蔽无效标题：`登入你的账户` / `登录你的账户` / `sign in` / `login`。

### 3.3 演员来源（已按需求固定）
当前仅使用：
- `meta[property='og:video:actor']`

## 4. 播放链路经验
- 优先从页面与 `getAndUnpack(html)` 中提取 `surrit` 的 `playlist.m3u8`
- 识别 UUID 后兜底拼接：
  - `https://surrit.com/<uuid>/playlist.m3u8`
- 调 `M3u8Helper.generateM3u8` 时加请求头：
  - `Referer: https://missav.live/`
  - `Origin: https://missav.live`
  - `User-Agent: ...`
- 仍无结果时再用 `WebViewResolver additionalUrls=.*\.m3u8.*` 抓真实请求

## 5. 设备侧排查命令
```powershell
adb logcat -c
adb logcat -d | Select-String -Pattern "PluginManager|MissAVProvider|fetchPage|getMainPage|loadLinks|m3u8|WebViewResolver|cf_clearance|Error|Exception"
```

## 6. 常见坑位
- 不要使用 `jina.ai` 作为抓取中转。
- 不要把域名改成 `missav.com`（需求明确使用 `missav.ws` 体系链接，当前实现主站为 `missav.live`，分类含 `dm150/cn/fc2`）。
- 临时克隆目录（如 `_tmp_*`）会干扰 Gradle 项目加载；用完即删。
- Kotlin 增量缓存偶发损坏时：
  1. `gradlew --stop`
  2. 删除 `MissAVProvider/build/kotlin/compileDebugKotlin`
  3. 重新 `make`

## 7. 本次清理记录
已执行：
- `gradlew clean`
- 删除根目录缓存目录：`.kotlin`、`.gradle`、`build`

