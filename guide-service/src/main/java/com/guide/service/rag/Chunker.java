package com.guide.service.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 知识分块：按句界切分后滑窗合并成 ~chunkSize 的块，块间保留 overlap 字符保证语义连续。
 * 移植自 Python 版验证过的算法（300 字符 / 60 重叠），参数见 RagService。
 */
public final class Chunker {

    /** 句界：出现在 。！？!?；;\n 之后即可切分 */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？!?；;\\n])");

    private Chunker() {
    }

    public static List<String> chunk(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        String[] sentences = SENTENCE_SPLIT.split(text);
        StringBuilder buf = new StringBuilder();
        for (String s : sentences) {
            if (s == null || s.isBlank()) {
                continue;
            }
            if (buf.length() + s.length() > chunkSize && buf.length() > 0) {
                chunks.add(buf.toString());
                // 上一块尾部作为下一块开头上下文
                String tail = buf.substring(Math.max(0, buf.length() - overlap));
                buf.setLength(0);
                buf.append(tail).append(s);
            } else {
                buf.append(s);
            }
        }
        if (!buf.toString().isBlank()) {
            chunks.add(buf.toString());
        }
        return chunks;
    }
}
