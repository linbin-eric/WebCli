package cc.jfire.webcli.server;

import cc.jfire.baseutil.Resource;
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
@Resource
public class AgentManager
{
    private final Map<String, ServerTcpHandler>           agents                      = new ConcurrentHashMap<>();
    private final Map<String, List<PtyInfo>>              agentPtyLists               = new ConcurrentHashMap<>();
    private final Map<String, BiConsumer<String, String>> ptyOutputListeners          = new ConcurrentHashMap<>();
    private final Map<String, BiConsumer<String, String>> visibilityDisabledCallbacks = new ConcurrentHashMap<>();
    // 记录每个 Agent 当前被 attach 的 ptyId 列表（不含 agentId 前缀）
    private final Map<String, List<String>>               agentAttachedPtys           = new ConcurrentHashMap<>();

    public void registerAgent(String agentId, ServerTcpHandler handler)
    {
        agents.put(agentId, handler);
        log.info("Agent 已注册: {}", agentId);
        // 检查是否有之前 attach 的终端需要重新 attach
        reattachPtysForAgent(agentId, handler);
    }

    private void reattachPtysForAgent(String agentId, ServerTcpHandler handler)
    {
        List<String> attachedPtys = agentAttachedPtys.get(agentId);
        if (attachedPtys != null && !attachedPtys.isEmpty())
        {
            log.info("Agent {} 重连，重新 attach {} 个终端", agentId, attachedPtys.size());
            for (String ptyId : attachedPtys)
            {
                handler.sendPtyAttach(ptyId);
                log.debug("重新发送 PTY_ATTACH: {}", ptyId);
            }
        }
    }

    public void unregisterAgent(String agentId)
    {
        agents.remove(agentId);
        agentPtyLists.remove(agentId);
        log.info("Agent 已注销: {}", agentId);
    }

    public void updatePtyList(String agentId, String ptyListJson)
    {
        try
        {
            List<PtyInfo> list = Dson.fromString(new TypeUtil<List<PtyInfo>>()
            {
            }.getType(), ptyListJson);
            agentPtyLists.put(agentId, list);
        }
        catch (Exception e)
        {
            log.error("解析 PTY 列表失败", e);
        }
    }

    public List<PtyInfo> getAllRemotePtys()
    {
        List<PtyInfo> result = new ArrayList<>();
        for (Map.Entry<String, List<PtyInfo>> entry : agentPtyLists.entrySet())
        {
            String agentId = entry.getKey();
            for (PtyInfo pty : entry.getValue())
            {
                // 创建新的 PtyInfo，ID 前缀加上 agentId
                PtyInfo remotePty = new PtyInfo(agentId + ":" + pty.getId(), pty.getName(), pty.isAlive(), pty.isRemoteViewable());
                result.add(remotePty);
            }
        }
        return result;
    }

    public void refreshAllPtyLists()
    {
        for (ServerTcpHandler handler : agents.values())
        {
            if (handler.isAuthenticated())
            {
                handler.requestPtyList();
            }
        }
    }

    public void forwardPtyOutput(String agentId, String ptyId, String data)
    {
        String                     fullPtyId = agentId + ":" + ptyId;
        BiConsumer<String, String> listener  = ptyOutputListeners.get(fullPtyId);
        if (listener != null)
        {
            listener.accept(fullPtyId, data);
        }
    }

    public void registerPtyOutputListener(String fullPtyId, BiConsumer<String, String> listener)
    {
        ptyOutputListeners.put(fullPtyId, listener);
    }

    public void unregisterPtyOutputListener(String fullPtyId)
    {
        ptyOutputListeners.remove(fullPtyId);
    }

    /**
     * 记录某个终端被 attach
     */
    public void recordPtyAttach(String agentId, String ptyId)
    {
        agentAttachedPtys.computeIfAbsent(agentId, k -> new ArrayList<>()).add(ptyId);
        log.debug("记录 PTY attach: agentId={}, ptyId={}", agentId, ptyId);
    }

    /**
     * 移除某个终端的 attach 记录
     */
    public void removePtyAttach(String agentId, String ptyId)
    {
        List<String> ptys = agentAttachedPtys.get(agentId);
        if (ptys != null)
        {
            ptys.remove(ptyId);
            if (ptys.isEmpty())
            {
                agentAttachedPtys.remove(agentId);
            }
        }
        log.debug("移除 PTY attach 记录: agentId={}, ptyId={}", agentId, ptyId);
    }

    public void handlePtyVisibilityDisabled(String agentId, String ptyId)
    {
        String fullPtyId = agentId + ":" + ptyId;
        // 移除输出监听器
        ptyOutputListeners.remove(fullPtyId);
        // 通知所有订阅该终端的远端客户端
        notifyVisibilityDisabled(fullPtyId);
    }

    public void notifyVisibilityDisabled(String fullPtyId)
    {
        BiConsumer<String, String> callback = visibilityDisabledCallbacks.get(fullPtyId);
        if (callback != null)
        {
            callback.accept(fullPtyId, "VISIBILITY_DISABLED");
            visibilityDisabledCallbacks.remove(fullPtyId);
        }
    }

    public void registerVisibilityDisabledCallback(String fullPtyId, BiConsumer<String, String> callback)
    {
        visibilityDisabledCallbacks.put(fullPtyId, callback);
    }

    public void unregisterVisibilityDisabledCallback(String fullPtyId)
    {
        visibilityDisabledCallbacks.remove(fullPtyId);
    }

    public ServerTcpHandler getAgentHandler(String agentId)
    {
        return agents.get(agentId);
    }

    public String[] parseFullPtyId(String fullPtyId)
    {
        int idx = fullPtyId.indexOf(':');
        if (idx > 0)
        {
            return new String[]{fullPtyId.substring(0, idx), fullPtyId.substring(idx + 1)};
        }
        return null;
    }
}
