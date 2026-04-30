<div align="center">
  <img src="figure/logo1_whitebg.png" alt="X-OmniClaw" width="500">
  <p>
    <a href="https://github.com/OPPO-Mente-Lab/X-OmniClaw/releases/latest"><img src="https://img.shields.io/badge/Release-latest-blue.svg" alt="Latest release" /></a>
    <a href="https://www.android.com/"><img src="https://img.shields.io/badge/Android-8.0%2B-green.svg" alt="Android 8.0+" /></a>
    <a href="https://github.com/OPPO-Mente-Lab/X-OmniClaw/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License Apache 2.0" /></a>
    <a href="assets/X_OmniClaw_Technical_Report__1_.pdf"><img src="https://img.shields.io/badge/Paper-PDF-orange.svg" alt="Paper PDF" /></a>
  </p>
</div>

**X-OmniClaw** is an edge-native **Multimodal Android Agent** that integrates multimodal perception, memory, and action. It operates independently of any virtual environments, functioning directly on physical Android devices. By capturing real-time visual telemetry and executing native touch interactions, it performs cross-app operations through on-device tools.

**Omni** refers to the integration of three sensing domains: on-screen UI state, real-world visual context, and audio input. **X-** emphasizes cross-modal fusion, turning fragmented signals into structured intent representations that can drive reliable perception-to-action execution in real mobile ecosystems.

**English** · [简体中文 Chinese](README_zh.md)

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

To ensure task completion in complex mobile GUI environments, X-OmniClaw organizes every interface interaction as a minimal **Observation → Reasoning → Execution** loop during device manipulation. It first perceives the current screen interface and the outcome of the previous action, then infers the next optimal operation, and finally invokes atomic Android actions for actual execution. This loop repeats continuously until the task is accomplished or terminated.
### 1. Core methodology: from “chat” to “execution”

X-OmniClaw follows **iterative recursive reasoning** so tasks can finish in complex mobile GUIs.

<table style="width:100%; border-collapse:collapse; border:1px solid #d0d7de; table-layout:fixed; margin-bottom:12px;">
<colgroup>
<col style="width:18%">
<col style="width:82%">
</colgroup>
<thead>
<tr style="background:#f6f8fa; border-bottom:1px solid #d0d7de;">
<th align="left" style="padding:8px 12px;">Stage</th>
<th align="left" style="padding:8px 12px;">Description</th>
</tr>
</thead>
<tbody>
<tr style="border-bottom:1px solid #eef1f4;"><td style="padding:8px 12px; vertical-align:top;"><strong>observation</strong></td><td style="padding:8px 12px;">Build a unified observation stack from screenshots, XML metadata, screen projection. The stack perceives the execution outcome of the previous step and provides decision evidence for subsequent action planning.</td></tr>
<tr style="border-bottom:1px solid #eef1f4;"><td style="padding:8px 12px; vertical-align:top;"><strong>reasoning</strong></td><td style="padding:8px 12px;">LLM/VLM interprets the current page, checks the previous action state, retrieves relevant memory, selects skills/tools, and decides whether to answer directly or continue execution.</td></tr>
<tr><td style="padding:8px 12px; vertical-align:top;"><strong>execution</strong></td><td style="padding:8px 12px;">Dispatch concrete operations through Android atomic actions, including taps, swipes, text input, and app switches.</td></tr>
</tbody>
</table>

### 2. System architecture: four-layer closed loop

At the system-level lifecycle, X-OmniClaw forms a full closed loop through **perceive → plan → act → verify**. The perception layer aggregates multimodal inputs, the planning layer forms task plans, the execution layer dispatches device actions, and the verification layer checks results and decides whether to continue.

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
<tr style="border-bottom:1px solid #eef1f4;"><td style="padding:8px 12px; vertical-align:top;">Planning</td><td style="padding:8px 12px; vertical-align:top;"><strong>Agent Loop</strong></td><td style="padding:8px 12px;">Main agent loop: task decomposition; Kotlin bridge for dispatch.</td></tr>
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
<tr style="border-bottom:1px solid #eef1f4;"><td style="padding:8px 12px; vertical-align:top;"><strong>Omni Perception</strong></td><td style="padding:8px 12px; vertical-align:top;">Unified multimodal ingress and intent understanding</td><td style="padding:8px 12px;">Integrates UI states, real-world visual contexts, speech inputs, scheduled triggers, floating widgets, and external channels; uses temporal alignment and scene-grounded VLM understanding to convert raw streams into structured intent.</td></tr>
<tr style="border-bottom:1px solid #eef1f4;"><td style="padding:8px 12px; vertical-align:top;"><strong>Omni Memory</strong></td><td style="padding:8px 12px; vertical-align:top;">Multimodal Personalized Memory</td><td style="padding:8px 12px;">Combines working memory for task continuity with long-term personal memory distilled from local multimodal data, enabling personalized multi-turn interactions and memory-grounded automation.</td></tr>
<tr><td style="padding:8px 12px; vertical-align:top;"><strong>Omni Action</strong></td><td style="padding:8px 12px; vertical-align:top;">Robust mobile execution and reusable skills</td><td style="padding:8px 12px;">Runs an observation-reasoning-execution loop over hybrid UI evidence;  convert user navigation into reusable deeplink/intent-based skills.</td></tr>
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

### Three demo tracks / four demos

<!-- Two rows × two columns; demos are GIFs under videos/ (relative paths) -->
<table width="100%">
<colgroup>
<col width="50%">
<col width="50%">
</colgroup>
<thead>
<tr style="background:#f6f8fa; border-bottom:1px solid #d0d7de;">
<th align="left" width="50%">📷 Demo A1 — Camera-informed execution</th>
<th align="left" width="50%">📺 Demo A2 — ScreenAvatar execution / screen companion</th>
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
        <img src="videos/demo1.1_fast.gif" alt="Demo A1 — camera-informed execution recording" width="100%" style="display:block; max-width:100%;">
    </td>
    <td style="padding:8px 12px;">
        <img src="videos/demo1.2_fast.gif" alt="Demo A2 — ScreenAvatar execution recording" width="100%" style="display:block; max-width:100%;">
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
<th align="left" width="50%">✂️ Demo B — Memory-based one-tap video</th>
<th align="left" width="50%">📦 Demo C — Instant portal to a Meituan flash-sale page / Behavior cloning</th>
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
        <img src="videos/demo2_fast.gif" alt="Demo B — memory-based one-tap video recording" width="100%" style="display:block; max-width:100%;">
    </td>
    <td style="padding:8px 12px;">
        <img src="videos/demo3_fast.gif" alt="Demo C — instant portal to a Meituan flash-sale page recording / behavior cloning recording" width="100%" style="display:block; max-width:100%;">
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
| `capcut-theme-video` | “Make a one-tap video from today’s landscapes.” |
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
| `openrouter` | `z-ai/glm-4.5-air:free` |
| `anthropic` | `claude-opus-4` |
| `openai` | `gpt-4.1` |
| `moonshot` | `kimi-k2.5` |
| `minimax` | `MiniMax-M2.5` |
| `ollama` | No fixed ID (`/api/tags`) |

### Config snippet: Agent model

Matches in-app provider / default model.

```json
{
  "models": {
    "providers": {
      "openrouter": {
        "baseUrl": "https://openrouter.ai/api/v1",
        "api": "openai-completions",
        "apiKey": "<OPENROUTER_API_KEY>",
        "models": [
          {
            "id": "z-ai/glm-4.5-air:free",
            "name": "GLM 4.5 Air (free)",
            "contextWindow": 131072,
            "maxTokens": 8192
          }
        ]
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

### Config snippet: Speech STT

Maps to **STT** screen. Example: **SiliconFlow** + **FunAudioLLM/SenseVoiceSmall**.  
SiliconFlow signup: [https://cloud.siliconflow.cn](https://cloud.siliconflow.cn).  
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

### Config snippet: Vision VLM

Maps to **VLM**. Example using **OpenRouter** with an OpenAI-compatible vision model:

```json
{
  "models": {
    "providers": {
      "vlm": {
        "baseUrl": "https://openrouter.ai/api/v1",
        "api": "openai-completions",
        "apiKey": "sk-or-v1-xxx",
        "models": [
          {
            "id": "qwen/qwen3.6-flash",
            "name": "Qwen 3.6 Flash (via OpenRouter)",
            "contextWindow": 200000,
            "maxTokens": 16384
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
3. Optional: configure **Feishu / Discord** (and similar) in-app.

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

---

<a id="license"></a>
## 📄 License

[Apache License 2.0](LICENSE): commercial use and modifications allowed; retain notices and describe changes.

---

<a id="acknowledgments"></a>
## 🙏 Acknowledgments

**[HermesApp](https://github.com/SelectXn00b/HermesApp)**: This project’s engineering started from and evolved atop that open-source codebase. Thanks to the HermesApp maintainers and community.

---

## Star History

<a href="https://www.star-history.com/?type=date&repos=OPPO-Mente-Lab%2FX-OmniClaw">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=OPPO-Mente-Lab/X-OmniClaw&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=OPPO-Mente-Lab/X-OmniClaw&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=OPPO-Mente-Lab/X-OmniClaw&type=date&legend=top-left" />
 </picture>
</a>
