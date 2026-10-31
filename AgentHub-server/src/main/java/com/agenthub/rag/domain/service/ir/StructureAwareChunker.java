package com.agenthub.rag.domain.service.ir;

import com.agenthub.rag.domain.service.DocumentChunk;
import com.agenthub.rag.domain.service.DocumentChunker;
import com.agenthub.rag.domain.service.TocNoiseFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 结构感知切分入口。所属域：rag。
 * <p>
 * 链路：切前清洗（Structurer 内 TocNoiseFilter）→ IR → 原型路由 → 四策略 →
 * oversize → 空壳合并 → 切后去噪。纯算法，不碰 IO / 模型。
 */
public class StructureAwareChunker {

    private static final Logger log = LoggerFactory.getLogger(StructureAwareChunker.class);

    private final DocumentStructurer documentStructurer;
    private final DocumentTypeAnalyzer documentTypeAnalyzer;
    private final ChunkStrategyRouter chunkStrategyRouter;
    private final ContentCategoryClassifier contentCategoryClassifier;
    private final int shellMinChars;
    /** 是否启用茶饮等业务域噪声过滤；AgentHub 默认关，避免硬编码语料规则 */
    private final boolean domainNoiseFilterEnabled;

    /**
     * @param documentStructurer         IR 构建
     * @param documentTypeAnalyzer       原型识别
     * @param chunkStrategyRouter        策略路由
     * @param contentCategoryClassifier  主题粗分类（可空，空则新建默认）
     * @param shellMinChars              空壳合并阈值
     * @param domainNoiseFilterEnabled   是否启用可配的领域噪声过滤
     */
    public StructureAwareChunker(DocumentStructurer documentStructurer,
                                 DocumentTypeAnalyzer documentTypeAnalyzer,
                                 ChunkStrategyRouter chunkStrategyRouter,
                                 ContentCategoryClassifier contentCategoryClassifier,
                                 int shellMinChars,
                                 boolean domainNoiseFilterEnabled) {
        this.documentStructurer = documentStructurer;
        this.documentTypeAnalyzer = documentTypeAnalyzer;
        this.chunkStrategyRouter = chunkStrategyRouter;
        this.contentCategoryClassifier = contentCategoryClassifier == null
                ? new ContentCategoryClassifier() : contentCategoryClassifier;
        this.shellMinChars = Math.max(20, shellMinChars);
        this.domainNoiseFilterEnabled = domainNoiseFilterEnabled;
    }

    /**
     * 默认构造：标准四策略 + 默认空壳阈值，领域噪声过滤关闭。
     */
    public StructureAwareChunker(DocumentStructurer documentStructurer,
                                 DocumentTypeAnalyzer documentTypeAnalyzer,
                                 ChunkStrategyRouter chunkStrategyRouter,
                                 ContentCategoryClassifier contentCategoryClassifier) {
        this(documentStructurer, documentTypeAnalyzer, chunkStrategyRouter, contentCategoryClassifier,
                ShellChunkProcessor.DEFAULT_SHELL_MIN_CHARS, false);
    }

    /**
     * 非 Spring 场景（单测 / DocumentChunker 委托）。
     *
     * @return 内置四策略的默认切分器
     */
    public static StructureAwareChunker createDefault() {
        return new StructureAwareChunker(
                new DocumentStructurer(),
                new DocumentTypeAnalyzer(),
                new ChunkStrategyRouter(List.of(
                        new EntryBasedChunkStrategy(),
                        new HierarchicalChunkStrategy(),
                        new NarrativeChunkStrategy(),
                        new TabularChunkStrategy()
                )),
                new ContentCategoryClassifier(),
                ShellChunkProcessor.DEFAULT_SHELL_MIN_CHARS,
                false);
    }

    /**
     * @param fileName  原始文件名
     * @param text      解析后正文
     * @param chunkSize 目标长度
     * @param overlap   重叠
     * @return 带元数据的切片
     */
    public List<DocumentChunk> chunk(String fileName, String text, int chunkSize, int overlap) {
        return chunk(fileName, text, chunkSize, overlap, DocumentChunker.DEFAULT_MIN_CHUNK_SIZE);
    }

    /**
     * @param minChunkSize 过短切片合并阈值（策略内同章节合并）
     * @return 带元数据的切片；正文无效时为空
     */
    public List<DocumentChunk> chunk(String fileName, String text, int chunkSize, int overlap, int minChunkSize) {
        StructuredDocument ir = documentStructurer.structure(fileName, text, chunkSize, overlap, minChunkSize);
        ir.setDocType(documentTypeAnalyzer.analyze(ir));
        Object noiseObj = ir.getMetadata().get("noiseHits");
        Object headerObj = ir.getMetadata().get("headerCount");
        log.info("[IR][{}] StructureAware route file={} docType={} headerCount={} noiseHits={} chunkSize={} overlap={} minChunk={} shellMin={}",
                DocumentStructurer.BUILD_TAG, fileName, ir.getDocType(),
                headerObj, noiseObj, chunkSize, overlap, minChunkSize, shellMinChars);
        if (ir.getDocType() == DocumentType.NARRATIVE) {
            log.warn("[IR][{}] 文档被判定为 NARRATIVE：sectionTitle 通常为空；若期望章节切片请检查 HEADER 是否被误杀",
                    DocumentStructurer.BUILD_TAG);
        }
        List<DocumentChunk> routed = chunkStrategyRouter.route(ir);
        int size = Math.max(80, chunkSize);
        int ov = Math.max(0, overlap);
        // 超长再切（part 元数据 + 锚点）→ 空壳/装饰合并（仅同 header_path / 同专篇）
        List<DocumentChunk> sized = OversizePartSplitter.split(routed, size, ov);
        List<DocumentChunk> collapsed = ShellChunkProcessor.collapse(sized, shellMinChars, shellMinChars);

        List<DocumentChunk> kept = new ArrayList<>();
        int index = 0;
        for (DocumentChunk chunk : collapsed) {
            if (chunk == null) {
                continue;
            }
            String cleanedContent = TocNoiseFilter.sanitizeChunkText(chunk.getContent());
            cleanedContent = ShellChunkProcessor.stripTrailingHorizontalRules(cleanedContent);
            if (!StringUtils.hasText(cleanedContent)
                    || TocNoiseFilter.isLowQualityChunk(cleanedContent)
                    || ShellChunkProcessor.isDecorativeOnly(cleanedContent)
                    || ShellChunkProcessor.isCorpusPreamble(cleanedContent)) {
                continue;
            }
            if (TocNoiseFilter.isTocTitle(chunk.getSectionTitle())) {
                continue;
            }
            // 领域噪声默认关；开启后才走可配规则（含历史茶饮规则）
            if (domainNoiseFilterEnabled
                    && TocNoiseFilter.isDomainNoise(chunk.getSectionTitle(), cleanedContent)) {
                continue;
            }
            DocumentChunk cleaned = cleanedContent.equals(chunk.getContent())
                    ? chunk
                    : new DocumentChunk(cleanedContent, chunk.getPageNum(), chunk.getSectionTitle(),
                    new LinkedHashMap<>(chunk.getMetadata()));
            // 裸片兜底：入库前再确保 title 非空
            if (!StringUtils.hasText(cleaned.getSectionTitle())
                    && !StringUtils.hasText(cleaned.metaString("entry_name"))
                    && !StringUtils.hasText(cleaned.metaString("header_path"))) {
                String filled = OversizePartSplitter.ensureTitle("", cleaned, cleanedContent);
                cleaned = new DocumentChunk(cleaned.getContent(), cleaned.getPageNum(), filled,
                        new LinkedHashMap<>(cleaned.getMetadata()));
            }
            ContentCategory cat = contentCategoryClassifier.classify(cleaned, fileName);
            cleaned.withMeta("file_name", fileName == null ? "" : fileName);
            cleaned.withMeta("chunk_index", index);
            cleaned.withMeta("source_type", ir.getDocType() == null ? "" : ir.getDocType().name());
            cleaned.withMeta("ir_build", DocumentStructurer.BUILD_TAG);
            if (noiseObj != null) {
                cleaned.withMeta("noise_hits", noiseObj);
            }
            cleaned.withMeta("content_category", cat.name());
            cleaned.withMeta("category", cat.name());
            kept.add(cleaned);
            index++;
        }
        long titled = kept.stream().filter(c -> StringUtils.hasText(c.getSectionTitle())).count();
        long paged = kept.stream().filter(c -> c.getPageNum() > 0).count();
        int minMerges = DocumentChunker.lastMinMergeCount();
        for (DocumentChunk c : kept) {
            c.withMeta("min_merge_count", minMerges);
        }
        log.info("[IR][{}] 切片统计 file={} total={} sectionTitleNonEmpty={}({}%) pageNumNonZero={}({}%) noiseHits={} minMerges={}",
                DocumentStructurer.BUILD_TAG, fileName, kept.size(),
                titled, kept.isEmpty() ? 0 : Math.round(100.0 * titled / kept.size()),
                paged, kept.isEmpty() ? 0 : Math.round(100.0 * paged / kept.size()),
                noiseObj, minMerges);
        return kept;
    }

    /**
     * 条目父块等可能远超 chunkSize；超长必须再切。
     *
     * @param chunks    策略产出
     * @param chunkSize 上限
     * @param overlap   重叠
     * @return 再切后的列表
     */
    static List<DocumentChunk> splitOversize(List<DocumentChunk> chunks, int chunkSize, int overlap) {
        return OversizePartSplitter.split(chunks, chunkSize, overlap);
    }
}
