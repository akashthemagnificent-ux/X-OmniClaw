---
name: capcut-theme-video
description: CapCut (剪映) one-tap video creation on Android. Always clear A_latest first (copy_images_to_album clear-only), then retrieve by time (list_gallery_images) or content (image-memories). Copy to A_latest (clear_album_first), CapCut 照片视频 (Photos/Videos) → A_latest → 全部 (All), multi_tap, N=K.
metadata: { "xomniclaw": { "always": false, "emoji": "🎬" } }
---

# CapCut (剪映) One-Tap Video and Album Photo Selection

This Skill guides you through obtaining the target photos, storing them uniformly in the `A_latest` album, and completing "一键成片 (one-tap edit)" in CapCut.

## When to Use

Load this Skill when the user wants a multi-step UI operation such as: **open CapCut**, **一键成片 (one-tap edit)**, **select multiple photos from the album (by time or by theme)**, **go to the finished video / export the video**.

Package name: `com.lemon.lv`

## Album Name `A_latest` (hard rule)

- **This flow uses exactly one album name: `A_latest`** (consistent with `Pictures/A_latest/` and the CapCut "照片视频 (Photos/Videos)" dropdown).  
- **Forbidden variants**: `A_latest_scenery`, `theme_A_latest`, `latest`, with spaces, etc.; theme/time is expressed only in retrieval — **do not** write it into the album name.  
- **The album must be cleared before adding anything**: on the final write, `copy_images_to_album` must use **`clear_album_first: true`**.  
- **Before any retrieval, `A_latest` must also be cleared first** (Path A and Path B are **the same**): see "Step 0" below. This prevents old copies from mixing into time-based `list_gallery_images` results, and prevents material left over from the previous round from being confused with this round's copies when selecting by content.

---

## Core Strategy: How to Obtain Photos and the Target Count K

Before entering CapCut, you must first determine which photos to select and the **target count K**. Whether by time or by theme, **all selected photos must first be copied into the `A_latest` album**, and then selected uniformly from the `A_latest` album in CapCut.

### Step 0: Clear `A_latest` first (required for both Path A and Path B, before any retrieval)

Before **any** `list_gallery_images` (Path A) or `memory_search` / `memory_get` (Path B), first call **`copy_images_to_album`**:

- `album_name: "A_latest"`
- `clear_album_first: true`
- **Do not pass** `content_uris` / `media_ids` → the tool **only clears** that album (0 records deleted if the album is empty).

**Reason in brief**:

- **Path A**: if old copies in `A_latest` remain in MediaStore, retrievals such as `date_preset` that are **not excluded by album** will mix them with the real target photos.  
- **Path B**: clearing first guarantees that when `A_latest` is later written, the album state is clean and not confused with leftovers from the previous finished video.

**Optional probe** (equivalent to clearing directly first): first `list_gallery_images`, `album_filter: "A_latest"`, `limit: 1`; only when **`count` ≥ 1** execute the clear-only call above.

### Step 1: Obtain the target photos' content_uris

After completing Step 0, choose one of two options according to the user's needs:

#### Path A: Based on time or album name (non-theme semantics)
**Use case**: the user explicitly asks for "photos taken today", "yesterday's photos", "the last 24 hours", or specifies a concrete system album name.
**Steps**:
1. Call `list_gallery_images` using `date_preset` (`today` | `yesterday` | `last_24h`) or `album_filter` (raise **`limit`** if necessary, max 100).
2. Extract all `content_uri` values from the returned image list.

#### Path B: Based on a specific theme, event, or image content
**Use case**: the user asks to find images by content, such as "scenery", "seaside", "cat", "trip to Beijing".
**Steps**:
1. **Extract theme words**: pull retrieval terms out of the user's words.
2. **Search image-memories** (when finding images by content, do not use `list_gallery_images`'s `name_filter` as a theme search: filenames are mostly `IMG…` and contain no semantics like "scenery" or "cat", so you easily get 0 results):
   - Call `memory_search` with the theme word in `query`; you may raise `maxResults` (recommended ≥ 10) and lower `minScore` slightly (recommended ≤ 0.4) to recall more.
   - Iterate the returned `citations` list and **call `memory_get` for each item**, extracting `content_uri` or `media_id`; **do not stop after reading just one**, to avoid missing images.

### Step 2: Write into the fixed album `A_latest` (hard rule)

Whichever path you take — A or B — once you have `content_uris` or `media_ids`:
1. Call `copy_images_to_album`: `album_name: "A_latest"`, pass the collected `content_uris` or `media_ids`, **`clear_album_first: true`** (clear again then copy; connects with Step 0 to avoid omissions or leftovers under concurrency).
2. After copying, call `list_gallery_images` with `album_filter: "A_latest"` and note the returned `count`; this is the **target count K**.
3. In CapCut, you must **first tap the "照片视频 (Photos/Videos)" dropdown and select the `A_latest` album**, then select photos.

---

## CapCut Operation Steps

### 1. Deep Link directly to the one-tap edit page (preferred)
When there is no need to tap 一键成片 (one-tap edit) again on the CapCut home page, use `device` `action=open` with `uri` + `package_name`:
```text
device(action="open", package_name="com.lemon.lv", uri="videocut://template/smart_recommend?enter_from=intelligent_edit&tab_name=edit")
```
Then `device(action="snapshot")` to confirm whether you have entered the media selection screen. If a system permission popup such as "是否允许打开剪映 (Allow opening CapCut?)" appears (e.g. `com.oplus.securitypermission`), tap allow first, then snapshot. If the Deep Link fails, tap `text '一键成片'` (one-tap edit) by ref on the home page.

### 2. Media selection page: album and category switching

> **snapshot vs screenshot hard rule (violation means failure)**
> Throughout, use only `device(action="snapshot")` to get the accessibility tree with refs and tap via `tap ref`. **Do not** use `screenshot` to guess coordinates.

1. **First tap the album dropdown (hard rule)**: find the `link '照片视频'` (Photos/Videos) row in the tree, and `tap` that row's `ref` (with `snapshot_id`). **Do not** substitute a nearby unlabeled `button` in the same area.
2. **Select the `A_latest` album**: expand the list, `snapshot` again, and find `text 'A_latest'` in the list (name exactly A_latest, no suffix).
   - **Correct**: `tap` the ref of the `button` that is the "previous node" immediately before `text 'A_latest'`.
   - **Wrong**: accidentally tapping the `button` after it (that is the next album row).  
3. **Switch to the "全部 (All)" tab (important)**:
   - After selecting `A_latest`, CapCut may default to the "照片 (Photos)" or "视频 (Videos)" tab.
   - You **must** find **`text '全部'`** (or `button '全部'`) in the top tab bar and tap its **ref**.
   - **Purpose**: ensure you can see all media under the `A_latest` album (whether images or videos).
   - `snapshot` to confirm `全部` is selected (`selected`).   

### 3. Select media (tap the "circle", not the "big image") (batch taps)
1. **Selection notes**
   In the album grid, each cell often has a pair of adjacent nodes (a large `button` first, then a small `link` circle):
   - **Tap only the selection circle**: usually a `link` (no text when unselected; often carries a number like `link '1' (selected)` when selected).
   - **Do not tap the large unlabeled `button`**: that is the whole thumbnail area; tapping it enters the full-size preview.
2. **Use `multi_tap` to select everything**:
   - In the current tab grid, list only the refs of each cell's **selection circle** (usually `link`), and **do not mix in** the same cell's large unlabeled thumbnail `button`.
   - **`multi_tap` accepts at most 15 refs per call** (matching the number of visible cells on one screen); if there are more: **multi_tap one batch → `snapshot` → scroll → multi_tap the next batch**. Duplicate refs are deduplicated (order preserved).
   - Every `act` (including `multi_tap`) must carry the **`snapshot_id`** from the **most recent** `snapshot` (refs expire in strict mode).
3. **Verify**:
   - **Check the count**: after every tap or `multi_tap` you **must** `snapshot` and check whether N in the bottom **`下一步 (N)`** (Next) has reached the target count **K**.
   - **Pitfall**: the UI often shows "选择3个以上素材，成片效果更佳 (Select 3 or more media for a better result)"; this is only a recommendation and **never** means selecting 3 photos is enough to tap Next. You must select until **N = K**.


### 4. Wait and Next
- Once N in `下一步 (N)` (Next) equals K, tap "Next".
- For the subsequent generation/export stage, you must use `act` + `wait` to wait:
  ```text
  device(action="act", kind="wait", timeMs=10000)
  ```
  **Wrong**: `device(action="wait")`.
- If export is very slow, use several short waits + snapshots; do not assume one 10-minute wait has taken effect.

---

## Brief Checklist

1. **Step 0 (same for both paths)**: `copy_images_to_album` (`A_latest` + `clear_album_first: true`, no URIs) to clear only.
2. **Retrieve**: Path A → `list_gallery_images`; Path B → `memory_search` / `memory_get` → get the `content_uri` list.
3. **Store uniformly**: `copy_images_to_album` (`A_latest` + **`clear_album_first: true`** + this round's URIs) → `list_gallery_images` `album_filter: "A_latest"` to get **K**.
4. **Enter CapCut**: prefer the Deep Link; if it fails, tap by ref on the home page.
5. **Select the album set**: choose `A_latest` from the dropdown.
6. **Switch to the All tab**: **you must tap the "全部 (All)" tab** to ensure the media is visible.
7. **Select photos**: tap only the selection circles (`link`); use `multi_tap` (≤15 per call) together with `snapshot`.
8. **Verify and wait**: `下一步 (N)` (Next) until N = K; `act` + `wait` then `snapshot`.

