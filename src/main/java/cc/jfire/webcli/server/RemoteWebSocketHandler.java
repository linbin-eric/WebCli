package cc.jfire.webcli.server;

import cc.jfire.dson.Dson;
import cc.jfire.jnet.common.api.Pipeline;
import cc.jfire.jnet.common.api.ReadProcessor;
import cc.jfire.jnet.common.api.ReadProcessorNode;
import cc.jfire.jnet.common.buffer.buffer.IoBuffer;
import cc.jfire.jnet.extend.websocket.dto.WebSocketFrame;
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
    private final ConcurrentHashMap<String, String> pipelinePtyMap = new ConcurrentHashMap<>();

    public RemoteWebSocketHandler(AgentManager agentManager) {
        this.agentManager = agentManager;
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
            switch (msg.getType()) {
                case PTY_REMOTE_LIST -> handlePtyRemoteList(pipeline);
                case PTY_INPUT -> handlePtyInput(pipeline, msg);
                case PTY_RESIZE -> handlePtyResize(pipeline, msg);
                case PTY_CLOSE -> handlePtyClose(pipeline, msg);
                case PTY_ATTACH -> handlePtyAttach(pipeline, msg);
                default -> log.warn("远端 Web 不支持的消息类型: {}", msg.getType());
            }
        } catch (Exception e) {
            log.error("处理消息失败", e);
            sendError(pipeline, e.getMessage());
        }
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
            ServerTcpHandler handler = agentManager.getAgentHandler(parts[0]);
            if (handler != null) {
                handler.sendPtyClose(parts[1]);
            }
            agentManager.unregisterPtyOutputListener(fullPtyId);
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
            ServerTcpHandler handler = agentManager.getAgentHandler(parts[0]);
            if (handler != null) {
                pipelinePtyMap.put(pipeline.pipelineId(), fullPtyId);

                // 注册输出监听器
                agentManager.registerPtyOutputListener(fullPtyId, (ptyId, data) -> {
                    WsMessage outMsg = new WsMessage();
                    outMsg.setType(MessageType.PTY_OUTPUT);
                    outMsg.setPtyId(ptyId);
                    outMsg.setData(data);
                    sendMessage(pipeline, outMsg);
                });

                // 注册可见性禁用回调
                agentManager.registerVisibilityDisabledCallback(fullPtyId, (ptyId, reason) -> {
                    // 通知远端客户端终端已不可见
                    WsMessage closeMsg = new WsMessage();
                    closeMsg.setType(MessageType.PTY_VISIBILITY_DISABLED);
                    closeMsg.setPtyId(ptyId);
                    closeMsg.setData(reason);
                    sendMessage(pipeline, closeMsg);
                    // 清理本地状态
                    pipelinePtyMap.remove(pipeline.pipelineId());
                    log.info("终端 {} 已关闭远端可见，通知远端客户端断开", ptyId);
                });

                // 通知 Agent 附加到该终端
                handler.sendPtyAttach(parts[1]);

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
        String fullPtyId = pipelinePtyMap.remove(pipeline.pipelineId());
        if (fullPtyId != null) {
            agentManager.unregisterPtyOutputListener(fullPtyId);
            agentManager.unregisterVisibilityDisabledCallback(fullPtyId);
            String[] parts = agentManager.parseFullPtyId(fullPtyId);
            if (parts != null) {
                ServerTcpHandler handler = agentManager.getAgentHandler(parts[0]);
                if (handler != null) {
                    handler.sendPtyDetach(parts[1]);
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
