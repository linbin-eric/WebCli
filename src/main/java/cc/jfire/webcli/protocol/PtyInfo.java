package cc.jfire.webcli.protocol;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PtyInfo {
    private String id;
    private boolean alive;
}
