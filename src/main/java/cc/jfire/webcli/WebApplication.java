package cc.jfire.webcli;

import cc.jfire.baseutil.RuntimeJVM;
import cc.jfire.baseutil.Resource;
import cc.jfire.boot.http.HttpAppServer;
import cc.jfire.jfire.core.ApplicationContext;
import cc.jfire.jfire.core.prepare.annotation.ComponentScan;
import cc.jfire.jfire.core.prepare.annotation.EnableAutoConfiguration;
import cc.jfire.jfire.core.prepare.annotation.configuration.Configuration;
import cc.jfire.jfire.core.prepare.annotation.PropertyPath;
import cc.jfire.jnet.common.coder.TotalLengthFieldBasedFrameDecoder;
import cc.jfire.jnet.common.processor.LengthEncoder;
import cc.jfire.jnet.common.util.ChannelConfig;
import cc.jfire.jnet.server.AioServer;
import cc.jfire.webcli.agent.AgentTcpClient;
import cc.jfire.webcli.config.WebCliConfig;
import cc.jfire.webcli.pty.PtyManager;
import cc.jfire.webcli.server.AgentManager;
import cc.jfire.webcli.server.RemoteWebSocketHandler;
import cc.jfire.webcli.server.ServerTcpHandler;
import cc.jfire.webcli.web.WebSocketHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@EnableAutoConfiguration
@Configuration
@PropertyPath("classpath:application.yml")
@ComponentScan("cc.jfire.webcli")
public class WebApplication
{
    @Resource
    private              WebCliConfig   config;
    private              AioServer      localWebServer;
    private              AioServer      remoteWebServer;
    private              AioServer      tcpServer;
    private              PtyManager     ptyManager;
    private              AgentTcpClient agentTcpClient;
    private              AgentManager   agentManager;

    public void start()
    {
        // 如果配置中 shell 为空，则应用默认配置
        applyDefaultsIfNeeded();

        if (config.isAllMode())
        {
            startAllMode();
        }
        else if (config.isServerMode())
        {
            startServerMode();
        }
        else
        {
            startAgentMode();
        }
    }

    private void applyDefaultsIfNeeded()
    {
        if (config.getShell() == null || config.getShell().isBlank())
        {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win"))
            {
                config.setShell("cmd.exe");
                config.setShellArgs(null);
            }
            else if (os.contains("mac"))
            {
                config.setShell("/bin/zsh");
                config.setShellArgs(new String[]{"-i", "-l"});
            }
            else
            {
                config.setShell("/bin/bash");
                config.setShellArgs(new String[]{"-i", "-l"});
            }
        }
        if (config.getWorkingDirectory() == null || config.getWorkingDirectory().isBlank())
        {
            config.setWorkingDirectory(System.getProperty("user.home"));
        }
    }

    private void startAgentMode()
    {
        // 启动本地 PTY 管理器和 Web 服务
        this.ptyManager = new PtyManager(config);
        WebSocketHandler         wsHandler     = new WebSocketHandler(ptyManager);
        ApplicationContext       context       = ApplicationContext.boot(WebApplication.class);
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

    private void startServerMode()
    {
        // 启动 Agent 管理器
        this.agentManager = new AgentManager();
        // 启动 TCP 服务端，接受 Agent 连接
        startTcpServer();
        // 启动远端 Web 服务
        startRemoteWebServer();
    }

    private void startAllMode()
    {
        // 同时启动 Agent 和 Server
        log.info("启动 All 模式：同时运行 Agent 和 Server");
        // 1. 启动 Server 部分
        this.agentManager = new AgentManager();
        startTcpServer();
        startRemoteWebServer();
        // 2. 启动 Agent 部分（本地 PTY 和 Web）
        this.ptyManager = new PtyManager(config);
        WebSocketHandler         wsHandler     = new WebSocketHandler(ptyManager);
        ApplicationContext       context       = ApplicationContext.boot(WebApplication.class);
        ChannelConfig            channelConfig = new ChannelConfig().setIp("127.0.0.1").setPort(config.getWebPort()).setChannelGroup(ChannelConfig.DEFAULT_CHANNEL_GROUP);
        HttpAppServer.StartParam startParam    = new HttpAppServer.StartParam().setChannelConfig(channelConfig).setContext(context).setWebDir("local").setWebSocketProcessor(wsHandler);
        localWebServer = HttpAppServer.start(startParam);
        log.info("本地 Web 服务已启动，监听地址: {}:{}", channelConfig.getIp(), config.getWebPort());
        log.info("请访问本地终端: http://127.0.0.1:{}/", config.getWebPort());
        // 3. Agent 连接到本地 Server
        agentTcpClient = new AgentTcpClient(config, ptyManager);
        agentTcpClient.connect();
        log.info("Agent 正在连接本地 Server: {}:{}", config.getServerHost(), config.getServerPort());
    }

    private void startTcpServer()
    {
        ChannelConfig tcpConfig = new ChannelConfig().setIp("0.0.0.0").setPort(config.getTcpPort()).setChannelGroup(ChannelConfig.DEFAULT_CHANNEL_GROUP);
        tcpServer = AioServer.newAioServer(tcpConfig, pipeline -> {
            pipeline.addReadProcessor(new TotalLengthFieldBasedFrameDecoder(0, 4, 4, 1024 * 1024));
            pipeline.addReadProcessor(new ServerTcpHandler(config, agentManager));
            pipeline.addWriteProcessor(new LengthEncoder(0, 4));
        });
        tcpServer.start();
        log.info("TCP 服务已启动，监听端口: {}", config.getTcpPort());
    }

    private void startRemoteWebServer()
    {
        RemoteWebSocketHandler   wsHandler     = new RemoteWebSocketHandler(agentManager);
        ApplicationContext       context       = ApplicationContext.boot(WebApplication.class);
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
        app.start();
    }
}
