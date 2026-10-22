---
name: clipboard-to-shortcut
description: |
  Turn an App jump link from the clipboard into a reusable shortcut Skill. Next time the user says the Skill name, they can jump straight to the corresponding App page with one tap.
  Activate when the user says intents such as "turn the clipboard link into a skill", "help me save this link as a shortcut", "create a skill XX from the clipboard content", "make this link into a skill", "help me turn the copied link into a one-tap jump".
  Supports share links from mainstream Apps such as 淘宝, 拼多多, 抖音, 快手, 知乎, 小红书, 美团, 哔哩哔哩, 高德, QQ音乐, 支付宝, and 百度.
metadata:
  {
    "xomniclaw": {
      "always": false,
      "emoji": "🔖",
      "version": "1.0.0",
      "category": "navigation"
    }
  }
---

# Clipboard to Shortcut Skill

Read an App jump link from the phone clipboard and automatically generate a standalone shortcut SKILL.md. From then on the user only needs to say the Skill name to jump straight to the corresponding App page with one tap.

## 🚫 Absolutely Forbidden (Violation Will Crash)

> **Do not use exec / shell to run any of the following commands; they will 100% crash with a SecurityException:**
>
> ```
> # ❌ All forbidden — no exceptions
> am start -a android.intent.action.VIEW -d "https://..."    # ← most common mistake!
> am start -n com.taobao.taobao/...
> content query --uri content://clipboard/clipboard
> service call clipboard 2
> adb shell ...
> input tap / input swipe / input text
> ```
>
> **Known crash log (if you see an error like this, you used the wrong tool):**
> ```
> java.lang.SecurityException: Permission Denial: package=com.android.shell does not belong to uid=...
> ```
>
> ---
>
> **✅ The only correct way to read the clipboard:**
> ```kotlin
> device(action = "clipboard")
> ```
>
> **✅ The only correct way to open an App (must include package_name to avoid opening the browser):**
> ```kotlin
> device(action = "open", uri = "https://...", package_name = "com.taobao.taobao")
> ```
>
> **⛔ Never use exec to call `am start` as a substitute or fallback. If it fails, tell the user instead of switching to shell.**

## 🎯 When to Use

✅ "Turn the clipboard link into a skill called 淘宝 Sneakers"
✅ "Help me create a skill from the clipboard content called 抖音 Funny Videos"
✅ "Save the copied link as a shortcut jump skill"
✅ "Make a skill so I can open this page with one tap next time"
✅ "Help me turn this link into a one-tap skill XX"

## 📋 Workflow

### 0) Pre-check

Check Device status:
- `Accessibility: ✅ connected` → continue.
- `Accessibility: ❌ not connected` → stop and reply to the user:

```
The device is not currently connected to the accessibility service (Accessibility not connected), so the clipboard cannot be read.
Please reconnect the device's accessibility service in X-OmniClaw and try again.
```

### 1) Get the Skill name

Extract the Skill name from the user's query. Users usually say "make it into a skill XX" or "create a skill called XX".

- If the user does not provide a name, automatically generate a short Chinese name based on the link's App and content, for example `淘宝 Sneakers`, `抖音 Funny Videos`.
- Naming rule: use the Chinese name directly, no more than 20 characters. The folder name must match the `name` field (both Chinese). Do not convert to pinyin or English.

### 2) Read the clipboard and extract the URL

```kotlin
val clipboardText = device(action = "clipboard")

val urlRegex = Regex("""https?://[^\s\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]+""")
var url = urlRegex.find(clipboardText)?.value
    ?.trimEnd('，', '。', '、', '"', '"', ')', '）', ']', '】', '>', '》', '￥', ' ')
    ?: throw Exception("No valid link found in the clipboard")
```

### 3) Identify the App type and get package_name

Identify the App based on URL characteristics and determine `package_name`. **When opening, you must pass `package_name`, otherwise the link will be intercepted by the browser.**

| App | URL characteristics | package_name |
|-----|---------|-------------|
| 淘宝 | `e.tb.cn`, `tb.cn`, `taobao.com` | `com.taobao.taobao` |
| 拼多多 | `yangkeduo.com`, `pinduoduo.com`, `pddurl.cn` | `com.xunmeng.pinduoduo` |
| 抖音 | `v.douyin.com`, `douyin.com` | `com.ss.android.ugc.aweme` |
| 快手 | `v.kuaishou.com`, `kuaishou.com` | `com.smile.gifmaker` |
| 知乎 | `zhihu.com` | `com.zhihu.android` |
| 小红书 | `xhslink.com`, `xiaohongshu.com` | `com.xingin.xhs` |
| 美团 | `dpurl.cn`, `meituan.com`, `dianping.com` | `com.sankuai.meituan` |
| 哔哩哔哩 | `b23.tv`, `bilibili.com` | `tv.danmaku.bili` |
| 高德 | `surl.amap.com`, `amap.com` | `com.autonavi.minimap` |
| QQ音乐 | `y.qq.com` | `com.tencent.qqmusic` |
| 支付宝 | `render.alipay.com` (including `alipays://`) | `com.eg.android.AlipayGphone` |
| 百度 | `mbd.baidu.com` | `com.baidu.searchbox` |

### 4) Special link handling (支付宝)

```kotlin
if (url.contains("render.alipay.com") && url.contains("scheme=")) {
    val schemeParam = Regex("""scheme=([^&]+)""").find(url)?.groupValues?.get(1)
    if (schemeParam != null) {
        url = java.net.URLDecoder.decode(schemeParam, "UTF-8")
    }
}
```

### 5) Verify the link works

First try opening it once to confirm the link is valid. **You must include the `package_name` parameter to force opening in the native App and avoid the browser:**

```kotlin
device(action = "open", uri = url, package_name = "{matching App's package_name}")
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
```

Use snapshot to confirm the target App page opened successfully (not the browser). If the link is invalid or the App is not installed, tell the user the Skill cannot be created and explain why.

### 6) Generate the shortcut SKILL.md

After confirming the link is valid, generate SKILL.md in the `/sdcard/.xomniclaw/workspace/skills/{skill-name}/` directory. Use the Chinese name directly for `{skill-name}` (e.g. `淘宝 Sneakers`); the folder name must match the `name` field in SKILL.md.

#### ⚠️ Mandatory YAML Frontmatter Format (Must Be Strictly Followed)

The generated SKILL.md **must** begin with YAML frontmatter, formatted as follows:

1. **The frontmatter must be on the very first line of the file**, starting with `---` and ending with `---`
2. **Do not put the frontmatter in the middle or at the bottom of the file** (e.g. under a "## Metadata" section); SkillParser only parses frontmatter at the top of the file
3. The frontmatter contains only three fields: `name`, `description`, `metadata`
4. `name`: use the Chinese name (e.g. `淘宝 Sneakers`), **do not convert to English or pinyin** (e.g. ~~cheap-braised-duck-chaoyang~~)
5. `description`: use a YAML block scalar (`|`) containing the feature description and trigger words
6. `metadata`: **must use JSON format** (enclosed in braces), **do not use YAML nested format**

**✅ Correct metadata format (JSON):**
```yaml
metadata:
  {
    "xomniclaw": {
      "always": false,
      "emoji": "🛒",
      "version": "1.0.0",
      "category": "navigation"
    }
  }
```

**❌ Incorrect metadata format (YAML nested, SkillParser cannot parse):**
```yaml
metadata:
  xomniclaw:
    always: false
    emoji: 🛒
    version: 1.0.0
    category: navigation
```

#### Complete generated SKILL.md template

**Generate strictly according to the following template; do not change the frontmatter position or format:**

```markdown
---
name: {skill-name (Chinese name)}
description: |
  A shortcut to jump straight to the specified page in {App name}. Automatically created from a clipboard link.
  Activate when the user says "open {skill-name}", "use {skill-name}", "with {skill-name}".
metadata:
  {
    "xomniclaw": {
      "always": false,
      "emoji": "{emoji for the matching App}",
      "version": "1.0.0",
      "category": "navigation"
    }
  }
---

# {skill-name (Chinese name)}

Jump straight to the specified page in {App name}.

## 🚫 Absolutely Forbidden (Violation Will Crash)

> **Do not use exec / shell (including `am start`); it will 100% throw a SecurityException.**
>
> **✅ The only correct way to open an App (must include package_name):**
> ```kotlin
> device(action = "open", uri = "...", package_name = "{package_name}")
> ```

## 📋 Execution Steps

### Step 1: Open the target page

```kotlin
device(action = "open", uri = "{url}", package_name = "{package_name}")
```

### Step 2: Wait and confirm

```kotlin
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
```

After the snapshot, report briefly to the user based on the page content:

```
Jumped to {App name}; currently showing {page content description}.
```

## ⚠️ Notes

- Use `device(action="open", uri="...", package_name="...")` to open; do not use shell `am start`
- **The package_name parameter is required**; without it the browser opens instead of the native App. See the package name table in step 3
- If the App is not installed, take a snapshot and tell the user
- If the link has expired, tell the user the link is invalid
- Original link source: {summary of clipboardText}
- Created at: {current date}
```

#### Pre-generation self-check list

Before generating SKILL.md, check each item:

- [ ] The first line of the file is `---` (frontmatter start marker)
- [ ] The `name` field uses a Chinese name
- [ ] `description` uses the `|` block scalar format
- [ ] `metadata` uses JSON format (braces), not YAML nested format
- [ ] After the frontmatter ends with `---`, the Markdown body (`# Title`) follows immediately
- [ ] There is no second frontmatter block wrapped in `---` in the file

### 7) App Emoji mapping

When generating SKILL.md, choose the emoji based on the identified App type. The `device(action="open")` in the generated SKILL.md must also include `package_name`:

| App | Emoji | package_name |
|-----|-------|-------------|
| 淘宝 | 🛒 | `com.taobao.taobao` |
| 拼多多 | 🍊 | `com.xunmeng.pinduoduo` |
| 抖音 | 🎵 | `com.ss.android.ugc.aweme` |
| 快手 | 🎬 | `com.smile.gifmaker` |
| 知乎 | 💡 | `com.zhihu.android` |
| 小红书 | 📕 | `com.xingin.xhs` |
| 美团 | 🍜 | `com.sankuai.meituan` |
| 哔哩哔哩 | 📺 | `tv.danmaku.bili` |
| 高德 | 🗺️ | `com.autonavi.minimap` |
| QQ音乐 | 🎶 | `com.tencent.qqmusic` |
| 支付宝 | 💰 | `com.eg.android.AlipayGphone` |
| 百度 | 🔍 | `com.baidu.searchbox` |
| Unknown | 🔗 | (do not pass package_name) |

### 8) Reply to the user

After creation is complete, confirm to the user:

```
✅ Successfully created the shortcut Skill "{skill-name}"!

📋 Link source: {App name} - {content summary}
🔗 Target address: {url}

Next time just say "open {skill-name}" or "use {skill-name}" to jump straight to that page.
```

## 💡 Complete Examples

### Example 1: Turn a 淘宝 product link into a Skill

**User query**: "Turn the clipboard link into a skill called 淘宝 Sneakers"

**Clipboard content**:
```
【淘宝】https://e.tb.cn/h.io9Fd5S?tk=fNSL55NU59K CZ009 Nike SB Dunk Low low-top sneakers
```

**Execution flow**:
```kotlin
// 1. Read the clipboard
val clip = device(action = "clipboard")
// → "【淘宝】https://e.tb.cn/h.io9Fd5S?tk=fNSL55NU59K CZ009 ..."

// 2. Extract the URL
val url = "https://e.tb.cn/h.io9Fd5S?tk=fNSL55NU59K"

// 3. Identify as a 淘宝 link

// 4. Verify the link - include package_name to force opening in the 淘宝 App
device(action = "open", uri = url, package_name = "com.taobao.taobao")
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
// → snapshot confirms: 淘宝 product page Nike SB Dunk Low (opened in the 淘宝 App, not the browser)

// 5. Generate SKILL.md → /sdcard/.xomniclaw/workspace/skills/淘宝 Sneakers/SKILL.md
```

**Generated SKILL.md (note: frontmatter is at the very top of the file, metadata uses JSON format, name uses Chinese)**:
```markdown
---
name: 淘宝 Sneakers
description: |
  A shortcut to jump straight to the 淘宝 product page (Nike SB Dunk Low sneakers).
  Activate when the user says "open 淘宝 Sneakers" or "use 淘宝 Sneakers".
metadata:
  {
    "xomniclaw": {
      "always": false,
      "emoji": "🛒",
      "version": "1.0.0",
      "category": "navigation"
    }
  }
---

# 淘宝 Sneakers

Jump straight to the 淘宝 Nike SB Dunk Low sneakers product page.

## 🚫 Absolutely Forbidden (Violation Will Crash)

> **Do not use exec / shell (including `am start`); it will 100% throw a SecurityException.**
>
> **✅ The only correct way to open an App (must include package_name):**
> ```kotlin
> device(action = "open", uri = "...", package_name = "com.taobao.taobao")
> ```

## 📋 Execution Steps

### Step 1: Open the target page

```kotlin
device(action = "open", uri = "https://e.tb.cn/h.io9Fd5S?tk=fNSL55NU59K", package_name = "com.taobao.taobao")
```

### Step 2: Wait and confirm

```kotlin
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
```

After the snapshot, reply: `Jumped to 淘宝; currently showing the Nike SB Dunk Low sneakers product detail page.`

## ⚠️ Notes

- Use `device(action="open", uri="...", package_name="com.taobao.taobao")` to open; without package_name the browser opens
- Original link source: 淘宝 share - Nike SB Dunk Low low-top sneakers
- Created at: 2026-04-13
```

**Reply**:
```
✅ Successfully created the shortcut Skill "淘宝 Sneakers"!

📋 Link source: 淘宝 - Nike SB Dunk Low low-top sneakers
🔗 Target address: https://e.tb.cn/h.io9Fd5S?tk=fNSL55NU59K

Next time just say "open 淘宝 Sneakers" or "use 淘宝 Sneakers" to jump straight to that product page.
```

---

### Example 2: Turn a 抖音 video link into a Skill

**User query**: "Help me create a skill from the clipboard link called 抖音 Funny Crosstalk"

**Clipboard content**:
```
3.33 Copy and open 抖音, check out [Xixi History's work] 1993 recording...
https://v.douyin.com/Sw2BOwYSsp4/ X@M.WZ
```

**Execution flow**:
```kotlin
// 1. Read the clipboard
val clip = device(action = "clipboard")

// 2. Extract the URL
val url = "https://v.douyin.com/Sw2BOwYSsp4/"

// 3. Identify as a 抖音 link

// 4. Verify the link - include package_name to force opening in the 抖音 App
device(action = "open", uri = url, package_name = "com.ss.android.ugc.aweme")
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")

// 5. Generate SKILL.md → /sdcard/.xomniclaw/workspace/skills/抖音 Funny Crosstalk/SKILL.md
```

**Core jump command in the generated SKILL.md**:
```kotlin
device(action = "open", uri = "https://v.douyin.com/Sw2BOwYSsp4/", package_name = "com.ss.android.ugc.aweme")
```

**Reply**:
```
✅ Successfully created the shortcut Skill "抖音 Funny Crosstalk"!

📋 Link source: 抖音 - Xixi History 1993 historical recording
🔗 Target address: https://v.douyin.com/Sw2BOwYSsp4/

Next time just say "open 抖音 Funny Crosstalk" or "use 抖音 Funny Crosstalk" to jump straight to that video.
```

---

### Example 3: Turn an 支付宝 link into a Skill (with special decoding)

**User query**: "Turn the clipboard link into a skill called 支付宝 Campaign"

**Clipboard content**:
```
https://render.alipay.com/p/s/i/?scheme=alipays%3A%2F%2Fplatformapi%2Fstartapp%3FappId%3D20000793%26url%3D...
```

**Execution flow**:
```kotlin
// 1. Read the clipboard
val clip = device(action = "clipboard")

// 2. Extract the URL
var url = "https://render.alipay.com/p/s/i/?scheme=alipays%3A%2F%2F..."

// 3. Special handling for 支付宝: decode the scheme parameter
url = "alipays://platformapi/startapp?appId=20000793&url=..."

// 4. Verify the link - 支付宝 uses the alipays:// scheme to jump directly, automatically matching the 支付宝 App
device(action = "open", uri = url, package_name = "com.eg.android.AlipayGphone")
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")

// 5. Generate SKILL.md; the jump uri uses the decoded alipays:// deep link
```

**Core jump command in the generated SKILL.md**:
```kotlin
device(action = "open", uri = "alipays://platformapi/startapp?appId=20000793&url=...", package_name = "com.eg.android.AlipayGphone")
```

## 🔄 Error Handling

### Clipboard is empty or has no URL

```
The clipboard is empty, or no valid link was found. Please copy the link shared by the App and try again.
```

### Target App is not installed

```
Tried to verify the link, but {App name} is not installed on the phone. Please install the App before creating the shortcut.
```

### Link has expired

```
This link has expired or is invalid, so the shortcut cannot be created. Please copy a valid link and try again.
```

### Skill name conflict

If a Skill with the same name already exists in the target directory, prompt the user:

```
A Skill named "{skill-name}" already exists. Overwrite it? Or please provide a new name.
```

### User did not provide a Skill name

Automatically generate a name based on the App type and a summary of the clipboard content, and tell the user:

```
No Skill name was specified, so it was automatically named "{auto-name}". You can ask to rename it at any time.
```
