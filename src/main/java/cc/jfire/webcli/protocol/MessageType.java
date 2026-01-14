package cc.jfire.webcli.protocol;

public enum MessageType {
    PTY_OUTPUT,
    PTY_INPUT,
    PTY_RESIZE,
    PTY_CREATE,
    PTY_CLOSE,
    PTY_LIST,
    PTY_SWITCH,
    PTY_ATTACH,
    PTY_RENAME,
    PTY_SET_REMOTE_VIEWABLE,  // 设置终端可远程查看属性
    PTY_REMOTE_LIST,          // 获取可远程查看的终端列表
    PTY_VISIBILITY_DISABLED,  // 终端远端可见性已禁用
    ERROR,
    SUCCESS
}
