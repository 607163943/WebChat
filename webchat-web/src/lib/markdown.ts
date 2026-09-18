import MarkdownIt from 'markdown-it'
import { createHighlighterCoreSync } from 'shiki/core'
import { createJavaScriptRegexEngine } from 'shiki/engine/javascript'

// 细粒度引入：只装实际会用到的语言，避免把整包 grammar 打进产物
import bash from 'shiki/langs/bash.mjs'
import css from 'shiki/langs/css.mjs'
import html from 'shiki/langs/html.mjs'
import java from 'shiki/langs/java.mjs'
import javascript from 'shiki/langs/javascript.mjs'
import json from 'shiki/langs/json.mjs'
import markdown from 'shiki/langs/markdown.mjs'
import python from 'shiki/langs/python.mjs'
import sql from 'shiki/langs/sql.mjs'
import typescript from 'shiki/langs/typescript.mjs'
import vue from 'shiki/langs/vue.mjs'
import xml from 'shiki/langs/xml.mjs'
import yaml from 'shiki/langs/yaml.mjs'
import githubLight from 'shiki/themes/github-light.mjs'

const THEME = 'github-light'

/**
 * 用纯 JS 正则引擎而不是 oniguruma：不需要加载 WASM，
 * 于是可以同步创建高亮器，代码块渲染也就不必是异步的（流式输出下这点很重要）。
 */
const highlighter = createHighlighterCoreSync({
  themes: [githubLight],
  langs: [
    bash,
    css,
    html,
    java,
    javascript,
    json,
    markdown,
    python,
    sql,
    typescript,
    vue,
    xml,
    yaml,
  ],
  engine: createJavaScriptRegexEngine(),
})

const md = new MarkdownIt({
  // 不解析原始 HTML。内容来自模型，关掉它可以免去一整类注入风险，
  // 也让下面用 v-html 渲染的产物是可信的
  html: false,
  linkify: true,
  breaks: true,
  highlight(code, lang) {
    const language = lang.trim()
    if (!language) {
      return ''
    }
    try {
      if (highlighter.getLoadedLanguages().includes(language)) {
        return highlighter.codeToHtml(code, { lang: language, theme: THEME })
      }
    } catch {
      // 语言别名或语法异常时退回纯文本，不要让整段渲染失败
    }
    // 返回空串表示「不接管」，交给 markdown-it 自己做转义
    return ''
  },
})

const defaultLinkOpen = md.renderer.rules.link_open

// 正文里的外链一律新窗口打开，并带上 noopener
md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  const token = tokens[idx]
  if (token) {
    token.attrSet('target', '_blank')
    token.attrSet('rel', 'noopener noreferrer')
  }
  return defaultLinkOpen
    ? defaultLinkOpen(tokens, idx, options, env, self)
    : self.renderToken(tokens, idx, options)
}

/**
 * 流式输出时 ``` 可能还没闭合。
 *
 * 直接把半截围栏交给解析器，会让已经开始渲染的代码块在闭合的瞬间整段跳变
 * （先按普通文本渲染，闭合后突然变成代码块）。渲染前先把围栏补齐，观感就稳定了。
 */
export function closeUnclosedFences(text: string): string {
  const fences = text.match(/^```/gm)
  if (fences && fences.length % 2 === 1) {
    return `${text}\n\`\`\``
  }
  return text
}

export function renderMarkdown(text: string): string {
  return md.render(closeUnclosedFences(text))
}
