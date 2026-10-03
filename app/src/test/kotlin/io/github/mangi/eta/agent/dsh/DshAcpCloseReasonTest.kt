package io.github.mangi.eta.agent.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACP 子进程断开时给出的原因。
 *
 * 回归背景：以前无论 su 被 root 管理器拒了、chroot 失败，还是随包 node 崩了，界面都只有
 * 一句「initialize 失败：protocol-eof」——退出码与子进程最后的 stderr 全被丢掉，
 * 用户看不出该修什么，logcat 里也只有一个 EOF。这里把「退出码 + stderr 尾部」钉住。
 */
class DshAcpCloseReasonTest {

    @Test
    fun exitCodeAndStderrTailBothReachTheMessage() {
        val reason = describeClose(
            exited = true,
            exitCode = 1,
            stderrTail = listOf("chroot: failed to run command", "Error: EPERM"),
        )

        assertTrue("退出码丢了：$reason", reason.contains("进程退出码 1"))
        assertTrue("stderr 尾部丢了：$reason", reason.contains("Error: EPERM"))
        assertTrue("前面几行 stderr 也该在：$reason", reason.contains("chroot: failed to run command"))
    }

    @Test
    fun missingStderrStillReportsTheExitCode() {
        val reason = describeClose(exited = true, exitCode = 127, stderrTail = emptyList())

        assertEquals("protocol-eof（进程退出码 127）", reason)
    }

    @Test
    fun aSurvivingProcessIsNotReportedAsAnExit() {
        val reason = describeClose(exited = false, exitCode = -1, stderrTail = emptyList())

        assertFalse("没退出的进程不该报退出码：$reason", reason.contains("退出码"))
        assertTrue("要说清是 stdout 断了：$reason", reason.contains("stdout"))
    }
}
