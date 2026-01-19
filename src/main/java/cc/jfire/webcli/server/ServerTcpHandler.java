package cc.jfire.webcli.server;

import cc.jfire.jnet.common.api.Pipeline;
import cc.jfire.se2.JfireSE;
import cc.jfire.jnet.common.api.ReadProcessor;
import cc.jfire.jnet.common.api.ReadProcessorNode;
import cc.jfire.jnet.common.buffer.buffer.IoBuffer;
import cc.jfire.webcli.config.WebCliConfig;
import cc.jfire.webcli.crypto.AesGcmCrypto;
import cc.jfire.webcli.protocol.TcpMessage;
import cc.jfire.webcli.protocol.TcpMessageType;
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

@Slf4j
public class ServerTcpHandler implements ReadProcessor<IoBuffer> {
    private final WebCliConfig config;
    private final AgentManager agentManager;
    private final SecureRandom secureRandom = new SecureRandom();
    private final JfireSE jfireSE = JfireSE.config().build();
    private Pipeline pipeline;
    private AesGcmCrypto crypto;
    private volatile boolean authenticated = false;
    private String agentId;
    private KeyPair serverKeyPair;
    private byte[] clientNonce;
    private String clientPubKey;
    private byte[] serverNonce;
    private byte[] sessionKey;

    public ServerTcpHandler(WebCliConfig config, AgentManager agentManager) {
        this.config = config;
        this.agentManager = agentManager;
    }

    @Override
    public void read(IoBuffer buffer, ReadProcessorNode next) {
        this.pipeline = next.pipeline();
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
            case AUTH_REQUEST -> handleAuthRequest(msg);
            case AUTH_FINISH -> handleAuthFinish(msg);
            case PTY_LIST_RESPONSE -> handlePtyListResponse(msg);
            case PTY_OUTPUT -> handlePtyOutput(msg);
            case PTY_VISIBILITY_CHANGED -> handlePtyVisibilityChanged(msg);
            case HEARTBEAT -> sendHeartbeatResponse();
            default -> log.warn("未知消息类型: {}", msg.getType());
        }
    }

    private void handleAuthRequest(TcpMessage msg) {
        try {
            String agentId = msg.getAgentId();
            String clientNonceB64 = msg.getClientNonce();
            String clientPubKey = msg.getClientPubKey();
            String clientMac = msg.getClientMac();

            if (agentId == null || clientNonceB64 == null || clientPubKey == null || clientMac == null) {
                log.warn("AUTH_REQUEST 缺少必要字段");
                return;
            }

            // 校验 clientMac：HMAC(token, "AUTH_REQUEST"||agentId||clientPubKey||clientNonce)
            String macInput = String.join("|", "AUTH_REQUEST", agentId, clientPubKey, clientNonceB64);
            String expectedClientMac = Base64.getEncoder().encodeToString(hmacSha256(tokenBytes(), macInput.getBytes(StandardCharsets.UTF_8)));
            if (!expectedClientMac.equals(clientMac)) {
                log.warn("Agent 认证失败：clientMac 校验不通过");
                TcpMessage response = new TcpMessage();
                response.setType(TcpMessageType.AUTH_RESPONSE);
                response.setData("INVALID_AUTH");
                sendMessage(response, false);
                return;
            }

            this.agentId = agentId;
            this.clientPubKey = clientPubKey;
            this.clientNonce = Base64.getDecoder().decode(clientNonceB64);

            // 生成服务端握手参数
            serverKeyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair();
            String serverPubKey = Base64.getEncoder().encodeToString(serverKeyPair.getPublic().getEncoded());
            serverNonce = new byte[32];
            secureRandom.nextBytes(serverNonce);
            String serverNonceB64 = Base64.getEncoder().encodeToString(serverNonce);

            // 计算 serverMac：HMAC(token, "AUTH_RESPONSE"||agentId||serverPubKey||serverNonce||clientPubKey||clientNonce)
            String serverMacInput = String.join("|", "AUTH_RESPONSE", agentId, serverPubKey, serverNonceB64, clientPubKey, clientNonceB64);
            String serverMac = Base64.getEncoder().encodeToString(hmacSha256(tokenBytes(), serverMacInput.getBytes(StandardCharsets.UTF_8)));

            // 派生会话密钥（等 AUTH_FINISH 校验通过后再标记 authenticated）
            PublicKey clientPublicKey = decodeX25519PublicKey(clientPubKey);
            byte[] sharedSecret = computeSharedSecret(serverKeyPair.getPrivate(), clientPublicKey);
            sessionKey = hmacSha256(tokenBytes(), concat(sharedSecret, this.clientNonce, serverNonce));

            TcpMessage response = new TcpMessage();
            response.setType(TcpMessageType.AUTH_RESPONSE);
            response.setAgentId(agentId);
            response.setServerPubKey(serverPubKey);
            response.setServerNonce(serverNonceB64);
            response.setServerMac(serverMac);
            sendMessage(response, false); // 握手阶段明文
        } catch (Exception e) {
            log.error("处理 AUTH_REQUEST 失败", e);
        }
    }

    private void handleAuthFinish(TcpMessage msg) {
        try {
            if (sessionKey == null || agentId == null) {
                log.warn("收到 AUTH_FINISH 但会话未初始化");
                return;
            }
            String finishMac = msg.getFinishMac();
            if (finishMac == null) {
                log.warn("AUTH_FINISH 缺少 finishMac");
                return;
            }
            String finishInput = String.join("|", "AUTH_FINISH", agentId);
            String expectedFinishMac = Base64.getEncoder().encodeToString(hmacSha256(sessionKey, finishInput.getBytes(StandardCharsets.UTF_8)));
            if (!expectedFinishMac.equals(finishMac)) {
                log.warn("Agent 认证失败：finishMac 校验不通过");
                return;
            }

            this.crypto = new AesGcmCrypto(sessionKey);
            this.authenticated = true;
            agentManager.registerAgent(agentId, this);
            log.info("Agent 认证成功: {}", agentId);
        } catch (Exception e) {
            log.error("处理 AUTH_FINISH 失败", e);
        }
    }

    private void handlePtyListResponse(TcpMessage msg) {
        agentManager.updatePtyList(agentId, msg.getData());
    }

    private void handlePtyOutput(TcpMessage msg) {
        agentManager.forwardPtyOutput(agentId, msg.getPtyId(), msg.getData());
    }

    private void handlePtyVisibilityChanged(TcpMessage msg) {
        if (msg.getRemoteViewable() != null && !msg.getRemoteViewable()) {
            agentManager.handlePtyVisibilityDisabled(agentId, msg.getPtyId());
            log.info("终端 {}:{} 已关闭远端可见", agentId, msg.getPtyId());
        }
    }

    private void sendHeartbeatResponse() {
        TcpMessage msg = new TcpMessage();
        msg.setType(TcpMessageType.HEARTBEAT);
        sendMessage(msg, true);
    }

    public void sendMessage(TcpMessage msg, boolean encrypt) {
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

    public void requestPtyList() {
        TcpMessage msg = new TcpMessage();
        msg.setType(TcpMessageType.PTY_LIST_REQUEST);
        sendMessage(msg, true);
    }

    public void sendPtyInput(String ptyId, String data) {
        TcpMessage msg = new TcpMessage();
        msg.setType(TcpMessageType.PTY_INPUT);
        msg.setPtyId(ptyId);
        msg.setData(data);
        sendMessage(msg, true);
    }

    public void sendPtyResize(String ptyId, int cols, int rows) {
        TcpMessage msg = new TcpMessage();
        msg.setType(TcpMessageType.PTY_RESIZE);
        msg.setPtyId(ptyId);
        msg.setCols(cols);
        msg.setRows(rows);
        sendMessage(msg, true);
    }

    public void sendPtyClose(String ptyId) {
        TcpMessage msg = new TcpMessage();
        msg.setType(TcpMessageType.PTY_CLOSE);
        msg.setPtyId(ptyId);
        sendMessage(msg, true);
    }

    public void sendPtyAttach(String ptyId) {
        TcpMessage msg = new TcpMessage();
        msg.setType(TcpMessageType.PTY_ATTACH);
        msg.setPtyId(ptyId);
        sendMessage(msg, true);
    }

    public void sendPtyDetach(String ptyId) {
        TcpMessage msg = new TcpMessage();
        msg.setType(TcpMessageType.PTY_DETACH);
        msg.setPtyId(ptyId);
        sendMessage(msg, true);
    }

    @Override
    public void readFailed(Throwable e, ReadProcessorNode next) {
        log.error("Agent 连接断开: {}", agentId, e);
        if (agentId != null) {
            agentManager.unregisterAgent(agentId);
        }
    }

    public String getAgentId() {
        return agentId;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }
}
