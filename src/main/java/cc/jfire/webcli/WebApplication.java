package cc.jfire.webcli;

import cc.jfire.baseutil.RuntimeJVM;
import cc.jfire.boot.http.HttpAppServer;
import cc.jfire.jfire.core.ApplicationContext;
import cc.jfire.jfire.core.prepare.annotation.EnableAutoConfiguration;
import cc.jfire.jfire.core.prepare.annotation.configuration.Configuration;
import cc.jfire.jnet.server.AioServer;
import cc.jfire.webcli.pty.PtyManager;
import cc.jfire.webcli.web.WebSocketHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@EnableAutoConfiguration
@Configuration
public class WebApplication {
    private static final int DEFAULT_PORT = 18080;

    private final int port;
    private final PtyManager ptyManager;
    private AioServer server;

    public WebApplication(int port) {
        this.port = port;
        this.ptyManager = new PtyManager();
    }

    public void start() {
        WebSocketHandler wsHandler = new WebSocketHandler(ptyManager);

        ApplicationContext context = ApplicationContext.boot(WebApplication.class);
        server = HttpAppServer.start(port, context, "web", null, wsHandler);

        log.info("WebCli 服务已启动，端口: {}", port);
        log.info("请访问: http://127.0.0.1:{}/", port);
    }

    public void shutdown() {
        if (server != null) {
            server.shutdown();
        }
        ptyManager.shutdown();
        log.info("WebCli 服务已关闭");
    }

    public static void main(String[] args) {
        RuntimeJVM.registerMainClass(WebApplication.class.getName());

        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                log.warn("无效的端口参数，使用默认端口: {}", DEFAULT_PORT);
            }
        }

        WebApplication app = new WebApplication(port);

        Runtime.getRuntime().addShutdownHook(new Thread(app::shutdown));

        app.start();
    }
}
