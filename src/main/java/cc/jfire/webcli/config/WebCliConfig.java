package cc.jfire.webcli.config;

import lombok.Data;

@Data
public class WebCliConfig
{
    private String   shell;
    private String[] shellArgs;

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

    public static WebCliConfig defaultConfig()
    {
        WebCliConfig config = new WebCliConfig();
        String       os     = System.getProperty("os.name").toLowerCase();
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
