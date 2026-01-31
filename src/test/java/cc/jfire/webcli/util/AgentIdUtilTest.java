package cc.jfire.webcli.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class AgentIdUtilTest
{
    @Test
    public void sanitize_shouldNormalize()
    {
        assertEquals("my-host", AgentIdUtil.sanitize(" My Host "));
        assertEquals("agent", AgentIdUtil.sanitize(":::|||"));
        assertEquals("a_b-1", AgentIdUtil.sanitize("A_B-1"));
    }

    @Test
    public void withSuffix_shouldAppendIndex()
    {
        assertEquals("myhost", AgentIdUtil.withSuffix("myhost", 1));
        assertEquals("myhost-2", AgentIdUtil.withSuffix("myhost", 2));
        assertEquals("a-b-3", AgentIdUtil.withSuffix("A B", 3));
    }
}

