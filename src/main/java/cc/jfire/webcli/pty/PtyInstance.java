package cc.jfire.webcli.pty;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
@Getter
public class PtyInstance {
    private final String id;
    private final PtyProcess process;
    private final BufferedReader reader;
    private final OutputStreamWriter writer;
    private volatile boolean running = true;
    private Consumer<String> outputConsumer;
    private Thread readThread;

    public PtyInstance(String[] command) throws IOException {
        this.id = UUID.randomUUID().toString();
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");

        this.process = new PtyProcessBuilder()
                .setCommand(command)
                .setEnvironment(env)
                .start();

        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
    }

    public void setOutputConsumer(Consumer<String> consumer) {
        this.outputConsumer = consumer;
    }

    public void startReading() {
        readThread = Thread.startVirtualThread(() -> {
            try {
                char[] buffer = new char[1024];
                int len;
                while (running && (len = reader.read(buffer)) != -1) {
                    String output = new String(buffer, 0, len);
                    Consumer<String> consumer = outputConsumer;
                    if (consumer != null) {
                        consumer.accept(output);
                    }
                }
            } catch (IOException e) {
                if (running) {
                    log.error("读取 PTY 输出失败", e);
                }
            }
        });
    }

    public void write(String input) throws IOException {
        writer.write(input);
        writer.flush();
    }

    public void resize(int cols, int rows) {
        process.setWinSize(new WinSize(cols, rows));
    }

    public void close() {
        running = false;
        try {
            writer.close();
            reader.close();
        } catch (IOException e) {
            log.error("关闭 PTY 流失败", e);
        }
        process.destroy();
    }

    public boolean isAlive() {
        return process.isAlive();
    }
}
