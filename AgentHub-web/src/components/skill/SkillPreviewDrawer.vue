<template>
  <!-- 只读预览：按 Anthropic Agent Skills 规范拼 SKILL.md，再 Markdown 渲染 -->
  <el-drawer
    :model-value="modelValue"
    :title="title"
    direction="rtl"
    size="50%"
    class="skill-preview-drawer"
    @close="emit('update:modelValue', false)"
  >
    <div v-if="!hasPreview" class="preview-empty">
      <el-empty description="还没有可预览的内容，先填写名称或正文" />
    </div>
    <!-- 渲染结果来自 marked，已过标签白名单清洗（见 script 注释） -->
    <div v-else class="preview-body md" v-html="html" />
  </el-drawer>
</template>

<script setup lang="ts">
/**
 * Skill 预览抽屉。所属域：skill。
 * 不按中文字段名平铺；按 Anthropic Agent Skills 的 SKILL.md 规范拼 frontmatter + 正文，
 * 再交给 marked 渲染。只读，不参与脏检查。
 */
import { computed } from 'vue'
import { marked } from 'marked'

const props = defineProps<{
  modelValue: boolean
  /** 抽屉标题，通常是 Skill 标题或名称 */
  title: string
  /** SKILL.md frontmatter：name */
  name?: string
  /** SKILL.md frontmatter：description（模型判断是否加载的核心字段） */
  description?: string
  /**
   * 触发关键词：逗号串或数组均可。
   * 非空时写入 frontmatter 的 trigger_keywords。
   */
  triggerKeywords?: string | string[]
  /** 可选展示用标题，非空时写入 frontmatter 的 title */
  skillTitle?: string
  /** Markdown 正文（对应 form content / contentMd） */
  content?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
}>()

/** marked 的 GFM 开关：表格与换行按 GitHub 口径 */
marked.setOptions({ gfm: true, breaks: true })

/** 允许保留的标签；其余标签一律去掉（保留其文本内容） */
const ALLOWED_TAGS = new Set([
  'a', 'p', 'br', 'hr', 'strong', 'b', 'em', 'i', 'del', 's', 'u', 'sub', 'sup',
  'blockquote', 'code', 'pre', 'ul', 'ol', 'li',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'table', 'thead', 'tbody', 'tr', 'th', 'td'
])

/**
 * 把触发词统一成逗号串；空则返回空串（调用方据此决定是否输出该行）。
 *
 * @param raw 逗号串或数组
 */
function normalizeKeywords(raw?: string | string[]): string {
  if (raw == null) {
    return ''
  }
  if (Array.isArray(raw)) {
    return raw.map((word) => String(word).trim()).filter(Boolean).join(',')
  }
  return String(raw)
    .split(/[,，]/)
    .map((word) => word.trim())
    .filter(Boolean)
    .join(',')
}

/**
 * 按 Anthropic Agent Skills 规范拼 SKILL.md 全文。
 * 顺序：--- → name → description →（可选）trigger_keywords / title → --- → 空行 → 正文。
 *
 * @return 可交给 Markdown 渲染器的完整字符串
 */
function assembleSkillMd(): string {
  const lines: string[] = ['---']
  lines.push(`name: ${props.name?.trim() || ''}`)
  // description 是模型判断是否加载此技能的核心字段，即使空也输出键
  lines.push(`description: ${props.description?.trim() || ''}`)

  const keywords = normalizeKeywords(props.triggerKeywords)
  if (keywords) {
    lines.push(`trigger_keywords: ${keywords}`)
  }

  const skillTitle = props.skillTitle?.trim() || ''
  if (skillTitle) {
    lines.push(`title: ${skillTitle}`)
  }

  lines.push('---')
  lines.push('')
  // 正文原样拼接，不做二次加工
  lines.push(props.content || '')
  return lines.join('\n')
}

/**
 * 清洗 marked 产物：剥掉非白名单标签与全部 on* 事件属性。
 *
 * @param raw marked 生成的 HTML
 * @return 可安全交给 v-html 的 HTML
 */
function sanitize(raw: string): string {
  const doc = new DOMParser().parseFromString(`<div id="root">${raw}</div>`, 'text/html')
  const root = doc.getElementById('root')
  if (!root) {
    return ''
  }
  // 自底向上遍历，先处理叶节点，避免删父节点后子节点遍历失效
  const walk = (node: Element) => {
    for (const child of Array.from(node.children)) {
      walk(child)
      const tag = child.tagName.toLowerCase()
      if (!ALLOWED_TAGS.has(tag)) {
        // 危险容器（script/style/iframe）连内容一起丢；普通未知标签只脱壳
        if (tag === 'script' || tag === 'style' || tag === 'iframe' || tag === 'object' || tag === 'embed') {
          child.remove()
        } else {
          child.replaceWith(...Array.from(child.childNodes))
        }
        continue
      }
      for (const attr of Array.from(child.attributes)) {
        const name = attr.name.toLowerCase()
        // 事件属性、srcdoc、以及非 http(s)/相对地址的链接一律丢弃
        const unsafeLink =
          (name === 'href' || name === 'src') && /^\s*(javascript|data|vbscript):/i.test(attr.value)
        if (name.startsWith('on') || name === 'srcdoc' || unsafeLink) {
          child.removeAttribute(attr.name)
        }
      }
      // 表格在外层套滚动容器，长表不会把抽屉撑破
      if (tag === 'table' && child.parentElement?.classList.contains('md-table-wrap') === false) {
        const wrap = doc.createElement('div')
        wrap.className = 'md-table-wrap'
        child.replaceWith(wrap)
        wrap.appendChild(child)
      }
    }
  }
  walk(root)
  return root.innerHTML
}

/** 名称或正文至少有一项才展示预览，避免空表单打开空白 SKILL 壳 */
const hasPreview = computed(
  () => !!(props.name?.trim() || props.content?.trim())
)

const html = computed(() => {
  if (!hasPreview.value) {
    return ''
  }
  return sanitize(marked.parse(assembleSkillMd()) as string)
})
</script>

<style>
/*
 * 抽屉 body 撑满剩余高度、自身不滚；预览框在内部滚动。
 * 类名落在 teleported 根上，需非 scoped（对齐 AgentTrialDrawer）。
 */
.skill-preview-drawer .el-drawer__body,
.skill-preview-drawer.el-drawer .el-drawer__body {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  /* 底边留一点空隙，框贴到底部上方即可 */
  padding-bottom: 16px;
}
</style>

<style scoped>
.preview-body {
  box-sizing: border-box;
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 16px 18px;
  color: #303133;
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  /* 滚动条走全局 index.css 统一样式，此处不另写 */
}

.preview-empty {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  min-height: 0;
}

/* Markdown 基础排版：只覆盖渲染产物，不影响抽屉壳 */
.preview-body :deep(h1),
.preview-body :deep(h2),
.preview-body :deep(h3),
.preview-body :deep(h4) {
  margin: 0.8em 0 0.4em;
  font-weight: 650;
  line-height: 1.35;
}

.preview-body :deep(h1) {
  font-size: 22px;
}

.preview-body :deep(h2) {
  font-size: 18px;
  padding-bottom: 4px;
  border-bottom: 1px solid #ebeef5;
}

.preview-body :deep(h3) {
  font-size: 16px;
}

.preview-body :deep(p) {
  margin: 0.5em 0;
}

.preview-body :deep(ul),
.preview-body :deep(ol) {
  margin: 0.5em 0;
  padding-left: 1.4em;
}

.preview-body :deep(li) {
  margin: 0.2em 0;
}

.preview-body :deep(blockquote) {
  margin: 0.6em 0;
  padding: 4px 12px;
  color: var(--ah-text-secondary, #6b7280);
  border-left: 3px solid #d0d5dd;
  background: #fafafa;
}

.preview-body :deep(pre) {
  margin: 0.6em 0;
  padding: 10px 12px;
  overflow: auto;
  border-radius: 8px;
  background: #f3f4f8;
}

.preview-body :deep(pre code) {
  padding: 0;
  background: transparent;
}

.preview-body :deep(code) {
  padding: 1px 5px;
  border-radius: 4px;
  background: #f3f4f8;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
}

.preview-body :deep(.md-table-wrap) {
  max-width: 100%;
  margin: 0.6em 0;
  overflow-x: auto;
}

.preview-body :deep(table) {
  border-collapse: collapse;
  border: 1px solid #d0d5dd;
  background: #fff;
  font-size: 13px;
}

.preview-body :deep(th),
.preview-body :deep(td) {
  padding: 6px 10px;
  border: 1px solid #d0d5dd;
  text-align: left;
  vertical-align: top;
}

.preview-body :deep(th) {
  background: #f3f4f8;
  font-weight: 600;
}

.preview-body :deep(hr) {
  margin: 1em 0;
  border: 0;
  border-top: 1px solid #ebeef5;
}

.preview-body :deep(a) {
  color: #635bff;
}
</style>
