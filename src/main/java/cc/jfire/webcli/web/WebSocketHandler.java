package cc.jfire.webcli.web;

import cc.jfire.dson.Dson;
import cc.jfire.jnet.common.api.Pipeline;
import cc.jfire.jnet.common.api.ReadProcessor;
import cc.jfire.jnet.common.api.ReadProcessorNode;
import cc.jfire.jnet.common.buffer.buffer.IoBuffer;
import cc.jfire.jnet.extend.websocket.dto.WebSocketFrame;
import cc.jfire.webcli.protocol.MessageType;
import cc.jfire.webcli.protocol.PtyInfo;
import cc.jfire.webcli.protocol.WsMessage;
import cc.jfire.webcli.pty.PtyInstance;
import cc.jfire.webcli.pty.PtyManager;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class WebSocketHandler implements ReadProcessor<Object>
{
    private final PtyManager                         ptyManager;
    private final ConcurrentHashMap<String, String>  pipelinePtyMap = new ConcurrentHashMap<>();

    public WebSocketHandler(PtyManager ptyManager)
    {
        this.ptyManager = ptyManager;
    }

    @Override
    public void read(Object obj, ReadProcessorNode next)
    {
        if (obj instanceof WebSocketFrame frame)
        {
            Pipeline pipeline = next.pipeline();
            try
            {
                if (frame.getOpcode() == WebSocketFrame.OPCODE_CLOSE)
                {
                    handleClose(pipeline);
                    return;
                }
                if (frame.getOpcode() == WebSocketFrame.OPCODE_TEXT)
                {
                    IoBuffer payload = frame.getPayload();
                    byte[]   bytes   = new byte[payload.remainRead()];
                    payload.get(bytes);
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    log.debug("收到消息:{}", text);
                    handleMessage(pipeline, text);
                }
            }
            finally
            {
                frame.free();
            }
        }
        else
        {
            next.fireRead(obj);
        }
    }

    private void handleMessage(Pipeline pipeline, String text)
    {
        try
        {
            WsMessage msg = Dson.fromString(WsMessage.class, text);
            switch (msg.getType())
            {
                case PTY_CREATE -> handlePtyCreate(pipeline, msg);
                case PTY_INPUT -> handlePtyInput(pipeline, msg);
                case PTY_RESIZE -> handlePtyResize(pipeline, msg);
                case PTY_CLOSE -> handlePtyClose(pipeline, msg);
                case PTY_LIST -> handlePtyList(pipeline);
                case PTY_SWITCH -> handlePtySwitch(pipeline, msg);
                case PTY_ATTACH -> handlePtyAttach(pipeline, msg);
                case PTY_RENAME -> handlePtyRename(pipeline, msg);
                default -> log.warn("未知消息类型: {}", msg.getType());
            }
        }
        catch (Exception e)
        {
            log.error("处理消息失败", e);
            sendError(pipeline, e.getMessage());
        }
    }

    private void handlePtyCreate(Pipeline pipeline, WsMessage msg) throws IOException
    {
        String name = msg.getName();
        PtyInstance pty = ptyManager.create(name);
        pipelinePtyMap.put(pipeline.pipelineId(), pty.getId());
        pty.setOutputConsumer(output -> {
            WsMessage outMsg = new WsMessage();
            outMsg.setType(MessageType.PTY_OUTPUT);
            outMsg.setPtyId(pty.getId());
            outMsg.setData(Base64.getEncoder().encodeToString(output.getBytes(StandardCharsets.UTF_8)));
            sendMessage(pipeline, outMsg);
        });
        pty.startReading();
        WsMessage response = new WsMessage();
        response.setType(MessageType.SUCCESS);
        response.setPtyId(pty.getId());
        response.setName(pty.getName());
        sendMessage(pipeline, response);
    }

    private void handlePtyInput(Pipeline pipeline, WsMessage msg) throws IOException
    {
        String      ptyId = msg.getPtyId() != null ? msg.getPtyId() : pipelinePtyMap.get(pipeline.pipelineId());
        PtyInstance pty   = ptyManager.get(ptyId);
        if (pty != null)
        {
            // 对 Base64 编码的数据进行解码
            String decoded = new String(Base64.getDecoder().decode(msg.getData()), StandardCharsets.UTF_8);
            pty.write(decoded);
        }
    }

    private void handlePtyResize(Pipeline pipeline, WsMessage msg)
    {
        String      ptyId = msg.getPtyId() != null ? msg.getPtyId() : pipelinePtyMap.get(pipeline.pipelineId());
        PtyInstance pty   = ptyManager.get(ptyId);
        if (pty != null && msg.getCols() != null && msg.getRows() != null)
        {
            pty.resize(msg.getCols(), msg.getRows());
        }
    }

    private void handlePtyClose(Pipeline pipeline, WsMessage msg)
    {
        String ptyId = msg.getPtyId() != null ? msg.getPtyId() : pipelinePtyMap.get(pipeline.pipelineId());
        if (ptyId != null)
        {
            ptyManager.remove(ptyId);
            pipelinePtyMap.remove(pipeline.pipelineId());
            WsMessage response = new WsMessage();
            response.setType(MessageType.SUCCESS);
            sendMessage(pipeline, response);
        }
    }

    private void handlePtyList(Pipeline pipeline)
    {
        List<PtyInfo> list     = ptyManager.getAll().stream().map(pty -> new PtyInfo(pty.getId(), pty.getName(), pty.isAlive())).toList();
        WsMessage     response = new WsMessage();
        response.setType(MessageType.PTY_LIST);
        response.setData(Dson.toJson(list));
        sendMessage(pipeline, response);
    }

    private void handlePtySwitch(Pipeline pipeline, WsMessage msg)
    {
        PtyInstance pty = ptyManager.get(msg.getPtyId());
        if (pty != null)
        {
            pipelinePtyMap.put(pipeline.pipelineId(), msg.getPtyId());
            WsMessage response = new WsMessage();
            response.setType(MessageType.SUCCESS);
            response.setPtyId(msg.getPtyId());
            sendMessage(pipeline, response);
        }
        else
        {
            sendError(pipeline, "PTY 不存在: " + msg.getPtyId());
        }
    }

    private void handlePtyAttach(Pipeline pipeline, WsMessage msg)
    {
        PtyInstance pty = ptyManager.get(msg.getPtyId());
        if (pty != null)
        {
            // 绑定当前连接到该 PTY
            pipelinePtyMap.put(pipeline.pipelineId(), msg.getPtyId());
            // 设置输出消费者
            pty.setOutputConsumer(output -> {
                WsMessage outMsg = new WsMessage();
                outMsg.setType(MessageType.PTY_OUTPUT);
                outMsg.setPtyId(pty.getId());
                outMsg.setData(Base64.getEncoder().encodeToString(output.getBytes(StandardCharsets.UTF_8)));
                sendMessage(pipeline, outMsg);
            });
            // 发送历史输出
            String history = pty.getOutputHistory();
            if (history != null && !history.isEmpty())
            {
                WsMessage historyMsg = new WsMessage();
                historyMsg.setType(MessageType.PTY_OUTPUT);
                historyMsg.setPtyId(pty.getId());
                historyMsg.setData(Base64.getEncoder().encodeToString(history.getBytes(StandardCharsets.UTF_8)));
                sendMessage(pipeline, historyMsg);
            }
            // 发送成功响应
            WsMessage response = new WsMessage();
            response.setType(MessageType.SUCCESS);
            response.setPtyId(msg.getPtyId());
            sendMessage(pipeline, response);
        }
        else
        {
            sendError(pipeline, "PTY 不存在: " + msg.getPtyId());
        }
    }

    private void handleClose(Pipeline pipeline)
    {
        pipelinePtyMap.remove(pipeline.pipelineId());
    }

    private void handlePtyRename(Pipeline pipeline, WsMessage msg)
    {
        PtyInstance pty = ptyManager.get(msg.getPtyId());
        if (pty != null)
        {
            pty.setName(msg.getName());
            WsMessage response = new WsMessage();
            response.setType(MessageType.SUCCESS);
            response.setPtyId(msg.getPtyId());
            response.setName(pty.getName());
            sendMessage(pipeline, response);
        }
        else
        {
            sendError(pipeline, "PTY 不存在: " + msg.getPtyId());
        }
    }

    private void sendMessage(Pipeline pipeline, WsMessage msg)
    {
        String   json    = Dson.toJson(msg);
        log.debug("发送消息: {}", json.length() > 100 ? json.substring(0, 100) + "..." : json);
        byte[]   bytes   = json.getBytes(StandardCharsets.UTF_8);
        IoBuffer payload = pipeline.allocator().allocate(bytes.length);
        payload.put(bytes);
        WebSocketFrame frame = new WebSocketFrame();
        frame.setOpcode(WebSocketFrame.OPCODE_TEXT);
        frame.setPayload(payload);
        pipeline.fireWrite(frame);
    }

    private void sendError(Pipeline pipeline, String error)
    {
        WsMessage msg = new WsMessage();
        msg.setType(MessageType.ERROR);
        msg.setData(error);
        sendMessage(pipeline, msg);
    }

    @Override
    public void readFailed(Throwable e, ReadProcessorNode next)
    {
        log.error("WebSocket 读取失败", e);
        handleClose(next.pipeline());
    }

//    @Override
//    public void readCompleted(ReadProcessorNode next)
//    {
//        // 单次读取完成，不做任何处理，继续等待下一次读取
//        // 连接关闭通过 readFailed 或 WebSocket Close 帧处理
//    }
}
