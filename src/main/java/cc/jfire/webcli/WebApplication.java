package cc.jfire.webcli;

import cc.jfire.baseutil.RuntimeJVM;
import cc.jfire.boot.http.HttpAppServer;
import cc.jfire.dson.Dson;
import cc.jfire.jfire.core.ApplicationContext;
import cc.jfire.jfire.core.prepare.annotation.EnableAutoConfiguration;
import cc.jfire.jfire.core.prepare.annotation.configuration.Configuration;
import cc.jfire.jnet.common.util.ChannelConfig;
import cc.jfire.jnet.server.AioServer;
import cc.jfire.webcli.config.WebCliConfig;
import cc.jfire.webcli.pty.PtyManager;
import cc.jfire.webcli.web.WebSocketHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@EnableAutoConfiguration
@Configuration
public class WebApplication
{
    private static final int          DEFAULT_PORT    = 18080;
    private static final String       CONFIG_FILE     = "webcli.json";
    private final        int          port;
    private final        PtyManager   ptyManager;
    private final        WebCliConfig config;
    private              AioServer    server;

    public WebApplication(int port, WebCliConfig config)
    {
        this.port       = port;
        this.config     = config;
        this.ptyManager = new PtyManager(config);
    }

    public void start()
    {
        WebSocketHandler   wsHandler = new WebSocketHandler(ptyManager);
        ApplicationContext context   = ApplicationContext.boot(WebApplication.class);
        ChannelConfig channelConfig = new ChannelConfig()
                .setIp("127.0.0.1")
                .setPort(port)
                .setChannelGroup(ChannelConfig.DEFAULT_CHANNEL_GROUP);
        HttpAppServer.StartParam startParam = new HttpAppServer.StartParam()
                .setChannelConfig(channelConfig)
                .setContext(context)
                .setWebDir("web")
                .setWebSocketProcessor(wsHandler);
        server = HttpAppServer.start(startParam);
        log.info("WebCli 服务已启动，监听地址: {}:{}", channelConfig.getIp(), port);
        log.info("请访问: http://127.0.0.1:{}/", port);
    }

    public void shutdown()
    {
        if (server != null)
        {
            server.shutdown();
        }
        ptyManager.shutdown();
        log.info("WebCli 服务已关闭");
    }

    public static void main(String[] args)
    {
        RuntimeJVM.registerMainClass(WebApplication.class.getName());
        int          port   = DEFAULT_PORT;
        WebCliConfig config = WebCliConfig.defaultConfig();
        if (args.length > 0)
        {
            try
            {
                port = Integer.parseInt(args[0]);
            }
            catch (NumberFormatException e)
            {
                log.warn("无效的端口参数，使用默认端口: {}", DEFAULT_PORT);
            }
        }
        WebApplication app = new WebApplication(port, config);
        Runtime.getRuntime().addShutdownHook(new Thread(app::shutdown));
        app.start();
    }


}
