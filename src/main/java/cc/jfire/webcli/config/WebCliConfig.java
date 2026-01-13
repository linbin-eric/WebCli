package cc.jfire.webcli.config;

import cc.jfire.baseutil.Resource;
import cc.jfire.jfire.core.inject.notated.PropertyRead;
import lombok.Data;

@Data
@Resource
public class WebCliConfig
{
    @PropertyRead("webcli.shell.cmd")
    private String   shell;
    @PropertyRead("webcli.shell.args")
    private String[] shellArgs;
    @PropertyRead("webcli.shell.directory")
    private String   workingDirectory;
    // P1 阶段新增配置
    @PropertyRead("webcli.mode")
    private String   mode          = "all";      // 运行模式: agent、server 或 all
    @PropertyRead("webcli.token")
    private String   token         = "123456";               // 预共享密钥(PSK)，不在网络中明文传输
    @PropertyRead("webcli.serverHost")
    private String   serverHost    = "localhost";          // 远端服务器地址 (Agent 模式)
    @PropertyRead("webcli.serverPort")
    private int      serverPort    = 9090;   // 远端服务器 TCP 端口 (Agent 模式)
    @PropertyRead("webcli.tcpPort")
    private int      tcpPort       = 9090;      // TCP 监听端口 (Server 模式)
    @PropertyRead("webcli.webPort")
    private int      webPort       = 18080;     // 本地 Web 服务端口
    @PropertyRead("webcli.remoteWebPort")
    private int      remoteWebPort = 18081; // 远端 Web 服务端口 (Server/All 模式)

    public String[] getShellCommand()
    {
        if (shell == null || shell.isBlank())
        {
            return null;
        }
        if (shellArgs == null || shellArgs.length == 0)
        {
            return new String[]{shell};
        }
        String[] command = new String[shellArgs.length + 1];
        command[0] = shell;
        System.arraycopy(shellArgs, 0, command, 1, shellArgs.length);
        return command;
    }

    public boolean isServerMode()
    {
        return "server".equalsIgnoreCase(mode);
    }

    public boolean isAgentMode()
    {
        return "agent".equalsIgnoreCase(mode) || mode == null;
    }

    public boolean isAllMode()
    {
        return "all".equalsIgnoreCase(mode);
    }

    public static WebCliConfig defaultConfig()
    {
        WebCliConfig config = new WebCliConfig();
        config.setWorkingDirectory(System.getProperty("user.home"));
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win"))
        {
            config.setShell("cmd.exe");
            config.setShellArgs(null);
        }
        else if (os.contains("mac"))
        {
            config.setShell("/bin/zsh");
            config.setShellArgs(new String[]{"-i", "-l"});
        }
        else
        {
            config.setShell("/bin/bash");
            config.setShellArgs(new String[]{"-i", "-l"});
        }
        return config;
    }
}
