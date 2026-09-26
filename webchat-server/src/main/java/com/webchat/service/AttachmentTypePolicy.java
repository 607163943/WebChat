package com.webchat.service;

import com.webchat.common.BizException;
import com.webchat.common.ResultCode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

import static java.util.Map.entry;

/**
 * 附件的类型边界。这是<b>真正的</b>那道门——前端那份同名列表只是为了让用户少等一次没有意义的传输，
 * 改请求或直接 curl 就能绕过它。
 *
 * <p>白名单是<b>精确枚举</b>，不是 {@code image/*} 这样的前缀规则：前缀会把 {@code image/svg+xml}
 * 一并放进来，而 SVG 可以内嵌脚本，公网可读的桶里直接打开它的 URL 就是一个存储型 XSS 入口。
 * 也刻意不含 {@code application/pdf}——LangChain4j 的 DashScope 适配只认 TEXT/IMAGE/AUDIO/VIDEO
 * 四种内容，PDF 会落进 default 分支被静默塞成一个空的 {@code {}} 内容块，既不报错也不起作用。
 *
 * <p><b>音频当前不在列表里</b>：{@code qwen3.8-max} 的官方输入模态是图像 / 文本 / 视频，
 * 实测带音频提交会被服务端以 {@code An incorrect modal 'audio' was entered} 拒绝。
 * 要支持音频得先把模型换成 Qwen-Omni 系列，再把对应 MIME 加回本表与前端那份副本。
 *
 * <p>光比对 {@code Content-Type} 是不够的：那个头由客户端提供，把 SVG 改名成 .png 再改头就能过。
 * 所以还要<b>嗅探文件头</b>，并要求嗅探结果的顶层类型与声明值一致。
 *
 * <p><b>纯文本是这条规则的一个例外</b>：{@code text/plain} 没有任何文件头可嗅，任何字节序列都可以
 * 「是」一个 txt。所以它改用另一条判据——内容必须能按 UTF-8 或 GB18030 严格解码，且不含 NUL 字节
 * （见 {@link PlainTextDecoder}）。编码拿不准就拒收：放一段乱码进去，检索会静默地不工作。
 *
 * <p>文本与图片／视频在链路上的去向也不同：媒体是 base64 塞进多模态消息，文本是切分后进向量库、
 * 提问时检索片段注入提示词（见 {@code ai.rag}）。所以 {@link #topLevelType} 返回 {@code text} 的那些
 * 附件不能再走 {@code ImageContent} / {@code VideoContent} 的分派。
 */
@Component
public class AttachmentTypePolicy {

    public static final String IMAGE = "image";
    public static final String VIDEO = "video";
    public static final String TEXT = "text";

    private static final String SEPARATOR = "/";
    private static final String TEXT_PLAIN = "text/plain";

    /**
     * MIME → 落盘扩展名。<b>只列能确认可用的格式</b>——多放一个没验证过的类型，
     * 就是给用户留一个「传得上去、发给模型才失败」的陷阱。
     *
     * <p>扩展名只能来自这张表，<b>绝不从 original_name 截取</b>——那是用户完全可控的展示字段，
     * {@code "a.png/../../../../x"} 这样的名字会让落盘与删除都越出根目录。
     */
    private static final Map<String, String> EXTENSION_BY_MIME = Map.ofEntries(
            entry("image/png", "png"),
            entry("image/jpeg", "jpg"),
            entry("image/gif", "gif"),
            entry("image/webp", "webp"),
            entry("image/bmp", "bmp"),
            entry("video/mp4", "mp4"),
            entry(TEXT_PLAIN, "txt"));

    /** 声明类型是否在白名单内 */
    public boolean isSupported(String mimeType) {
        return mimeType != null && EXTENSION_BY_MIME.containsKey(normalize(mimeType));
    }

    /** 该类型是否走检索而不是多模态 */
    public boolean isText(String mimeType) {
        return TEXT.equals(topLevelType(mimeType).orElse(null));
    }

    /** {@code image/png} → {@code image}；不在白名单内返回空 */
    public Optional<String> topLevelType(String mimeType) {
        if (!isSupported(mimeType)) {
            return Optional.empty();
        }
        String normalized = normalize(mimeType);
        return Optional.of(normalized.substring(0, normalized.indexOf(SEPARATOR)));
    }

    /** 由 MIME 推出落盘扩展名 */
    public String extensionOf(String mimeType) {
        return EXTENSION_BY_MIME.get(normalize(mimeType));
    }

    /**
     * 校验一次上传：声明类型必须受支持，且文件头必须印证它属于同一个顶层类型。
     *
     * @return 嗅探出的规范 MIME（写入 {@code tb_attachment.mime_type} 的就是它）
     * @throws BizException 类型不支持，或文件内容与声明不符
     */
    public String resolveMimeType(String declaredMimeType, byte[] content) {
        String declared = normalize(declaredMimeType);
        if (!isSupported(declared)) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "不支持的文件类型，只能上传常见的图片（PNG / JPEG / GIF / WebP / BMP）、MP4 视频或 txt 文本文件");
        }
        if (TEXT_PLAIN.equals(declared)) {
            // 文本没有文件头可嗅，改用「能不能严格解码成文本」当判据。声明值就是 text/plain，
            // 解出来自然也是 text/plain，不存在图片那种「顶层类型对不上」的校验
            if (PlainTextDecoder.decode(content).isEmpty()) {
                throw new BizException(ResultCode.BAD_REQUEST,
                        "这个文件的内容不是 UTF-8 或 GB18030 编码的纯文本（UTF-16 与二进制文件不支持）");
            }
            return TEXT_PLAIN;
        }
        String detected = detect(content)
                .orElseThrow(() -> new BizException(ResultCode.BAD_REQUEST,
                        "这个文件的内容不是可识别的图片或视频，可能扩展名与实际格式不符"));
        if (!topLevelType(detected).equals(topLevelType(declared))) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "文件内容与扩展名不符：声明为 " + declared + "，实际是 " + detected);
        }
        return detected;
    }

    /**
     * 按文件头嗅探规范 MIME，识别不出返回空。
     *
     * <p>只覆盖带 magic bytes 的媒体格式；{@code text/plain} 不在其中，它由
     * {@link PlainTextDecoder} 的可解码性判据把关（见 {@link #resolveMimeType}）。识别不出即拒收，
     * 宁可让少数上报异常的文件被挡下，也不要放进一个无法确认类型的文件——类型是这条链路后续
     * 所有判断的唯一依据。
     */
    public Optional<String> detect(byte[] content) {
        if (matches(content, 0, 0x89, 'P', 'N', 'G')) {
            return Optional.of("image/png");
        }
        if (matches(content, 0, 0xFF, 0xD8, 0xFF)) {
            return Optional.of("image/jpeg");
        }
        if (matches(content, 0, 'G', 'I', 'F', '8')) {
            return Optional.of("image/gif");
        }
        if (matches(content, 0, 'B', 'M')) {
            return Optional.of("image/bmp");
        }
        // RIFF 容器：靠第 8 字节起的格式标签区分 WebP 与其它 RIFF 家族
        if (matches(content, 0, 'R', 'I', 'F', 'F') && matches(content, 8, 'W', 'E', 'B', 'P')) {
            return Optional.of("image/webp");
        }
        // MP4 家族的 ftyp box 落在第 4 字节
        if (matches(content, 4, 'f', 't', 'y', 'p')) {
            return Optional.of("video/mp4");
        }
        return Optional.empty();
    }

    /** 浏览器偶尔会带上参数（如 {@code video/mp4; codecs=avc1}）或大小写不一，统一收拾一下 */
    private static String normalize(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        String value = mimeType.trim().toLowerCase();
        int parameterIndex = value.indexOf(';');
        return parameterIndex < 0 ? value : value.substring(0, parameterIndex).trim();
    }

    private static boolean matches(byte[] content, int offset, int... magic) {
        if (content.length < offset + magic.length) {
            return false;
        }
        for (int index = 0; index < magic.length; index++) {
            if ((content[offset + index] & 0xFF) != magic[index]) {
                return false;
            }
        }
        return true;
    }
}
