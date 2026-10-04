package com.ruyicode.agent;

import com.ruyicode.conversation.ToolPairing;
import com.ruyicode.permission.PermissionChecker;
import com.ruyicode.permission.PermissionMode;
import com.ruyicode.permission.PermissionReply;
import com.ruyicode.permission.PermissionResponse;
import com.ruyicode.tool.Tool;
import com.ruyicode.tool.ToolCategory;
import com.ruyicode.tool.ToolRegistry;
import com.ruyicode.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PermissionFeedbackTest {

    private final AtomicBoolean executed = new AtomicBoolean();

    private final Tool bash = new Tool() {
        @Override public String name() { return "Bash"; }
        @Override public String description() { return ""; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }
        @Override public Map<String, Object> schema() { return Map.of(); }
        @Override public ToolResult execute(Map<String, Object> args) {
            executed.set(true);
            return new ToolResult("pushed", false);
        }
    };

    /** 执行一次需要确认的调用，用 reply 答复确认请求，返回工具结果和界面收到的结果事件 */
    private Object[] runWithReply(Path tmpDir, PermissionReply reply) throws Exception {
        Path rulesDir = tmpDir.resolve(".ruyicode");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("permissions.yaml"),
                "- rule: \"Bash(git push origin main)\"\n  effect: ask\n");
        var checker = new PermissionChecker(PermissionMode.DEFAULT, tmpDir);
        var registry = new ToolRegistry();
        registry.register(bash);
        var queue = new LinkedBlockingQueue<AgentEvent>(64);
        var executor = new StreamingExecutor(registry, checker, null, queue);

        var resultFuture = CompletableFuture.supplyAsync(() -> executor.executeAll(List.of(
                new StreamingExecutor.ToolCallInfo("t1", "Bash", Map.of("command", "git push origin main")))));

        AgentEvent.ToolResultEvent uiResult = null;
        boolean asked = false;
        while (uiResult == null) {
            var ev = queue.poll(5, TimeUnit.SECONDS);
            assertNotNull(ev, "executor produced no event in time");
            if (ev instanceof AgentEvent.PermissionRequestEvent p) {
                asked = true;
                p.future().complete(reply);
            } else if (ev instanceof AgentEvent.ToolResultEvent r) {
                uiResult = r;
            }
        }
        assertTrue(asked, "ask rule should trigger a permission request");
        var results = resultFuture.get(5, TimeUnit.SECONDS);
        return new Object[]{results.getFirst(), uiResult};
    }

    @Test
    void denyWithFeedbackIsSentToModel(@TempDir Path tmpDir) throws Exception {
        var got = runWithReply(tmpDir,
                new PermissionReply(PermissionResponse.DENY, "  push to a feature branch instead  "));
        var result = (StreamingExecutor.ToolExecResult) got[0];
        var uiResult = (AgentEvent.ToolResultEvent) got[1];

        String want = ToolPairing.REJECTED_TOOL_RESULT
                + " To tell you how to proceed, the user said:\npush to a feature branch instead";
        assertEquals(want, result.output());
        assertTrue(result.isError());
        assertEquals(want, uiResult.output(), "UI and model should see the same rejection text");
        assertFalse(executed.get(), "tool must not run after denial");
    }

    @Test
    void denyWithoutFeedbackKeepsPlainRejection(@TempDir Path tmpDir) throws Exception {
        var got = runWithReply(tmpDir, PermissionReply.of(PermissionResponse.DENY));
        var result = (StreamingExecutor.ToolExecResult) got[0];
        assertEquals(ToolPairing.REJECTED_TOOL_RESULT, result.output());
        assertFalse(executed.get());
    }

    @Test
    void allowIgnoresFeedbackAndRunsTool(@TempDir Path tmpDir) throws Exception {
        var got = runWithReply(tmpDir, PermissionReply.of(PermissionResponse.ALLOW));
        var result = (StreamingExecutor.ToolExecResult) got[0];
        assertEquals("pushed", result.output());
        assertTrue(executed.get());
    }

    @Test
    void rejectedToolResultHelper() {
        assertEquals(ToolPairing.REJECTED_TOOL_RESULT, ToolPairing.rejectedToolResult(null));
        assertEquals(ToolPairing.REJECTED_TOOL_RESULT, ToolPairing.rejectedToolResult("   "));
        assertTrue(ToolPairing.rejectedToolResult("用 git mv").endsWith("the user said:\n用 git mv"));
    }
}
