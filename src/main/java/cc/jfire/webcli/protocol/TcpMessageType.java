package cc.jfire.webcli.protocol;

public enum TcpMessageType {
    AUTH_REQUEST,
    AUTH_RESPONSE,
    AUTH_FINISH,
    PTY_LIST_REQUEST,
    PTY_LIST_RESPONSE,
    PTY_OUTPUT,
    PTY_INPUT,
    PTY_RESIZE,
    PTY_CLOSE,
    HEARTBEAT,
    PTY_ATTACH,
    PTY_DETACH,
    PTY_VISIBILITY_CHANGED
}
