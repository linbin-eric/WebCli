package cc.jfire.webcli.pty;

import cc.jfire.baseutil.PostConstruct;
import cc.jfire.baseutil.Resource;
import cc.jfire.webcli.config.WebCliConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
@Resource
public class PtyManager
{
    private final    ConcurrentHashMap<String, PtyInstance> instances = new ConcurrentHashMap<>();
    private          String[]                               defaultCommand;
    private          String                                 workingDirectory;
    private volatile Consumer<PtyInstance>                  onPtyCreated;

    @cc.jfire.baseutil.Resource
    private WebCliConfig config;

    @PostConstruct
    public void init()
    {
        this.defaultCommand = config.getShellCommand();
        this.workingDirectory = config.getWorkingDirectory();
        log.info("默认 Shell 命令: {}", String.join(" ", defaultCommand));
        log.info("默认工作目录: {}", workingDirectory);
    }

    public PtyInstance create(String name) throws IOException
    {
        // 使用固定大尺寸，忽略客户端尺寸参数
        return create(defaultCommand, name, 200, 100);
    }

    public PtyInstance create(String name, int cols, int rows) throws IOException
    {
        // 使用固定大尺寸，忽略客户端尺寸参数
        return create(defaultCommand, name, 200, 100);
    }

    public PtyInstance create(String[] command, String name) throws IOException
    {
        // 使用固定大尺寸
        return create(command, name, 200, 100);
    }

    public PtyInstance create(String[] command, String name, int cols, int rows) throws IOException
    {
        PtyInstance instance = new PtyInstance(command, name, workingDirectory, cols, rows);
        instances.put(instance.getId(), instance);
        log.info("创建 PTY 实例: {}, 名称: {}, 尺寸: {}x{}", instance.getId(), name, cols, rows);
        if (onPtyCreated != null)
        {
            onPtyCreated.accept(instance);
        }
        return instance;
    }

    public PtyInstance get(String id)
    {
        return instances.get(id);
    }

    public void remove(String id)
    {
        PtyInstance instance = instances.remove(id);
        if (instance != null)
        {
            instance.close();
            log.info("移除 PTY 实例: {}", id);
        }
    }

    public Collection<PtyInstance> getAll()
    {
        return instances.values();
    }

    public void shutdown()
    {
        for (PtyInstance instance : instances.values())
        {
            instance.close();
        }
        instances.clear();
    }

    public void setOnPtyCreated(Consumer<PtyInstance> onPtyCreated)
    {
        this.onPtyCreated = onPtyCreated;
    }
}
