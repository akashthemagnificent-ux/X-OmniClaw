---
name: app-search
description: |
  Mainstream APP search. Activate when the user wants to search in apps such as 拼多多, 美团, 高德地图, 抖音, 快手, 小红书, 哔哩哔哩, 知乎, 百度, OPPO音乐, OPPO软件商店, etc. Covers e-commerce shopping, local services, short-video content, knowledge search, music search, app-store search and other scenarios.
metadata:
  {
    "xomniclaw": {
      "always": false,
      "emoji": "🔍",
      "version": "1.0.0",
      "category": "search"
    }
  }
---

# App Search Tool

Use each mainstream APP's Deep Link to go straight to its search page, browse the results, and give the user a useful summary.

## 🎯 When to Use

Use this skill when user asks about:

### E-commerce shopping
✅ **拼多多 Search** - "search Pinduoduo for tissues", "what cheap headphones are on Pinduoduo"

### Local services
✅ **美团 Search** - "search Meituan for nearby hotpot restaurants", "find me hotels on Meituan"
✅ **高德地图** - "search Amap for gas stations", "find nearby parking lots"

### Short video / content
✅ **抖音 Search** - "search Douyin for fitness tutorials", "look at travel guides on Douyin"
✅ **快手 Search** - "search Kuaishou for recipes", "find fishing videos on Kuaishou"
✅ **小红书 Search** - "search Xiaohongshu for outfit ideas", "find skincare guides on Xiaohongshu"
✅ **哔哩哔哩 Search** - "search Bilibili for programming tutorials", "look at gadget reviews on Bilibili"

### Knowledge / search
✅ **知乎 Search** - "search Zhihu for how to learn Python", "find me personal-finance advice on Zhihu"
✅ **百度 Search** - "search Baidu for the weather forecast", "look up this question on Baidu"

### Music
✅ **OPPO音乐 Search** - "search OPPO Music for Jay Chou", "help me search OPPO Music for Sunny Day"

### App store
✅ **OPPO软件商店 Search** - "search OPPO App Store for Hongguo Short Dramas", "find Douyin in the app store"

## 🛑 Tool Choice (must read, easiest place to trip up)

To jump to an APP's search page you **can only and must** call `device(action="open", uri="...")`:

| Operation | ✅ Must use | ❌ Absolutely forbidden |
|---|---|---|
| Jump to the target APP's search page | `device(action="open", uri="<deep_link>")` | `start_activity(uri=...)` / `start_activity(package=..., activity=...)` / `start_activity(component=...)` |

> **Why `start_activity` is forbidden**: the activities of third-party APPs (小红书/抖音/拼多多/B站/快手/知乎, etc.) almost never allow explicit launching from another process. No matter which component or package+activity combination you try — `MainActivity` / `IndexSearchV2Activity` / `.search.SearchActivity`, etc. — **the return value is 100% `Unable to find explicit activity class`**, and you will waste 10+ iterations without succeeding.
>
> **The correct path**: the deep links in the routing table (`xhsdiscover://`, `snssdk1128://`, `pinduoduo://`, etc.) are automatically routed by the system to the corresponding APP via an `ACTION_VIEW` Intent, which is exactly what `device(action="open", uri=...)` does internally; **you do not need to and should not specify an activity name**.
>
> **think → action consistency**: if you wrote "use `device(action="open")`" in `<think>`, you must actually call `device` in the function call — do not switch to `start_activity`. This is the most common failure mode of weak models. When you read this paragraph, check yourself again: is the tool you are about to call in this turn named `device`?

## 📋 Workflow

### Standard search flow

> 🚫 **Prohibition**: when calling `device(action="open", ...)`, **pass only the two parameters `action` and `uri`**; **passing `package_name` is strictly forbidden**. The system resolves the target APP automatically from the URI scheme; specifying `package_name` manually causes ActivityNotFound.
>
> ⚡ **Only exception — 百度APP**: the `baiduboxapp://` scheme has been tested and cannot trigger search; you must use an HTTPS link and specify `package_name`.
>
> ✅ `device(action="open", uri="imeituan://www.meituan.com/search?q=火锅")`
> ❌ `device(action="open", package_name="com.meituan.android.fe", uri="imeituan://www.meituan.com/search?q=火锅")`
> ✅ `device(action="open", uri="https://m.baidu.com/s?word=天气预报", package_name="com.baidu.searchbox")` ← 百度 exception
> ❌ `device(action="open", uri="baiduboxapp://search?keyword=天气预报")` ← does not work in testing

```
1. Parse the user's intent, identify the target APP and the search keyword
2. Build the corresponding Deep Link from the APP routing table (you must copy the template verbatim from the routing table and only replace {keyword})
3. Jump to the target APP's search page via device(action="open", uri="...") (passing package_name is forbidden)
4. Wait for the APP to load search results: device(action="act", kind="wait", timeMs=1000)
5. Fetch the page UI tree: device(action="snapshot")
6. Extract the concrete information from the results in the text nodes returned by snapshot
7. Answer the user based on the extracted concrete information (within 200 characters, must include real numbers and details)
```

## 🗺️ APP Routing Table

### E-commerce

| APP | Deep Link Template | Scheme |
|------|----------------|--------|
| 拼多多 | `pinduoduo://com.xunmeng.pinduoduo/search_result.html?search_key={keyword}` | `pinduoduo` |

### Local services

| APP | Deep Link Template | Scheme |
|------|----------------|--------|
| 美团 (global search) | `imeituan://www.meituan.com/search?q={keyword}` | `imeituan` |
| 美团 (hotel search) | `imeituan://www.meituan.com/hotel/search?q={keyword}` | `imeituan` |
| 高德地图 | `androidamap://poi?keywords={keyword}` | `androidamap` |

> ⚠️ **Special note for 美团**: the scheme is **`imeituan`** (with the `i` prefix), not ~~`meituan`~~. The path is `/search?q=`, not ~~`/web/?keyword=`~~. The wrong form `meituan://www.meituan.com/web/?keyword=xxx` causes ActivityNotFound.

### Short video / content

| APP | Deep Link Template | Scheme |
|------|----------------|--------|
| 抖音 | `snssdk1128://search?keyword={keyword}` | `snssdk1128` |
| 快手 | `kwai://search?keyword={keyword}` | `kwai` |
| 小红书 | `xhsdiscover://search/result?keyword={keyword}` | `xhsdiscover` |
| 哔哩哔哩 | `bilibili://search?keyword={keyword}` | `bilibili` |

> ⚠️ **Special note for 小红书**: the scheme is **`xhsdiscover`**, not ~~`xhslink`~~, ~~`xhs`~~, ~~`xiaohongshu`~~. The path is **`/search/result`** (not ~~`/search`~~), and the parameter name is **`keyword`**. Correct form `xhsdiscover://search/result?keyword=xxx`. Common mistake: writing the scheme as ~~`xhslink://search?keyword=xxx`~~ causes ActivityNotFound.

### Knowledge / search

| APP | Deep Link Template | Scheme |
|------|----------------|--------|
| 知乎 | `zhihu://search?q={keyword}` | `zhihu` |
| 百度APP | `https://m.baidu.com/s?word={keyword}` (must specify package name `com.baidu.searchbox`) | `https` |

> ⚠️ **Special note for 百度APP**: the `baiduboxapp://search?keyword=xxx` Deep Link **does not work in testing** (it stays on the home screen and does not trigger search). You must use the HTTPS link `https://m.baidu.com/s?word=xxx` and **explicitly specify the package name** `com.baidu.searchbox`, i.e. `device(action="open", uri="https://m.baidu.com/s?word=xxx", package_name="com.baidu.searchbox")`. 百度 is the only exception that requires `package_name`.

### Music

| APP | Deep Link Template | Scheme | Package name |
|------|----------------|--------|------|
| OPPO音乐 | `allmusic://search/keyword/{keyword}` | `allmusic` | `com.heytap.music` |

> ⚠️ **Special note for OPPO音乐**: the path format is `/search/keyword/{keyword}`, with the keyword concatenated directly into the path. Correct form: `allmusic://search/keyword/周杰伦晴天`.

### App stores

| APP | Deep Link Template | Scheme | Package name |
|------|----------------|--------|------|
| OPPO软件商店 | `market://search?q={keyword}` | `market` | `com.heytap.market` (Android 10+) / `com.oppo.market` (Android 9 and below) |

> ⚠️ **Special note for OPPO软件商店**: **passing `package_name` is strictly forbidden**; the system automatically routes to the installed app store based on the `market://` scheme. Correct form: `market://search?q=红果短剧`.

## 🔗 Deep Link Construction Rules

Join multiple search terms in the keyword with `+`.

### Common APP examples

| APP | User intent | Search keyword | Deep Link |
|-----|---------|-----------|-----------|
| 拼多多 | search Pinduoduo for tissues | tissues | `pinduoduo://com.xunmeng.pinduoduo/search_result.html?search_key=纸巾` |
| 美团 | search Meituan for hotpot | hotpot | `imeituan://www.meituan.com/search?q=火锅` |
| 高德 | search Amap for gas stations | gas stations | `androidamap://poi?keywords=加油站` |
| 抖音 | search Douyin for fitness tutorials | fitness tutorials | `snssdk1128://search?keyword=健身教程` |
| 快手 | search Kuaishou for recipes | recipes | `kwai://search?keyword=美食做法` |
| 小红书 | search Xiaohongshu for outfit ideas | outfit ideas | `xhsdiscover://search/result?keyword=穿搭` |
| B站 | search Bilibili for programming tutorials | programming tutorials | `bilibili://search?keyword=编程教程` |
| 知乎 | search Zhihu for personal-finance advice | personal-finance advice | `zhihu://search?q=理财建议` |
| 百度 | search Baidu for the weather forecast | weather forecast | `https://m.baidu.com/s?word=天气预报` (must specify `package_name="com.baidu.searchbox"`) |
| OPPO音乐 | search OPPO Music for Jay Chou's Sunny Day | Jay Chou Sunny Day | `allmusic://search/keyword/周杰伦晴天` |
| OPPO软件商店 | search OPPO App Store for Hongguo Short Dramas | Hongguo Short Dramas | `market://search?q=红果短剧` |

## 🚀 X-OmniClaw Implementation

**Tool**: `device` (DeviceTool — unified device control tool)

⚠️ **Important**: do not use the shell `am start` command to jump to a Deep Link (it will fail with a SecurityException permission denial). You must use `device(action="open", uri="...")` to launch an `ACTION_VIEW` Intent from the X-OmniClaw app context.

### Step 1: Identify the target APP and construct the Deep Link

```kotlin
// Identify the APP and keyword from the user's intent
val app = "douyin"  // identify the target APP from the user query
val keyword = "fitness+tutorial"

// APP → Deep Link mapping
val deepLink = when (app) {
    // E-commerce
    "pdd", "拼多多" ->
        "pinduoduo://com.xunmeng.pinduoduo/search_result.html?search_key=$keyword"

    // Local services
    "meituan", "美团" ->
        "imeituan://www.meituan.com/search?q=$keyword"
    "meituan_hotel", "美团酒店" ->
        "imeituan://www.meituan.com/hotel/search?q=$keyword"
    "amap", "高德", "高德地图" ->
        "androidamap://poi?keywords=$keyword"

    // Short video/content
    "douyin", "抖音" ->
        "snssdk1128://search?keyword=$keyword"
    "kuaishou", "快手" ->
        "kwai://search?keyword=$keyword"
    "xiaohongshu", "小红书" ->
        "xhsdiscover://search/result?keyword=$keyword"
    "bilibili", "B站", "哔哩哔哩" ->
        "bilibili://search?keyword=$keyword"

    // Knowledge/search
    "zhihu", "知乎" ->
        "zhihu://search?q=$keyword"
    // ⚠️ 百度 is the exception: the baiduboxapp:// scheme does not work in testing; use HTTPS + explicit package name
    "baidu", "百度" ->
        "https://m.baidu.com/s?word=$keyword"  // also pass package_name="com.baidu.searchbox"

    // Music
    "oppomusic", "OPPO音乐", "oppo音乐" ->
        "allmusic://search/keyword/$keyword"

    // App stores
    "oppostore", "OPPO软件商店", "oppo软件商店", "软件商店" ->
        "market://search?q=$keyword"

    else -> throw IllegalArgumentException("Unsupported APP: $app")
}

// 百度 needs an explicit package name; all other APPs must NOT pass package_name
if (app in listOf("baidu", "百度")) {
    device(action = "open", uri = deepLink, package_name = "com.baidu.searchbox")
} else {
    device(action = "open", uri = deepLink)
}
```

### Step 2: Wait for the page to load and confirm

```kotlin
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
```

### Step 3: Analyze the snapshot content (key step)

**You must carefully read every text node returned by each snapshot**, and extract the relevant information according to APP type:

#### E-commerce (拼多多)
- **Full product name** (including brand, series, model)
- **Price** (to the yuan, e.g. ¥599, ¥1299)
- **Store name** (e.g. "XX flagship store")
- **Sales/review count** (e.g. "100k+ reviews", "50k+ units sold")
- **Tags** (e.g. "billion-yuan subsidy", "free shipping")

#### Local services (美团, 高德地图)
- **Store/place name**
- **Rating** (e.g. 4.8 points)
- **Price per person** (e.g. ¥89/person)
- **Distance** (e.g. 1.2 km)
- **Address**
- **Business status** (open/closed)
- **Tags** (e.g. "Must-Eat list", "Black Pearl")

#### Short video/content (抖音, 快手, 小红书, 哔哩哔哩)
- **Video/note title**
- **Author/creator name**
- **Views/likes** (e.g. "1M views", "52k likes")
- **Publish time**
- **Content summary/tags**

#### Knowledge/search (知乎, 百度)
- **Question/article title**
- **Answerer/author**
- **Upvotes/reads**
- **Excerpt content**

#### Music (OPPO音乐)
- **Song name**
- **Singer/artist**
- **Album name**
- **Release year**
- **Song duration**
- **Plays/popularity** (if displayed)

#### App store (OPPO软件商店)
- **App name**
- **App icon description**
- **Developer/company**
- **Rating** (e.g. 4.8 points)
- **Downloads** (e.g. "100M+ downloads")
- **App size**
- **App description/tags**
- **Whether it is official/verified**

### Step 4: Answer the user based on concrete information

⛔ **Never give a vague reply**. The following reply styles are forbidden:

```
❌ "The search results show several related items, take a look"
❌ "You can keep scrolling up and down on this page for more"
❌ "Tap the content you're interested in to see details"
❌ "There are different types and price points, choose according to your preference"
```

✅ **Must include concrete numbers and details**; adjust the reply format by APP type:

**E-commerce reply template**:

```
For you, I searched "{search term}" in {APP name} and picked a few worth a look:

1. {full product name} - ¥{price}, {store name}, {sales/reviews}
2. {full product name} - ¥{price}, {store name}, {sales/reviews}
3. {full product name} - ¥{price}, {store name}, {sales/reviews}

Price range ¥{lowest}-¥{highest}. {1-2 sentences of concrete shopping advice}. Want me to open one for details?
```

**Local services reply template**:

```
For you, I searched "{search term}" in {APP name}, a few recommendations:

1. {store name} - {rating} points, ¥{price} per person, {distance} away, {tag}
2. {store name} - {rating} points, ¥{price} per person, {distance} away, {tag}
3. {store name} - {rating} points, ¥{price} per person, {distance} away, {tag}

{1-2 sentences of recommendation reason}. Want me to check a store's details or navigate you there?
```

**Content reply template**:

```
For you, I searched "{search term}" in {APP name}, a few recommendations:

1. "{title}" - @{author}, {views/likes}, {brief description}
2. "{title}" - @{author}, {views/likes}, {brief description}
3. "{title}" - @{author}, {views/likes}, {brief description}

{1-2 sentences of recommendation reason}. Want me to open one and take a look?
```

**Music reply template**:

```
For you, I searched "{search term}" in {APP name}, found these results:

1. "{song name}" - {artist}, album "{album name}", {release year}
2. "{song name}" - {artist}, album "{album name}", {release year}
3. "{song name}" - {artist}, album "{album name}", {release year}

{1-2 sentences introducing the song or why it's recommended}. Which one should I play?
```

**App store reply template**:

```
For you, I searched "{search term}" in {APP name}, found these apps:

1. "{app name}" - {developer}, {rating} points, {downloads}, {app size}
2. "{app name}" - {developer}, {rating} points, {downloads}, {app size}
3. "{app name}" - {developer}, {rating} points, {downloads}, {app size}

{1-2 sentences introducing the app or why it's recommended}. Which one should I download for you?
```

## 💡 Complete Examples

### Example 1: 拼多多 search

**User**: "search Pinduoduo for tissues"

```kotlin
val keyword = "tissues"
device(action = "open", uri = "pinduoduo://com.xunmeng.pinduoduo/search_result.html?search_key=$keyword")
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
```

**Reply example**:

```
Searched 拼多多 for "tissues" and picked a few worth a look:

1. ZhiHu wood-pulp facial tissues, 40-pack case - ¥16.9, ZhiHu flagship store, 1M+ units sold, billion-yuan subsidy
2. C&S facial tissues Face series, 3-ply 120 sheets, 30 packs - ¥39.9, C&S official flagship store, 500k+ units sold
3. Vinda Ultra Strong facial tissues, 24 packs - ¥29.9, Vinda official flagship store, 800k+ units sold, free shipping

Price range ¥16.9-¥39.9. ZhiHu is the best value for stocking up, C&S Face series feels better, Vinda balances quality and price. Want me to open one for details?
```

### Example 2: 抖音 search

**User**: "search Douyin for fitness tutorials"

```kotlin
val keyword = "fitness tutorials"
device(action = "open", uri = "snssdk1128://search?keyword=$keyword")
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
```

**Reply example**:

```
Searched 抖音 for "fitness tutorials", here are a few popular videos:

1. "30-minute full-body fat-burn follow-along for beginners" - @PamelaReif, 3.2M views, suitable for zero basics
2. "5 must-do moves for men to build muscle" - @LiuGengHong, 1.8M views, dumbbell home training
3. "15-minute abs daily plan" - @ZoeyZhou, 2.5M views, 28-day check-in challenge

These are all top fitness creators with professional content. Pamela is good for follow-along fat burn, Liu Geng Hong focuses on strength, Zoey Zhou suits women's toning. Want me to open a video?
```

### Example 3: 美团 search

**User**: "search Meituan for nearby hotpot restaurants"

```kotlin
val keyword = "hotpot"
device(action = "open", uri = "imeituan://www.meituan.com/search?q=$keyword")
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
```

**Reply example**:

```
Searched 美团 for "hotpot", a few recommendations:

1. Haidilao Hotpot (MixC store) - 4.8 points, ¥128 per person, 1.2 km away, on the Must-Eat list
2. Banu Tripe Hotpot (CBD store) - 4.7 points, ¥145 per person, 0.8 km away, signature tripe
3. Nan Hotpot (Taikoo Li store) - 4.6 points, ¥95 per person, 2.1 km away, long-queue favorite with great value

Haidilao has good service and suits group meals, Banu is famous for authentic tripe, Nan Hotpot has the best value. Want me to check a store's details or group-buy coupons?
```

### Example 4: 小红书 search

**User**: "search Xiaohongshu for sunscreen recommendations"

```kotlin
val keyword = "sunscreen+recommendations"
device(action = "open", uri = "xhsdiscover://search/result?keyword=$keyword")
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
```

**Reply example**:

```
Searched 小红书 for "sunscreen recommendations", selected a few highly liked guides:

1. "2026 sunscreen red/black list! Tested 30 products and finally found the one" - @IngredientNerdAli, 52k likes, in-depth review post
2. "Sunscreen for oily skin! No pilling, no breakouts" - @DrLinDermatology, 38k likes, doctor recommended
3. "The ceiling of commute sunscreen, affordable alternative to Anessa" - @PearBlossom, 21k likes, ¥59 budget pick tested

Highly liked posts generally recommend Shiseido Blue Bottle and Winona clear sunscreen; oily skin prefers La Roche-Posay. Want me to open a post for the specific product list?
```

### Example 5: OPPO音乐 search

**User**: "search OPPO Music for Jay Chou's Sunny Day"

```kotlin
val keyword = "Jay Chou Sunny Day"
device(action = "open", uri = "allmusic://search/keyword/$keyword")
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
```

**Reply example**:

```
Searched OPPO音乐 for "Jay Chou Sunny Day", found these results:

1. "Sunny Day" - Jay Chou, album "Ye Huimei", released 2003, classic Mandopop
2. "Sunny Day (Live)" - Jay Chou, 2019 concert recording, great live atmosphere
3. "Sunny Day (Piano)" - instrumental cover, good for studying/relaxing

The original "Sunny Day" is one of Jay Chou's most classic love songs, lyrics by Vincent Fang. Which one should I play?
```

### Example 6: OPPO软件商店 search

**User**: "search OPPO App Store for Hongguo Short Dramas"

```kotlin
val keyword = "Hongguo Short Dramas"
device(action = "open", uri = "market://search?q=$keyword")
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
```

**Reply example**:

```
Searched OPPO软件商店 for "Hongguo Short Dramas", found these apps:

1. "Hongguo Short Dramas" - Beijing Hongguo Vision Technology Co., Ltd., 4.8 points, 100M+ downloads, 68MB, officially verified
2. "Hongguo Free Short Dramas" - Hongguo Tech, 4.5 points, 50M+ downloads, 55MB, massive free short dramas
3. "Hongguo Theater" - Hongguo Media, 4.3 points, 10M+ downloads, 42MB, curated popular short dramas

The first is the official authentic app, with the highest downloads and best rating. Want me to download it for you?
```

## 🔍 APP Identification Strategy

Identify the target APP from the user query:

| User keyword | Identified as |
|-----------|--------|
| 拼多多、PDD、拼多 | 拼多多 |
| 美团、美团外卖 | 美团 |
| 美团酒店 | 美团 (hotel search) |
| 高德、高德地图、导航搜 | 高德地图 |
| 抖音、TikTok | 抖音 |
| 快手 | 快手 |
| 小红书、红书 | 小红书 |
| B站、哔哩哔哩、bilibili | 哔哩哔哩 |
| 知乎 | 知乎 |
| 百度、百度一下 | 百度APP |
| OPPO音乐、oppo音乐 | OPPO音乐 |
| OPPO软件商店、oppo软件商店、软件商店、应用商店 | OPPO软件商店 |

## 🔍 Keyword Extraction Strategy

When extracting search keywords from the user query:

```kotlin
// brand + year + category combination (e-commerce/travel scenarios)
val brand = "Logitech"
val year = "2026"
val category = "mechanical keyboard"
val keyword = "$brand+$year+$category"

// location + category combination (local services/map scenarios)
val location = "Sanya"
val type = "hotel"
val keyword = "$location+$type"

// direct keyword (content/knowledge scenarios)
val keyword = "fitness tutorials"
```

**Time-word mapping**:

| User phrasing | Converted to |
|---------|-------|
| this year | 2026 |
| new model/latest | 2026+new |
| last year | 2025 |

**美团 Scenario Routing**:

| User intent | Route |
|---------|------|
| search food/entertainment/general | 美团 global search |
| explicitly mentions hotel/accommodation | 美团 hotel search |


## ⚠️ Notes

- **Must use the device tool**: jump via `device(action="open", uri="...")`, not the shell `am start` (it throws SecurityException). **Passing the `package_name` parameter is strictly forbidden** — the system resolves the target APP automatically from the URI scheme, and specifying package_name manually causes ActivityNotFound. **The only exception is 百度APP**: because its `baiduboxapp://` scheme does not work in testing, you must use an HTTPS link + `package_name="com.baidu.searchbox"`
- **Copy the URI strictly from the routing table**: do not spell a Deep Link from memory; you must copy the template verbatim from the routing table above and only replace the `{keyword}` part. Common mistakes: writing the 美团 scheme as `meituan://` (correct is `imeituan://`), or the path as `/web/?keyword=` (correct is `/search?q=`)
- **The APP must be installed**: a Deep Link needs the corresponding APP; if it is not installed, you get ActivityNotFoundException
- **Login state**: some search results require a logged-in account to display fully
- **Network dependency**: an internet connection is required to load search results
- **Load time**: when the network is slow, increase the `wait` timeMs appropriately
- **Reply length**: keep it within 200 characters, but it must contain the concrete information read from the snapshot
- **No boilerplate**: never reply with vague filler such as "contains different types" or "you can keep browsing". If the snapshot does not contain enough text, say so honestly, wait again, and take a new snapshot

## 🔄 Error Handling

### APP not installed

```kotlin
// device(action="open", uri="...") returns an ActivityNotFoundException error
// Tell the user the corresponding APP needs to be installed
// Some APPs can fall back to their web version:
// Note: the main 百度 link is already HTTPS, so this fallback does not apply
val webFallback = mapOf(
    "拼多多" to "https://mobile.yangkeduo.com/search_result.html?search_key=$keyword",
    "知乎" to "https://www.zhihu.com/search?type=content&q=$keyword",
    "哔哩哔哩" to "https://search.bilibili.com/all?keyword=$keyword",
    "百度" to "https://www.baidu.com/s?wd=$keyword"
)
device(action = "open", uri = webFallback[app] ?: "https://www.baidu.com/s?wd=$keyword")
```

### Search returns no results

```kotlin
// Try simplifying the keyword: drop the year and qualifiers
val simplifiedKeyword = "mechanical keyboard"  // remove brand and other qualifiers
// Search again with the simplified keyword
```

### Page load timeout

```kotlin
device(action = "act", kind = "wait", timeMs = 1000)
device(action = "snapshot")
// If it still has not loaded, prompt the user to check the network
```

### 美团 Scheme error

```kotlin
// ❌ Wrong example (the scheme is missing the i prefix, and the path is wrong too)
device(action = "open", uri = "meituan://www.meituan.com/web/?keyword=$keyword")

// ✅ Correct form
device(action = "open", uri = "imeituan://www.meituan.com/search?q=$keyword")
```

### 百度APP baiduboxapp:// Scheme unavailable

```kotlin
// ❌ the baiduboxapp:// scheme does not trigger search in testing (stays on the home screen)
device(action = "open", uri = "baiduboxapp://search?keyword=$keyword")

// ❌ the utils path does not work either
device(action = "open", uri = "baiduboxapp://utils/search?keyword=$keyword")

// ✅ Correct form: use an HTTPS link + explicitly specify the package name
device(action = "open", uri = "https://m.baidu.com/s?word=$keyword", package_name = "com.baidu.searchbox")
```


## 📱 Supported Queries

| APP | Query type | Example | Search keyword |
|-----|---------|------|-----------|
| 拼多多 | Low-price search | "search Pinduoduo for cheap headphones" | headphones |
| 美团 | Food search | "search Meituan for hotpot" | hotpot |
| 美团 | Hotel search | "search Meituan for hotels" | hotels |
| 高德 | POI search | "search Amap for gas stations" | gas stations |
| 抖音 | Video search | "search Douyin for fitness" | fitness |
| 快手 | Video search | "search Kuaishou for recipes" | recipes |
| 小红书 | Note search | "search Xiaohongshu for outfit ideas" | outfit ideas |
| B站 | Video search | "search Bilibili for programming tutorials" | programming tutorials |
| 知乎 | Q&A search | "search Zhihu for how to learn Python" | how to learn Python |
| 百度 | General search | "search Baidu for the weather forecast" | weather forecast |
| OPPO音乐 | Music search | "search OPPO Music for Jay Chou's Sunny Day" | Jay Chou Sunny Day |
| OPPO软件商店 | App search | "search the app store for Hongguo Short Dramas" | Hongguo Short Dramas |
