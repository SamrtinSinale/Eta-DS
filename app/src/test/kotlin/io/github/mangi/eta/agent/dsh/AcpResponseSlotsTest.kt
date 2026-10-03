package io.github.mangi.eta.agent.dsh

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ACP 在途请求的结果槽。
 *
 * 回归背景：老实现是 `ConcurrentHashMap<Long, Channel<JSONObject>>` + `Channel.RENDEZVOUS`，
 * 回包时 `pending.remove(id)?.trySend(message)`。零容量通道的 `trySend` **只在已经有接收者
 * 挂在 `receive()` 上时才成功**，而 `request()` 的顺序是先 `send()` 再 `receive()`——
 * dsh 秒回时（`job_output` 这种读完就返回的工具就是）结果被静默丢掉，调用方随后原地等到
 * 超时：`session/prompt` 是 **30 分钟**。表现就是"Heta 卡住了"。
 *
 * 这里把「结果早于 await 到达也不能丢」钉死。谁把结果槽换回零容量通道，第一个用例就会红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AcpResponseSlotsTest {

    private fun result(ok: Boolean = true): JSONObject =
        JSONObject().put("result", JSONObject().put("ok", ok))

    @Test
    fun resultThatArrivesBeforeAnyoneAwaitsIsStillThere() = runBlocking {
        val slots = AcpResponseSlots()
        val slot = slots.register(7)

        // 关键顺序：先回包，再去取。老实现在这一步就把结果丢了。
        assertTrue("没人等着也应该收下结果", slots.settle(7, result()))

        val response = withTimeout(1_000) { slot.await() }
        assertTrue("结果应该原样等在槽里", response.getJSONObject("result").getBoolean("ok"))
    }

    @Test
    fun failingAllUnsticksEveryInFlightRequest() = runBlocking {
        val slots = AcpResponseSlots()
        val first = slots.register(1)
        val second = slots.register(2)

        slots.failAll("已停止")

        // 老实现的 failPending 也是 trySend，同样可能把"已停止"丢掉，界面于是继续卡着。
        val one = withTimeout(1_000) { first.await() }
        val two = withTimeout(1_000) { second.await() }
        assertEquals("已停止", one.getJSONObject("error").getString("message"))
        assertEquals("已停止", two.getJSONObject("error").getString("message"))
    }

    @Test
    fun settlingAnUnknownRequestIsANoOp() {
        val slots = AcpResponseSlots()
        slots.register(3)
        slots.forget(3)

        // 超时之后 dsh 才回包：没人再等这个结果了，不该报错也不该留在表里。
        assertFalse(slots.settle(3, result()))
        assertFalse(slots.settle(4, result()))
    }

    @Test
    fun settlingTwiceKeepsTheFirstResult() = runBlocking {
        val slots = AcpResponseSlots()
        val slot = slots.register(5)

        assertTrue(slots.settle(5, result(ok = true)))
        assertFalse("第二次结算应该被拒", slots.settle(5, result(ok = false)))
        assertTrue(withTimeout(1_000) { slot.await() }.getJSONObject("result").getBoolean("ok"))
    }
}
