package com.ruyicode.filehistory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 rewind 回滚到文件创建之前的快照时，新建的文件会被删除。 */
class FileHistoryRewindTest {

    @Test
    void rewindDeletesFileCreatedAfterTargetSnapshot(@TempDir Path base) throws IOException {
        Path projectDir = base.resolve("project");
        Files.createDirectories(projectDir);
        FileHistory fh = new FileHistory(base.toString(), "session-1");

        // 第一轮：没有任何文件改动，纯对话，打一个快照。
        fh.makeSnapshot(0, "第一轮");

        // 第二轮：新建一个文件。trackEdit 在写入前调用，此时文件还不存在。
        Path newFile = projectDir.resolve("NewFile.java");
        fh.trackEdit(newFile.toString());
        Files.writeString(newFile, "class NewFile {}");
        fh.makeSnapshot(2, "第二轮：新建文件");

        assertTrue(Files.exists(newFile));

        // 回滚到第一轮的快照，也就是这个文件创建之前的状态。
        List<String> changed = fh.rewind(0);

        assertFalse(Files.exists(newFile), "回滚到文件创建之前，文件应该被删除");
        assertTrue(changed.contains(newFile.toAbsolutePath().toString()));
    }

    @Test
    void rewindRestoresEditOnExistingFile(@TempDir Path base) throws IOException {
        Path projectDir = base.resolve("project");
        Files.createDirectories(projectDir);
        FileHistory fh = new FileHistory(base.toString(), "session-1");

        Path existing = projectDir.resolve("Existing.java");
        Files.writeString(existing, "original");

        fh.trackEdit(existing.toString());
        fh.makeSnapshot(0, "第一轮：修改前的快照");

        Files.writeString(existing, "modified");
        fh.makeSnapshot(2, "第二轮：改了内容");

        List<String> changed = fh.rewind(0);

        assertEquals("original", Files.readString(existing));
        assertTrue(changed.contains(existing.toAbsolutePath().toString()));
    }

    @Test
    void rewindToLatestSnapshotKeepsCreatedFile(@TempDir Path base) throws IOException {
        Path projectDir = base.resolve("project");
        Files.createDirectories(projectDir);
        FileHistory fh = new FileHistory(base.toString(), "session-1");

        fh.makeSnapshot(0, "第一轮");

        Path newFile = projectDir.resolve("NewFile.java");
        fh.trackEdit(newFile.toString());
        Files.writeString(newFile, "class NewFile {}");
        fh.makeSnapshot(2, "第二轮：新建文件");

        // 回滚到文件创建之后的这个快照本身，文件应该保留（内容还原成当时写入的内容）。
        fh.rewind(1);

        assertTrue(Files.exists(newFile));
        assertEquals("class NewFile {}", Files.readString(newFile));
    }
}
