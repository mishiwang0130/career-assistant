import DOMPurify from 'dompurify'
import type { Config } from 'dompurify'
import { marked } from 'marked'

/**
 * 助手正文的 Markdown 渲染。
 *
 * 模型的回答按 Markdown 产出（小标题、加粗、列表、表格等，见 docs/技术约定.md 各功能章节的正文结构要求），
 * 气泡必须先把它转成 HTML 再展示。模型输出属于不可信内容，因此统一走「marked 转换 + DOMPurify 过滤」：
 * 禁止把原始内容直接当 HTML 插入页面，也不要为了少数样式放开事件属性或脚本标签。
 */

/**
 * 允许保留的标签白名单。
 *
 * 只放开 Markdown 常见的排版结构；表单、iframe、图片、样式标签一律不放行——回答正文不需要它们，
 * 放行只会带来 XSS 与「模型给出不存在的外链图片」这类副作用。
 */
const ALLOWED_TAGS = [
  'p',
  'br',
  'hr',
  'strong',
  'em',
  'del',
  'code',
  'pre',
  'blockquote',
  'ul',
  'ol',
  'li',
  'a',
  'table',
  'thead',
  'tbody',
  'tr',
  'th',
  'td',
  'h1',
  'h2',
  'h3',
  'h4',
  'h5',
  'h6',
]

/**
 * 允许保留的属性白名单。
 *
 * `class` 是代码块语言标记（`language-xxx`）需要的；`colspan` / `rowspan` 供表格合并单元格使用；
 * `target` / `rel` 由下面的外链钩子统一写入，不接受模型自带的值。
 */
const ALLOWED_ATTR = [
  'href',
  'title',
  'class',
  'align',
  'colspan',
  'rowspan',
  'target',
  'rel',
]

/** 净化配置：白名单之外全部丢弃，并显式禁掉内联样式。 */
const SANITIZE_OPTIONS: Config = {
  ALLOWED_TAGS,
  ALLOWED_ATTR,
  FORBID_ATTR: ['style'],
}

// 外链统一新窗口打开并去掉 referrer：模型给出的链接不保证可达，点开也不该把当前会话地址带出去。
// 钩子在属性过滤之后执行，因此这里写入的 target / rel 不受白名单过滤影响。
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node instanceof Element && node.tagName === 'A') {
    const href = node.getAttribute('href') ?? ''
    if (/^https?:\/\//i.test(href)) {
      node.setAttribute('target', '_blank')
      node.setAttribute('rel', 'noopener noreferrer')
    }
  }
})

// GFM 打开表格与删除线；breaks 打开后单个换行也会断开，保持与提示词里「一段一句」的排版一致。
marked.setOptions({ gfm: true, breaks: true })

/**
 * 把 Markdown 正文渲染成可安全插入页面的 HTML。
 *
 * @param source 模型产出的 Markdown 正文
 * @returns 过滤后的 HTML 片段，内容为空时返回空串
 */
export function renderMarkdown(source: string): string {
  if (!source) {
    return ''
  }
  const html = marked.parse(source, { async: false })
  return DOMPurify.sanitize(html, SANITIZE_OPTIONS)
}
