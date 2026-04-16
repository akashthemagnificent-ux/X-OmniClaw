<div align="center">
  <img src="figure\logo1_whitebg.png" alt="omniclaw" width="500">
  <h1>omniclaw</h1>
  <p>
    <!-- badges 待补充 -->
  </p>
</div>

<!-- 项目简介待补充 -->

## News

## Key Features of omniclaw

## Demo

<h3 id="demo-1-camera-informed-execution"><strong>【DEMO1.1：Camera-informed execution】</strong></h3>

<p>Agent planning and actions conditioned on camera observations.</p>

<p>基于相机提问，自动规划和执行。</p>

<p><img src="videos/VID_camera.gif" alt="DEMO1.1 Camera-informed execution" width="240" /></p>

<p>通过语音输入“帮我在淘宝上搜一下这个多少钱”，x-ominiclaw 即可自动解析意图并跳转至淘宝完成搜索。</p>

<p>By simply saying, "Check the price of this on Taobao," x-ominiclaw automatically interprets the intent and executes the search directly within the Taobao app.</p>



<h3 id="demo-1-2-screen-avatar-execution"><strong>【DEMO1.2：ScreenAvatar execution】</strong></h3>

<p>Agent planning and actions conditioned on screen projections.</p>

<p>基于屏幕投影，自动规划和执行。</p>

<p><img src="videos/VID_screenavater.gif" alt="DEMO1.2 ScreenAvatar execution" width="240" /></p>

<p>通过语音输入“帮我做一下这几道题”，x-ominiclaw 即可自动理解需求并全自动执行，无需任何人工干预。</p>

<p>基于屏幕投影理解时，为减少对用户操作的干扰，客户端会以<strong>悬浮窗</strong>形式跟随当前界面；仅在用户<strong>点击麦克风开始收音</strong>后才会唤醒语音理解与后续自动化流程。</p>

<p>With a simple voice command like "Help me solve these problems," x-ominiclaw automatically interprets the task and executes the solution end-to-end without any manual intervention.</p>

<p>When operating from screen projection, the client uses a <strong>floating window</strong> that follows the active UI to minimize interference; voice understanding and downstream automation <strong>wake only after the user taps the microphone</strong> to start capture.</p>


<h3 id="demo-2-memory-based-one-click-video"><strong>【DEMO2：Memory-based one-click video】</strong></h3>

<p>基于记忆的素材组织与编排，一键生成成片（memory-based one-click video）。</p>

<p><img src="videos/VID_memory2video.gif" alt="DEMO2 Memory-based one-click video" width="240" /></p>

<p>将设备相册中的长程多模态记忆与专业剪辑软件（剪映 / CapCut）的自动化技能相连接：系统避开难以审计的聊天记录或向量库，转而让智能体在可检查、可 diff 的 Markdown 记忆文件上操作，通过工具链将语义检索桥接到确定性的媒体句柄，最终在第三方应用中实现精准的屏幕交互。</p>

<p>Link long-term multimodal memories from device galleries with professional editing automation (CapCut): the system bypasses unauditable chat histories or vector databases, enabling agents to operate on inspectable, diff-friendly Markdown artifacts while bridging semantic retrieval to deterministic media handles for precise screen interactions in third-party apps.</p>


<h3 id="demo-4-trace-capture-claw-recall"><strong>【DEMO4：TraceCapture (轨迹捕捉) + ClawRecall (经验执行)】</strong></h3>

<p>启动轨迹捕捉 → 用户执行 → 沉淀经验 → 复现执行</p>

<p>Start TraceCapture → User Demonstration → Experience Distillation → Automated Execution</p>

<p><img src="videos/VID_memory2video.gif" alt="DEMO4 TraceCapture and ClawRecall" width="240" /></p>

<p>Instead of complex manual setups, simply "show" the agent how to perform a task once. The system captures your interaction trace and transforms it into a reusable skill, allowing you to trigger deep-link app functions or multi-step workflows with just a single sentence. It turns your daily habits into automated shortcuts, bridging the gap between saying it and getting it done.</p>

<p>无需复杂的配置，只需向智能体演示一遍操作即可。系统会自动捕捉您的交互轨迹并将其转化为可复用的技能。此后，只需一句话即可触发深层应用功能或多步复杂工作流。它将您的日常习惯沉淀为自动化快捷指令，真正实现“言出必行”的无缝体验。</p>

## Architecture

## Table of Contents

- [News](#news)
- [Key Features of omniclaw](#key-features-of-omniclaw)
- [Demo](#demo)
  - [DEMO1.1：Camera-informed execution](#demo-1-camera-informed-execution)
  - [DEMO1.2：ScreenAvatar execution](#demo-1-2-screen-avatar-execution)
  - [DEMO2：Memory-based one-click video](#demo-2-memory-based-one-click-video)
  - [DEMO4：TraceCapture + ClawRecall](#demo-4-trace-capture-claw-recall)
- [Architecture](#architecture)
- [Table of Contents](#table-of-contents)
- [Features](#features)
- [Install](#install)
- [Quick Start](#quick-start)
- [Chat Apps](#chat-apps)
- [Agent Social Network](#agent-social-network)
- [Configuration](#configuration)
- [Multiple Instances](#multiple-instances)
- [Memory](#memory)
- [CLI Reference](#cli-reference)
- [In-Chat Commands](#in-chat-commands)
- [Periodic Tasks](#periodic-tasks)
- [Python SDK](#python-sdk)
- [OpenAI-Compatible API](#openai-compatible-api)
- [Docker](#docker)
- [Linux Service](#linux-service)
- [Project Structure](#project-structure)
- [Contribute & Roadmap](#contribute--roadmap)
- [Star History](#star-history)

## Features

## Install

## Quick Start

## Chat Apps

## Agent Social Network

## Configuration

## Multiple Instances

## Memory

## CLI Reference

## In-Chat Commands

## Periodic Tasks

## Python SDK

## OpenAI-Compatible API

## Docker

## Linux Service

## Project Structure

## Contribute & Roadmap

## Star History
