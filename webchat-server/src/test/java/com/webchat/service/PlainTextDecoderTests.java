package com.webchat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文本解码的单元测试。
 *
 * <p>它是「这个文件算不算纯文本」这条判据的实现，也是后续切分、向量化取文的唯一来源——
 * 上传校验与索引两处共用同一个实例，测试钉住的是这两处共同依赖的那条底线：
 * 解不出来就返回空（宁可拒收），绝不用替换字符凑出一个「看起来成功」的结果。
 */
class PlainTextDecoderTests {

    @Test
    @DisplayName("UTF-8 与 GB18030 都能解出来：中文 Windows 存出来的 txt 多半是后者")
    void decodesBothCandidateCharsets() {
        assertThat(PlainTextDecoder.decode("会议纪要".getBytes(StandardCharsets.UTF_8)))
                .contains("会议纪要");
        assertThat(PlainTextDecoder.decode("会议纪要".getBytes(Charset.forName("GB18030"))))
                .contains("会议纪要");
    }

    @Test
    @DisplayName("剥掉 UTF-8 的 BOM：记事本存「UTF-8」时默认写它，不剥会污染正文开头")
    void stripsUtf8Bom() {
        byte[] withBom = ("\uFEFF会议纪要").getBytes(StandardCharsets.UTF_8);

        String decoded = PlainTextDecoder.decode(withBom).orElseThrow();

        assertThat(decoded).isEqualTo("会议纪要");
        assertThat(decoded.charAt(0)).isNotEqualTo('\uFEFF');
    }

    @Test
    @DisplayName("含 NUL 字节的内容一律当二进制，别想蒙混成文本")
    void rejectsNullBytes() {
        assertThat(PlainTextDecoder.decode(new byte[]{'a', 0, 'b'})).isEmpty();
    }

    @Test
    @DisplayName("UTF-16 一律拒收：带 BOM 的按标记挡下，含 ASCII 的按 NUL 挡下")
    void rejectsUtf16() {
        // 记事本存 UTF-16 必写 BOM。纯中文的 UTF-16 一个 NUL 都没有，
        // 少了这条判据它会被当成 GB18030「成功」解出一段乱码——比解码失败更糟
        assertThat(PlainTextDecoder.decode("笔记".getBytes(StandardCharsets.UTF_16))).isEmpty();
        assertThat(PlainTextDecoder.decode(new byte[]{(byte) 0xFF, (byte) 0xFE, 0x41, 0x00})).isEmpty();
        // 含 ASCII 的 UTF-16 靠 NUL 判据挡下
        assertThat(PlainTextDecoder.decode("a".getBytes(StandardCharsets.UTF_16LE))).isEmpty();
    }

    @Test
    @DisplayName("两种字符集都解不出来（有非法字节序列）就返回空，而不是留下替换字符")
    void rejectsUndecodableBytes() {
        // 0xC3 后面跟 0x28 不是合法的 UTF-8 续接字节，GB18030 也组不成合法双字节
        assertThat(PlainTextDecoder.decode(new byte[]{(byte) 0xC3, (byte) 0x28, (byte) 0xA0}))
                .isEmpty();
    }

    @Test
    @DisplayName("空内容不算文本，返回空")
    void rejectsEmptyContent() {
        assertThat(PlainTextDecoder.decode(new byte[0])).isEmpty();
    }

    @Test
    @DisplayName("换行与制表符是合法文本的一部分，不能当成二进制挡掉")
    void keepsWhitespaceCharacters() {
        assertThat(PlainTextDecoder.decode("第一行\r\n\t缩进".getBytes(StandardCharsets.UTF_8)))
                .contains("第一行\r\n\t缩进");
    }
}
