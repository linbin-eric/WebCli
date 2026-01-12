# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

WebCLI 是一个基于 Web 的终端模拟器，通过 WebSocket 提供浏览器中的 PTY（伪终端）访问。使用 JfireBoot 框架和 pty4j 库实现。

## 构建命令

```bash
# 编译项目
mvn compile

# 运行测试
mvn test

# 打包
mvn package

# 运行应用（默认端口 18080）
java -jar target/webcli-1.0-SNAPSHOT.jar

# 指定端口运行
java -jar target/webcli-1.0-SNAPSHOT.jar 8080
```

## 依赖的本地 jar 包源码位置

1. JfireBoot：/Users/linbin/Documents/代码/JfireBoot
2. Jnet：/Users/linbin/Documents/代码/Jnet

## 架构概述

### 核心组件

- **WebApplication**: 应用入口，启动 HTTP 服务器和 WebSocket 处理器
- **PtyManager**: PTY 实例管理器，负责创建、获取、移除 PTY 实例
- **PtyInstance**: 单个 PTY 会话封装，使用 pty4j 创建伪终端进程
- **WebSocketHandler**: WebSocket 消息处理器，处理前端与 PTY 之间的通信

### 通信协议

WebSocket 消息使用 JSON 格式（通过 Dson 序列化），消息类型定义在 `MessageType` 枚举中：

| 类型 | 方向 | 说明 |
|------|------|------|
| PTY_CREATE | 客户端→服务端 | 创建新终端 |
| PTY_INPUT | 客户端→服务端 | 发送输入（Base64 编码） |
| PTY_OUTPUT | 服务端→客户端 | 终端输出（Base64 编码） |
| PTY_RESIZE | 客户端→服务端 | 调整终端大小 |
| PTY_CLOSE | 客户端→服务端 | 关闭终端 |
| PTY_LIST | 客户端→服务端 | 获取终端列表 |
| PTY_SWITCH | 客户端→服务端 | 切换当前终端 |
| PTY_ATTACH | 客户端→服务端 | 附加到已有终端 |
| PTY_RENAME | 客户端→服务端 | 重命名终端 |

### 前端

前端位于 `src/main/resources/web/`，使用 xterm.js 实现终端渲染。

## 技术栈

- Java 21（使用虚拟线程）
- JfireBoot/Jnet（HTTP 服务器和 WebSocket）
- pty4j（伪终端）
- Lombok（代码简化）
- xterm.js（前端终端）
