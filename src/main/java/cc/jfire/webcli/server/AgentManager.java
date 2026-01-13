package cc.jfire.webcli.server;

import cc.jfire.baseutil.reflect.TypeUtil;
import cc.jfire.dson.Dson;
import cc.jfire.webcli.protocol.PtyInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

@Slf4j
public class AgentManager {
    private final Map<String, ServerTcpHandler> agents = new ConcurrentHashMap<>();
    private final Map<String, List<PtyInfo>> agentPtyLists = new ConcurrentHashMap<>();
    private final Map<String, BiConsumer<String, String>> ptyOutputListeners = new ConcurrentHashMap<>();

    public void registerAgent(String agentId, ServerTcpHandler handler) {
        agents.put(agentId, handler);
        log.info("Agent 已注册: {}", agentId);
    }

    public void unregisterAgent(String agentId) {
        agents.remove(agentId);
        agentPtyLists.remove(agentId);
        log.info("Agent 已注销: {}", agentId);
    }

    public void updatePtyList(String agentId, String ptyListJson) {
        try {
            List<PtyInfo> list = Dson.fromString(new TypeUtil<List<PtyInfo>>(){}.getType(), ptyListJson);
            agentPtyLists.put(agentId, list);
        } catch (Exception e) {
            log.error("解析 PTY 列表失败", e);
        }
    }

    public List<PtyInfo> getAllRemotePtys() {
        List<PtyInfo> result = new ArrayList<>();
        for (Map.Entry<String, List<PtyInfo>> entry : agentPtyLists.entrySet()) {
            String agentId = entry.getKey();
            for (PtyInfo pty : entry.getValue()) {
                // 创建新的 PtyInfo，ID 前缀加上 agentId
                PtyInfo remotePty = new PtyInfo(
                        agentId + ":" + pty.getId(),
                        pty.getName(),
                        pty.isAlive(),
                        pty.isRemoteViewable()
                );
                result.add(remotePty);
            }
        }
        return result;
    }

    public void refreshAllPtyLists() {
        for (ServerTcpHandler handler : agents.values()) {
            if (handler.isAuthenticated()) {
                handler.requestPtyList();
            }
        }
    }

    public void forwardPtyOutput(String agentId, String ptyId, String data) {
        String fullPtyId = agentId + ":" + ptyId;
        BiConsumer<String, String> listener = ptyOutputListeners.get(fullPtyId);
        if (listener != null) {
            listener.accept(fullPtyId, data);
        }
    }

    public void registerPtyOutputListener(String fullPtyId, BiConsumer<String, String> listener) {
        ptyOutputListeners.put(fullPtyId, listener);
    }

    public void unregisterPtyOutputListener(String fullPtyId) {
        ptyOutputListeners.remove(fullPtyId);
    }

    public ServerTcpHandler getAgentHandler(String agentId) {
        return agents.get(agentId);
    }

    public String[] parseFullPtyId(String fullPtyId) {
        int idx = fullPtyId.indexOf(':');
        if (idx > 0) {
            return new String[]{fullPtyId.substring(0, idx), fullPtyId.substring(idx + 1)};
        }
        return null;
    }
}
