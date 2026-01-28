package cc.jfire.webcli.server;

import cc.jfire.dson.Dson;
import cc.jfire.jnet.common.api.Pipeline;
import cc.jfire.jnet.common.api.ReadProcessor;
import cc.jfire.jnet.common.api.ReadProcessorNode;
import cc.jfire.jnet.common.buffer.buffer.IoBuffer;
import cc.jfire.jnet.extend.websocket.dto.WebSocketFrame;
import cc.jfire.webcli.config.WebCliConfig;
import cc.jfire.webcli.protocol.MessageType;
import cc.jfire.webcli.protocol.PtyInfo;
import cc.jfire.webcli.protocol.WsMessage;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class RemoteWebSocketHandler implements ReadProcessor<Object> {
    private final AgentManager agentManager;
    private final LoginManager loginManager;
    private final ConcurrentHashMap<String, String> pipelinePtyMap = new ConcurrentHashMap<>();

    public RemoteWebSocketHandler(AgentManager agentManager, WebCliConfig config) {
        this.agentManager = agentManager;
        this.loginManager = new LoginManager(config);
    }

    @Override
    public void read(Object obj, ReadProcessorNode next) {
        if (obj instanceof WebSocketFrame frame) {
            Pipeline pipeline = next.pipeline();
            try {
                if (frame.getOpcode() == WebSocketFrame.OPCODE_CLOSE) {
                    handleClose(pipeline);
                    return;
                }
                if (frame.getOpcode() == WebSocketFrame.OPCODE_TEXT) {
                    IoBuffer payload = frame.getPayload();
                    byte[] bytes = new byte[payload.remainRead()];
                    payload.get(bytes);
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    handleMessage(pipeline, text);
                }
            } finally {
                frame.free();
            }
        } else {
            next.fireRead(obj);
        }
    }

    private void handleMessage(Pipeline pipeline, String text) {
        try {
            WsMessage msg = Dson.fromString(WsMessage.class, text);
            String pipelineId = pipeline.pipelineId();

            // 登录消息特殊处理
            if (msg.getType() == MessageType.LOGIN) {
                handleLogin(pipeline, msg);
                return;
            }

            // 其他消息需要先验证是否已登录
            if (!loginManager.isAuthenticated(pipelineId)) {
                sendLoginRequired(pipeline);
                return;
            }

            switch (msg.getType()) {
                case PTY_REMOTE_LIST -> handlePtyRemoteList(pipeline);
                case PTY_INPUT -> handlePtyInput(pipeline, msg);
                case PTY_RESIZE -> handlePtyResize(pipeline, msg);
                case PTY_CLOSE -> handlePtyClose(pipeline, msg);
                case PTY_DETACH -> handlePtyDetach(pipeline, msg);
                case PTY_ATTACH -> handlePtyAttach(pipeline, msg);
                default -> log.warn("远端 Web 不支持的消息类型: {}", msg.getType());
            }
        } catch (Exception e) {
            log.error("处理消息失败", e);
            sendError(pipeline, e.getMessage());
        }
    }

    /**
     * 处理登录请求
     */
    private void handleLogin(Pipeline pipeline, WsMessage msg) {
        String clientIp = getClientIp(pipeline);
        String pipelineId = pipeline.pipelineId();

        LoginManager.LoginResult result = loginManager.verify(
            msg.getUsername(),
            msg.getPasswordHash(),
            msg.getSalt(),
            clientIp
        );

        WsMessage response = new WsMessage();
        if (result.success()) {
            loginManager.markAuthenticated(pipelineId);
            response.setType(MessageType.LOGIN_SUCCESS);
            response.setData(result.message());
        } else {
            response.setType(MessageType.LOGIN_FAILED);
            response.setData(result.message());
        }
        sendMessage(pipeline, response);
    }

    /**
     * 发送需要登录的消息
     */
    private void sendLoginRequired(Pipeline pipeline) {
        WsMessage msg = new WsMessage();
        msg.setType(MessageType.LOGIN_FAILED);
        msg.setData("请先登录");
        sendMessage(pipeline, msg);
    }

    /**
     * 获取客户端 IP 地址
     */
    private String getClientIp(Pipeline pipeline) {
        String address = pipeline.getRemoteAddressWithoutException();
        return address != null ? address : "unknown";
    }

    private void handlePtyRemoteList(Pipeline pipeline) {
        // 刷新所有 Agent 的 PTY 列表
        agentManager.refreshAllPtyLists();

        // 等待短暂时间让 Agent 响应
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        List<PtyInfo> list = agentManager.getAllRemotePtys();
        WsMessage response = new WsMessage();
        response.setType(MessageType.PTY_REMOTE_LIST);
        response.setData(Dson.toJson(list));
        sendMessage(pipeline, response);
    }

    private void handlePtyInput(Pipeline pipeline, WsMessage msg) {
        String fullPtyId = msg.getPtyId();
        String[] parts = agentManager.parseFullPtyId(fullPtyId);
        if (parts != null) {
            ServerTcpHandler handler = agentManager.getAgentHandler(parts[0]);
            if (handler != null) {
                handler.sendPtyInput(parts[1], msg.getData());
            }
        }
    }

    private void handlePtyResize(Pipeline pipeline, WsMessage msg) {
        String fullPtyId = msg.getPtyId();
        String[] parts = agentManager.parseFullPtyId(fullPtyId);
        if (parts != null && msg.getCols() != null && msg.getRows() != null) {
            ServerTcpHandler handler = agentManager.getAgentHandler(parts[0]);
            if (handler != null) {
                handler.sendPtyResize(parts[1], msg.getCols(), msg.getRows());
            }
        }
    }

    private void handlePtyClose(Pipeline pipeline, WsMessage msg) {
        String fullPtyId = msg.getPtyId();
        String[] parts = agentManager.parseFullPtyId(fullPtyId);
        if (parts != null) {
            String agentId = parts[0];
            String ptyId = parts[1];
            ServerTcpHandler handler = agentManager.getAgentHandler(agentId);
            if (handler != null) {
                handler.sendPtyClose(ptyId);
            }
            agentManager.unregisterPtyOutputListener(fullPtyId);
            agentManager.removePtyAttach(agentId, ptyId);
            pipelinePtyMap.remove(pipeline.pipelineId());
        }

        WsMessage response = new WsMessage();
        response.setType(MessageType.SUCCESS);
        sendMessage(pipeline, response);
    }

    /**
     * 处理远端断开连接请求（只断开远端显示，不关闭本地 PTY）
     */
    private void handlePtyDetach(Pipeline pipeline, WsMessage msg) {
        String fullPtyId = msg.getPtyId();
        String[] parts = agentManager.parseFullPtyId(fullPtyId);
        if (parts != null) {
            String agentId = parts[0];
            String ptyId = parts[1];
            ServerTcpHandler handler = agentManager.getAgentHandler(agentId);
            if (handler != null) {
                // 只发送 detach，不发送 close
                handler.sendPtyDetach(ptyId);
            }
            agentManager.unregisterPtyOutputListener(fullPtyId);
            agentManager.unregisterVisibilityDisabledCallback(fullPtyId);
            agentManager.removePtyAttach(agentId, ptyId);
            pipelinePtyMap.remove(pipeline.pipelineId());
        }

        WsMessage response = new WsMessage();
        response.setType(MessageType.SUCCESS);
        sendMessage(pipeline, response);
    }

    private void handlePtyAttach(Pipeline pipeline, WsMessage msg) {
        String fullPtyId = msg.getPtyId();
        String[] parts = agentManager.parseFullPtyId(fullPtyId);
        if (parts != null) {
            String agentId = parts[0];
            String ptyId = parts[1];
            ServerTcpHandler handler = agentManager.getAgentHandler(agentId);
            if (handler != null) {
                pipelinePtyMap.put(pipeline.pipelineId(), fullPtyId);

                // 记录 attach 状态，用于 Agent 重连后恢复
                agentManager.recordPtyAttach(agentId, ptyId);

                // 注册输出监听器
                agentManager.registerPtyOutputListener(fullPtyId, (ptyIdParam, data) -> {
                    WsMessage outMsg = new WsMessage();
                    outMsg.setType(MessageType.PTY_OUTPUT);
                    outMsg.setPtyId(ptyIdParam);
                    outMsg.setData(data);
                    sendMessage(pipeline, outMsg);
                });

                // 注册可见性禁用回调
                agentManager.registerVisibilityDisabledCallback(fullPtyId, (ptyIdParam, reason) -> {
                    // 通知远端客户端终端已不可见
                    WsMessage closeMsg = new WsMessage();
                    closeMsg.setType(MessageType.PTY_VISIBILITY_DISABLED);
                    closeMsg.setPtyId(ptyIdParam);
                    closeMsg.setData(reason);
                    sendMessage(pipeline, closeMsg);
                    // 清理本地状态
                    pipelinePtyMap.remove(pipeline.pipelineId());
                    // 移除 attach 记录
                    agentManager.removePtyAttach(agentId, ptyId);
                    log.info("终端 {} 已关闭远端可见，通知远端客户端断开", ptyIdParam);
                });

                // 通知 Agent 附加到该终端
                handler.sendPtyAttach(ptyId);

                WsMessage response = new WsMessage();
                response.setType(MessageType.SUCCESS);
                response.setPtyId(fullPtyId);
                sendMessage(pipeline, response);
            } else {
                sendError(pipeline, "Agent 不存在");
            }
        } else {
            sendError(pipeline, "无效的 PTY ID");
        }
    }

    private void handleClose(Pipeline pipeline) {
        String pipelineId = pipeline.pipelineId();
        // 清理登录状态
        loginManager.removeAuthentication(pipelineId);

        String fullPtyId = pipelinePtyMap.remove(pipelineId);
        if (fullPtyId != null) {
            agentManager.unregisterPtyOutputListener(fullPtyId);
            agentManager.unregisterVisibilityDisabledCallback(fullPtyId);
            String[] parts = agentManager.parseFullPtyId(fullPtyId);
            if (parts != null) {
                String agentId = parts[0];
                String ptyId = parts[1];
                agentManager.removePtyAttach(agentId, ptyId);
                ServerTcpHandler handler = agentManager.getAgentHandler(agentId);
                if (handler != null) {
                    handler.sendPtyDetach(ptyId);
                }
            }
        }
    }

    private void sendMessage(Pipeline pipeline, WsMessage msg) {
        String json = Dson.toJson(msg);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        IoBuffer payload = pipeline.allocator().allocate(bytes.length);
        payload.put(bytes);
        WebSocketFrame frame = new WebSocketFrame();
        frame.setOpcode(WebSocketFrame.OPCODE_TEXT);
        frame.setPayload(payload);
        pipeline.fireWrite(frame);
    }

    private void sendError(Pipeline pipeline, String error) {
        WsMessage msg = new WsMessage();
        msg.setType(MessageType.ERROR);
        msg.setData(error);
        sendMessage(pipeline, msg);
    }

    @Override
    public void readFailed(Throwable e, ReadProcessorNode next) {
        log.error("WebSocket 读取失败", e);
        handleClose(next.pipeline());
    }
}
