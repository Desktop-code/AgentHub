package com.agenthub.rag.domain.service.ir;

import com.agenthub.rag.domain.service.DocumentChunk;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 按切片正文 / 章节标题推断 {@link ContentCategory}，写入元数据供检索过滤。
 */
public class ContentCategoryClassifier {

    private static final Pattern DISEASE = Pattern.compile(
            "感冒|流感|咳嗽|发热|发烧|流鼻涕|流涕|鼻塞|头痛|咽痛|腹泻|痢|哮喘|失眠|高血压|糖尿病|痛经|中暑");
    private static final Pattern HERB_HINT = Pattern.compile(
            "《|功效应用|用量用法|别名|性味|归经|炮制|药材|本草");
    private static final Pattern TEA_RECIPE = Pattern.compile(
            "茶方|药茶|泡饮|冲服|代茶|金银花茶|菊花茶|配方|材料：|用法：");
    private static final Pattern TEA_BASE = Pattern.compile(
            "茶饮调制|调制方法|茶具|水温|基础知识|冲泡技巧|选茶|存茶|泡茶步骤");

    public ContentCategory classify(DocumentChunk chunk, String fileName) {
        if (chunk == null) {
            return ContentCategory.OTHER;
        }
        String section = nvl(chunk.getSectionTitle());
        String content = nvl(chunk.getContent());
        String entry = nvl(chunk.metaString("entry_name"));
        String field = nvl(chunk.metaString("field_type"));
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);

        String head = section + "\n" + content;
        if (TEA_BASE.matcher(section).find() || TEA_BASE.matcher(content).find()) {
            // 通用调制说明：优先标为茶饮基础，避免被「感冒」向量漂到这里
            if (!DISEASE.matcher(section).find()) {
                return ContentCategory.TEA_BASE;
            }
        }
        // 章节标题即病名 → 疾病（优先于茶方关键词，避免「感冒…金银花茶」被标成茶方）
        if (DISEASE.matcher(section).find() || DISEASE.matcher(entry).find()) {
            return ContentCategory.DISEASE;
        }
        if (StringUtils.hasText(entry) && HERB_HINT.matcher(content).find()) {
            return ContentCategory.HERB;
        }
        if (HERB_HINT.matcher(content).find() && !DISEASE.matcher(section).find()
                && content.contains("《")) {
            return ContentCategory.HERB;
        }
        if (TEA_RECIPE.matcher(head).find() || (name.contains("茶") && DISEASE.matcher(head).find()
                && content.contains("材料"))) {
            return ContentCategory.TEA_RECIPE;
        }
        if (DISEASE.matcher(section).find() || DISEASE.matcher(entry).find()
                || (DISEASE.matcher(content).find() && content.length() < 200 && !content.contains("《"))) {
            return ContentCategory.DISEASE;
        }
        if (TEA_RECIPE.matcher(head).find()) {
            return ContentCategory.TEA_RECIPE;
        }
        if (StringUtils.hasText(field) && HERB_HINT.matcher(field).find()) {
            return ContentCategory.HERB;
        }
        if (name.contains("茶饮") || name.contains("药茶")) {
            if (DISEASE.matcher(head).find()) {
                return ContentCategory.DISEASE;
            }
            if (content.contains("《") || HERB_HINT.matcher(content).find()) {
                return ContentCategory.HERB;
            }
        }
        return ContentCategory.OTHER;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
