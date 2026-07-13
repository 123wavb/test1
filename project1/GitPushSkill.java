import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * GitPushSkill — 一键上传代码到 GitHub 的技能类
 * <p>
 * 执行流程：检测变更 → 暂存 → 等待用户输入提交备注 → 提交 → 推送到远程
 * <p>
 * 适配 Windows 系统，通过 cmd.exe /c 执行 Git 命令。
 * test1
 */
public class GitPushSkill {

    // ============================
    //  配色 / 进度输出辅助
    // ============================
    private static final String GREEN  = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String CYAN   = "\u001B[36m";
    private static final String RESET  = "\u001B[0m";

    // ============================
    //  入口方法
    // ============================

    /**
     * 程序入口
     * <p>
     * 执行步骤：
     * 1. 解析命令行参数，获取 projectPath（默认当前目录）
     * 2. 验证路径是否存在
     * 3. 调用 executeSkill() 执行完整流程
     *
     * @param args 可选参数：--projectPath=<路径>
     */
    public static void main(String[] args) {
        // ---- 检测是否运行测试模式 ----
        for (String arg : args) {
            if (arg.equals("--test")) {
                testEnvironment(args);
                return;
            }
        }

        // ---- 解析参数 ----
        String projectPath = System.getProperty("user.dir"); // 默认当前工作目录

        for (String arg : args) {
            if (arg.startsWith("--projectPath=")) {
                projectPath = arg.substring("--projectPath=".length());
            }
        }

        // ---- 验证路径 ----
        Path path = Paths.get(projectPath).normalize();
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            System.err.println(RED + "[错误] 项目路径不存在或不是目录：" + path.toAbsolutePath() + RESET);
            System.exit(1);
        }

        // ---- 执行核心流程 ----
        GitPushSkill skill = new GitPushSkill();
        boolean success = skill.executeSkill(path.toAbsolutePath().toString());

        if (success) {
            System.out.println(GREEN + "\n✅ 推送成功完成！" + RESET);
        } else {
            System.err.println(RED + "\n❌ 推送过程中出现了错误，已终止。" + RESET);
            System.exit(1);
        }
    }

    // ============================
    //  核心流程编排
    // ============================

    /**
     * 核心流程编排方法
     * <p>
     * 按顺序执行六个步骤，任一步骤失败则终止并返回 false。
     *
     * @param projectPath 项目根目录的绝对路径
     * @return true 表示全部执行成功，false 表示中途失败
     */
    public boolean executeSkill(String projectPath) {
        System.out.println(CYAN + "========================================" + RESET);
        System.out.println(CYAN + "  GitPushSkill — 一键上传到 GitHub" + RESET);
        System.out.println(CYAN + "  项目路径：" + projectPath + RESET);
        System.out.println(CYAN + "========================================\n" + RESET);

        // ---- 步骤1：检测变更 ----
        System.out.println(YELLOW + "[步骤 1/6] 检测项目变更 ..." + RESET);
        List<String> changedFiles = getChangedFiles(projectPath);
        if (changedFiles == null) {
            System.err.println(RED + "[错误] 检测变更失败，终止执行。" + RESET);
            return false;
        }
        if (changedFiles.isEmpty()) {
            System.out.println(YELLOW + "[提示] 项目没有检测到任何变更，无需提交。" + RESET);
            return false;
        }
        System.out.println("  共检测到 " + changedFiles.size() + " 个变更文件：");
        for (String file : changedFiles) {
            System.out.println("    · " + file);
        }
        System.out.println();

        // ---- 步骤2：展示变更文件列表（已在步骤1中一并输出） ----
        // 此处直接沿用步骤1的输出结果，无需重复操作

        // ---- 步骤3：暂存变更 ----
        System.out.println(YELLOW + "[步骤 3/6] 暂存变更 (git add) ..." + RESET);
        if (!stageChanges(projectPath)) {
            System.err.println(RED + "[错误] 暂存变更失败，终止执行。" + RESET);
            return false;
        }
        System.out.println("  ✅ 文件已暂存\n");

        // ---- 步骤4：等待用户输入 commit message ----
        System.out.println(YELLOW + "[步骤 4/6] 请输入提交备注 ..." + RESET);
        String commitMessage = readCommitMessageFromUser();
        if (commitMessage == null) {
            // 用户输入读取失败（如 IO 异常）
            System.err.println(RED + "[错误] 读取提交信息失败，终止执行。" + RESET);
            return false;
        }
        System.out.println("  您输入的提交信息：" + commitMessage + "\n");

        // ---- 步骤5：提交变更 ----
        System.out.println(YELLOW + "[步骤 5/6] 提交变更 (git commit) ..." + RESET);
        if (!commitChanges(projectPath, commitMessage)) {
            System.err.println(RED + "[错误] 提交变更失败，终止执行。" + RESET);
            return false;
        }
        System.out.println("  ✅ 已提交\n");

        // ---- 步骤6：推送到远程（含安全确认） ----
        System.out.println(YELLOW + "[步骤 6/6] 推送到远程仓库 (git push) ..." + RESET);

        // 推送前输出摘要供用户确认
        printPushSummary(projectPath, changedFiles, commitMessage);

        if (!confirmPush()) {
            System.out.println(YELLOW + "[提示] 用户取消了推送操作。" + RESET);
            return false;
        }

        if (!pushToRemote(projectPath)) {
            System.err.println(RED + "[错误] 推送到远程失败，终止执行。" + RESET);
            return false;
        }
        System.out.println("  ✅ 已成功推送到远程仓库\n");

        return true;
    }

    // ============================
    //  步骤1：检测变更
    // ============================

    /**
     * 检测项目中的变更文件列表
     * <p>
     * 执行 git status --porcelain 命令，解析输出结果，
     * 提取所有已修改 / 新增 / 删除 / 重命名的文件路径。
     * <p>
     * git status --porcelain 输出格式：
     *   XY 文件路径
     *   X 为暂存区状态，Y 为工作区状态
     *   例如：" M src/Main.java" 表示工作区已修改
     *         "?? README.md"      表示未跟踪的新文件
     *
     * @param projectPath 项目根目录路径
     * @return 变更文件路径列表，失败返回 null
     */
    public List<String> getChangedFiles(String projectPath) {
        try {
            // 执行 git status --porcelain，获取机器可读的变更状态
            String output = runCommand(projectPath, "git status --porcelain");
            if (output == null) {
                return null; // 命令执行失败
            }

            List<String> changedFiles = new ArrayList<>();
            String[] lines = output.split("\n");

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue; // 跳过空行
                }

                // 解析前两个状态字符后面的文件路径
                // 常见的格式：XY 路径 或 XY "带空格的路径"
                // 例如：" M src/App.java" 或 "R  old/path -> new/path"
                if (line.length() > 3) {
                    String pathPart = line.substring(2).trim();

                    // 处理重命名情况："R  old -> new"，只取新路径
                    if (pathPart.contains(" -> ")) {
                        String[] parts = pathPart.split(" -> ");
                        pathPart = parts[parts.length - 1].trim();
                    }

                    // 移除可能的引号包裹（路径含空格时 Git 会加引号）
                    if (pathPart.startsWith("\"") && pathPart.endsWith("\"")) {
                        pathPart = pathPart.substring(1, pathPart.length() - 1);
                    }

                    if (!pathPart.isEmpty()) {
                        changedFiles.add(pathPart);
                    }
                }
            }

            return changedFiles;

        } catch (Exception e) {
            System.err.println(RED + "[异常] 检测变更时发生异常：" + e.getMessage() + RESET);
            return null;
        }
    }

    // ============================
    //  步骤4：等待用户输入提交备注
    // ============================

    /**
     * 从控制台读取用户手动输入的 commit message
     * <p>
     * 执行流程：
     *   1. 在控制台输出提示信息，引导用户输入提交备注
     *   2. 使用 BufferedReader 读取用户从键盘输入的一整行文字
     *   3. 如果用户直接按回车（输入为空），给出警告并提示重新输入
     *   4. 循环直到用户输入非空内容为止
     * <p>
     * 该环节在 git add 之后、git commit 之前执行，
     * 让用户有机会在暂存完所有变更后，根据实际变更内容撰写提交信息。
     *
     * @return 用户输入的非空提交信息字符串，读取失败返回 null
     */
    public String readCommitMessageFromUser() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String input = null;

        while (true) {
            try {
                System.out.print("  请为本次提交输入 commit message（输入完成后按回车确认）：");

                input = reader.readLine();

                // 检查是否读取到 EOF（用户可能按了 Ctrl+Z 或 Ctrl+D）
                if (input == null) {
                    System.err.println(RED + "\n  [警告] 读取到输入流结束，终止提交。" + RESET);
                    return null;
                }

                // 去除首尾空白后检查是否为空
                String trimmed = input.trim();
                if (trimmed.isEmpty()) {
                    System.out.println(RED + "  ❌ 提交信息不能为空，请重新输入：" + RESET);
                    // 继续循环，让用户重新输入
                    continue;
                }

                // 输入有效，返回去除首尾空白后的内容
                return trimmed;

            } catch (IOException e) {
                System.err.println(RED + "\n  [异常] 读取用户输入时发生 IO 错误：" + e.getMessage() + RESET);
                return null;
            }
        }
    }

    // ============================
    //  步骤3：暂存变更
    // ============================

    /**
     * 执行 git add . 暂存所有变更
     * <p>
     * 使用 git add . 而非 git add -A，区别：
     *   git add .    → 暂存当前目录及子目录下的所有变更
     *   git add -A   → 暂存整个工作树的变更（含已删除的文件）
     * <p>
     * 一般场景下 git add . 已足够；如需要处理文件删除可使用 git add -A
     *
     * @param projectPath 项目根目录路径
     * @return true 表示暂存成功，false 表示失败
     */
    public boolean stageChanges(String projectPath) {
        try {
            System.out.println("  正在执行：git add .");
            String output = runCommand(projectPath, "git add .");

            if (output == null) {
                // runCommand 返回 null 表示执行异常
                return false;
            }

            return true;

        } catch (Exception e) {
            System.err.println(RED + "[异常] 暂存变更时发生异常：" + e.getMessage() + RESET);
            return false;
        }
    }

    // ============================
    //  步骤5：提交变更
    // ============================

    /**
     * 执行 git commit -m "提交信息" 提交已暂存的变更
     * <p>
     * 注意：提交信息中如果包含特殊字符（如引号），需要做转义处理。
     * 这里将提交信息写入临时文件后通过 git commit -F 的方式提交，
     * 避免命令行引号转义带来的问题。
     *
     * @param projectPath   项目根目录路径
     * @param commitMessage 提交信息内容
     * @return true 表示提交成功，false 表示失败
     */
    public boolean commitChanges(String projectPath, String commitMessage) {
        // ---- 保存提交信息到临时文件 ----
        File tempFile = null;
        try {
            // 创建临时文件来保存提交信息，避免命令行参数转义问题
            tempFile = File.createTempFile("git-commit-msg-", ".txt");
            tempFile.deleteOnExit(); // JVM 退出时自动删除

            // 使用 UTF-8 编码写入提交信息，确保中文正常显示
            Files.writeString(tempFile.toPath(), commitMessage, java.nio.charset.StandardCharsets.UTF_8);

            System.out.println("  正在执行：git commit -F " + tempFile.getName());

            // 通过 -F 从文件读取提交信息，避免引号转义
            String output = runCommand(projectPath,
                    "git commit -F \"" + tempFile.getAbsolutePath() + "\"");

            if (output == null) {
                return false; // 执行异常
            }

            // 输出 git commit 的结果信息
            System.out.println("  " + output.trim().replace("\n", "\n  "));
            return true;

        } catch (IOException e) {
            System.err.println(RED + "[异常] 写入提交信息临时文件失败：" + e.getMessage() + RESET);
            return false;
        } catch (Exception e) {
            System.err.println(RED + "[异常] 提交变更时发生异常：" + e.getMessage() + RESET);
            return false;
        } finally {
            // 清理临时文件
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    // ============================
    //  步骤6：推送到远程
    // ============================

    /**
     * 执行 git push 将本地提交推送到远程仓库
     * <p>
     * 默认推送当前分支到 origin 远程。
     * 如果需要指定远程名称和分支，可以修改命令为：
     *   git push origin <branch-name>
     *
     * @param projectPath 项目根目录路径
     * @return true 表示推送成功，false 表示失败
     */
    public boolean pushToRemote(String projectPath) {
        try {
            System.out.println("  正在执行：git push");
            String output = runCommand(projectPath, "git push");

            if (output == null) {
                return false; // 执行异常
            }

            // 输出 git push 的结果
            System.out.println("  " + output.trim().replace("\n", "\n  "));
            return true;

        } catch (Exception e) {
            System.err.println(RED + "[异常] 推送到远程时发生异常：" + e.getMessage() + RESET);
            return false;
        }
    }

    // ============================
    //  推送前安全确认
    // ============================

    /**
     * 在 git push 之前输出将要推送的内容摘要，让用户确认
     * <p>
     * 摘要包含：
     *   - 当前分支名称
     *   - 变更文件数量
     *   - 提交信息标题
     *   - 最近一次提交的详细信息（git log -1）
     *
     * @param projectPath   项目根目录路径
     * @param changedFiles  变更文件列表
     * @param commitMessage 提交信息
     */
    private void printPushSummary(String projectPath, List<String> changedFiles, String commitMessage) {
        try {
            // ---- 获取当前分支名称 ----
            String branch = runCommand(projectPath, "git rev-parse --abbrev-ref HEAD");
            if (branch != null) {
                branch = branch.trim();
            } else {
                branch = "未知";
            }

            // ---- 获取远程仓库地址 ----
            String remoteUrl = runCommand(projectPath, "git remote get-url origin");
            if (remoteUrl != null) {
                remoteUrl = remoteUrl.trim();
            } else {
                remoteUrl = "未配置远程仓库";
            }

            // ---- 获取最近一次提交的简要信息 ----
            String lastCommit = runCommand(projectPath, "git log -1 --oneline");
            if (lastCommit != null) {
                lastCommit = lastCommit.trim();
            } else {
                lastCommit = "无提交记录";
            }

            // ---- 输出推送摘要 ----
            System.out.println();
            System.out.println(CYAN + "═══════════════════════════════════════" + RESET);
            System.out.println(CYAN + "           推送内容摘要" + RESET);
            System.out.println(CYAN + "═══════════════════════════════════════" + RESET);
            System.out.println("  远程仓库  : " + remoteUrl);
            System.out.println("  当前分支  : " + branch);
            System.out.println("  变更文件  : " + changedFiles.size() + " 个");
            System.out.println("  提交信息  : " + commitMessage.split("\n")[0]);
            System.out.println("  最新提交  : " + lastCommit);
            System.out.println(CYAN + "───────────────────────────────────────" + RESET);
            System.out.println();

        } catch (Exception e) {
            // 获取摘要失败不影响主流程，仅输出提示
            System.out.println(YELLOW + "[提示] 无法获取推送摘要（不影响推送）：" + e.getMessage() + RESET);
        }
    }

    /**
     * 向用户请求确认是否执行推送操作
     * <p>
     * 从控制台读取用户输入，Y/y 或直接回车表示确认，
     * 其他输入表示取消。
     *
     * @return true 表示用户确认推送，false 表示取消
     */
    private boolean confirmPush() {
        System.out.print("  是否确认推送以上内容到远程仓库？(Y/n): ");
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String input = reader.readLine();
            // 空输入或 y/Y 都视为确认
            return input == null || input.isBlank() || input.trim().equalsIgnoreCase("y");
        } catch (IOException e) {
            // 读取输入失败，安全起见取消推送
            System.err.println(RED + "[异常] 读取用户输入失败：" + e.getMessage() + RESET);
            return false;
        }
    }

    // ============================
    //  通用命令执行工具
    // ============================

    /**
     * 需要继承到子进程的代理环境变量名称列表
     * <p>
     * Git 依赖 libcurl 进行网络操作，当位于代理网络环境（如公司内网）时，
     * 必须设置 HTTP_PROXY / HTTPS_PROXY 等环境变量才能正常访问远程仓库。
     * 该列表同时包含大写和小写两种形式，以兼容不同操作系统和工具链。
     */
    private static final String[] PROXY_ENV_VARS = {
            "HTTP_PROXY", "http_proxy",
            "HTTPS_PROXY", "https_proxy",
            "ALL_PROXY", "all_proxy",
            "NO_PROXY", "no_proxy"
    };

    /**
     * 通用命令执行方法，自动适配 Windows / Linux / macOS
     * <p>
     * 执行流程：
     *   1. 根据操作系统选择命令前缀
     *      - Windows → cmd.exe /c <命令>
     *      - Linux/macOS → sh -c <命令>
     *   2. 通过 ProcessBuilder 启动进程
     *   3. 【关键】从当前 JVM 进程的 System.getenv() 中继承代理环境变量
     *      （HTTP_PROXY / HTTPS_PROXY / ALL_PROXY / NO_PROXY 的大小写形式）
     *      并显式设置到子进程的环境变量中，确保 Git 能通过代理访问 GitHub
     *   4. 读取进程的标准输出和错误输出
     *   5. 等待进程执行完毕
     *   6. 检查退出码，成功返回输出内容，失败返回 null
     * <p>
     * Windows 路径说明：
     *   - 路径中的反斜杠 \ 在 cmd.exe 中正常使用
     *   - 如果路径包含空格，程序调用方需自行加引号包裹
     *
     * @param workDir 工作目录（git 项目根目录）
     * @param command 要执行的命令（如 "git status --porcelain"）
     * @return 命令的标准输出内容（stdout），失败返回 null
     */
    public String runCommand(String workDir, String command) {
        try {
            // ---- 1. 构建命令 ----
            // Windows 需要 cmd.exe /c 前缀，Linux/macOS 使用 sh -c
            String shell;
            String shellFlag;

            if (isWindows()) {
                shell = "cmd.exe";
                shellFlag = "/c";
            } else {
                shell = "sh";
                shellFlag = "-c";
            }

            // ---- 2. 创建 ProcessBuilder ----
            ProcessBuilder pb = new ProcessBuilder(
                    shell,
                    shellFlag,
                    command
            );

            // 设置工作目录
            pb.directory(new File(workDir));

            // 合并标准输出和错误输出，方便统一读取
            pb.redirectErrorStream(true);

            // ---- ★ 关键修复：继承终端代理环境变量 ----
            // ProcessBuilder 默认会继承父进程的环境，但在某些 Windows 环境下，
            // 通过 IDEA / VS Code 等 IDE 运行 Java 时，终端中通过 set HTTP_PROXY=...
            // 设置的代理变量不会自动传递到 JVM 的子进程。
            // 这里显式从 System.getenv() 读取并设置到 ProcessBuilder 中，确保 Git 命令能通过代理联网。
            Map<String, String> env = pb.environment();
            Map<String, String> systemEnv = System.getenv();
            boolean anyProxySet = false;

            for (String varName : PROXY_ENV_VARS) {
                String value = systemEnv.get(varName);
                if (value != null && !value.isEmpty()) {
                    env.put(varName, value);
                    anyProxySet = true;
                }
            }

            // 仅在非 git status 命令时输出代理继承信息，避免 git status 的输出过于冗长
            if (!command.contains("status --porcelain") && anyProxySet) {
                String httpsProxy = env.get("HTTPS_PROXY");
                if (httpsProxy == null) {
                    httpsProxy = env.get("https_proxy");
                }
                System.out.println("  [代理] 已继承代理环境变量"
                        + (httpsProxy != null ? " (HTTPS_PROXY=" + maskProxyUrl(httpsProxy) + ")" : ""));
            }

            // ---- 3. 启动进程 ----
            Process process = pb.start();

            // ---- 4. 读取输出 ----
            // 使用 UTF-8 读取，确保中文正常显示
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            // ---- 5. 等待进程结束 ----
            int exitCode = process.waitFor();

            // ---- 6. 检查退出码 ----
            if (exitCode == 0) {
                // 成功：返回输出内容（去除末尾多余的换行）
                String result = output.toString();
                while (result.endsWith("\n")) {
                    result = result.substring(0, result.length() - 1);
                }
                return result;
            } else {
                // 失败：输出错误信息
                String errorOutput = output.toString().trim();
                System.err.println(RED + "[命令执行失败] 退出码: " + exitCode + RESET);
                if (!errorOutput.isEmpty()) {
                    System.err.println(RED + "  错误信息: " + errorOutput + RESET);
                }
                return null;
            }

        } catch (IOException e) {
            System.err.println(RED + "[IO异常] 执行命令时发生 IO 错误：" + e.getMessage() + RESET);
            return null;
        } catch (InterruptedException e) {
            System.err.println(RED + "[中断异常] 命令执行被中断：" + e.getMessage() + RESET);
            Thread.currentThread().interrupt(); // 恢复中断状态
            return null;
        } catch (Exception e) {
            System.err.println(RED + "[异常] 执行命令时发生未知异常：" + e.getMessage() + RESET);
            return null;
        }
    }

    // ============================
    //  系统判断工具
    // ============================

    /**
     * 判断当前操作系统是否为 Windows 系列
     * <p>
     * 通过系统属性 os.name 判断，常见的 Windows 名称包括：
     *   Windows 10, Windows 11, Windows Server 2019 等
     * 所有以 "Windows" 开头的操作系统名称都会被识别为 Windows。
     *
     * @return true 表示当前系统为 Windows，false 表示其他系统（Linux / macOS / Unix）
     */
    public boolean isWindows() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
        return osName.startsWith("windows");
    }

    // ============================
    //  代理工具方法
    // ============================

    /**
     * 对代理 URL 进行脱敏处理，隐藏密码信息
     * <p>
     * 例如：http://user:password@proxy.example.com:8080
     *  →  http://user:****@proxy.example.com:8080
     * <p>
     * 用于在控制台输出代理信息时防止敏感信息泄露。
     *
     * @param proxyUrl 原始代理 URL
     * @return 脱敏后的代理 URL，如果为 null 则返回空字符串
     */
    private String maskProxyUrl(String proxyUrl) {
        if (proxyUrl == null || proxyUrl.isEmpty()) {
            return "";
        }
        // 匹配 http://user:password@host 或 https://user:password@host 格式
        // 将密码部分替换为 ****
        return proxyUrl.replaceAll("://([^:]+):([^@]+)@", "://$1:****@");
    }

    // ============================
    //  测试入口（可选）
    // ============================

    /**
     * 快速测试当前环境是否支持 Git 命令
     * <p>
     * 执行方式：
     *   java GitPushSkill --test
     * <p>
     * 测试内容：
     *   - 检测操作系统类型
     *   - 尝试执行 git --version 查看 Git 是否可用
     *
     * @param args 传入 --test 启动测试模式
     */
    public static void testEnvironment(String[] args) {
        GitPushSkill skill = new GitPushSkill();

        System.out.println(CYAN + "===== GitPushSkill 环境检测 =====" + RESET);
        System.out.println("  操作系统: " + System.getProperty("os.name"));
        System.out.println("  是否 Windows: " + skill.isWindows());
        System.out.println("  当前工作目录: " + System.getProperty("user.dir"));
        System.out.println();

        System.out.println(YELLOW + "正在检测 Git 命令 ..." + RESET);
        String version = skill.runCommand(System.getProperty("user.dir"), "git --version");
        if (version != null) {
            System.out.println(GREEN + "  ✅ " + version.trim() + RESET);
        } else {
            System.err.println(RED + "  ❌ Git 不可用，请确保 Git 已安装并添加到 PATH 环境变量" + RESET);
        }

        // ---- 检测代理环境变量 ----
        System.out.println();
        System.out.println(YELLOW + "正在检测代理环境变量 ..." + RESET);
        Map<String, String> sysEnv = System.getenv();
        boolean foundProxy = false;
        for (String proxyVar : new String[]{"HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy"}) {
            String val = sysEnv.get(proxyVar);
            if (val != null && !val.isEmpty()) {
                System.out.println(GREEN + "  ✅ " + proxyVar + " = " + skill.maskProxyUrl(val) + RESET);
                foundProxy = true;
            }
        }
        if (!foundProxy) {
            System.out.println(YELLOW + "  ⚠ 未检测到代理环境变量。如果您在代理网络环境中，请设置 HTTP_PROXY 环境变量。" + RESET);
            System.out.println(YELLOW + "     例如：set HTTPS_PROXY=http://127.0.0.1:7890" + RESET);
        }
    }
}