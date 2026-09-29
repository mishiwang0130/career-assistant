import { describe, expect, it } from 'vitest'

import { renderMarkdown } from '@/utils/markdown'

describe('助手正文的 Markdown 渲染', () => {
  it('把标题、加粗与列表渲染成 HTML', () => {
    const html = renderMarkdown('## 综合评分\n\n**78 分**\n\n- 量化不足\n- 项目描述空泛')

    expect(html).toContain('<h2>综合评分</h2>')
    expect(html).toContain('<strong>78 分</strong>')
    expect(html).toContain('<li>量化不足</li>')
  })

  it('单元内的单个换行保留为换行', () => {
    const html = renderMarkdown('第一行\n第二行')

    expect(html).toContain('第一行<br>第二行')
  })

  it('渲染代码块与表格', () => {
    const html = renderMarkdown(
      '| 维度 | 得分 |\n| --- | --- |\n| 表达 | 70 |\n\n```java\nint count = 1;\n```',
    )

    expect(html).toContain('<table>')
    expect(html).toContain('<th>维度</th>')
    expect(html).toContain('<td>70</td>')
    expect(html).toContain('<code class="language-java">')
    expect(html).toContain('int count = 1;')
  })

  it('代码块内的标签按文本转义，不当作 HTML 解析', () => {
    const html = renderMarkdown('```\n<script>alert(1)</script>\n```')

    expect(html).toContain('&lt;script&gt;')
    expect(html).not.toContain('<script')
  })

  it('过滤脚本、事件属性、内联框架与危险链接', () => {
    const html = renderMarkdown(
      '<img src=x onerror="alert(1)">\n\n<script>alert(2)</script>\n\n' +
        '[点我](javascript:alert(3))\n\n<iframe src="https://example.com"></iframe>',
    )

    expect(html).not.toContain('onerror')
    expect(html).not.toContain('<img')
    expect(html).not.toContain('<script')
    expect(html).not.toContain('<iframe')
    expect(html).not.toContain('javascript:')
  })

  it('外链在新窗口打开，页内锚点不改造', () => {
    expect(renderMarkdown('[岗位详情](https://example.com/job/1)')).toContain(
      'rel="noopener noreferrer"',
    )
    expect(renderMarkdown('[岗位详情](https://example.com/job/1)')).toContain('target="_blank"')
    expect(renderMarkdown('[看第一条](#first)')).not.toContain('target=')
  })

  it('空内容返回空串', () => {
    expect(renderMarkdown('')).toBe('')
  })
})
