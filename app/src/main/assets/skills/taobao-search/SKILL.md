---
name: taobao-search
description: |
  Taobao product search. Activate when the user wants to search for products, find a product from a certain brand, compare prices, browse new arrivals, or in other e-commerce shopping scenarios.
metadata:
  {
    "xomniclaw": {
      "always": false,
      "emoji": "🛒",
      "version": "1.0.0",
      "category": "shopping"
    }
  }
---

# Taobao Search Tool

Use Taobao Deep Links to jump straight to the search page, browse products, and give the user shopping advice.

## 🎯 When to Use

Use this skill when user asks about:

✅ **Product search** - "Help me find adidas's new men's shoes this year"
✅ **Brand lookup** - "Search for Nike running shoes"
✅ **Price comparison shopping** - "See what good Bluetooth earbuds are on Taobao"
✅ **New arrivals browsing** - "What new dresses are there this year"
✅ **Search with specific conditions** - "Backpacks under 200 yuan"

## 📋 Workflow

### Standard Search Flow

```
1. Parse the user's intent and extract search keywords
2. Jump to the Taobao search page via device(action="open", uri="taobao://...")
3. Wait for the Taobao app to load search results: device(action="act", kind="wait", timeMs=1500)
4. Fetch the page UI tree: device(action="snapshot", format="compact")
   —— this is the **only source** of text such as product name/price/store/sales
5. Extract product information item by item from the text nodes returned by snapshot (see Step 3 for details)
6. Answer the user based on the concrete product information extracted (under 200 words, must include real numbers and details)
```

> ⚠️ **Absolutely forbidden**: Do not use `read_file` to read screenshot PNGs (e.g. `read_file("/sdcard/.xomniclaw/workspace/screenshots/*.png")`). PNG is binary; read_file decodes it as UTF-8 into a mass of garbled text and blows up the context window, and the whole session is terminated with `context window exceeds limit`. **Use only `device(action="snapshot")` to read page text**.

## 🔗 Deep Link Construction Rules

Taobao search Deep Link format:

```
taobao://s.taobao.com/search?q={keyword}
```

Keywords use `+` to join multiple search terms, for example:

| User intent | Search keywords | Deep Link |
|---------|-----------|-----------|
| adidas new men's shoes this year | adidas+2026+new+men+shoes | `taobao://s.taobao.com/search?q=阿迪达斯+2026+新款+男鞋` |
| Nike running shoes | Nike+running+shoes | `taobao://s.taobao.com/search?q=耐克+跑步鞋` |
| 200 yuan Bluetooth earbuds | Bluetooth+earbuds | `taobao://s.taobao.com/search?q=蓝牙耳机` |

## 🚀 X-OmniClaw Implementation

**Tool**: `device` (DeviceTool — unified device control tool)

⚠️ **Important**: Do not use the shell `am start` command to jump to a Deep Link (it will be denied with SecurityException); you must use `device(action="open", uri="...")` to launch an `ACTION_VIEW` Intent through the X-OmniClaw app context.

### Step 1: Jump to the Taobao search page via Deep Link

```kotlin
// Build search keywords
val keyword = "adidas+2026+new+men+shoes"
val deepLink = "taobao://s.taobao.com/search?q=$keyword"

// Jump via the device tool's open action + uri parameter
device(action = "open", uri = deepLink)
```

### Step 2: Wait for the page to load and fetch the UI tree

```kotlin
// Wait for the Taobao app to start and load search results
device(action = "act", kind = "wait", timeMs = 1500)

// Fetch the page UI tree (including all visible text nodes) —— the only source of product information
device(action = "snapshot", format = "compact")
```

> Do not use `screenshot` in this step: `screenshot` is only for internal grounding (locating tap coordinates) and does not give you the image content. Only `snapshot` returns every visible text node on the page (including product name, price, store name) to you as plain text.

### Step 3: Extract product information from the snapshot text nodes (key step)

snapshot returns UI tree text, for example:
```
text "ULTRABOOST 5X 2026春季款男子跑步鞋"
text "¥899"
text "阿迪达斯官方旗舰店"
text "2000+人付款"
```

**You must extract the following fields from the text nodes item by item**:

- **Full product name** (including brand, series, model, e.g. "Nike Air Max 270 React")
- **Price** (exact to the yuan, e.g. ¥599, ¥1299)
- **Color/colorway** (e.g. black/white, gray-blue, fluorescent green) —— if no color text appears in the snapshot, this field may be omitted
- **Store name** (e.g. "Nike official flagship store", "XX Sports Specialty Store")
- **Sales/payment count** (e.g. "2000+ buyers", "500+ sold this month")
- **Tags/selling points** (e.g. "official authentic", "new arrival first release", "free shipping")

> If the snapshot text nodes are sparse (the page is still loading or blocked by a popup), **call `device(action="act", kind="wait", timeMs=1500)` again and re-snapshot**; do not `read_file` a screenshot PNG.

### Step 4: Answer the user based on the concrete product information

⛔ **Vague replies are strictly forbidden**. The following reply styles are prohibited:

```
❌ "The search results show Nike's new men's shoes from multiple sellers, including different styles and price points"
❌ "You can keep scrolling up and down on the current page to see more products"
❌ "Tap a product you're interested in to view details and price"
❌ "Includes different styles and price points, so you can choose according to your preferences"
```

This kind of reply is as good as no answer — the user can see the screen themselves. Your value is in **summarizing the concrete product information on the screen for the user**.

✅ **Must include concrete numbers and details**, reply format:

```
I searched Taobao for "{search term}" and picked a few worth a look:

1. {full product name incl. model} - ¥{price}, {colour}, {shop name}, {sales}
2. {full product name incl. model} - ¥{price}, {colour}, {shop name}, {sales}
3. {full product name incl. model} - ¥{price}, {colour}, {shop name}, {sales}

Price range ¥{lowest}-¥{highest}. {1-2 sentences of concrete buying advice, e.g. which is the better value, which shop is the official flagship}. Want me to open one of them for details?
```

## 💡 Complete Example

**User**: "Help me find adidas's new men's shoes this year"

**Execution flow**:

```kotlin
// 1. Parse intent → keywords: adidas+2026+new+men+shoes
val keyword = "adidas+2026+new+men+shoes"

// 2. Jump to Taobao via Deep Link (using the device tool's uri parameter)
device(action = "open", uri = "taobao://s.taobao.com/search?q=$keyword")

// 3. Wait for loading and fetch the UI tree
device(action = "act", kind = "wait", timeMs = 1500)
device(action = "snapshot", format = "compact")  // ← the only source of information

// 4. Extract from the snapshot text nodes: product name (with model), price, color, store name, sales
// 5. Answer the user based on the concrete information extracted (under 200 words)
```

**Reply example** (note that every item is concrete information actually read from the snapshot text nodes):

```
I searched Taobao for "adidas 2026 new men's shoes" and picked a few worth a look:

1. Adidas ULTRABOOST 5X 2026 spring men's running shoes - ¥899, black/white colorway, Adidas official flagship store, 2000+ buyers
2. Adidas Originals GAZELLE Indoor sneakers - ¥799, navy/white, adidas Originals official flagship store, 1500+ buyers
3. Adidas SAMBA OG classic reissue - ¥699, white/black heel, Dewu adidas authorized store, 3000+ buyers

Price range ¥699-¥899. All three are this year's new colorways; SAMBA has the highest sales and good value, while ULTRABOOST is the pick for running performance. The first two are official flagship stores and more reliable. Want me to open one of them for details?
```

## 🔍 Keyword Extraction Strategy

When extracting search keywords from a user query:

```kotlin
// brand + time + category combination
val brand = "adidas"     // brand name
val year = "2026"          // year ("this year" → current year)
val category = "new+men+shoes"  // product category

val keyword = "$brand+$year+$category"
```

**Time word mapping**:

| User phrasing | Convert to |
|---------|-------|
| this year | 2026 |
| new arrivals | 2026+new |
| latest | 2026+new |
| last year | 2025 |

## ⚠️ Notes

- **Must use the device tool**: jump via `device(action="open", uri="...")`, do not use shell `am start` (it will throw SecurityException)
- **The Taobao app must be installed**: Deep Link requires the Taobao app; if it is not installed, it returns ActivityNotFoundException
- **Login state**: some search results require a logged-in Taobao account to display fully
- **Network dependency**: a network connection is needed to load search results
- **Load time**: when the network is slow, increase the `wait` timeMs appropriately; when snapshot text is sparse, wait another 1-2 seconds and re-snapshot
- **Reply length**: keep the reply under 200 words, but it must contain the concrete product information read from the snapshot
- **No boilerplate**: never reply with vague filler like "includes different styles and price points" or "you can keep browsing". If there is not enough product text in the snapshot, wait and re-snapshot; if it still fails, say so truthfully
- **Never read_file a screenshot PNG**: `read_file("/sdcard/.../screenshots/*.png")` returns binary garbage and blows up the context, terminating the whole session with `context window exceeds limit`; for any "read the screenshot content" need, always use `device(action="snapshot")` instead

## 🔄 Error Handling

### Taobao Not Installed

```kotlin
// device(action="open", uri="...") returns an ActivityNotFoundException error
// Fallback: open with a browser
device(action = "open", uri = "https://s.taobao.com/search?q=$keyword")
```

### No Search Results

```kotlin
// If search results are empty, try simplifying the keywords
val simplifiedKeyword = "adidas+men+shoes"  // remove qualifiers such as the year
device(action = "open", uri = "taobao://s.taobao.com/search?q=$simplifiedKeyword")
```

### Page Load Timeout

```kotlin
// Increase the wait time and fetch the UI tree again
device(action = "act", kind = "wait", timeMs = 2000)
device(action = "snapshot", format = "compact")
// If the snapshot still has no product text nodes, tell the user to check network/login state
```

## 📱 Supported Queries

| Query type | Example | Search keywords |
|---------|------|-----------|
| Brand + category | "Find Nike running shoes" | Nike+running+shoes |
| Brand + year + category | "adidas new this year" | adidas+2026+new |
| Category + price | "Backpacks under 300" | backpack |
| Generic category | "Nice dresses" | dress+new |
| Specific product | "iPhone 16 phone case" | iPhone+16+phone+case |
