package cc.jfire.webcli.protocol;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PtyInfo {
    private String id;
    private String name;
    private boolean alive;
    private boolean remoteViewable;
    private boolean remoteCreated;

    public PtyInfo(String id, String name, boolean alive) {
        this.id = id;
        this.name = name;
        this.alive = alive;
        this.remoteViewable = false;
        this.remoteCreated = false;
    }

    public PtyInfo(String id, String name, boolean alive, boolean remoteViewable) {
        this.id = id;
        this.name = name;
        this.alive = alive;
        this.remoteViewable = remoteViewable;
        this.remoteCreated = false;
    }
}
