package com.wenshan.upload.terminal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TerminalPathGuard 单元测试：非安全目录矩阵、/rootA-evil 前缀绕过、.. 逃逸、重定向、symlink 逃逸。
 */
class TerminalPathGuardTest {

    @TempDir
    Path tempDir;

    private Path rootA;
    private Path rootB;
    private TerminalPathGuard guardFromRootA;

    @BeforeEach
    void setUp() throws IOException {
        rootA = Files.createDirectories(tempDir.resolve("rootA")).toRealPath();
        rootB = Files.createDirectories(tempDir.resolve("rootB")).toRealPath();
        Files.createDirectories(rootA.resolve("docs"));
        Files.writeString(rootA.resolve("a.txt"), "a");
        Files.writeString(rootA.resolve("docs/a.txt"), "a-doc");
        Files.writeString(rootB.resolve("b.txt"), "b");
        // 根内指向系统目录的 symlink：必须按真实路径拒绝
        Files.createSymbolicLink(rootA.resolve("link-etc"), Path.of("/etc"));
        List<Path> roots = List.of(rootA, rootB);
        guardFromRootA = new TerminalPathGuard(roots, rootA);
    }

    @Test
    void allowsCommandsInsideRoots() {
        List<String> allowed = List.of(
                "ls",
                "ls -la",
                "pwd",
                "cat a.txt",
                "cat docs/a.txt",
                "echo hi > out.txt",
                "echo hi >> out.txt",
                "cat < a.txt",
                "echo hi 2>&1",
                "cd docs",
                "find . -name '*.log'",
                "git status",
                "ls | grep txt",
                "rm -rf ./build",
                "/bin/echo ok",
                "/usr/bin/env",
                "cd " + rootB,
                "cat " + rootB.resolve("b.txt"));
        for (String command : allowed) {
            assertThat(guardFromRootA.violation(command))
                    .as("应允许: %s", command)
                    .isEmpty();
        }
    }

    @Test
    void blocksAbsolutePathsOutsideRoots() {
        assertViolation("ls /", "/");
        assertViolation("cat /etc/hosts", "/etc/hosts");
        assertViolation("cd /tmp", "/tmp");
        assertViolation("find / -name x", "/");
        assertViolation("echo hi > /tmp/x", "/tmp/x");
        assertViolation("echo hi 2>/etc/x", "/etc/x");
        assertViolation("cat /etc/passwd /rootB/b.txt", "/etc/passwd");
    }

    @Test
    void blocksRelativeEscapeAndRedirectOutside() {
        assertViolation("cat ../x", "../x");
        assertViolation("echo hi > ../outside.txt", "../outside.txt");
        assertViolation("echo hi 2>../../outside.txt", "../../outside.txt");
        assertViolation("cat ../../etc/hosts", "../../etc/hosts");
    }

    @Test
    void blocksHomeTokens() {
        assertViolation("cat ~/secret.txt", "~/secret.txt");
        assertViolation("cat ~", "~");
        assertViolation("cat $HOME/secret.txt", "$HOME/secret.txt");
        assertViolation("cat ${HOME}/secret.txt", "${HOME}/secret.txt");
    }

    @Test
    void blocksPrefixLookalikeRoot() throws IOException {
        Path evil = Files.createDirectories(tempDir.resolve("rootA-evil"));
        Files.writeString(evil.resolve("x.txt"), "evil");
        assertViolation("cat " + evil.resolve("x.txt"), evil.resolve("x.txt").toString());
        // 根内再 chdir 到 rootB 合法；但 rootA-evil 不是根
        assertViolation("cat " + rootA + "-evil/x.txt", rootA + "-evil/x.txt");
    }

    @Test
    void blocksSymlinkEscapeWithinRoot() {
        assertViolation("cat link-etc/hosts", "link-etc/hosts");
        assertViolation("cat " + rootA.resolve("link-etc/hosts"), rootA.resolve("link-etc/hosts").toString());
        assertViolation("echo hi > link-etc/newfile", "link-etc/newfile");
    }

    @Test
    void allowsCrossRootAndDoesNotCheckCommandItself() {
        // 命令词本身可以是根外绝对路径（/bin/ls 是系统可执行文件）
        assertThat(guardFromRootA.violation("/bin/ls .")).isEmpty();
        assertThat(guardFromRootA.violation("/usr/bin/env echo hi")).isEmpty();
        // 跨根路径都安全
        assertThat(guardFromRootA.violation("cd " + rootB)).isEmpty();
        assertThat(guardFromRootA.violation("cat " + rootB.resolve("b.txt")))
                .isEmpty();
    }

    @Test
    void messageMatchesContract() {
        assertThat(TerminalPathGuard.message("/etc/hosts"))
                .isEqualTo("命令涉及非安全目录：/etc/hosts（终端仅允许访问 app.storage.roots 内的目录）");
    }

    private void assertViolation(String command, String expectedToken) {
        var violation = guardFromRootA.violation(command);
        assertThat(violation).as("应拦截: %s", command).isPresent();
        assertThat(violation.get()).as("token 应匹配: %s", command).isEqualTo(expectedToken);
    }
}
