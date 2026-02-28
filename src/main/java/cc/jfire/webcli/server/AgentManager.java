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
    private final Map<String, ServerTcpHandler>                                                   agents                      = new ConcurrentHashMap<>();
    private final Map<String, List<PtyInfo>>                                                      agentPtyLists               = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentHashMap<String, BiConsumer<String, String>>>             ptyOutputListeners          = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentHashMap<String, BiConsumer<String, String>>>             visibilityDisabledCallbacks = new ConcurrentHashMap<>();
    // 记录每个 Agent 当前被 attach 的 ptyId 及其引用计数（不含 agentId 前缀）
    private final Map<String, ConcurrentHashMap<String, Integer>>                                agentAttachedPtys           = new ConcurrentHashMap<>();

    /**
     * 尝试注册 Agent（同名不覆盖）。
     *
     * @return true 表示注册成功；false 表示 agentId 已存在
     */
    public boolean tryRegisterAgent(String agentId, ServerTcpHandler handler)
    {
        ServerTcpHandler existing = agents.putIfAbsent(agentId, handler);
        if (existing != null)
        {
            return false;
        }
        log.info("Agent 已注册: {}", agentId);
        // 检查是否有之前 attach 的终端需要重新 attach
        reattachPtysForAgent(agentId, handler);
        return true;
    }

    private void reattachPtysForAgent(String agentId, ServerTcpHandler handler)
    {
        ConcurrentHashMap<String, Integer> attachedPtys = agentAttachedPtys.get(agentId);
        if (attachedPtys != null && !attachedPtys.isEmpty())
        {
            List<String> ptyIds = new ArrayList<>(attachedPtys.keySet());
            log.info("Agent {} 重连，重新 attach {} 个终端", agentId, ptyIds.size());
            for (String ptyId : ptyIds)
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
                PtyInfo remotePty = new PtyInfo(agentId + ":" + pty.getId(), pty.getName(), pty.isAlive(), pty.isRemoteViewable(), pty.isRemoteCreated());
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
        String fullPtyId = agentId + ":" + ptyId;
        ConcurrentHashMap<String, BiConsumer<String, String>> listeners = ptyOutputListeners.get(fullPtyId);
        if (listeners != null)
        {
            listeners.values().forEach(listener -> listener.accept(fullPtyId, data));
        }
    }

    public void registerPtyOutputListener(String fullPtyId, String listenerId, BiConsumer<String, String> listener)
    {
        ptyOutputListeners.computeIfAbsent(fullPtyId, k -> new ConcurrentHashMap<>()).put(listenerId, listener);
    }

    public void unregisterPtyOutputListener(String fullPtyId, String listenerId)
    {
        ConcurrentHashMap<String, BiConsumer<String, String>> listeners = ptyOutputListeners.get(fullPtyId);
        if (listeners != null)
        {
            listeners.remove(listenerId);
            if (listeners.isEmpty())
            {
                ptyOutputListeners.remove(fullPtyId, listeners);
            }
        }
    }

    public void unregisterPtyOutputListener(String fullPtyId)
    {
        ptyOutputListeners.remove(fullPtyId);
    }

    /**
     * 记录某个终端被 attach
     */
    public synchronized boolean recordPtyAttach(String agentId, String ptyId)
    {
        ConcurrentHashMap<String, Integer> ptys = agentAttachedPtys.computeIfAbsent(agentId, k -> new ConcurrentHashMap<>());
        int nextCount = ptys.merge(ptyId, 1, Integer::sum);
        log.debug("记录 PTY attach: agentId={}, ptyId={}, count={}", agentId, ptyId, nextCount);
        return nextCount == 1;
    }

    /**
     * 移除某个终端的 attach 记录
     */
    public synchronized boolean removePtyAttach(String agentId, String ptyId)
    {
        ConcurrentHashMap<String, Integer> ptys = agentAttachedPtys.get(agentId);
        if (ptys != null)
        {
            Integer current = ptys.get(ptyId);
            if (current != null)
            {
                if (current <= 1)
                {
                    ptys.remove(ptyId);
                }
                else
                {
                    ptys.put(ptyId, current - 1);
                }
            }
            if (ptys.isEmpty())
            {
                agentAttachedPtys.remove(agentId);
            }
            boolean detached = !ptys.containsKey(ptyId);
            log.debug("移除 PTY attach 记录: agentId={}, ptyId={}, detached={}", agentId, ptyId, detached);
            return detached;
        }
        log.debug("移除 PTY attach 记录: agentId={}, ptyId={}, detached=true(无记录)", agentId, ptyId);
        return true;
    }

    /**
     * 清空某个终端的 attach 计数（用于强制下线场景）
     */
    public synchronized void clearPtyAttach(String agentId, String ptyId)
    {
        ConcurrentHashMap<String, Integer> ptys = agentAttachedPtys.get(agentId);
        if (ptys == null)
        {
            return;
        }
        ptys.remove(ptyId);
        if (ptys.isEmpty())
        {
            agentAttachedPtys.remove(agentId);
        }
        log.debug("清空 PTY attach 记录: agentId={}, ptyId={}", agentId, ptyId);
    }

    public void handlePtyVisibilityDisabled(String agentId, String ptyId)
    {
        String fullPtyId = agentId + ":" + ptyId;
        // 移除输出监听器
        ptyOutputListeners.remove(fullPtyId);
        // 清空 attach 计数，避免后续重连误恢复
        clearPtyAttach(agentId, ptyId);
        // 通知所有订阅该终端的远端客户端
        notifyVisibilityDisabled(fullPtyId);
    }

    public void notifyVisibilityDisabled(String fullPtyId)
    {
        ConcurrentHashMap<String, BiConsumer<String, String>> callbacks = visibilityDisabledCallbacks.remove(fullPtyId);
        if (callbacks != null)
        {
            callbacks.values().forEach(callback -> callback.accept(fullPtyId, "VISIBILITY_DISABLED"));
        }
    }

    public void registerVisibilityDisabledCallback(String fullPtyId, String callbackId, BiConsumer<String, String> callback)
    {
        visibilityDisabledCallbacks.computeIfAbsent(fullPtyId, k -> new ConcurrentHashMap<>()).put(callbackId, callback);
    }

    public void unregisterVisibilityDisabledCallback(String fullPtyId, String callbackId)
    {
        ConcurrentHashMap<String, BiConsumer<String, String>> callbacks = visibilityDisabledCallbacks.get(fullPtyId);
        if (callbacks != null)
        {
            callbacks.remove(callbackId);
            if (callbacks.isEmpty())
            {
                visibilityDisabledCallbacks.remove(fullPtyId, callbacks);
            }
        }
    }

    public void unregisterVisibilityDisabledCallback(String fullPtyId)
    {
        visibilityDisabledCallbacks.remove(fullPtyId);
    }

    public ServerTcpHandler getAgentHandler(String agentId)
    {
        return agents.get(agentId);
    }

    public List<String> getAgentIds()
    {
        return agents.keySet().stream().sorted().toList();
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

    public void upsertPty(String agentId, String ptyId, String name, boolean alive, boolean remoteViewable)
    {
        upsertPty(agentId, ptyId, name, alive, remoteViewable, false);
    }

    public void upsertPty(String agentId, String ptyId, String name, boolean alive, boolean remoteViewable, boolean remoteCreated)
    {
        agentPtyLists.compute(agentId, (k, oldList) -> {
            List<PtyInfo> list = oldList != null ? new ArrayList<>(oldList) : new ArrayList<>();
            boolean found = false;
            for (int i = 0; i < list.size(); i++)
            {
                PtyInfo existing = list.get(i);
                if (existing != null && ptyId.equals(existing.getId()))
                {
                    existing.setName(name);
                    existing.setAlive(alive);
                    existing.setRemoteViewable(remoteViewable);
                    existing.setRemoteCreated(remoteCreated);
                    found = true;
                    break;
                }
            }
            if (!found)
            {
                list.add(new PtyInfo(ptyId, name, alive, remoteViewable, remoteCreated));
            }
            return list;
        });
    }

    public void updatePtyName(String agentId, String ptyId, String name)
    {
        agentPtyLists.computeIfPresent(agentId, (k, oldList) -> {
            List<PtyInfo> list = new ArrayList<>(oldList);
            for (PtyInfo pty : list)
            {
                if (pty != null && ptyId.equals(pty.getId()))
                {
                    pty.setName(name);
                }
            }
            return list;
        });
    }
}
