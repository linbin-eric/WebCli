package cc.jfire.webcli;

import cc.jfire.baseutil.Resource;
import cc.jfire.baseutil.RuntimeJVM;
import cc.jfire.boot.http.HttpAppServer;
import cc.jfire.jfire.core.ApplicationContext;
import cc.jfire.jfire.core.AwareContextInited;
import cc.jfire.jfire.core.prepare.annotation.ComponentScan;
import cc.jfire.jfire.core.prepare.annotation.EnableAutoConfiguration;
import cc.jfire.jfire.core.prepare.annotation.PropertyPath;
import cc.jfire.jfire.core.prepare.annotation.configuration.Configuration;
import cc.jfire.jnet.common.coder.ValidatedLengthFrameDecoder;
import cc.jfire.jnet.common.coder.ValidatedLengthFrameEncoder;
import cc.jfire.jnet.common.util.ChannelConfig;
import cc.jfire.jnet.server.AioServer;
import cc.jfire.webcli.agent.AgentTcpClient;
import cc.jfire.webcli.config.WebCliConfig;
import cc.jfire.webcli.pty.PtyManager;
import cc.jfire.webcli.server.AgentManager;
import cc.jfire.webcli.server.LoginManager;
import cc.jfire.webcli.server.RemoteWebSocketHandler;
import cc.jfire.webcli.server.ServerTcpHandler;
import cc.jfire.webcli.web.WebSocketHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@EnableAutoConfiguration
@Configuration
@PropertyPath("classpath:application.yml")
@ComponentScan("cc.jfire.webcli")
public class WebApplication implements AwareContextInited
{
    /**
     * 协议魔法值，用于帧验证
     */
    private static final int            PROTOCOL_MAGIC = 0x57454243; // "WEBC" in hex
    @Resource
    private              WebCliConfig   config;
    @Resource
    private              PtyManager     ptyManager;
    @Resource
    private              AgentManager   agentManager;
    @Resource
    private              LoginManager   loginManager;
    private              AioServer      localWebServer;
    private              AioServer      remoteWebServer;
    private              AioServer      tcpServer;
    private              AgentTcpClient agentTcpClient;

    public void start(ApplicationContext context)
    {
        if (config.isAllMode())
        {
            startAllMode(context);
        }
        else if (config.isServerMode())
        {
            startServerMode(context);
        }
        else
        {
            startLocalMode(context);
        }
    }

    private void startLocalMode(ApplicationContext context)
    {
        // 启动本地 Web 服务
        WebSocketHandler         wsHandler     = new WebSocketHandler(ptyManager);
        ChannelConfig            channelConfig = new ChannelConfig().setIp("127.0.0.1").setPort(config.getWebPort()).setChannelGroup(ChannelConfig.DEFAULT_CHANNEL_GROUP);
        HttpAppServer.StartParam startParam    = new HttpAppServer.StartParam().setChannelConfig(channelConfig).setContext(context).setWebDir("local").setWebSocketProcessor(wsHandler);
        localWebServer = HttpAppServer.start(startParam);
        log.info("本地 Web 服务已启动，监听地址: {}:{}", channelConfig.getIp(), config.getWebPort());
        log.info("请访问: http://127.0.0.1:{}/", config.getWebPort());
        // 如果配置了远端服务器，启动 TCP 客户端
        if (config.getServerHost() != null && !config.getServerHost().isBlank())
        {
            agentTcpClient = new AgentTcpClient(config, ptyManager);
            agentTcpClient.connect();
            log.info("正在连接远端服务器: {}:{}", config.getServerHost(), config.getServerPort());
        }
    }

    private void startServerMode(ApplicationContext context)
    {
        // 启动 TCP 服务端，接受 Agent 连接
        startTcpServer();
        // 启动远端 Web 服务
        startRemoteWebServer(context);
    }

    private void startAllMode(ApplicationContext context)
    {
        // 同时启动 Agent 和 Server
        log.info("启动 All 模式：同时运行 Agent 和 Server");
        startServerMode(context);
        startLocalMode(context);
    }

    private void startTcpServer()
    {
        ChannelConfig tcpConfig = new ChannelConfig().setIp("0.0.0.0").setPort(config.getTcpPort()).setChannelGroup(ChannelConfig.DEFAULT_CHANNEL_GROUP);
        tcpServer = AioServer.newAioServer(tcpConfig, pipeline -> {
            pipeline.addReadProcessor(new ValidatedLengthFrameDecoder(PROTOCOL_MAGIC, 1024 * 1024));
            pipeline.addReadProcessor(new ServerTcpHandler(config, agentManager));
            pipeline.addWriteProcessor(new ValidatedLengthFrameEncoder(PROTOCOL_MAGIC, pipeline.allocator()));
        });
        tcpServer.start();
        log.info("TCP 服务已启动，监听端口: {}", config.getTcpPort());
    }

    private void startRemoteWebServer(ApplicationContext context)
    {
        RemoteWebSocketHandler   wsHandler     = new RemoteWebSocketHandler(agentManager, loginManager);
        int                      remoteWebPort = config.getRemoteWebPort();
        ChannelConfig            webConfig     = new ChannelConfig().setIp("0.0.0.0").setPort(remoteWebPort).setChannelGroup(ChannelConfig.DEFAULT_CHANNEL_GROUP);
        HttpAppServer.StartParam startParam    = new HttpAppServer.StartParam().setChannelConfig(webConfig).setContext(context).setWebDir("remote").setWebSocketProcessor(wsHandler);
        remoteWebServer = HttpAppServer.start(startParam);
        log.info("远端 Web 服务已启动，监听端口: {}", remoteWebPort);
        log.info("请访问远端终端: http://0.0.0.0:{}/", remoteWebPort);
    }

    public void shutdown()
    {
        if (localWebServer != null)
        {
            localWebServer.shutdown();
        }
        if (remoteWebServer != null)
        {
            remoteWebServer.shutdown();
        }
        if (tcpServer != null)
        {
            tcpServer.shutdown();
        }
        if (ptyManager != null)
        {
            ptyManager.shutdown();
        }
        if (agentTcpClient != null)
        {
            agentTcpClient.shutdown();
        }
        log.info("WebCli 服务已关闭");
    }

    public static void main(String[] args)
    {
        RuntimeJVM.registerMainClass(WebApplication.class.getName());
        ApplicationContext context = ApplicationContext.boot(WebApplication.class);
        WebApplication     app     = context.getBean(WebApplication.class);
        Runtime.getRuntime().addShutdownHook(new Thread(app::shutdown));
    }

    @Override
    public void aware(ApplicationContext context)
    {
        start(context);
    }
}
