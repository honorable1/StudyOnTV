/**
 * 复制文本到剪贴板，覆盖三种运行环境：
 *
 * 1. App 内嵌 WebView —— 走原生桥（最可靠，Android WebView 的 Clipboard API 经常假成功）
 * 2. HTTPS / localhost —— 用标准 Clipboard API
 * 3. 局域网 HTTP（http://192.168.x.x:8099）—— 非安全上下文里没有 navigator.clipboard，
 *    只能用 execCommand 兜底。
 *
 * 第 3 种有个坑：弹窗（Radix Dialog）有焦点陷阱，把临时 textarea 插到 document.body 上时，
 * 焦点会立刻被抢回弹窗里的按钮，选中内容变成空字符串，execCommand 依然返回 true，
 * 于是提示「已复制」但粘贴出来是空的。所以这里必须把 textarea 插进弹窗内部，
 * 并在执行前确认真的有选中内容，否则如实报错。
 */
function nativeBridge(): { copyText?: (v: string) => boolean } | null {
  const bridge = (window as any).SOTVNative;
  return bridge && typeof bridge.copyText === 'function' ? bridge : null;
}

function focusScope(value: string): HTMLTextAreaElement {
  const ta = document.createElement('textarea');
  ta.value = value;
  ta.setAttribute('readonly', 'readonly');
  ta.setAttribute('aria-hidden', 'true');
  ta.tabIndex = -1;
  ta.style.cssText = [
    'position:fixed', 'top:50%', 'left:50%', 'width:1px', 'height:1px',
    'padding:0', 'margin:0', 'border:0', 'outline:0', 'boxShadow:none',
    'background:transparent', 'color:transparent', 'opacity:0.01',
    'z-index:2147483647', 'pointer-events:none'
  ].join(';');
  return ta;
}

function copyWithExecCommand(value: string): boolean {
  // 弹窗打开时插到弹窗里，避开焦点陷阱；否则插到 body
  const active = document.activeElement as HTMLElement | null;
  const dialog = active?.closest?.('[role="dialog"]') as HTMLElement | null;
  const host = dialog || document.body;
  const previous = active;
  const ta = focusScope(value);
  host.appendChild(ta);
  let copied = false;
  try {
    ta.focus({ preventScroll: true });
    ta.select();
    ta.setSelectionRange(0, value.length);
    // 关键校验：确认选中内容真的等于要复制的文本，否则 execCommand 会“成功”地复制空字符串
    const selection = typeof document.getSelection === 'function' ? String(document.getSelection()) : '';
    if (selection && selection.length > 0) copied = document.execCommand('copy');
  } catch {
    copied = false;
  } finally {
    ta.remove();
    try { previous?.focus?.({ preventScroll: true }); } catch { /* 忽略 */ }
  }
  return copied;
}

export type CopyResult = 'ok' | 'empty' | 'failed';

export async function copyText(text: string): Promise<CopyResult> {
  const value = text ?? '';
  if (!value) return 'empty';

  const bridge = nativeBridge();
  if (bridge?.copyText) {
    try { if (bridge.copyText(value)) return 'ok'; } catch { /* 继续尝试浏览器方案 */ }
  }

  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value);
      return 'ok';
    }
  } catch { /* 非安全上下文或被拒绝，走兜底 */ }

  return copyWithExecCommand(value) ? 'ok' : 'failed';
}
