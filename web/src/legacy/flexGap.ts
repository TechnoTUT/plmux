/**
 * flex の gap に対応していないブラウザ (Safari 14.1 未満、iOS 12 など) で、gap の代わりに子要素へ margin を付ける。
 *
 * gap-* の付いた flex 要素を探し、computed style の向き (md:flex-row などの画面幅による切り替えも反映される) と
 * column-gap / row-gap から、2 つ目以降の子に margin-left / margin-top を付ける。
 * Safari 12 は gap を grid 用として解釈するので、flex に効かなくても computed style からは値を読める。
 * 折り返し (flex-wrap) では、次の行の先頭に来た子には左ではなく上に間隔を付ける。
 */
export function installFlexGapFallback() {
  if (supportsFlexGap()) return
  document.documentElement.classList.add('no-flexgap')

  let scheduled = false
  const schedule = () => {
    if (scheduled) return
    scheduled = true
    requestAnimationFrame(() => {
      scheduled = false
      apply()
    })
  }
  new MutationObserver(schedule).observe(document.body, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['class'],
  })
  window.addEventListener('resize', schedule)
  schedule()
}

function supportsFlexGap(): boolean {
  const el = document.createElement('div')
  el.style.display = 'flex'
  el.style.flexDirection = 'column'
  el.style.rowGap = '1px'
  el.appendChild(document.createElement('div'))
  el.appendChild(document.createElement('div'))
  document.body.appendChild(el)
  const ok = el.scrollHeight === 1
  document.body.removeChild(el)
  return ok
}

/** margin 系のクラス (ml-auto など) を持つ子は、そちらを優先して触らない */
const HAS_MARGIN_CLASS = /(^|\s|:)-?m[xytrbl]?-/
const MARKED = 'data-flexgap'

function apply() {
  // 前回付けた margin を一旦外す (画面幅で向きが変わることがあるため)
  document.querySelectorAll<HTMLElement>(`[${MARKED}]`).forEach((el) => {
    el.style.marginLeft = ''
    el.style.marginTop = ''
    el.style.marginRight = ''
    el.removeAttribute(MARKED)
  })
  document.querySelectorAll<HTMLElement>('[class*="gap-"]').forEach((parent) => {
    const cs = getComputedStyle(parent)
    if (cs.display !== 'flex' && cs.display !== 'inline-flex') return
    const column = cs.flexDirection.startsWith('column')
    const reverse = cs.flexDirection.endsWith('reverse')
    const gap = column ? cs.rowGap : cs.columnGap
    if (!gap || gap === 'normal' || parseFloat(gap) === 0) return
    const wraps = !column && cs.flexWrap !== 'nowrap'
    const rowGap = cs.rowGap && cs.rowGap !== 'normal' ? cs.rowGap : ''
    const children: HTMLElement[] = []
    for (const child of Array.from(parent.children) as HTMLElement[]) {
      const ccs = getComputedStyle(child)
      if (ccs.display === 'none' || ccs.position === 'absolute' || ccs.position === 'fixed') continue
      children.push(child)
    }
    children.forEach((child, i) => {
      if (i === 0 || HAS_MARGIN_CLASS.test(child.className)) return
      if (column) child.style.marginTop = gap
      else if (reverse) child.style.marginRight = gap
      else child.style.marginLeft = gap
      child.setAttribute(MARKED, '')
    })
    if (!wraps) return
    // margin を付けた後の配置で、前の子より下に回り込んだ子は行頭なので、横ではなく上に間隔を付ける
    let prevTop = children.length ? children[0].offsetTop : 0
    children.forEach((child, i) => {
      if (i === 0) return
      const top = child.offsetTop
      if (top > prevTop && child.hasAttribute(MARKED)) {
        child.style.marginLeft = ''
        child.style.marginRight = ''
        child.style.marginTop = rowGap
      }
      prevTop = top
    })
  })
}
