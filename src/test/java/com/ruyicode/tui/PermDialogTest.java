package com.ruyicode.tui;

import com.ruyicode.permission.PermissionReply;
import com.ruyicode.permission.PermissionResponse;
import com.ruyicode.tui.tea.KeyPressMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class PermDialogTest {

    private RuyiCodeModel model;
    private CompletableFuture<PermissionReply> future;

    @BeforeEach
    void setUp() {
        model = new RuyiCodeModel(List.of(), null, null, false, false);
        future = new CompletableFuture<>();
        model.openPermDialogForTest("Bash", "rm -rf build", future);
    }

    private void press(String key) {
        model.handlePermDialogKey(new KeyPressMessage(key, null));
    }

    private void type(String text) {
        text.codePoints().forEach(cp -> {
            char[] chars = Character.toChars(cp);
            model.handlePermDialogKey(new KeyPressMessage(new String(chars), chars));
        });
    }

    @Test
    void denyWithFeedback() {
        press("down");
        press("down");
        type("别删，jk 移到 backup😀");
        press("backspace"); // 删掉 emoji，整个码点一起删
        assertTrue(model.renderPermOptions().contains("别删，jk 移到 backup█"));
        press("enter");

        var reply = future.getNow(null);
        assertNotNull(reply);
        assertEquals(PermissionResponse.DENY, reply.response());
        assertEquals("别删，jk 移到 backup", reply.feedback());
        assertFalse(model.isPermDialogOpen());
        assertEquals("", model.permFeedbackText());
    }

    @Test
    void typingIgnoredOutsideNo() {
        type("hello");
        assertEquals("", model.permFeedbackText());
        press("enter");
        var reply = future.getNow(null);
        assertEquals(PermissionResponse.ALLOW, reply.response());
        assertEquals("", reply.feedback());
    }

    @Test
    void arrowKeysMoveCursorAndFeedbackOnlyUsedOnNo() {
        press("down");
        press("down");
        type("draft");
        press("up"); // 回到第二项，已输入的话不随答复发出
        press("enter");
        var reply = future.getNow(null);
        assertEquals(PermissionResponse.ALLOW_ALWAYS, reply.response());
        assertEquals("", reply.feedback());
    }

    @Test
    void escapeDenies() {
        press("escape");
        var reply = future.getNow(null);
        assertEquals(PermissionResponse.DENY, reply.response());
        assertFalse(model.isPermDialogOpen());
    }

    @Test
    void ctrlCWhileStreamingDeniesAndClosesDialog() {
        model.update(new KeyPressMessage("ctrl+c", null));
        var reply = future.getNow(null);
        assertNotNull(reply, "agent should be answered instead of waiting for the timeout");
        assertEquals(PermissionResponse.DENY, reply.response());
        assertFalse(model.isPermDialogOpen());
    }

    @Test
    void placeholderOnlyWhenOnNo() {
        assertFalse(model.renderPermOptions().contains("tell RuyiCode what to do differently"));
        press("down");
        press("down");
        assertTrue(model.renderPermOptions().contains("tell RuyiCode what to do differently"));
    }
}
