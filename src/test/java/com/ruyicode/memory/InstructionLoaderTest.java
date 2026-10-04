package com.ruyicode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 指令文件的发现顺序：越靠后加载的优先级越高。 */
class InstructionLoaderTest {

    /** 建一个空 git 仓库，让 projectInstructionDirs 能定位到项目根。 */
    private void initGit(Path dir) throws IOException, InterruptedException {
        new ProcessBuilder("git", "init")
                .directory(dir.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor();
    }

    private void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    @Test
    void 同目录下_ruyicode目录里的文件排在后面(@TempDir Path dir) throws Exception {
        initGit(dir);
        write(dir.resolve("RUYICODE.md"), "plain file");
        write(dir.resolve(".ruyicode").resolve("RUYICODE.md"), "dotdir file");

        String out = InstructionLoader.loadInstructions(dir.toString());
        assertTrue(out.contains("plain file"));
        assertTrue(out.contains("dotdir file"));
        assertTrue(out.indexOf("plain file") < out.indexOf("dotdir file"),
                ".ruyicode/RUYICODE.md 应排在 RUYICODE.md 之后");
    }

    @Test
    void ruyicode目录里的文件参与逐级遍历(@TempDir Path root) throws Exception {
        initGit(root);
        Path sub = root.resolve("pkg").resolve("deep");
        Files.createDirectories(sub);
        write(root.resolve(".ruyicode").resolve("RUYICODE.md"), "dotdir root");
        write(sub.resolve(".ruyicode").resolve("RUYICODE.md"), "dotdir leaf");

        String out = InstructionLoader.loadInstructions(sub.toString());
        assertTrue(out.indexOf("dotdir root") < out.indexOf("dotdir leaf"),
                "深层目录的 .ruyicode/RUYICODE.md 应排在浅层之后");
    }
}
