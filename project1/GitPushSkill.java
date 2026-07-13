import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * GitPushSkill — 一键上传代码到 GitHub/GitLab 等多远程仓库
 * <p>
 * 两种运行模式：
 *   预览模式（--mode=preview）
 *     仅收集并打印变更文件列表、远程仓库列表、当前分支等信息，
 *     不执行任何写操作（不 add、不 commit、不 push），
 *     供 Claude 在对话中展示给用户确认。
 *   <p>
 *   执行模式（--mode=execute）
 *     执行完整的 add → commit → push 流程，
 *     支持推送到所有远程仓库，单个仓库失败不影响其他仓库，
 *     最后输出每个仓库的推送结果汇总。
 * <p>
 * 调用方式：
 *   # 预览模式
 *   java GitPushSkill --projectPath="D:\项目" --mode=preview
 *   <p>
 *   # 执行模式
 *   java GitPushSkill --projectPath="D:\项目" --mode=execute --message="feat: 新增登录模块"
 * <p>
 * 适配 Windows 系统，通过 cmd.exe /c 执行 Git 命令。
 * 提交时使用 git -c i18n.commitEncoding=UTF-8 确保中文不出现乱码。
 */
public class GitPushSkill {

    // ============================
    //  入口方法
    // ============================

    /**
     * 程序入口
     * <p>
     * 支持的参数：
     *   --projectPath=<路径>   目标 Git 项目根目录（必填）
     *   --mode=<模式>          运行模式：preview 或 execute（必填）
     *   --message=<提交信息>   提交信息，execute 模式必填
     *   --test                运行环境检测模式
     */
    public static void main(String[] args) {
        // ---- 检测是否运行测试模式 ----
        for (String arg : args) {
            if (arg.equals("--test")) {
                testEnvironment();
                return;
            }
        }

        // ---- 解析参数 ----
        String projectPath = null;
        String mode = null;
        String commitMessage = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if (arg.startsWith("--projectPath=")) {
                projectPath = arg.substring("--projectPath=".length());
            } else if (arg.startsWith("--mode=")) {
                mode = arg.substring("--mode=".length());
            } else if (arg.startsWith("--message=")) {
                commitMessage = arg.substring("--message=".length());
            } else if (arg.equals("-m") || arg.equals("--message")) {
                if (i + 1 < args.length) {
                    commitMessage = args[++i];
                }
            }
        }

        // ---- 校验 --projectPath 必填 ----
        if (projectPath == null || projectPath.trim().isEmpty()) {
            System.err.println("[错误] 缺少必填参数 --projectPath，请指定项目路径。");
            System.err.println("  用法：java GitPushSkill --projectPath=\"<项目路径>\" --mode=preview|execute [--message=\"<提交信息>\"]");
            System.exit(1);
        }

        // ---- 校验 --mode 必填 ----
        if (mode == null || mode.trim().isEmpty()) {
            System.err.println("[错误] 缺少必填参数 --mode，请指定运行模式（preview 或 execute）。");
            System.exit(1);
        }
        mode = mode.trim().toLowerCase();
        if (!mode.equals("preview") && !mode.equals("execute")) {
            System.err.println("[错误] --mode 参数值无效，仅支持 preview 或 execute，当前值：" + mode);
            System.exit(1);
        }

        // ---- execute 模式校验 --message ----
        if (mode.equals("execute") && (commitMessage == null || commitMessage.trim().isEmpty())) {
            System.err.println("[错误] execute 模式缺少必填参数 --message，请指定提交信息。");
            System.err.println("  示例：java GitPushSkill --projectPath=\"D:\\MyProject\" --mode=execute -m \"feat: 新增登录功能\"");
            System.exit(1);
        }

        // ---- 验证项目路径 ----
        Path path = Paths.get(projectPath).normalize();
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            System.err.println("[错误] 项目路径不存在或不是目录：" + path.toAbsolutePath());
            System.exit(1);
        }
        String absPath = path.toAbsolutePath().toString();

        // ---- 根据模式执行 ----
        GitPushSkill skill = new GitPushSkill();
        boolean success;

        if (mode.equals("preview")) {
            success = skill.runPreview(absPath);
        } else {
            success = skill.runExecute(absPath, commitMessage.trim());
        }

        System.exit(success ? 0 : 1);
    }

    // ============================
    //  预览模式
    // ============================

    /**
     * 预览模式：收集项目变更信息并输出纯文本格式报告
     * <p>
     * 收集内容：
     *   1. 变更文件列表（git status --porcelain）
     *   2. 远程仓库列表（git remote -v）
     *   3. 当前分支名称（git branch --show-current）
     * <p>
     * 不执行任何写操作，仅供展示给用户确认。
     *
     * @param projectPath 项目根目录绝对路径
     * @return true 表示信息收集成功，false 表示失败
     */
    public boolean runPreview(String projectPath) {
        try {
            // ---- 获取变更文件列表 ----
            List<String> changedFiles = getChangedFiles(projectPath);
            if (changedFiles == null) {
                System.err.println("[错误] 获取变更文件列表失败。");
                return false;
            }

            // ---- 获取远程仓库列表 ----
            List<RemoteInfo> remotes = getRemoteList(projectPath);
            if (remotes == null) {
                System.err.println("[错误] 获取远程仓库列表失败。");
                return false;
            }

            // ---- 获取当前分支 ----
            String branch = getCurrentBranch(projectPath);
            if (branch == null) {
                branch = "未知";
            }

            // ---- 输出预览信息（纯文本，无颜色转义，方便 Claude 直接读取） ----
            System.out.println("=== 变更文件列表 ===");
            if (changedFiles.isEmpty()) {
                System.out.println("  （无变更）");
            } else {
                for (String file : changedFiles) {
                    // 取前两个字符作为状态标记，如 "M "、"A "、"??"、"R "
                    String status = file.length() > 2 ? file.substring(0, 2).trim() : file;
                    String filePath = file.length() > 3 ? file.substring(2).trim() : file;
                    System.out.println("  " + status + "  " + filePath);
                }
            }

            System.out.println();

            System.out.println("=== 远程仓库列表 ===");
            if (remotes.isEmpty()) {
                System.out.println("  （未配置远程仓库）");
            } else {
                for (RemoteInfo remote : remotes) {
                    System.out.println("  " + remote.name + "  : " + remote.url);
                }
            }

            System.out.println();

            System.out.println("=== 推送信息 ===");
            System.out.println("  当前分支 : " + branch);
            System.out.println("  变更文件数 : " + changedFiles.size());
            System.out.println("  远程仓库数 : " + remotes.size());

            return true;

        } catch (Exception e) {
            System.err.println("[异常] 预览模式执行失败：" + e.getMessage());
            return false;
        }
    }

    // ============================
    //  执行模式
    // ============================

    /**
     * 执行模式：完整执行 add → commit → push 流程
     * <p>
     * 流程：
     *   1. 检测变更文件
     *   2. git add . 暂存所有变更
     *   3. git commit -m "提交信息" 提交变更
     *   4. 获取所有远程仓库列表
     *   5. 逐个 git push 到每个远程仓库，失败不中断
     *   6. 输出每个仓库的推送结果汇总
     * <p>
     * 不包含任何交互式确认，所有确认由 Claude 在对话中完成。
     *
     * @param projectPath   项目根目录绝对路径
     * @param commitMessage 提交信息
     * @return true 表示 commit 成功且至少有一个远程推送成功，false 表示全部失败
     */
    public boolean runExecute(String projectPath, String commitMessage) {
        // ---- 步骤1：检测变更 ----
        System.out.println("[步骤 1/4] 检测项目变更 ...");
        List<String> changedFiles = getChangedFiles(projectPath);
        if (changedFiles == null) {
            System.err.println("[错误] 检测变更失败。");
            return false;
        }
        if (changedFiles.isEmpty()) {
            System.out.println("[提示] 项目没有检测到任何变更，无需提交。");
            return false;
        }
        System.out.println("  共 " + changedFiles.size() + " 个文件变更");

        // ---- 步骤2：暂存变更 ----
        System.out.println("[步骤 2/4] 暂存变更 (git add .) ...");
        if (!stageChanges(projectPath)) {
            System.err.println("[错误] 暂存变更失败。");
            return false;
        }
        System.out.println("  OK");

        // ---- 步骤3：提交变更 ----
        System.out.println("[步骤 3/4] 提交变更 ...");
        if (!commitWithMessage(projectPath, commitMessage)) {
            System.err.println("[错误] 提交变更失败。");
            return false;
        }
        System.out.println("  OK");

        // ---- 步骤4：推送所有远程仓库 ----
        System.out.println("[步骤 4/4] 推送到远程仓库 ...");
        List<RemoteInfo> remotes = getRemoteList(projectPath);
        if (remotes == null || remotes.isEmpty()) {
            System.err.println("[警告] 未检测到远程仓库，跳过推送。");
            return false;
        }

        // 逐个推送每个远程仓库，收集结果
        List<PushResult> results = new ArrayList<>();
        for (RemoteInfo remote : remotes) {
            PushResult result = pushToRemote(projectPath, remote.name);
            results.add(result);
        }

        // ---- 输出推送结果汇总 ----
        System.out.println();
        System.out.println("=== 推送结果 ===");
        int successCount = 0;
        for (PushResult result : results) {
            String icon = result.success ? "✅" : "❌";
            System.out.println("  " + result.remoteName + "  : " + icon + " " + result.message);
            if (result.success) {
                successCount++;
            }
        }

        return successCount > 0;
    }

    // ============================
    //  远端信息与推送结果类型
    // ============================

    /** 远程仓库信息 */
    private static class RemoteInfo {
        String name;  // 远程名称，如 origin
        String url;   // 远程地址，如 https://github.com/user/repo.git

        RemoteInfo(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    /** 推送结果 */
    private static class PushResult {
        String remoteName; // 远程名称
        boolean success;   // 是否成功
        String message;    // 结果描述

        PushResult(String remoteName, boolean success, String message) {
            this.remoteName = remoteName;
            this.success = success;
            this.message = message;
        }
    }

    // ============================
    //  Git 操作：检测变更
    // ============================

    /**
     * 检测项目中的变更文件列表
     * <p>
     * 执行 git status --porcelain，解析输出结果，
     * 提取所有已修改 / 新增 / 删除 / 重命名的文件行。
     * 每行保留前两位状态标记，如 " M src/A.java"、"?? README.md"。
     *
     * @param projectPath 项目根目录路径
     * @return 变更文件状态行列表，失败返回 null
     */
    public List<String> getChangedFiles(String projectPath) {
        try {
            String output = runCommand(projectPath, "git status --porcelain");
            if (output == null) {
                return null;
            }

            List<String> changedFiles = new ArrayList<>();
            String[] lines = output.split("\n");
            for (String line : lines) {
                if (!line.trim().isEmpty()) {
                    changedFiles.add(line);
                }
            }
            return changedFiles;

        } catch (Exception e) {
            System.err.println("[异常] 检测变更时发生异常：" + e.getMessage());
            return null;
        }
    }

    // ============================
    //  Git 操作：获取远程仓库列表
    // ============================

    /**
     * 获取项目配置的所有远程仓库
     * <p>
     * 执行 git remote -v，解析输出，
     * 提取每个远程仓库的名称和第一个 URL（fetch URL）。
     * git remote -v 输出格式：
     *   origin  https://github.com/user/repo.git (fetch)
     *   origin  https://github.com/user/repo.git (push)
     *
     * @param projectPath 项目根目录路径
     * @return 远程仓库信息列表，失败返回 null
     */
    public List<RemoteInfo> getRemoteList(String projectPath) {
        try {
            String output = runCommand(projectPath, "git remote -v");
            if (output == null) {
                return null;
            }

            // 解析输出，取每个远程的 fetch 地址（去重）
            // 格式："origin\thttps://github.com/user/repo.git (fetch)"
            Map<String, String> remoteMap = new LinkedHashMap<>();
            String[] lines = output.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // 按空格或制表符切分
                // 格式：origin\thttps://xxx.git (fetch)
                // 也可能：origin\thttps://xxx.git (push)
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    String name = parts[0];
                    String url = parts[1];
                    // 只取 fetch 地址（当行末包含 (fetch) 时才记录）
                    // 如果行末是 (push) 则跳过，避免重复
                    if (line.contains("(fetch)")) {
                        remoteMap.put(name, url);
                    }
                }
            }

            List<RemoteInfo> remotes = new ArrayList<>();
            for (Map.Entry<String, String> entry : remoteMap.entrySet()) {
                remotes.add(new RemoteInfo(entry.getKey(), entry.getValue()));
            }
            return remotes;

        } catch (Exception e) {
            System.err.println("[异常] 获取远程仓库列表时发生异常：" + e.getMessage());
            return null;
        }
    }

    // ============================
    //  Git 操作：获取当前分支
    // ============================

    /**
     * 获取当前所在分支名称
     * <p>
     * 执行 git branch --show-current（Git 2.22+ 支持）。
     *
     * @param projectPath 项目根目录路径
     * @return 当前分支名称，失败返回 null
     */
    public String getCurrentBranch(String projectPath) {
        try {
            String output = runCommand(projectPath, "git branch --show-current");
            if (output == null) {
                return null;
            }
            return output.trim();
        } catch (Exception e) {
            System.err.println("[异常] 获取当前分支时发生异常：" + e.getMessage());
            return null;
        }
    }

    // ============================
    //  Git 操作：暂存变更
    // ============================

    /**
     * 执行 git add . 暂存所有变更
     *
     * @param projectPath 项目根目录路径
     * @return true 表示暂存成功，false 表示失败
     */
    public boolean stageChanges(String projectPath) {
        try {
            String output = runCommand(projectPath, "git add .");
            return output != null;
        } catch (Exception e) {
            System.err.println("[异常] 暂存变更时发生异常：" + e.getMessage());
            return false;
        }
    }

    // ============================
    //  Git 操作：提交变更
    // ============================

    /**
     * 执行 git commit -m "提交信息" 提交已暂存的变更
     * <p>
     * 使用 git -c i18n.commitEncoding=UTF-8 确保中文编码正确，
     * 使用 git -c i18n.logOutputEncoding=UTF-8 确保日志输出编码正确。
     * 提交信息通过 -m 参数直接传入，不使用临时文件。
     * <p>
     * 如果提交信息中包含双引号，会自动转义为 \" 防止 Shell 解析错误。
     *
     * @param projectPath   项目根目录路径
     * @param commitMessage 提交信息
     * @return true 表示提交成功，false 表示失败
     */
    public boolean commitWithMessage(String projectPath, String commitMessage) {
        try {
            // ---- 转义提交信息中的双引号 ----
            // 在 cmd.exe /c 环境中，双引号需要用 \" 转义
            String escapedMessage = commitMessage.replace("\"", "\\\"");

            // ---- 构建 git commit 命令 ----
            // git -c i18n.commitEncoding=UTF-8 commit -m "转义后的提交信息"
            // -c i18n.commitEncoding=UTF-8 确保 Git 将提交信息以 UTF-8 编码存储
            // -c i18n.logOutputEncoding=UTF-8 确保后续 git log 以 UTF-8 输出
            String command = "git -c i18n.commitEncoding=UTF-8 -c i18n.logOutputEncoding=UTF-8 commit -m \""
                    + escapedMessage + "\"";

            System.out.println("  命令：git commit -m \"...\" (UTF-8)");
            String output = runCommand(projectPath, command);

            if (output == null) {
                return false;
            }

            // 输出 git commit 的结果（去除多余的换行）
            String trimmed = output.trim();
            if (!trimmed.isEmpty()) {
                System.out.println("  " + trimmed.replace("\n", "\n  "));
            }
            return true;

        } catch (Exception e) {
            System.err.println("[异常] 提交变更时发生异常：" + e.getMessage());
            return false;
        }
    }

    // ============================
    //  Git 操作：推送到单个远程仓库
    // ============================

    /**
     * 将当前分支推送到指定的远程仓库
     * <p>
     * 执行 git push <remote-name> HEAD:<当前分支名>
     * 如果推送失败，只记录错误结果，不抛出异常，不影响其他仓库的推送。
     *
     * @param projectPath 项目根目录路径
     * @param remoteName  远程仓库名称（如 origin、company）
     * @return 推送结果对象（包含成功/失败状态和描述信息）
     */
    public PushResult pushToRemote(String projectPath, String remoteName) {
        try {
            // 获取当前分支名，用于构造推送目标
            String branch = getCurrentBranch(projectPath);
            if (branch == null || branch.isEmpty()) {
                return new PushResult(remoteName, false, "获取分支名失败");
            }

            // 执行 git push <remote> HEAD:<branch>
            // 使用当前分支名作为目标，确保推送到远端同名的分支
            String command = "git push " + remoteName + " HEAD:" + branch;
            String output = runCommand(projectPath, command);

            if (output != null) {
                // 推送成功：解析输出中的关键信息
                // 输出格式类似：To https://github.com/user/repo.git
                //               <commit-hash>..<new-hash> master -> master
                String summary = output.trim().replace("\n", " | ");
                return new PushResult(remoteName, true, summary);
            } else {
                return new PushResult(remoteName, false, "推送失败 - 请检查网络或权限");
            }

        } catch (Exception e) {
            return new PushResult(remoteName, false, "推送失败 - " + e.getMessage());
        }
    }

    // ============================
    //  通用命令执行工具
    // ============================

    /**
     * 需要继承到子进程的代理环境变量名称列表
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
     *   3. 继承代理环境变量（HTTP_PROXY / HTTPS_PROXY 等）
     *   4. 读取标准输出和错误输出
     *   5. 等待进程结束，检查退出码
     *
     * @param workDir 工作目录（git 项目根目录）
     * @param command 要执行的命令字符串
     * @return 命令的标准输出，失败返回 null
     */
    public String runCommand(String workDir, String command) {
        try {
            // ---- 1. 构建命令 ----
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
            ProcessBuilder pb = new ProcessBuilder(shell, shellFlag, command);
            pb.directory(new File(workDir));
            pb.redirectErrorStream(true);

            // ---- 3. 继承代理环境变量 ----
            Map<String, String> env = pb.environment();
            Map<String, String> systemEnv = System.getenv();
            for (String varName : PROXY_ENV_VARS) {
                String value = systemEnv.get(varName);
                if (value != null && !value.isEmpty()) {
                    env.put(varName, value);
                }
            }

            // ---- 4. 启动进程 ----
            Process process = pb.start();

            // ---- 5. 读取输出（UTF-8） ----
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            // ---- 6. 等待进程结束 ----
            int exitCode = process.waitFor();

            // ---- 7. 检查退出码 ----
            if (exitCode == 0) {
                String result = output.toString();
                while (result.endsWith("\n")) {
                    result = result.substring(0, result.length() - 1);
                }
                return result;
            } else {
                String errorOutput = output.toString().trim();
                if (!errorOutput.isEmpty()) {
                    System.err.println("  [命令执行失败] 退出码: " + exitCode);
                    System.err.println("  " + errorOutput);
                }
                return null;
            }

        } catch (IOException e) {
            System.err.println("[IO异常] 执行命令时发生 IO 错误：" + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            System.err.println("[中断异常] 命令执行被中断：" + e.getMessage());
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            System.err.println("[异常] 执行命令时发生异常：" + e.getMessage());
            return null;
        }
    }

    // ============================
    //  系统判断工具
    // ============================

    /**
     * 判断当前操作系统是否为 Windows 系列
     *
     * @return true 表示当前系统为 Windows
     */
    public boolean isWindows() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
        return osName.startsWith("windows");
    }

    // ============================
    //  测试入口
    // ============================

    /**
     * 运行环境检测，确认 Git 命令和代理变量是否可用
     */
    public static void testEnvironment() {
        GitPushSkill skill = new GitPushSkill();

        System.out.println("===== GitPushSkill 环境检测 =====");
        System.out.println("  操作系统: " + System.getProperty("os.name"));
        System.out.println("  是否 Windows: " + skill.isWindows());
        System.out.println("  当前工作目录: " + System.getProperty("user.dir"));
        System.out.println();

        System.out.println("正在检测 Git 命令 ...");
        String version = skill.runCommand(System.getProperty("user.dir"), "git --version");
        if (version != null) {
            System.out.println("  ✅ " + version.trim());
        } else {
            System.err.println("  ❌ Git 不可用，请确保 Git 已安装并添加到 PATH");
        }

        System.out.println();
        System.out.println("正在检测代理环境变量 ...");
        Map<String, String> sysEnv = System.getenv();
        boolean foundProxy = false;
        for (String proxyVar : new String[]{"HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy"}) {
            String val = sysEnv.get(proxyVar);
            if (val != null && !val.isEmpty()) {
                System.out.println("  ✅ " + proxyVar + " = " + val);
                foundProxy = true;
            }
        }
        if (!foundProxy) {
            System.out.println("  ⚠ 未检测到代理环境变量。如需代理请设置：set HTTPS_PROXY=http://127.0.0.1:7890");
        }
    }
}
