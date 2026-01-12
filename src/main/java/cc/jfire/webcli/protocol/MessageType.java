package cc.jfire.webcli.protocol;

public enum MessageType {
    PTY_OUTPUT,
    PTY_INPUT,
    PTY_RESIZE,
    PTY_CREATE,
    PTY_CLOSE,
    PTY_LIST,
    PTY_SWITCH,
    ERROR,
    SUCCESS
}
