package cc.jfire.webcli.util;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Objects;

public final class AgentIdUtil
{
    private static final int MAX_LEN = 64;

    private AgentIdUtil()
    {
    }

    public static String defaultAgentId()
    {
        String fromEnv = firstNonBlank(
                System.getenv("WEBCLI_AGENT_ID"),
                System.getenv("HOSTNAME"),
                System.getenv("COMPUTERNAME")
        );
        if (fromEnv != null)
        {
            return sanitize(fromEnv);
        }

        try
        {
            String hostName = InetAddress.getLocalHost().getHostName();
            if (hostName != null && !hostName.isBlank())
            {
                return sanitize(hostName);
            }
        }
        catch (Exception ignored)
        {
        }

        String user = System.getProperty("user.name");
        if (user != null && !user.isBlank())
        {
            return sanitize(user);
        }
        return "agent";
    }

    /**
     * 将输入规整为安全的 agentId：
     * - 仅保留 [a-z0-9_-]，其他字符替换为 '-'
     * - 去除首尾 '-'
     * - 长度上限 64
     * - 避免 ':' / '|' 等分隔符（会被替换为 '-'）
     */
    public static String sanitize(String input)
    {
        if (input == null)
        {
            return null;
        }
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty())
        {
            return "";
        }

        StringBuilder out = new StringBuilder(Math.min(s.length(), MAX_LEN));
        for (int i = 0; i < s.length() && out.length() < MAX_LEN; i++)
        {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            out.append(ok ? c : '-');
        }

        // 去掉首尾 '-'
        int start = 0;
        int end = out.length();
        while (start < end && out.charAt(start) == '-')
        {
            start++;
        }
        while (end > start && out.charAt(end - 1) == '-')
        {
            end--;
        }

        String result = out.substring(start, end);
        return result.isEmpty() ? "agent" : result;
    }

    /**
     * index 从 1 开始：1 -> base，2 -> base-2，3 -> base-3
     */
    public static String withSuffix(String base, int index)
    {
        String safeBase = sanitize(Objects.requireNonNullElse(base, "agent"));
        if (index <= 1)
        {
            return safeBase;
        }
        return safeBase + "-" + index;
    }

    private static String firstNonBlank(String... values)
    {
        if (values == null)
        {
            return null;
        }
        for (String v : values)
        {
            if (v != null && !v.isBlank())
            {
                return v;
            }
        }
        return null;
    }
}

