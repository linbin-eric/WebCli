package cc.jfire.webcli.agent;

import cc.jfire.dson.Dson;
import cc.jfire.jnet.client.ClientChannel;
import cc.jfire.se2.JfireSE;
import cc.jfire.jnet.common.api.Pipeline;
import cc.jfire.jnet.common.api.ReadProcessor;
import cc.jfire.jnet.common.api.ReadProcessorNode;
import cc.jfire.jnet.common.buffer.buffer.IoBuffer;
import cc.jfire.jnet.common.coder.ValidatedLengthFrameDecoder;
import cc.jfire.jnet.common.coder.ValidatedLengthFrameEncoder;
import cc.jfire.jnet.common.util.ChannelConfig;
import cc.jfire.webcli.config.WebCliConfig;
import cc.jfire.webcli.crypto.AesGcmCrypto;
import cc.jfire.webcli.protocol.PtyInfo;
import cc.jfire.webcli.protocol.TcpMessage;
import cc.jfire.webcli.protocol.TcpMessageType;
import cc.jfire.webcli.pty.PtyInstance;
import cc.jfire.webcli.pty.PtyManager;
import cc.jfire.webcli.util.AgentIdUtil;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@Slf4j
public class AgentTcpClient implements ReadProcessor<IoBuffer> {
    /** 协议魔法值，用于帧验证 */
    private static final int PROTOCOL_MAGIC = 0x57454243; // "WEBC" in hex

    private final WebCliConfig config;
    private final PtyManager ptyManager;
    private final String agentIdBase;
    private volatile String agentId;
    private int agentIdIndex = 1;
    private final SecureRandom secureRandom = new SecureRandom();
    private ClientChannel clientChannel;
    private Pipeline pipeline;
    private AesGcmCrypto crypto;
    private volatile boolean authenticated = false;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Consumer<String>> ptyOutputListeners = new ConcurrentHashMap<>();
    private final Map<String, BiConsumer<String, Boolean>> ptyVisibilityListeners = new ConcurrentHashMap<>();
    private final JfireSE jfireSE = JfireSE.config().build();
    private KeyPair clientKeyPair;
    private byte[] clientNonce;
    private String clientPubKey;
    private byte[] sessionKey;

    public AgentTcpClient(WebCliConfig config, PtyManager ptyManager) {
        this.config = config;
        this.ptyManager = ptyManager;
        this.agentIdBase = AgentIdUtil.sanitize(config.getAgentId());
        this.agentId = this.agentIdBase;
    }

    public void connect() {
        ChannelConfig channelConfig = new ChannelConfig()
                .setIp(config.getServerHost())
                .setPort(config.getServerPort());

        clientChannel = ClientChannel.newClient(channelConfig, pipeline -> {
            pipeline.addReadProcessor(new ValidatedLengthFrameDecoder(PROTOCOL_MAGIC, 1024 * 1024));
            pipeline.addReadProcessor(AgentTcpClient.this);
            pipeline.addWriteProcessor(new ValidatedLengthFrameEncoder(PROTOCOL_MAGIC, pipeline.allocator()));
        });

        if (clientChannel.connect()) {
            this.pipeline = clientChannel.pipeline();
            log.info("已连接到远端服务器: {}:{}", config.getServerHost(), config.getServerPort());
            sendAuthRequest();
            startHeartbeat();
        } else {
            log.error("连接远端服务器失败: {}:{}", config.getServerHost(), config.getServerPort());
            scheduleReconnect();
        }
    }

    private void sendAuthRequest() {
        try {
            clientKeyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair();
            clientPubKey = Base64.getEncoder().encodeToString(clientKeyPair.getPublic().getEncoded());

            clientNonce = new byte[32];
            secureRandom.nextBytes(clientNonce);
            String clientNonceB64 = Base64.getEncoder().encodeToString(clientNonce);

            String macInput = String.join("|", "AUTH_REQUEST", agentId, clientPubKey, clientNonceB64);
            String clientMac = Base64.getEncoder().encodeToString(hmacSha256(tokenBytes(), macInput.getBytes(StandardCharsets.UTF_8)));

            TcpMessage msg = new TcpMessage();
            msg.setType(TcpMessageType.AUTH_REQUEST);
            msg.setAgentId(agentId);
            msg.setClientNonce(clientNonceB64);
            msg.setClientPubKey(clientPubKey);
            msg.setClientMac(clientMac);
            sendMessage(msg, false); // 握手阶段明文
        } catch (Exception e) {
            log.error("发送认证请求失败", e);
        }
    }

    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            if (authenticated && clientChannel != null && clientChannel.alive()) {
                TcpMessage msg = new TcpMessage();
                msg.setType(TcpMessageType.HEARTBEAT);
                sendMessage(msg, true);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void scheduleReconnect() {
        scheduler.schedule(() -> {
            log.info("尝试重新连接...");
            connect();
        }, 5, TimeUnit.SECONDS);
    }

    @Override
    public void read(IoBuffer buffer, ReadProcessorNode next) {
        try {
            byte[] data = new byte[buffer.remainRead()];
            buffer.get(data);

            byte[] decrypted;
            if (authenticated && crypto != null) {
                decrypted = crypto.decrypt(data);
            } else {
                decrypted = data;
            }

            TcpMessage msg = (TcpMessage) jfireSE.deSerialize(decrypted);
            handleMessage(msg);
        } catch (Exception e) {
            log.error("处理消息失败", e);
        } finally {
            buffer.free();
        }
    }

    private void handleMessage(TcpMessage msg) {
        switch (msg.getType()) {
            case AUTH_RESPONSE -> handleAuthResponse(msg);
            case AUTH_RESULT -> handleAuthResult(msg);
            case PTY_LIST_REQUEST -> handlePtyListRequest();
            case PTY_INPUT -> handlePtyInput(msg);
            case PTY_RESIZE -> handlePtyResize(msg);
            case PTY_CLOSE -> handlePtyClose(msg);
            case PTY_ATTACH -> handlePtyAttach(msg);
            case PTY_DETACH -> handlePtyDetach(msg);
            case PTY_CREATE -> handlePtyCreate(msg);
            case PTY_RENAME -> handlePtyRename(msg);
            case HEARTBEAT -> {} // 忽略心跳响应
            default -> log.warn("未知消息类型: {}", msg.getType());
        }
    }

    private void handleAuthResponse(TcpMessage msg) {
        try {
            String serverPubKey = msg.getServerPubKey();
            String serverNonceB64 = msg.getServerNonce();
            String serverMac = msg.getServerMac();

            if (serverPubKey == null || serverNonceB64 == null || serverMac == null) {
                log.error("认证响应缺少必要字段");
                return;
            }

            // 校验 serverMac：HMAC(token, "AUTH_RESPONSE"||agentId||serverPubKey||serverNonce||clientPubKey||clientNonce)
            String clientNonceB64 = Base64.getEncoder().encodeToString(clientNonce);
            String macInput = String.join("|", "AUTH_RESPONSE", agentId, serverPubKey, serverNonceB64, clientPubKey, clientNonceB64);
            String expectedServerMac = Base64.getEncoder().encodeToString(hmacSha256(tokenBytes(), macInput.getBytes(StandardCharsets.UTF_8)));
            if (!expectedServerMac.equals(serverMac)) {
                log.error("认证失败：serverMac 校验不通过");
                return;
            }

            byte[] serverNonce = Base64.getDecoder().decode(serverNonceB64);
            PublicKey serverPublicKey = decodeX25519PublicKey(serverPubKey);
            byte[] sharedSecret = computeSharedSecret(clientKeyPair.getPrivate(), serverPublicKey);

            // 派生会话密钥：HMAC(token, sharedSecret || clientNonce || serverNonce)
            sessionKey = hmacSha256(tokenBytes(), concat(sharedSecret, clientNonce, serverNonce));
            crypto = new AesGcmCrypto(sessionKey);

            // 回发 AUTH_FINISH（基于 sessionKey 的 MAC），Server 校验成功后才正式注册 Agent
            String finishInput = String.join("|", "AUTH_FINISH", agentId);
            String finishMac = Base64.getEncoder().encodeToString(hmacSha256(sessionKey, finishInput.getBytes(StandardCharsets.UTF_8)));
            TcpMessage finish = new TcpMessage();
            finish.setType(TcpMessageType.AUTH_FINISH);
            finish.setAgentId(agentId);
            finish.setFinishMac(finishMac);
            sendMessage(finish, false); // AUTH_FINISH 明文
            log.info("会话密钥已建立，等待服务端确认注册...");
        } catch (Exception e) {
            log.error("处理认证响应失败", e);
        }
    }

    private void handleAuthResult(TcpMessage msg)
    {
        String result = msg.getData();
        if ("OK".equalsIgnoreCase(result))
        {
            authenticated = true;
            log.info("Agent 注册成功: {}", agentId);
            registerVisibilityListeners();
            return;
        }
        if ("DUPLICATE_AGENT_ID".equalsIgnoreCase(result))
        {
            String old = agentId;
            bumpAgentId();
            log.warn("agentId 重名，自动改名并重试注册: {} -> {}", old, agentId);
            sendAuthRequest();
            return;
        }
        log.warn("未知认证结果: {}", result);
    }

    private synchronized void bumpAgentId()
    {
        authenticated = false;
        crypto = null;
        sessionKey = null;
        agentIdIndex++;
        agentId = AgentIdUtil.withSuffix(agentIdBase, agentIdIndex);
    }

    private void handlePtyListRequest() {
        List<PtyInstance> remoteViewablePtys = ptyManager.getAll().stream()
                .filter(PtyInstance::isRemoteViewable)
                .toList();

        TcpMessage response = new TcpMessage();
        response.setType(TcpMessageType.PTY_LIST_RESPONSE);
        response.setAgentId(agentId);
        response.setData(Dson.toJson(remoteViewablePtys.stream()
                .map(pty -> new PtyInfo(
                        pty.getId(), pty.getName(), pty.isAlive(), pty.isRemoteViewable()))
                .toList()));
        sendMessage(response, true);
    }

    private void handlePtyInput(TcpMessage msg) {
        PtyInstance pty = ptyManager.get(msg.getPtyId());
        if (pty != null && pty.isRemoteViewable()) {
            try {
                String decoded = new String(Base64.getDecoder().decode(msg.getData()), StandardCharsets.UTF_8);
                pty.write(decoded);
            } catch (Exception e) {
                log.error("写入 PTY 失败", e);
            }
        }
    }

    private void handlePtyResize(TcpMessage msg) {
        PtyInstance pty = ptyManager.get(msg.getPtyId());
        if (pty != null && pty.isRemoteViewable() && msg.getCols() != null && msg.getRows() != null) {
            pty.resize(msg.getCols(), msg.getRows());
        }
    }

    private void handlePtyClose(TcpMessage msg) {
        PtyInstance pty = ptyManager.get(msg.getPtyId());
        if (pty != null && pty.isRemoteViewable()) {
            ptyManager.remove(msg.getPtyId());
        }
    }

    private void handlePtyAttach(TcpMessage msg) {
        PtyInstance pty = ptyManager.get(msg.getPtyId());
        if (pty != null && pty.isRemoteViewable()) {
            // 创建输出监听器，将输出转发到远端
            Consumer<String> listener = output -> {
                TcpMessage outMsg = new TcpMessage();
                outMsg.setType(TcpMessageType.PTY_OUTPUT);
                outMsg.setPtyId(pty.getId());
                outMsg.setAgentId(agentId);
                outMsg.setData(Base64.getEncoder().encodeToString(output.getBytes(StandardCharsets.UTF_8)));
                sendMessage(outMsg, true);
            };

            // 保存监听器引用以便后续移除
            ptyOutputListeners.put(msg.getPtyId(), listener);
            // 注册到 PtyInstance
            pty.addOutputListener(listener);

            // 发送历史输出
            String history = pty.getOutputHistory();
            if (history != null && !history.isEmpty()) {
                TcpMessage historyMsg = new TcpMessage();
                historyMsg.setType(TcpMessageType.PTY_OUTPUT);
                historyMsg.setPtyId(pty.getId());
                historyMsg.setAgentId(agentId);
                historyMsg.setData(Base64.getEncoder().encodeToString(history.getBytes(StandardCharsets.UTF_8)));
                sendMessage(historyMsg, true);
            }
        }
    }

    private void handlePtyDetach(TcpMessage msg) {
        Consumer<String> listener = ptyOutputListeners.remove(msg.getPtyId());
        if (listener != null) {
            PtyInstance pty = ptyManager.get(msg.getPtyId());
            if (pty != null) {
                pty.removeOutputListener(listener);
            }
        }
    }

    private void handlePtyCreate(TcpMessage msg)
    {
        TcpMessage response = new TcpMessage();
        response.setType(TcpMessageType.PTY_CREATE_RESULT);
        response.setRequestId(msg.getRequestId());
        response.setAgentId(agentId);

        if (!ptyManager.isRemoteCreateEnabled())
        {
            response.setData("远端新建终端已被本地禁用");
            sendMessage(response, true);
            return;
        }

        String name = buildUniqueRemoteTerminalName(msg.getName());
        int cols = msg.getCols() != null ? msg.getCols() : 120;
        int rows = msg.getRows() != null ? msg.getRows() : 40;

        try
        {
            PtyInstance pty = ptyManager.create(name, cols, rows);
            pty.startReading();
            // 远端创建的终端默认开启远端可见，便于直接 attach
            pty.setRemoteViewable(true);
            // 标记为远端创建
            pty.setRemoteCreated(true);

            response.setData("OK");
            response.setPtyId(pty.getId());
            response.setName(pty.getName());
            response.setRemoteViewable(true);
            sendMessage(response, true);
            log.info("远端创建终端成功: id={}, name={}", pty.getId(), pty.getName());
        }
        catch (Exception e)
        {
            log.error("远端创建终端失败", e);
            response.setData("创建终端失败: " + e.getMessage());
            sendMessage(response, true);
        }
    }

    private String buildUniqueRemoteTerminalName(String requestedName)
    {
        String baseName = requestedName != null ? requestedName.trim() : "";
        if (baseName.isBlank())
        {
            baseName = "终端";
        }

        String prefix = agentId + "-";
        String nameWithPrefix = baseName.startsWith(prefix) ? baseName : prefix + baseName;

        var existingNames = ptyManager.getAll().stream()
                .map(PtyInstance::getName)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        // 处理重名：递增序号（-2, -3, ...）
        if (!existingNames.contains(nameWithPrefix))
        {
            return nameWithPrefix;
        }

        for (int i = 2; i < 10_000; i++)
        {
            String candidate = nameWithPrefix + "-" + i;
            if (!existingNames.contains(candidate))
            {
                return candidate;
            }
        }

        // 极端情况下兜底，保证唯一性
        return nameWithPrefix + "-" + System.currentTimeMillis();
    }

    private void handlePtyRename(TcpMessage msg)
    {
        TcpMessage response = new TcpMessage();
        response.setType(TcpMessageType.PTY_RENAME_RESULT);
        response.setRequestId(msg.getRequestId());
        response.setAgentId(agentId);
        response.setPtyId(msg.getPtyId());

        if (msg.getPtyId() == null || msg.getPtyId().isBlank())
        {
            response.setData("ptyId 不能为空");
            sendMessage(response, true);
            return;
        }
        if (msg.getName() == null || msg.getName().isBlank())
        {
            response.setData("终端名称不能为空");
            sendMessage(response, true);
            return;
        }

        PtyInstance pty = ptyManager.get(msg.getPtyId());
        if (pty == null)
        {
            response.setData("终端不存在");
            sendMessage(response, true);
            return;
        }
        if (!pty.isRemoteViewable())
        {
            response.setData("终端未开启远端可见，禁止远端重命名");
            sendMessage(response, true);
            return;
        }

        pty.setName(msg.getName());
        response.setData("OK");
        response.setName(pty.getName());
        sendMessage(response, true);
        log.info("远端重命名终端成功: id={}, name={}", msg.getPtyId(), msg.getName());
    }

    private void registerVisibilityListeners() {
        for (PtyInstance pty : ptyManager.getAll()) {
            registerVisibilityListener(pty);
        }
        ptyManager.setOnPtyCreated(this::registerVisibilityListener);
    }

    private void registerVisibilityListener(PtyInstance pty) {
        BiConsumer<String, Boolean> listener = (ptyId, visible) -> {
            if (!visible) {
                handleVisibilityDisabled(ptyId);
            }
        };
        ptyVisibilityListeners.put(pty.getId(), listener);
        pty.addVisibilityChangeListener(listener);
    }

    private void handleVisibilityDisabled(String ptyId) {
        // 移除输出监听器
        Consumer<String> outputListener = ptyOutputListeners.remove(ptyId);
        if (outputListener != null) {
            PtyInstance pty = ptyManager.get(ptyId);
            if (pty != null) {
                pty.removeOutputListener(outputListener);
            }
        }
        // 通知服务端该终端已不可见
        if (authenticated && pipeline != null) {
            TcpMessage msg = new TcpMessage();
            msg.setType(TcpMessageType.PTY_VISIBILITY_CHANGED);
            msg.setPtyId(ptyId);
            msg.setAgentId(agentId);
            msg.setRemoteViewable(false);
            sendMessage(msg, true);
            log.info("通知服务端终端 {} 已关闭远端可见", ptyId);
        }
    }

    private void sendMessage(TcpMessage msg, boolean encrypt) {
        if (pipeline == null) return;

        try {
            byte[] data = jfireSE.serialize(msg);

            if (encrypt && crypto != null) {
                data = crypto.encrypt(data);
            }

            // ValidatedLengthFrameEncoder 会自动添加魔法值、长度和 CRC16
            IoBuffer buffer = pipeline.allocator().allocate(data.length);
            buffer.put(data);
            pipeline.fireWrite(buffer);
        } catch (Exception e) {
            log.error("发送消息失败", e);
        }
    }

    private byte[] tokenBytes() {
        return config.getToken().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    private static PublicKey decodeX25519PublicKey(String base64) throws Exception {
        byte[] encoded = Base64.getDecoder().decode(base64);
        KeyFactory keyFactory = KeyFactory.getInstance("X25519");
        return keyFactory.generatePublic(new X509EncodedKeySpec(encoded));
    }

    private static byte[] computeSharedSecret(PrivateKey privateKey, PublicKey publicKey) throws Exception {
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(privateKey);
        agreement.doPhase(publicKey, true);
        return agreement.generateSecret();
    }

    @Override
    public void readFailed(Throwable e, ReadProcessorNode next) {
        log.error("连接断开", e);
        authenticated = false;
        // 清理所有 PTY 输出监听器
        cleanupPtyListeners();
        scheduleReconnect();
    }

    private void cleanupPtyListeners() {
        // 移除所有已注册到 PtyInstance 的输出监听器
        for (Map.Entry<String, Consumer<String>> entry : ptyOutputListeners.entrySet()) {
            String ptyId = entry.getKey();
            Consumer<String> listener = entry.getValue();
            PtyInstance pty = ptyManager.get(ptyId);
            if (pty != null) {
                pty.removeOutputListener(listener);
                log.debug("已移除 PTY {} 的输出监听器", ptyId);
            }
        }
        ptyOutputListeners.clear();

        // 移除所有可见性监听器
        for (Map.Entry<String, BiConsumer<String, Boolean>> entry : ptyVisibilityListeners.entrySet()) {
            String ptyId = entry.getKey();
            BiConsumer<String, Boolean> listener = entry.getValue();
            PtyInstance pty = ptyManager.get(ptyId);
            if (pty != null) {
                pty.removeVisibilityChangeListener(listener);
            }
        }
        ptyVisibilityListeners.clear();
    }

    public void shutdown() {
        scheduler.shutdown();
        if (clientChannel != null && pipeline != null) {
            pipeline.shutdownInput();
        }
    }
}
