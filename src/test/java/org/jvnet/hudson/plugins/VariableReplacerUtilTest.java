package org.jvnet.hudson.plugins;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class VariableReplacerUtilTest {
    String onelineCommand = "cd /dir/${subdir}";

    @Rule
    public JenkinsRule rule = new JenkinsRule();

    @Test
    public void nullVarsDontChangeTheCommand() throws Exception {
        Map<String, String> vars = null;
        String finalCommand = VariableReplacerUtil.preludeWithEnvVars(onelineCommand, vars);
        assertEquals(finalCommand, onelineCommand);
    }

    @Test
    public void emptyVarsAddWhitespaceButDontChangeTheCommand() throws Exception {
        Map<String, String> vars = Collections.emptyMap();
        String finalCommand = VariableReplacerUtil.preludeWithEnvVars(onelineCommand, vars);
        assertEquals("\n".concat(onelineCommand), finalCommand);
    }

    @Test
    public void envVarsPreludeOriginalCommand() throws Exception {
        Map<String, String> vars = new HashMap<String, String>();
        vars.put("subdir", "child");
        vars.put("test", "value");
        String finalCommand = VariableReplacerUtil.preludeWithEnvVars(onelineCommand, vars);
        assertEquals("subdir=\"child\"\n\n".concat(onelineCommand), finalCommand);
    }
}
