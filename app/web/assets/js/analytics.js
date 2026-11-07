(function () {
  'use strict';

  // 默认关闭。这是给未成年学生用的应用，向境外统计服务（GA4 / Clarity）传输数据属于
  // 个人信息出境，必须在家长明确同意后才上报。同意状态存在 localStorage，
  // 由家长设置页或原生层写入 'sotv_analytics_consent' = '1'。
  var CONFIG = {
    ga4Id: 'G-561826488',
    clarityId: 'z7790sezw1',
    enabled: false,
    maskText: true,
    debug: false
  };

  try {
    if (window.localStorage.getItem('sotv_analytics_consent') === '1') CONFIG.enabled = true;
  } catch (e) {}

  var queue = [];
  window.SOTV_TRACK = function (name, params) {
    var event = { name: name, params: params || {}, ts: Date.now() };
    if (CONFIG.debug || !CONFIG.enabled || CONFIG.ga4Id.indexOf('XXXX') > -1) {
      if (CONFIG.debug) { console.log('[track]', event); }
      return;
    }
    try {
      if (window.gtag) {
        window.gtag('event', name, Object.assign({ page: location.pathname }, event.params));
      }
      if (window.clarity) {
        window.clarity('event', name);
      }
    } catch (e) {
      queue.push(event);
    }
  };

  // 会话录制会把页面上的题干和孩子输入的答案一起录下来，必须做文本遮罩
  function mask() {
    if (!CONFIG.maskText) return;
    try {
      document.documentElement.setAttribute('data-clarity-mask', 'true');
      document.body.setAttribute('data-clarity-mask', 'true');
      if (window.clarity) window.clarity('set', 'maskText', 'true');
    } catch (e) {}
  }

  if (CONFIG.enabled && CONFIG.ga4Id.indexOf('XXXX') < 0) {
    var ga = document.createElement('script');
    ga.async = true;
    ga.src = 'https://www.googletagmanager.com/gtag/js?id=' + CONFIG.ga4Id;
    document.head.appendChild(ga);
    window.dataLayer = window.dataLayer || [];
    window.gtag = function () { window.dataLayer.push(arguments); };
    window.gtag('js', new Date());
    window.gtag('config', CONFIG.ga4Id, { send_page_view: true });
  }

  if (CONFIG.enabled && CONFIG.clarityId.indexOf('abcdef') < 0) {
    mask();
    (function (c, l, a, r, i, t, y) {
      c[a] = c[a] || function () { (c[a].q = c[a].q || []).push(arguments); };
      t = l.createElement(r); t.async = 1; t.src = 'https://www.clarity.ms/tag/' + i;
      y = l.getElementsByTagName(r)[0]; y.parentNode.insertBefore(t, y);
    })(window, document, 'clarity', 'script', CONFIG.clarityId);
  }

  document.addEventListener('DOMContentLoaded', function () {
    if (CONFIG.maskText) mask();
    window.SOTV_TRACK('page_view', {
      page: location.pathname,
      title: document.title,
      ua_mobile: /Mobi|Android/i.test(navigator.userAgent)
    });
  });

  window.addEventListener('error', function (e) {
    window.SOTV_TRACK('js_error', { message: String(e.message).slice(0, 120) });
  });

  window.SOTV_ANALYTICS_CONFIG = CONFIG;
})();
