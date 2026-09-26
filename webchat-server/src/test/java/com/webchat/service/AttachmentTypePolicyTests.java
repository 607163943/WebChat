package com.webchat.service;

import com.webchat.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 类型边界的单元测试。
 *
 * <p>这是整条附件链路上唯一真正的安全边界（前端那份白名单改个请求就能绕过），
 * 所以每条拒绝路径都要有测试钉住：白名单外的一律拒收、文件头与声明对不上的也要拒收。
 */
class AttachmentTypePolicyTests {

    private final AttachmentTypePolicy policy = new AttachmentTypePolicy();

    @Test
    @DisplayName("白名单内的声明类型配上匹配的文件头：通过，并归一到规范 MIME")
    void acceptsSupportedTypes() {
        assertThat(policy.resolveMimeType("image/png", png())).isEqualTo("image/png");
        assertThat(policy.resolveMimeType("image/jpeg", jpeg())).isEqualTo("image/jpeg");
        assertThat(policy.resolveMimeType("image/webp", webp())).isEqualTo("image/webp");
        assertThat(policy.resolveMimeType("video/mp4", mp4())).isEqualTo("video/mp4");
    }

    @Test
    @DisplayName("文本没有文件头可嗅，改用「能不能严格解码」当判据：UTF-8 与 GB18030 都收")
    void acceptsDecodableText() {
        assertThat(policy.resolveMimeType("text/plain", text("这是一份会议纪要。"))).isEqualTo("text/plain");
        // GB18030 是 GBK 的超集，中文 Windows 上存出来的 txt 多半是它
        assertThat(policy.resolveMimeType("text/plain",
                "中文编码的文本".getBytes(Charset.forName("GB18030")))).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("改名成 .txt 的二进制文件要拒收：文本这条路没有文件头可对，只能靠内容判断")
    void rejectsBinaryRenamedToText() {
        // PNG 头里带 NUL 字节，任何合法文本都不会有
        assertThatThrownBy(() -> policy.resolveMimeType("text/plain", png()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是 UTF-8");
        // 纯随机的非法字节序列：两种字符集都解不出来
        assertThatThrownBy(() -> policy.resolveMimeType("text/plain",
                new byte[]{(byte) 0xC3, (byte) 0x28, (byte) 0xA0, (byte) 0xA1}))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("UTF-16 的 txt 会被拒：它必然含 NUL 字节，放进来只会得到一段乱码进向量库")
    void rejectsUtf16Text() {
        assertThatThrownBy(() -> policy.resolveMimeType("text/plain",
                "UTF-16 编码的文本".getBytes(StandardCharsets.UTF_16LE)))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("文本走的是检索这条路，顶层类型要能一眼认出来")
    void identifiesTextAsItsOwnTopLevelType() {
        assertThat(policy.topLevelType("text/plain")).contains("text");
        assertThat(policy.isText("text/plain")).isTrue();
        assertThat(policy.isText("image/png")).isFalse();
        assertThat(policy.extensionOf("text/plain")).isEqualTo("txt");
    }

    @Test
    @DisplayName("SVG 改名成 .png 再改 Content-Type 也过不了：文件头对不上")
    void rejectsSvgRenamedToPng() {
        byte[] svg = "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\"/>"
                .getBytes(StandardCharsets.UTF_8);

        // 声明是白名单里的 image/png，但内容不是 PNG —— 这正是前缀式白名单会放过去的攻击
        assertThatThrownBy(() -> policy.resolveMimeType("image/png", svg))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("顶层类型对不上要拒收：拿视频文件冒充图片同样不行")
    void rejectsTopLevelTypeMismatch() {
        assertThatThrownBy(() -> policy.resolveMimeType("image/png", mp4()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不符");
    }

    @Test
    @DisplayName("音频已被移出白名单：模型不接受音频输入，放进来就是个陷阱")
    void rejectsAudioEntirely() {
        assertThat(policy.isSupported("audio/mpeg")).isFalse();
        assertThat(policy.isSupported("audio/wav")).isFalse();

        assertThatThrownBy(() -> policy.resolveMimeType("audio/mpeg", mp3()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持的文件类型");
    }

    @Test
    @DisplayName("其余白名单外的一律拒收，包括可以内嵌脚本的 svg 与模型读不了的 pdf")
    void rejectsUnsupportedDeclaredTypes() {
        assertThat(policy.isSupported("image/svg+xml")).isFalse();
        assertThat(policy.isSupported("application/pdf")).isFalse();
        // 文本里只收纯文本：markdown 与 csv 在多数系统上另有 MIME，不在这次的范围内
        assertThat(policy.isSupported("text/markdown")).isFalse();
        assertThat(policy.isSupported("text/csv")).isFalse();
        assertThat(policy.isSupported(null)).isFalse();

        assertThatThrownBy(() -> policy.resolveMimeType("application/pdf", png()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持的文件类型");
    }

    @Test
    @DisplayName("Content-Type 带参数或大小写不一也能识别")
    void toleratesParameterAndCase() {
        assertThat(policy.resolveMimeType("VIDEO/MP4; codecs=avc1", mp4())).isEqualTo("video/mp4");
        assertThat(policy.resolveMimeType(" image/gif ", gif())).isEqualTo("image/gif");
    }

    @Test
    @DisplayName("识别不出内容的文件一律拒收，而不是放行")
    void rejectsUnknownContent() {
        assertThatThrownBy(() -> policy.resolveMimeType("image/png", new byte[]{0, 1, 2, 3}))
                .isInstanceOf(BizException.class);
        // 空内容也不行
        assertThatThrownBy(() -> policy.resolveMimeType("image/png", new byte[0]))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("扩展名只能来自白名单表，不随用户提供的文件名走")
    void extensionComesFromWhitelistTable() {
        assertThat(policy.extensionOf("image/jpeg")).isEqualTo("jpg");
        assertThat(policy.extensionOf("video/mp4")).isEqualTo("mp4");
        // 大小写与参数都不影响查表
        assertThat(policy.extensionOf("VIDEO/MP4; codecs=avc1")).isEqualTo("mp4");
    }

    @Test
    @DisplayName("顶层类型就是渲染与内容类型的分派依据")
    void exposesTopLevelType() {
        assertThat(policy.topLevelType("image/webp")).contains("image");
        assertThat(policy.topLevelType("video/mp4")).contains("video");
        assertThat(policy.topLevelType("application/pdf")).isEmpty();
    }

    // 下面这些只求头部字节正确，够嗅探用
    private static byte[] png() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    }

    private static byte[] jpeg() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
    }

    private static byte[] gif() {
        return new byte[]{'G', 'I', 'F', '8', '9', 'a'};
    }

    private static byte[] webp() {
        return new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
    }

    /** MP4 家族的 ftyp box 落在第 4 字节 */
    private static byte[] mp4() {
        return new byte[]{0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
    }

    private static byte[] mp3() {
        return new byte[]{'I', 'D', '3', 3, 0, 0};
    }

    private static byte[] text(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
