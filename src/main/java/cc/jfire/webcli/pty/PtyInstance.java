package cc.jfire.webcli.pty;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private volatile boolean remoteViewable = false;
    private Thread readThread;

    public PtyInstance(String[] command, String name, String workingDirectory) throws IOException {
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
                .setInitialColumns(120)
                .setInitialRows(40)
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
        this.remoteViewable = remoteViewable;
    }

    public void startReading() {
        readThread = Thread.startVirtualThread(() -> {
            try {
                byte[] buffer = new byte[1024];
                int len;
                while (running && (len = inputStream.read(buffer)) != -1) {
                    String output = new String(buffer, 0, len, StandardCharsets.UTF_8);
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

    public void write(String input) throws IOException {
        outputStream.write(input.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    public void resize(int cols, int rows) {
        process.setWinSize(new WinSize(cols, rows));
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
