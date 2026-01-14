package cc.jfire.webcli.pty;

import cc.jfire.webcli.config.WebCliConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
public class PtyManager {
    private final ConcurrentHashMap<String, PtyInstance> instances = new ConcurrentHashMap<>();
    private final String[] defaultCommand;
    private final String workingDirectory;
    private volatile Consumer<PtyInstance> onPtyCreated;

    public PtyManager(WebCliConfig config) {
        WebCliConfig defaultConfig = WebCliConfig.defaultConfig();
        String[] shellCommand = config.getShellCommand();
        if (shellCommand != null) {
            this.defaultCommand = shellCommand;
        } else {
            this.defaultCommand = defaultConfig.getShellCommand();
        }
        String workDir = config.getWorkingDirectory();
        if (workDir != null && !workDir.isBlank()) {
            this.workingDirectory = workDir;
        } else {
            this.workingDirectory = defaultConfig.getWorkingDirectory();
        }
        log.info("默认 Shell 命令: {}", String.join(" ", defaultCommand));
        log.info("默认工作目录: {}", workingDirectory);
    }

    public PtyInstance create(String name) throws IOException {
        return create(defaultCommand, name);
    }

    public PtyInstance create(String[] command, String name) throws IOException {
        PtyInstance instance = new PtyInstance(command, name, workingDirectory);
        instances.put(instance.getId(), instance);
        log.info("创建 PTY 实例: {}, 名称: {}", instance.getId(), name);
        if (onPtyCreated != null) {
            onPtyCreated.accept(instance);
        }
        return instance;
    }

    public PtyInstance get(String id) {
        return instances.get(id);
    }

    public void remove(String id) {
        PtyInstance instance = instances.remove(id);
        if (instance != null) {
            instance.close();
            log.info("移除 PTY 实例: {}", id);
        }
    }

    public Collection<PtyInstance> getAll() {
        return instances.values();
    }

    public void shutdown() {
        for (PtyInstance instance : instances.values()) {
            instance.close();
        }
        instances.clear();
    }

    public void setOnPtyCreated(Consumer<PtyInstance> onPtyCreated) {
        this.onPtyCreated = onPtyCreated;
    }
}
