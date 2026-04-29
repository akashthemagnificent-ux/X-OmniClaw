<div align="center">
  <img src="figure/logo1_whitebg.png" alt="X-OmniClaw" width="500">
  <p>
    <a href="https://github.com/OPPO-Mente-Lab/X-OmniClaw/releases/latest"><img src="https://img.shields.io/badge/Release-latest-blue.svg" alt="Latest release" /></a>
    <a href="https://www.android.com/"><img src="https://img.shields.io/badge/Android-8.0%2B-green.svg" alt="Android 8.0+" /></a>
    <a href="https://github.com/OPPO-Mente-Lab/X-OmniClaw/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License Apache 2.0" /></a>
    <a href="assets/main.pdf"><img src="https://img.shields.io/badge/Paper-PDF-orange.svg" alt="Paper PDF" /></a>
  </p>
</div>

**X-OmniClaw** is a **native Android agent** that combines speech, vision, and action. It works as an **executable graphical-interface engine**: no virtual environment—it runs on real devices and follows a **perceive → act → refine** loop.

**Omni** stands for holistic perception—the system cares about pixels on screen *and* physical context beyond the display; **X-** adds cross-modal fusion so fragmented signals become a coherent plan. The agent can complete task closures autonomously and reliably in real mobile ecosystems.

[简体中文 Chinese](README.md) · English (this document)

**[🧭 Overview](#overview) | [💡 Key features](#key-features) | [🎬 Demos](#use-cases-demo) | [🔧 Skills & tools](#skills-tools) | [🤖 Models](#models) | [🚀 Quick start](#quick-start) | [🛠️ Build from source](#build-from-source) | [📄 License](#license) | [🙏 Acknowledgments](#acknowledgments)**

---

## 📢 Updates

- **2026-04-22** 🧠 Execution policy tightened: plain-text replies can return locally; device actions uniformly go through the agent; stronger cross-package `ref` rebinding and error-location logging.
- **2026-04-20** 🧵 Multi-session parallelism: per-session agent loops, isolated runtime across sessions, precise stop chains—better stability on long tasks.
- **2026-03-31** ⏰ Scheduled automation: intervals, weekdays, or weekly plans; works screen-on or screen-off.
- **2026-03-25** 🎙️ Speech–vision spine: local speech–vision loop (recording, frames, decision, execution); speech and text share one execution core.
- **2026-03-14** 🛠️ Core runtime refactor: unified device tools (snapshot, actions, launch app, screenshots, etc.) aligned with key OpenClaw runtime capabilities.

---

<a id="overview"></a>
## 🧭 Overview

![X-OmniClaw local engine architecture](figure/x-omniclaw-local-engine2.png)

**X-OmniClaw** is a **full-modal mobile agent** for smartphones: no virtual environment; on-device **perceive → plan → act → verify** loop. The sections below summarize **execution methodology**, **four-layer architecture**, and **three omni-modal pillars**.

### 1. Core methodology: from “chat” to “execution”

X-OmniClaw follows **iterative recursive reasoning** so tasks can finish in complex mobile GUIs.

<table style="width:100%; border-collapse:collapse; border:1px solid #d0d7de; table-layout:fixed; margin-bottom:12px;">
<colgroup>
<col style="width:14%">
<col style="width:24%">
<col style="width:62%">
</colgroup>
<thead>
<tr style="background:#f6f8fa; border-bottom:1px solid #d0d7de;">
<th align="left" style="padding:8px 12px;">Stage</th>
<th align="left" style="padding:8px 12px;">Action</th>
<th align="left" style="padding:8px 12px;">Description</th>
</tr>
</thead>
<tbody>
<tr style="border-bottom:1px solid #eef1f4;"><td style="padding:8px 12px; vertical-align:top;">Observe</td><td style="padding:8px 12px; vertical-align:top;"><strong>Observe</strong></td><td style="padding:8px 12px;">Capture UI state via system snapshot, live screenshot, or accessibility tree.</td></tr>
<tr style="border-bottom:1px solid #eef1f4;"><td style="padding:8px 12px; vertical-align:top;">Think</td><td style="padding:8px 12px; vertical-align:top;"><strong>Think</strong></td><td style="padding:8px 12px;">LLM/VLM decides using goals, context, and action history.</td></tr>
<tr style="border-bottom:1px solid #eef1f4;"><td style="padding:8px 12px; vertical-align:top;">Act</td><td style="padding:8px 12px; vertical-align:top;"><strong>Act</strong></td><td style="padding:8px 12px;">Drive taps, swipes, text input, app switches, etc.</td></tr>
<tr style="border-bottom:1px solid #eef1f4;"><td style="padding:8px 12px; vertical-align:top;">Verify</td><td style="padding:8px 12px; vertical-align:top;"><strong>Verify</strong></td><td style="padding:8px 12px;">Re-capture UI and compare expected vs actual.</td></tr>
<tr><td style="padding:8px 12px; vertical-align:top;">Loop</td><td style="padding:8px 12px; vertical-align:top;"><strong>Loop</strong></td><td style="padding:8px 12px;">Feed new state into the next round until done or stopped.</td></tr>
</tbody>
</table>

### 2. System architecture: four-layer closed loop

Modular design closes the loop along **perceive → plan → act → verify**.

<table style="width:100%; border-collapse:collapse; border:1px solid #d0d7de; table-layout:fixed; margin-bottom:12px;">
<colgroup>
<col style="width:14%">
<col style="width:24%">
<col style="width:62%">
</colgroup>
<thead>
<tr style="background:#f6f8fa; border-bottom:1px solid #d0d7de;">
<th align="left" style="padding:8px 12px;">Layer</th>
<th align="left" style="padding:8px 12px;">Component</th>
<th align="left" style="padding:8px 12px;">Role</th>
</tr>
</thead>
<tbody>
<tr style="border-bottom:1px solid #eef1f4;"><td style="padding:8px 12px; vertical-align:top;">Perception</td><td style="padding:8px 12px; vertical-align:top;"><strong>Multi-modal Input</strong></td><td style="padding:8px 12px;">ASR, screenshot/recording frames, accessibility tree → unified context.</td></tr>
<tr style="border-bottom:1px solid #eef1f4;"><td style="padding:8px 12px; vertical-align:top;">Planning</td><td style="padding:8px 12px; vertical-align:top;"><strong>Agent Logic</strong></td><td style="padding:8px 12px;">Main agent loop: task decomposition; Kotlin bridge for dispatch.</td></tr>
<tr style="border-bottom:1px solid #eef1f4;"><td style="padding:8px 12px; vertical-align:top;">Execution</td><td style="padding:8px 12px; vertical-align:top;"><strong>Device Scheduler</strong></td><td style="padding:8px 12px;">Snapshots, simulated UI actions, app lifecycle.</td></tr>
<tr><td style="padding:8px 12px; vertical-align:top;">Verification</td><td style="padding:8px 12px; vertical-align:top;"><strong>Success Monitor</strong></td><td style="padding:8px 12px;">Post-action checks and loop detection for drift or completion.</td></tr>
</tbody>
</table>

### 3. Three omni-modal pillars

Beyond typical agents, X-OmniClaw strengthens **perception depth, memory breadth, and action robustness**.

<table style="width:100%; border-collapse:collapse; border:1px solid #d0d7de; table-layout:fixed; margin-bottom:12px;">
<colgroup>
<col style="width:14%">
<col style="width:24%">
<col style="width:62%">
</colgroup>
<thead>
<tr style="background:#f6f8fa; border-bottom:1px solid #d0d7de;">
<th align="left" style="padding:8px 12px;">Pillar</th>
<th align="left" style="padding:8px 12px;">Focus</th>
<th align="left" style="padding:8px 12px;">Implementation</th>
</tr>
</thead>
<tbody>
<tr style="border-bottom:1px solid #eef1f4;"><td style="padding:8px 12px; vertical-align:top;"><strong>Omni Perception</strong></td><td style="padding:8px 12px; vertical-align:top;">Fusion & intent parsing</td><td style="padding:8px 12px;">UI metadata plus real-world vision (camera); distill raw input into structured intent.</td></tr>
<tr style="border-bottom:1px solid #eef1f4;"><td style="padding:8px 12px; vertical-align:top;"><strong>Omni Memory</strong></td><td style="padding:8px 12px; vertical-align:top;">Cross-app continuity</td><td style="padding:8px 12px;">Working memory + long-term memory; semantic cues from private galleries.</td></tr>
<tr><td style="padding:8px 12px; vertical-align:top;"><strong>Omni Action</strong></td><td style="padding:8px 12px; vertical-align:top;">Heterogeneous interaction</td><td style="padding:8px 12px;">Vision-grounded robust UI; behavior cloning and trajectory replay for reuse.</td></tr>
</tbody>
</table>

---

<a id="key-features"></a>
## 💡 Key features

- **On-device executable agent loop**: multi-turn tasks use budgeting and loop detection; failures converge and execution continues.
- **Observable runs and cost**: stream steps, thoughts, tool calls, and results; accumulate LLM usage for UI display.
- **Unified device tools**: one surface for UI understanding, tap/type, launch app, screenshots, clipboard—with stability and mis-click guards.
- **Vision fallback & dual-track decisions**: prefer structured understanding; fall back to vision on messy pages.
- **Speech-to-action**: ASR plus screen understanding; align “the frame when push-to-talk fired” with decision input.
- **Gallery & media workflows**: searchable, summarizable, actionable flows (e.g. theme-based photo search, memory tidy, CapCut-style one-tap video).
- **Deep links & reproducible flows (when available)**: bookmarks and deep links compress long paths into one-shot commands (“record once, next time one sentence”).
- **Parallel sessions & controlled stop**: isolated sessions; interrupt per session.

---

<a id="use-cases-demo"></a>
## 🎬 Demos

### Four scenarios

<!-- Two rows × two columns; video src uses github.com/.../blob/main/... repository file URLs -->
<table width="100%">
<colgroup>
<col width="50%">
<col width="50%">
</colgroup>
<thead>
<tr style="background:#f6f8fa; border-bottom:1px solid #d0d7de;">
<th align="left" width="50%">📷 Demo 1.1 — Camera-grounded task</th>
<th align="left" width="50%">📺 Demo 1.2 — Casting / screen companion</th>
</tr>
</thead>
<tbody>
<tr style="vertical-align:top; border-bottom:1px solid #eef1f4;">
    <td style="padding:8px 12px;"><strong>User</strong><br>“How much is this bottle of water on Taobao?”</td>
    <td style="padding:8px 12px;"><strong>User</strong><br>“Let’s start the exercises.”</td>
</tr>
<tr style="vertical-align:top; border-bottom:1px solid #eef1f4;">
    <td style="padding:8px 12px;">
        <strong>Behavior</strong><br>
        • Camera + voice to infer intent<br>
        • Jump to target app search (e.g. Taobao)<br>
        • Scroll results, capture prices/volumes
    </td>
    <td style="padding:8px 12px;">
        <strong>Behavior</strong><br>
        • Follow cast/main screen<br>
        • Push-to-talk + screen understanding<br>
        • Multi-step execution with live feedback
    </td>
</tr>
<tr style="vertical-align:top; border-bottom:1px solid #eef1f4;">
    <td style="padding:8px 12px;"><strong>Object ID</strong> → <strong>e-commerce lookup</strong>.</td>
    <td style="padding:8px 12px;"><strong>Companion follow</strong> → <strong>multi-step screen QA</strong>.</td>
</tr>
<tr style="vertical-align:top;">
    <td style="padding:8px 12px;">
        <video src="https://github.com/OPPO-Mente-Lab/X-OmniClaw/blob/main/videos/demo1.1_fast.mp4" controls width="100%" autoplay muted playsinline loop preload="auto" style="display:block; max-width:100%;"></video>
    </td>
    <td style="padding:8px 12px;">
        <video src="https://github.com/OPPO-Mente-Lab/X-OmniClaw/blob/main/videos/demo1.2_fast.mp4" controls width="100%" autoplay muted playsinline loop preload="auto" style="display:block; max-width:100%;"></video>
    </td>
</tr>
</tbody>
</table>

<table width="100%">
<colgroup>
<col width="50%">
<col width="50%">
</colgroup>
<thead>
<tr style="background:#f6f8fa; border-bottom:1px solid #d0d7de;">
<th align="left" width="50%">✂️ Demo 2 — Memory-driven one-tap video</th>
<th align="left" width="50%">📦 Demo 3 — Behavior cloning</th>
</tr>
</thead>
<tbody>
<tr style="vertical-align:top; border-bottom:1px solid #eef1f4;">
    <td style="padding:8px 12px;"><strong>User</strong><br>“Find parrot-themed photos and make a one-tap video.”</td>
    <td style="padding:8px 12px;"><strong>User</strong><br>“Open Meituan flash deals.”</td>
</tr>
<tr style="vertical-align:top; border-bottom:1px solid #eef1f4;">
    <td style="padding:8px 12px;">
        <strong>Behavior</strong><br>
        • Build a searchable memory index; filter by “parrot”<br>
        • Stage picks into a temp album (e.g. <code>A_latest</code>)<br>
        • Jump to CapCut one-tap flow, batch select, export/share
    </td>
    <td style="padding:8px 12px;">
        <strong>Behavior</strong><br>
        • Record once → reusable bookmark/skill<br>
        • Later: one sentence to target page<br>
        • Fallback if launch fails
    </td>
</tr>
<tr style="vertical-align:top; border-bottom:1px solid #eef1f4;">
    <td style="padding:8px 12px;"><strong>Theme search</strong> → <strong>one-tap video</strong>.</td>
    <td style="padding:8px 12px;"><strong>Record once</strong> → <strong>one-shot navigation</strong>.</td>
</tr>
<tr style="vertical-align:top;">
    <td style="padding:8px 12px;">
        <video src="https://github.com/OPPO-Mente-Lab/X-OmniClaw/blob/main/videos/demo2_fast.mp4" controls width="100%" autoplay muted playsinline loop preload="auto" style="display:block; max-width:100%;"></video>
    </td>
    <td style="padding:8px 12px;">
        <video src="https://github.com/OPPO-Mente-Lab/X-OmniClaw/blob/main/videos/demo3_fast.mp4" controls width="100%" autoplay muted playsinline loop preload="auto" style="display:block; max-width:100%;"></video>
    </td>
</tr>
</tbody>
</table>

---

<a id="skills-tools"></a>
## 🔧 Skills & tools

Skills load from `app/src/main/assets/skills/`; execution reaches the UI via tools (including `device`).

### 🗂️ Bundled skills (10)

Path: `app/src/main/assets/skills/<skill>/SKILL.md`.

| Category | Skill IDs |
| :--- | :--- |
| **Search & apps** | `app-search`, `taobao-search` |
| **Gallery & media** | `gallery-qa`, `gallery-memory`, `capcut-theme-video`, `clipboard-to-shortcut` |
| **Configuration** | `model-config`, `channel-config` |
| **Skill management** | `skill-creator` |
| **Automation** | `scheduled-automation` |

### 🧪 Example utterances (say to the agent)

| Skill ID | Example |
| :--- | :--- |
| `app-search` | “Search Xiaohongshu for Beijing travel tips and send me the summary.” |
| `taobao-search` | “On Taobao, search men’s light sunscreen shirts; recommend 3 by price band and sales.” |
| `gallery-qa` | “What photos did I take today? Briefly in time order.” |
| `gallery-memory` | “Sync gallery memory and refresh my profile—scan the latest 20 photos.” |
| `clipboard-to-shortcut` | “Turn the clipboard URL into a skill named Taobao quick link.” |
| `channel-config` | “Configure Feishu channel: app id xxx, secret xxx.” |
| `model-config` | “Add an OpenAI-compatible provider at URL xxx; default model xxx.” |
| `scheduled-automation` | “Every Wednesday 10:00 open Xiaohongshu, search AI news, summarize.” |
| `capcut-theme-video` | “Make a one-tap video from today’s landscapes; put assets in A_latest first.” |
| `skill-creator` | “Summarize what just worked as a new skill and write SKILL.md.” |

---

<a id="models"></a>
## 🤖 Models

**Recommended:** enter API keys in the APK and save; config is written to `/sdcard/.xomniclaw/xomniclaw.json`—usually no manual JSON editing.

### In-app setup (recommended)

1. Open the app → **Model configuration** (drawer / settings; wizard may appear on first launch).
2. **Agent model**: pick provider → **API Key** (and Base URL if required) → default model → **Save**.
3. **Speech STT**: open **STT** from the same area; set transcription **API Key**, endpoint, model (e.g. SiliconFlow **SenseVoice Small**); keys may differ from Agent.
4. **Vision VLM**: **VLM settings** for screenshot/UI understanding (must support images); same or different provider as Agent.
5. After save, disk persists; gateway port etc. live in **Settings** or related screens.

UI fields map to the JSON below; edit the file directly only for bulk migration, scripting, or debugging.

### Built-in providers & example model IDs

| Provider ID | Example model ID |
| :--- | :--- |
| `bot-adaptor` | `doubao-seed-2.0-lite` |
| `openrouter` | `z-ai/glm-4.5-air:free` |
| `anthropic` | `claude-opus-4` |
| `openai` | `gpt-4.1` |
| `google` | `gemini-2.5-pro` |
| `moonshot` | `kimi-k2.5` |
| `xiaomi` | `mimo-v2-flash` |
| `minimax` | `MiniMax-M2.5` |
| `ollama` | No fixed ID (`/api/tags`) |

### Config snippet: Agent model (advanced)

Matches in-app provider / default model; example combo **bot-adaptor** + **doubao-seed-2.0-lite**.

```json
{
  "models": {
    "providers": {
      "openrouter": {
        "baseUrl": "https://openrouter.ai/api/v1",
        "apiKey": "sk-or-v1-xxx"
      }
    }
  },
  "agents": {
    "defaults": {
      "model": {
        "primary": "openrouter/z-ai/glm-4.5-air:free"
      }
    }
  }
}
```

### Config snippet: Speech STT (advanced)

Maps to **STT** screen; `models.providers.stt` is separate from Agent / VLM. Example: **SiliconFlow** + **FunAudioLLM/SenseVoiceSmall**.  
Free tiers depend on the vendor’s current policy.

```json
{
  "models": {
    "providers": {
      "stt": {
        "baseUrl": "https://api.siliconflow.cn/v1/audio/transcriptions",
        "api": "openai-completions",
        "apiKey": "<SiliconFlow API Key>",
        "models": [
          {
            "id": "FunAudioLLM/SenseVoiceSmall",
            "name": "SenseVoice Small",
            "contextWindow": 1,
            "maxTokens": 1
          }
        ]
      }
    }
  }
}
```

### Config snippet: Vision VLM (advanced)

Maps to **VLM**; `models.providers.vlm` for screenshots/UI. Example model **doubao-seed-2.0-lite**.

```json
{
  "models": {
    "providers": {
      "vlm": {
        "baseUrl": "https://api.example.com/v1/chat/completions",
        "api": "bot-adaptor",
        "apiKey": "<secret>",
        "models": [
          {
            "id": "doubao-seed-2.0-lite",
            "name": "Doubao Seed 2.0 Lite",
            "contextWindow": 32768,
            "maxTokens": 65536
          }
        ]
      }
    }
  }
}
```

---

<a id="quick-start"></a>
## 🚀 Quick start

### 📥 1) Download & install

Latest APK:  
[https://github.com/OPPO-Mente-Lab/X-OmniClaw/releases/latest](https://github.com/OPPO-Mente-Lab/X-OmniClaw/releases/latest)

### ⚙️ 2) First-time setup (prefer in-app forms)

1. **Model configuration**: set Agent **API Key** and a default model with **multimodal (image)** support; optionally configure **STT** and **VLM** separately.  
2. Grant permissions (seven, same as in-app checks): **Accessibility**, **Overlay**, **Screen capture**, **Photos**, **All files access**, **Camera**, **Microphone**.  
3. Optional: configure **Feishu / Discord** (and similar) in-app instead of editing JSON only.

### 📄 3) Config file location (auto-generated)

After saving from the UI:

```text
/sdcard/.xomniclaw/xomniclaw.json
```

Open this file directly mainly for backup, migration, or debugging.

---

<a id="build-from-source"></a>
## 🛠️ Build from source

### 📋 Requirements

- JDK 17 or newer  
- Android SDK  
- Gradle Wrapper (recommended)

### 🔨 Clone & build (example)

```bash
git clone <your GitLab repo URL>
cd X-OmniClaw
cp local.properties.example local.properties
# Edit local.properties: set sdk.dir to your Android SDK
./gradlew :app:assembleDebug
```

Upstream GitHub mirror (optional):

```bash
git clone https://github.com/OPPO-Mente-Lab/X-OmniClaw.git
cd X-OmniClaw
```

Windows PowerShell example:

```powershell
$env:JAVA_HOME = "D:\path\to\jdk-17"
$env:ANDROID_HOME = "D:\path\to\android-sdk"
Set-Location "D:\path\to\X-OmniClaw"
Copy-Item local.properties.example local.properties
notepad local.properties
.\gradlew.bat :app:assembleDebug
```

Example output path:

```text
releases/X-OmniClaw-v<version>-debug.apk
```

Observer extension:

```bash
./gradlew :extensions:observer:assembleDebug
```

---

<a id="license"></a>
## 📄 License

[Apache License 2.0](LICENSE): commercial use and modifications allowed; retain notices and describe changes.

---

<a id="acknowledgments"></a>
## 🙏 Acknowledgments

**[HermesApp](https://github.com/SelectXn00b/HermesApp)**: This project’s engineering started from and evolved atop that open-source codebase. Thanks to the HermesApp maintainers and community.

---
