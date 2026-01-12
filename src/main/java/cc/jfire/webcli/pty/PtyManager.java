package cc.jfire.webcli.pty;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class PtyManager {
    private final ConcurrentHashMap<String, PtyInstance> instances = new ConcurrentHashMap<>();
    private final String[] defaultCommand;

    public PtyManager() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            this.defaultCommand = new String[]{"cmd.exe"};
        } else {
            this.defaultCommand = new String[]{"/bin/bash", "-l"};
        }
    }

    public PtyInstance create() throws IOException {
        return create(defaultCommand);
    }

    public PtyInstance create(String[] command) throws IOException {
        PtyInstance instance = new PtyInstance(command);
        instances.put(instance.getId(), instance);
        log.info("创建 PTY 实例: {}", instance.getId());
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
}
