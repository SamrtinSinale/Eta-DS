package io.github.mangi.eta.agent.dsh

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 图片消息能不能过 dsh 的 ACP 准入。
 *
 * 回归背景：以前 `DshAcpRuntime.execute` 只看 `request.prompt`，纯图片消息在发出去之前
 * 就被判成「消息为空」，用户贴图只会收到这个提示，只能改成手动发图片路径。
 * 修好之后图片要真的能发出去，而 dsh 对 image block 的格式是**严格**的：
 *
 * - `mimeType` 只能是 png/jpeg/webp/gif（见 dsh-acp `IMAGE_MEDIA_TYPES`）
 * - `data` 必须是规范 base64，dsh 会用 `Buffer.from(data,'base64').toString('base64')`
 *   回环比对，带换行或非标准 padding 一律报 "image data must be canonical base64"
 *
 * 这里把这两条约束钉在测试里，避免以后换编码器时悄悄回归。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DshAcpImagePromptTest {

    /** 逐字抄自 dsh-acp 的 `CANONICAL_BASE64`。 */
    private val canonicalBase64 =
        Regex("""^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$""")

    private fun dataUrl(mime: String, raw: ByteArray) =
        "data:$mime;base64,${Base64.encodeToString(raw, Base64.NO_WRAP)}"

    @Test
    fun attachmentReferenceBecomesAnAcpImageBlock() {
        val bytes = "fake-jpeg-bytes".toByteArray()
        val block = DshAcpRuntime.acpImage(dataUrl("image/jpeg", bytes))
        assertNotNull(block)
        assertEquals("image/jpeg", block!!.mimeType)
        assertEquals(Base64.encodeToString(bytes, Base64.NO_WRAP), block.data)
    }

    @Test
    fun everyAcceptedMimeTypeSurvivesTheRoundTrip() {
        // dsh 的白名单就是这四个；少一个都意味着某类图片会被静默丢掉。
        for (mime in listOf("image/png", "image/jpeg", "image/webp", "image/gif")) {
            val block = DshAcpRuntime.acpImage(dataUrl(mime, byteArrayOf(1, 2, 3, 4)))
            assertNotNull("mime=$mime", block)
            assertEquals(mime, block!!.mimeType)
        }
    }

    @Test
    fun mimeTypeOutsideTheRasterVocabularyIsRejected() {
        // dsh 会直接 invalidParams，所以这里必须在发出去之前就拦掉。
        for (mime in listOf("image/bmp", "image/svg+xml", "image/tiff", "text/plain")) {
            assertNull("mime=$mime", DshAcpRuntime.acpImage(dataUrl(mime, byteArrayOf(1, 2, 3))))
        }
    }

    @Test
    fun mimeTypeIsLowercasedAndTheHeaderIsCaseInsensitive() {
        val block = DshAcpRuntime.acpImage("DATA:IMAGE/PNG;BASE64,${Base64.encodeToString(byteArrayOf(9, 9), Base64.NO_WRAP)}")
        assertNotNull(block)
        assertEquals("image/png", block!!.mimeType)
    }

    @Test
    fun payloadIsAlwaysReencodedToCanonicalBase64() {
        // 带换行的 base64 人眼可读、很多编码器默认就这么输出，但 dsh 会拒收。
        val raw = ByteArray(300) { it.toByte() }
        val wrapped = Base64.encodeToString(raw, Base64.DEFAULT)
        assertTrue("前提：DEFAULT 会产生换行", wrapped.contains('\n'))

        val block = DshAcpRuntime.acpImage("data:image/png;base64,$wrapped")
        assertNotNull(block)
        val data = block!!.data
        assertTrue("不能有换行", !data.contains('\n') && !data.contains('\r'))
        assertTrue("必须是 dsh 认的规范 base64", canonicalBase64.matches(data))
        assertTrue("内容不能变", raw.contentEquals(Base64.decode(data, Base64.NO_WRAP)))
    }

    @Test
    fun referencesDshCannotAcceptInlineAreRejected() {
        // dsh 的 image block 只收内联数据：远程 URL 要客户端自己先下载。
        // 这些引用返回 null，调用方按「这张图没带上」处理，而不是发出一个必然被拒的块。
        val rejected = listOf(
            "https://example.com/cat.png",
            "http://example.com/cat.png",
            "content://media/external/images/media/42",
            "/storage/emulated/0/Pictures/cat.png",
            "file:///storage/emulated/0/Pictures/cat.png",
            "data:image/png,notbase64",
            "data:image/png;base64",
            "data:image/png;base64,",
            "data:;base64,AAAA",
            "",
        )
        for (reference in rejected) {
            assertNull(reference, DshAcpRuntime.acpImage(reference))
        }
    }

    @Test
    fun aRealOnePixelPngRoundTrips() {
        // 真实的、能被 dsh 的 decodeImage 接受的最小输入。
        val png = Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
            Base64.DEFAULT,
        )
        val block = DshAcpRuntime.acpImage(dataUrl("image/png", png))
        assertNotNull(block)
        assertTrue(canonicalBase64.matches(block!!.data))
        assertTrue(png.contentEquals(Base64.decode(block.data, Base64.NO_WRAP)))
    }
}
