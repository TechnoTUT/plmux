import postcss, { type AtRule, type Root } from 'postcss'
import type { Plugin } from 'vite'

/**
 * iOS 12 (Safari 12) のような古いブラウザでも Decks タブが崩れないよう、ビルド後の CSS を書き換える。
 *
 * 色 (oklch → hex)、範囲指定の media query、ベンダープレフィックスなどは Vite の cssTarget で
 * lightningcss が変換してくれる。ここではそれで変換されない次の 2 つだけを扱う。
 *
 * - `@layer`: Safari 15.4 未満はブロックごと無視するので、中身をその場に展開する。
 *   Tailwind はレイヤー順 (theme → base → components → utilities) どおりに出力するので、展開しても順序は保たれる。
 * - `:where()` / `:is()`: Safari 14 未満は未対応で、そのセレクタを含むルールごと無視される。
 *   引数ごとにセレクタを展開する (例: `.a:where(.dark, .dark *)` → `.a.dark, .dark .a`)。
 *   詳細度は上がるが、Tailwind の出力では実害はない。
 *
 * 色の `color-mix()` / `lab()` は lightningcss が `@supports` で囲み、手前に代わりの値を出力しているのでそのままでよい。
 */
export function legacyCss(): Plugin {
  return {
    name: 'plmux:legacy-css',
    apply: 'build',
    enforce: 'post',
    generateBundle(_, bundle) {
      for (const file of Object.values(bundle)) {
        if (file.type !== 'asset' || !file.fileName.endsWith('.css')) continue
        file.source = transformCss(String(file.source))
      }
    },
  }
}

export function transformCss(css: string): string {
  const root = postcss.parse(css)
  unwrapLayers(root)
  root.walkRules((rule) => {
    if (!/:(where|is)\(/.test(rule.selector)) return
    const expanded = rule.selectors.flatMap(expandSelector)
    rule.selectors = [...new Set(expanded)]
  })
  return root.toString()
}

function unwrapLayers(root: Root) {
  // 入れ子のレイヤーもあるので、内側から順に展開する
  const layers: AtRule[] = []
  root.walkAtRules('layer', (at) => {
    layers.push(at)
  })
  for (const at of layers.reverse()) {
    if (at.nodes && at.nodes.length) at.replaceWith(at.nodes)
    else at.remove() // `@layer theme, base;` のような宣言だけの行
  }
}

const COMBINATOR = /[\s>+~]/

/** セレクタ中の最初の :where() / :is() を引数ごとに展開する (なくなるまで再帰する) */
export function expandSelector(sel: string): string[] {
  const m = findPseudo(sel)
  if (!m) return [sel]
  const { start, argStart, end } = m
  const args = splitTopLevel(sel.slice(argStart, end), ',').map((a) => a.trim())
  const compoundStart = findCompoundStart(sel, start)
  const results: string[] = []
  for (const arg of args) {
    // 引数に結合子があれば、最後の複合セレクタだけをその場に残し、残りはセレクタ全体の先頭に出す。
    // 例: `.kv>:last-child:where(.dark *)` → `.dark .kv>:last-child`
    // 祖先の条件 (末尾が子孫結合子) を先頭に出すのは、その祖先がセレクタ全体より上にある場合 (html.dark など) に正しい
    const parts = splitLastCompound(arg)
    if (parts.prefix && /[>+~]\s*$/.test(parts.prefix) && sel.slice(0, start).trim()) {
      throw new Error(`Cannot safely expand ${sel}: the argument ends with a child/sibling combinator`)
    }
    let last = parts.last
    if (last === '*' && (start > compoundStart || sel.slice(end + 1).match(/^[^\s>+~,]/))) last = ''
    const before = sel.slice(0, compoundStart)
    const compoundHead = sel.slice(compoundStart, start)
    const rest = sel.slice(end + 1)
    let merged = parts.prefix + before + compoundHead + last + rest
    if (!merged.trim()) continue
    merged = merged.replace(/^\s+/, '')
    results.push(...expandSelector(merged))
  }
  return results
}

function findPseudo(sel: string): { start: number; argStart: number; end: number } | null {
  let depth = 0
  let bracket = false
  for (let i = 0; i < sel.length; i++) {
    const c = sel[i]
    if (c === '\\') {
      i++
      continue
    }
    if (bracket) {
      if (c === ']') bracket = false
      continue
    }
    if (c === '[') bracket = true
    else if (c === '(') depth++
    else if (c === ')') depth--
    else if (c === ':' && depth === 0) {
      const name = /^:(where|is)\(/.exec(sel.slice(i))
      if (name) {
        const argStart = i + name[0].length
        return { start: i, argStart, end: matchParen(sel, argStart - 1) }
      }
    }
  }
  // :where() が別の擬似クラスの中にある場合 (例: :not(:where(...))) は展開しない
  return null
}

function matchParen(s: string, open: number): number {
  let depth = 0
  for (let i = open; i < s.length; i++) {
    const c = s[i]
    if (c === '\\') {
      i++
      continue
    }
    if (c === '(') depth++
    else if (c === ')' && --depth === 0) return i
  }
  throw new Error(`Unbalanced parentheses in selector: ${s}`)
}

function splitTopLevel(s: string, sep: string): string[] {
  const out: string[] = []
  let depth = 0
  let bracket = false
  let cur = ''
  for (let i = 0; i < s.length; i++) {
    const c = s[i]
    if (c === '\\') {
      cur += c + (s[i + 1] ?? '')
      i++
      continue
    }
    if (bracket) {
      if (c === ']') bracket = false
    } else if (c === '[') bracket = true
    else if (c === '(') depth++
    else if (c === ')') depth--
    else if (c === sep && depth === 0) {
      out.push(cur)
      cur = ''
      continue
    }
    cur += c
  }
  out.push(cur)
  return out
}

/** sel[pos] を含む複合セレクタの開始位置 (直前の結合子の次) */
function findCompoundStart(sel: string, pos: number): number {
  let start = 0
  let depth = 0
  let bracket = false
  for (let i = 0; i < pos; i++) {
    const c = sel[i]
    if (c === '\\') {
      i++
      continue
    }
    if (bracket) {
      if (c === ']') bracket = false
      continue
    }
    if (c === '[') bracket = true
    else if (c === '(') depth++
    else if (c === ')') depth--
    else if (depth === 0 && COMBINATOR.test(c)) start = i + 1
  }
  return start
}

/** `a > b c` → { prefix: 'a > b ', last: 'c' } */
function splitLastCompound(sel: string): { prefix: string; last: string } {
  const start = findCompoundStart(sel, sel.length)
  return { prefix: sel.slice(0, start), last: sel.slice(start) }
}
