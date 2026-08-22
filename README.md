# MineAstr

[![AI Assisted](https://img.shields.io/badge/AI-OpenAI%20Codex%20Assisted-10A37F?style=for-the-badge&logo=openai&logoColor=white)](#ai-制作声明)

> [!IMPORTANT]
> **AI 制作声明：MineAstr 采用生成式 AI 参与架构设计、编码、UI、文档与测试。** AI 生成或修改的内容由项目维护者审阅、验证并承担最终维护责任。

MineAstr 已拆分为两个独立工程分支。请根据要开发或发布的目标切换到对应分支。

## 分支

- `astrbot-plugin`：AstrBot 插件端，分支根目录就是可安装的 AstrBot 插件目录。
- `minecraft-mod`：Minecraft NeoForge Mod 端，分支根目录就是 Gradle/NeoForge 工程。

## 连接方式

MineAstr 使用 AstrBot 插件侧启动的专用 WebSocket 服务。Minecraft 服务端或单人集成服务器中的 MineAstr Mod 会主动连接 AstrBot：

```text
Minecraft MineAstr Mod -> ws://AstrBot机器:8765/ws -> AstrBot minecraft 平台适配器
```

因此跨机器部署时，需要开放 AstrBot 所在机器的 WebSocket 监听端口，而不是 Minecraft 服务器端口。

## 开发流程

```powershell
# 开发 AstrBot 插件
git switch astrbot-plugin

# 开发 Minecraft Mod
git switch minecraft-mod
```

`main` 只作为索引分支，不承载实际工程代码。

## 当前版本

- AstrBot 插件：`0.11.2`
- Minecraft Mod：`0.11.2`

## AI 制作声明

本项目使用了 OpenAI Codex 等生成式 AI 能力，参与 Java/Python 代码、LLM tools、客户端 UI、安全审查、README 与构建测试流程。AI 输出仍需经过人工审阅和实际测试。

英文声明：*This project was created with assistance from generative AI, including OpenAI Codex. AI-assisted changes remain subject to human review, testing, and maintainer responsibility.*
