package cc.jfire.webcli.pty;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@Slf4j
@Getter
public class PtyInstance {
    private static final int MAX_HISTORY_SIZE = 100 * 1024; // 100KB 历史缓冲区
    private final String id;
    private volatile String name;
    private final PtyProcess process;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final StringBuilder outputHistory = new StringBuilder();
    private volatile boolean running = true;
    private final List<Consumer<String>> outputListeners = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<String, Boolean>> visibilityChangeListeners = new CopyOnWriteArrayList<>();
    private volatile boolean remoteViewable = false;
    private Thread readThread;

    // 固定的 PTY 尺寸，足够大以适应大多数屏幕
    private static final int FIXED_COLS = 200;
    private static final int FIXED_ROWS = 100;

    public PtyInstance(String[] command, String name, String workingDirectory) throws IOException {
        this(command, name, workingDirectory, FIXED_COLS, FIXED_ROWS);
    }

    public PtyInstance(String[] command, String name, String workingDirectory, int cols, int rows) throws IOException {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        env.put("LANG", "en_US.UTF-8");
        env.put("LC_ALL", "en_US.UTF-8");
        // 禁用 zsh 自动建议等功能
        env.put("DISABLE_AUTO_UPDATE", "true");
        env.put("ZSH_AUTOSUGGEST_HIGHLIGHT_STYLE", "");
        env.remove("ZSH_AUTOSUGGEST_STRATEGY");

        this.process = new PtyProcessBuilder()
                .setCommand(command)
                .setEnvironment(env)
                .setDirectory(workingDirectory)
                .setConsole(false)
                .setInitialColumns(cols)
                .setInitialRows(rows)
                .start();

        this.inputStream = process.getInputStream();
        this.outputStream = process.getOutputStream();
    }

    public void addOutputListener(Consumer<String> listener) {
        outputListeners.add(listener);
    }

    public void removeOutputListener(Consumer<String> listener) {
        outputListeners.remove(listener);
    }

    public void setRemoteViewable(boolean remoteViewable) {
        boolean oldValue = this.remoteViewable;
        this.remoteViewable = remoteViewable;
        if (oldValue != remoteViewable) {
            for (BiConsumer<String, Boolean> listener : visibilityChangeListeners) {
                try {
                    listener.accept(this.id, remoteViewable);
                } catch (Exception e) {
                    log.error("可见性变更监听器处理失败", e);
                }
            }
        }
    }

    public void addVisibilityChangeListener(BiConsumer<String, Boolean> listener) {
        visibilityChangeListeners.add(listener);
    }

    public void removeVisibilityChangeListener(BiConsumer<String, Boolean> listener) {
        visibilityChangeListeners.remove(listener);
    }

    public void clearOutputListeners() {
        outputListeners.clear();
    }

    public void startReading() {
        readThread = Thread.startVirtualThread(() -> {
            try {
                InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                char[] buffer = new char[1024];
                int len;
                while (running && (len = reader.read(buffer)) != -1) {
                    String output = new String(buffer, 0, len);
                    log.debug("PTY 输出: {}", output.length() > 100 ? output.substring(0, 100) + "..." : output);
                    // 保存到历史缓冲区
                    synchronized (outputHistory) {
                        outputHistory.append(output);
                        // 如果超过最大大小，截断前面的内容
                        if (outputHistory.length() > MAX_HISTORY_SIZE) {
                            outputHistory.delete(0, outputHistory.length() - MAX_HISTORY_SIZE);
                        }
                    }
                    // 通知所有监听器
                    for (Consumer<String> listener : outputListeners) {
                        try {
                            listener.accept(output);
                        } catch (Exception e) {
                            log.error("输出监听器处理失败", e);
                        }
                    }
                }
            } catch (IOException e) {
                if (running) {
                    log.error("读取 PTY 输出失败", e);
                }
            }
        });
    }

    public String getOutputHistory() {
        synchronized (outputHistory) {
            return outputHistory.toString();
        }
    }

    public synchronized void write(String input) throws IOException {
        outputStream.write(input.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    public void resize(int cols, int rows) {
        int safeCols = Math.max(1, cols);
        int safeRows = Math.max(1, rows);
        try {
            process.setWinSize(new WinSize(safeCols, safeRows));
            log.debug("PTY resize: {}x{}", safeCols, safeRows);
        } catch (Exception e) {
            log.warn("PTY resize 失败: {}x{}", safeCols, safeRows, e);
        }
    }

    public void close() {
        running = false;
        try {
            outputStream.close();
            inputStream.close();
        } catch (IOException e) {
            log.error("关闭 PTY 流失败", e);
        }
        process.destroy();
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public void setName(String name) {
        this.name = name;
    }
}
