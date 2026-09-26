package com.webchat.service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * 纯文本附件的字节解码。上传时的类型校验（{@link AttachmentTypePolicy}）与后续的索引取文
 * （{@code DocumentIndexService}）都走这里，<b>不能各写一份</b>：两处独立猜字符集，迟早会出现
 * 「校验时判成 GBK、索引时又试出 UTF-8」这类不一致，而这种不一致只会表现为检索结果莫名其妙。
 *
 * <p>判据是<b>严格解码</b>：把 {@code CodingErrorAction} 设成 {@code REPORT}，遇到非法字节序列直接
 * 抛异常而不是替换成 U+FFFD——后者对任何字节都「成功」，等于没有校验。UTF-8 的多字节序列有
 * 严格的合法性约束，所以「UTF-8 解不出来才轮到 GB18030」是一条可靠的兜底链，不需要引入字符集探测库。
 *
 * <p>GB18030 而不是 GBK：前者是后者的<b>超集</b>，能少掉一批「明明是中文文本却解不出来」的误拒。
 */
public final class PlainTextDecoder {

    /**
     * 按可能性从高到低试的字符集，UTF-8 在前（当下的默认编码）。
     *
     * <p>只认这两种。UTF-16 不在其中且会被显式拒收（见 {@link #hasUtf16Bom}）——拿不准的编码宁可
     * 拒收，也不要塞一段乱码进向量库，那样检索会静默地不工作。
     */
    private static final List<Charset> CANDIDATES = List.of(StandardCharsets.UTF_8, Charset.forName("GB18030"));

    /** UTF-8 的 BOM（U+FEFF）。Windows 记事本存「UTF-8」时默认写它，不剥掉会污染正文开头 */
    private static final char BOM = 0xFEFF;

    private PlainTextDecoder() {
    }

    /**
     * 按候选字符集依次尝试解码。
     *
     * @return 解码后的文本（已剥掉开头的 BOM）；空内容、UTF-16 内容、二进制内容、或两种字符集
     * 都解不出来时返回空
     */
    public static Optional<String> decode(byte[] content) {
        if (content.length == 0 || looksBinary(content) || hasUtf16Bom(content)) {
            return Optional.empty();
        }
        for (Charset charset : CANDIDATES) {
            String text = strictDecode(content, charset);
            if (text != null) {
                return Optional.of(stripBom(text));
            }
        }
        return Optional.empty();
    }

    /**
     * 内容是否像二进制。
     *
     * <p>判据是 NUL 字节：合法文本（UTF-8 / GB18030）里不会出现它，而绝大多数二进制格式在头几个字节
     * 就会带上——这条比「有没有控制字符」更少误伤，中文文本里的 {@code \r\n\t} 都是合法的。
     *
     * <p>它同时挡掉了<b>含 ASCII 的</b> UTF-16 文本（那些字符的高位字节是 0）。纯中文的 UTF-16
     * 不含 NUL，由 {@link #hasUtf16Bom} 那条单独兜住——两个判据缺一不可。
     */
    private static boolean looksBinary(byte[] content) {
        for (byte value : content) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否带 UTF-16 的字节序标记。
     *
     * <p>不能只靠 NUL 判据：纯中文的 UTF-16 文本一个 NUL 都没有（「文」是 {@code 87 65}），
     * 而它会以 GB18030 的身份被「成功」解出来，得到一段乱码——比解码失败更糟，
     * 因为它会安安静静地进向量库，让检索结果变得莫名其妙。记事本存 UTF-16 时必写 BOM，所以这条拦得住。
     *
     * <p>{@code FF} 不在 GB18030 的合法字节范围内（前导字节 0x81–0xFE），UTF-8 也不可能以它开头，
     * 因此这个判据不会误伤那两种编码。
     */
    private static boolean hasUtf16Bom(byte[] content) {
        return content.length >= 2
                && ((content[0] == (byte) 0xFF && content[1] == (byte) 0xFE)
                || (content[0] == (byte) 0xFE && content[1] == (byte) 0xFF));
    }

    /** @return 解码结果；该字符集解不出来时返回 null */
    private static String strictDecode(byte[] content, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    }
}
