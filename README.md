# RuYiAgent (RuyiCode)

<p align="center">
  <strong>基于 Java 21、虚拟线程与 MCP 协议的企业级多智能体协同终端编程助手</strong>
  <br>
  <em>A High-Performance Terminal AI Coding Agent Platform Built with Java 21 and Model Context Protocol (MCP)</em>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-orange.svg" alt="Java 21" />
  <img src="https://img.shields.io/badge/Gradle-8.x-blue.svg" alt="Gradle" />
  <img src="https://img.shields.io/badge/MCP-2.0.1-brightgreen.svg" alt="Model Context Protocol" />
  <img src="https://img.shields.io/badge/Architecture-Multi--Agent-purple.svg" alt="Multi-Agent" />
  <img src="https://img.shields.io/badge/License-Apache%202.0-lightgrey.svg" alt="License" />
</p>

---

## 📖 项目简介 (Overview)

**RuYiAgent (RuyiCode)** 是一个使用 **Java 21** 纯自研的生产级终端智能体平台（对标 Claude Code / Cursor Agent 运行时）。项目基于事件驱动流式引擎与虚拟线程并发架构，集成了 Anthropic 官方 **Model Context Protocol (MCP 2.0.1)** 标准协议，具备工业级上下文预算压缩治理、多进程/多终端智能体协作（Multi-Agent Swarm）以及基于 Git Worktree 的物理沙箱隔离能力。

---

## ✨ 核心特性 (Key Features)

### 1. ⚡ 现代 Java 21 运行时与事件流驱动
* **虚拟线程并发模型**：利用 `Thread.startVirtualThread` 与事件流队列（`StreamingExecutor`），解耦终端 TUI 渲染与模型流式长连接，高并发高吞吐且极低资源占用。
* **双协议无缝适配**：原生支持 Anthropic（Claude 3.5/3.7 系列）与 OpenAI / OpenAI-Compatible（DeepSeek-V3/R1、GPT-4o、Kimi 等）接口规范。

### 2. 🧠 双层上下文治理与成本控制 (Context Engineering)
* **Layer 1 工具结果预算 (`ToolResultBudget`)**：对工具执行输出实施动态截断与预算聚合，防止单次 Shell/读取操作产生海量 Token 冲击。
* **Layer 2 自动上下文压缩 (`ContextCompactor`)**：逼近窗口上限（80% 触发阈值）时自动调用大模型生成历史摘要，配合 PTL（Prompt-Too-Long）退避重试机制。
* **状态机记忆恢复 (`RecoveryState`)**：压缩后自动补偿召回近期文件读取快照与活跃 Skill SOP，杜绝长会话信息丢失导致的模型幻觉。

### 3. 🔌 行业标准 MCP 2.0.1 协议与延迟工具加载
* **完整 MCP 客户端集成**：无缝连接外部工具生态系统（如 Context7 等 MCP 服务）。
* **延迟工具检索 (`ToolSearch`)**：将海量工具转换为延迟按需发现机制，避免全量工具直接注入导致的 Prompt 膨胀与 Prompt Caching 失效。

### 4. 🤖 多智能体协同体系 (Multi-Agent Swarm)
* **多运行后端分发 (`SpawnDispatcher`)**：支持进程内虚拟线程、Tmux 窗口拆分、iTerm2 终端多窗格拉起队友智能体（Teammates）。
* **异步文件邮箱通信 (`FileMailBox`)**：Agent 间通过结构化消息协议实现去中心化任务派发与进度回报。
* **共享任务看板 (`SharedTaskStore`)**：基于 `tasks.json` 实现任务全局分配、领抢与状态原子同步。

### 5. 🛡️ 企业级工程防御与工作区隔离
* **细粒度权限控制 (`PermissionChecker`)**：提供 `default`（确认模式）、`plan`（只读生成计划，需用户审批）、`bypass` 等多级权限控制。
* **Git Worktree 沙箱隔离 (`WorktreeManager`)**：子 Agent 在独立的 Git 物理分支工作区执行代码修改，保护开发者本地主干安全。
* **文件变更回溯 (`FileHistoryRewind`)**：自动记录智能体对文件的每一步修改，支持一键撤销与快照恢复。

### 6. 💻 双模态交互界面
* **终端 TUI 模式**：基于 TEA (The Elm Architecture) 架构模式与 JLine3 + Mordant 构建，支持 Markdown 渲染、交互弹窗与动态状态指示器。
* **Remote 模式**：内置基于 Javalin 的轻量级 HTTP/WebSocket 服务器，支持远程 Web 浏览器或移动端控制。

---

## 🏗️ 架构概览 (Architecture)

```text
                  ┌──────────────────────────────────────────────┐
                  │                 User Inputs                  │
                  │       (Interactive TUI / Remote Web)         │
                  └──────────────────────┬───────────────────────┘
                                         ▼
                  ┌──────────────────────────────────────────────┐
                  │              Agent Runtime Core              │
                  │    - StreamingExecutor (Virtual Threads)     │
                  │    - HookEngine & PermissionChecker          │
                  └──────┬────────────────┬───────────────┬──────┘
                         │                │               │
      ┌──────────────────┴──┐      ┌──────┴─────┐  ┌──────┴───────────────┐
      │ Context Engineering │      │ Tool & MCP │  │ Multi-Agent Swarm    │
      ├─────────────────────┤      ├────────────┤  ├──────────────────────┤
      │ ToolResultBudget    │      │ Read/Write │  │ SpawnDispatcher      │
      │ ContextCompactor    │      │ Bash/Diff  │  │ FileMailBox (Comm)   │
      │ RecoveryState       │      │ MCP 2.0.1  │  │ SharedTaskStore      │
      │ MemoryManager       │      │ ToolSearch │  │ Git Worktree Sandbox │
      └─────────────────────┘      └────────────┘  └──────────────────────┘
```

---

## 🚀 快速上手 (Quick Start)

### 1. 环境要求
* **JDK 21+**（推荐 Eclipse Temurin 或 Microsoft Build of OpenJDK 21）
* **Git 2.20+**
* （可选）Tmux / iTerm2（用于多终端 Agent 进程可视化）

### 2. 克隆与配置

```bash
# 克隆仓库
git clone https://github.com/Jhon-cmyk/ruyi-agent.git
cd ruyi-agent

# 创建本地配置文件
cp .ruyicode/config.yaml.example .ruyicode/config.yaml
```

编辑 `.ruyicode/config.yaml`，填入你的大模型 API Key（以 DeepSeek 为例）：

```yaml
providers:
  - name: deepseek-chat
    protocol: openai-compat
    base_url: https://api.deepseek.com
    api_key: "your-api-key-here"
    model: deepseek-chat
    thinking: false

permission_mode: default
```

### 3. 构建与运行

```bash
# 1. 编译并运行 TUI 交互模式
./gradlew run

# 2. 单次提问模式 (-p)
./gradlew run --args="-p '请分析当前目录下的项目结构'"

# 3. 启动 Remote Web 模式
./gradlew run --args="--remote=:18888"

# 4. 构建可执行 FatJar
./gradlew shadowJar
# 产物位于 build/libs/ruyicode.jar，可通过 java -jar 运行
```

---

## 📂 项目结构 (Project Structure)

```text
src/
├── main/java/com/ruyicode/
│   ├── RuyiCode.java         # 程序主入口与命令行参数解析
│   ├── agent/                # Agent 核心状态机与流式执行器
│   ├── compact/              # Layer 2 上下文压缩与 Recovery 状态机
│   ├── conversation/         # 会话消息建模与多模态块管理
│   ├── llm/                  # Anthropic / OpenAI 客户端抽象与流处理
│   ├── mcp/                  # Model Context Protocol (MCP 2.0.1) 协议客户端
│   ├── memory/               # 长期记忆管理器 (MEMORY.md)
│   ├── permission/           # 细粒度工具执行权限校验
│   ├── remote/               # Javalin HTTP/WebSocket 远程服务
│   ├── skill/                # 动态技能加载器与三级 SOP 系统
│   ├── teams/                # 多智能体协同 (SpawnDispatcher/MailBox/TaskStore)
│   ├── tool/                 # 内置工具 (Read/Write/Edit/Bash/ToolSearch)
│   ├── toolresult/           # Layer 1 工具预算控制 (ToolResultBudget)
│   ├── tui/                  # 基于 TEA 架构的终端交互界面
│   └── worktree/             # Git Worktree 沙箱管理与分支隔离
└── test/java/com/ruyicode/   # 40+ 单元与集成测试套件
```

---

## 🧪 自动化测试 (Testing)

项目拥有健全的单元测试与集成测试覆盖：

```bash
./gradlew test
```

---

## 📄 开源协议 (License)

本项目采用 [Apache License 2.0](LICENSE) 开源协议。
