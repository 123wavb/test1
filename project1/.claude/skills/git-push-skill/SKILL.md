---
name: git-push-skill
description: >-
  Git 推送技能 —— 将本地代码一键推送到 GitHub 远程仓库。
  当用户说以下任意一句话时触发：
    - "帮我把程序上传到远端仓库"
    - "帮我上传到github"
    - "帮我上传到 GitHub"
    - "推送代码"
    - "git push"
    - "提交代码到远端"
    - "把变更推上去"

  Examples:
  <example>
  user: 帮我把程序上传到远端仓库
  assistant: 好的，我来运行 GitPushSkill 帮你推送代码到远端仓库。
  </example>
  <example>
  user: 推送代码
  assistant: 正在调用 GitPushSkill 执行推送...
  </example>

version: 1.0.0
color: green
tools:
  - Bash
  - Read
user-invocable: true
---

# GitPushSkill — 一键上传到 GitHub

## 执行动作

当该 Skill 被触发时，执行以下步骤：

### 1. 检测 Java 编译产物是否存在

检查 class 文件是否存在：

```bash
ls "D:\Desktop\claudecode\project1\GitPushSkill.class"
```

如果不存在，先编译：

```bash
cd "D:\Desktop\claudecode\project1" && javac GitPushSkill.java -encoding UTF-8
```

### 2. 获取当前工作区目录

从上下文中获取用户当前正在操作的项目目录路径（即 VSCode 打开的工作区根目录）。

### 3. 执行 Java 程序

在终端中运行以下命令，将当前工作区目录作为参数传入：

```bash
java -cp "D:\Desktop\claudecode\project1" GitPushSkill --projectPath="<当前工作区目录>"
```

例如，如果当前工作区是 `D:\MyProject`，则实际执行的命令为：

```bash
java -cp "D:\Desktop\claudecode\project1" GitPushSkill --projectPath="D:\MyProject"
```

### 4. 交互式操作说明

Java 程序启动后会自动执行以下流程：

1. **检测变更** — 列出工作区中所有已修改/新增的文件
2. **暂存变更** — 执行 `git add .`
3. **等待用户输入提交备注** — 程序会在控制台打印提示，等待用户键盘输入 commit message
4. **提交变更** — 执行 `git commit`
5. **推送确认** — 显示推送摘要（远程仓库地址、分支、变更文件数），等待用户确认 Y/n
6. **推送到远程** — 执行 `git push`

### 5. 重要提示

- 该 Skill 适配 **Windows 系统**，默认通过 `cmd.exe /c` 执行 Git 命令
- 如果用户处于代理网络环境中，Java 程序会自动继承终端的 `HTTP_PROXY` / `HTTPS_PROXY` 环境变量
- 执行过程中所有输出（进度、错误信息）会实时打印到控制台
- 如果编译或执行失败，向用户报告具体的错误信息
