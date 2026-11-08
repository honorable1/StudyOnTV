(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  var all = function (selector) { return Array.prototype.slice.call(document.querySelectorAll(selector)); };
  var state = { screen: '', round: null, config: {}, learning: {}, busy: false, selected: [], version: 0, awaiting: false, reviewed: false, review: null, draft: null };
  var toastTimer, nextTimer, taps = 0, lastTap = 0, upTaps = 0, lastUpTap = 0;
  function nativeCall(name, args) {
    var bridge = window.SOTVNative;
    try { if (bridge && typeof bridge[name] === 'function') return bridge[name].apply(bridge, args || []); } catch (e) {}
    return null;
  }
  // 接口调用优先走原生桥（进程内路由）：设备令牌不再交给 JS，页面即使被注入脚本也拿不到凭据
  function request(path, body, callback) {
    var bridge = window.SOTVNative;
    if (bridge && typeof bridge.api === 'function') {
      var raw = '';
      try { raw = bridge.api(body ? 'POST' : 'GET', path, body ? JSON.stringify(body) : ''); } catch (e) { raw = ''; }
      var payload;
      try { payload = JSON.parse(raw); } catch (e) { return callback('连接设备失败'); }
      if (!payload) return callback('连接设备失败');
      if (payload.error) return callback(payload.message || '请求失败，请重试');
      return callback(null, payload);
    }
    var xhr = new XMLHttpRequest(), finished = false;
    function done(error, value) { if (finished) return; finished = true; callback(error, value); }
    xhr.open(body ? 'POST' : 'GET', path, true);
    xhr.timeout = 8000;
    xhr.setRequestHeader('Content-Type', 'application/json');
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4) return;
      var data;
      try { data = JSON.parse(xhr.responseText); } catch (e) { return done('连接设备失败'); }
      if (xhr.status < 200 || xhr.status >= 300 || data.error) return done(data.message || '请求失败，请重试');
      done(null, data);
    };
    xhr.onerror = function () { done('连接设备失败'); };
    xhr.ontimeout = function () { done('连接超时，请重试'); };
    xhr.send(body ? JSON.stringify(body) : null);
  }
  function toast(value) {
    var box = $('toast');
    if (!box) { box = document.createElement('div'); box.id = 'toast'; document.body.appendChild(box); }
    box.textContent = value; box.className = 'toast show';
    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(function () { box.className = 'toast'; }, 3000);
  }
  function show(screen) {
    all('.screen').forEach(function (el) { el.className = 'screen'; });
    $('screen-' + screen).className = 'screen active';
    state.screen = screen;
    document.body.className = 'on-' + screen;
  }
  function pill() {
    var c = state.config, st = state.learning;
    var left = Math.max(0, (c.roundsPerDay || 3) - (st.roundsDoneToday || 0));
    if (st.override && st.override.mode === 'unlock') { $('topPill').textContent = '家长已解锁 · ' + left2(st.override.remainingSeconds); return; }
    if (st.override && st.override.mode === 'indefinite') { $('topPill').textContent = '管控已关闭'; return; }
    if (st.free) { $('topPill').textContent = '自由时段'; return; }
    var remSec = st.remainingSeconds || 0;
    var remStr = remSec > 0 ? (remSec < 3600 ? '剩 ' + Math.ceil(remSec / 60) + ' 分钟' : '剩 ' + Math.floor(remSec / 3600) + ' 小时 ' + Math.ceil((remSec % 3600) / 60) + ' 分') + ' · ' : '';
    $('topPill').textContent = remStr + '还剩 ' + left + ' 组' + (st.extraPool > 0 ? ' · 加练池 ' + st.extraPool + ' 题' : '');
  }
  function left2(seconds) {
    var m = Math.ceil(seconds / 60);
    return m >= 60 ? '还剩 ' + Math.floor(m / 60) + ' 小时' + (m % 60 ? ' ' + (m % 60) + ' 分' : '') : '还剩 ' + m + ' 分钟';
  }
  function loadState(callback) {
    request('/api/state', null, function (error, result) {
      if (!error) { state.config = result.config; state.learning = result.learning; pill(); applyTheme(result.config.theme); }
      if (callback) callback(error, result);
    });
  }
  function start() {
    if (state.busy) return;
    state.busy = true;
    request('/api/quiz/start', {}, function (error, result) {
      state.busy = false;
      if (error) {
        show('quiz'); $('qText').textContent = error;
        $('qOptions').innerHTML = '<button class="btn btn-primary focusable" data-action="retry">重试</button>';
        return;
      }
      state.learning = result.status || state.learning;
      pill();
      if (result.pending) return pending(result.reviewId);
      if (result.reviewed) return reviewed(result);
      state.awaiting = false; state.reviewed = false;
      if (result.blocked) return lock(result.blocked);
      if (result.free) {
        // 「只在这段时间内需要先答题」模式下的时段外：不管控
        state.lockReason = '';
        var ov = state.learning.override;
        show('lock'); icon($('lockIcon'), 'check');
        $('lockTitle').textContent = ov && ov.mode === 'unlock' ? '家长已临时解锁' : '现在是自由时段';
        $('lockDesc').textContent = ov && ov.mode === 'unlock' ? left2(ov.remainingSeconds) + '，到时自动恢复学习规则' : '每天 ' + state.config.windowStart + ' — ' + state.config.windowEnd + ' 需要先完成学习任务，其余时间可以直接使用';
        $('lockNote').textContent = '';
        $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="home">返回桌面</button>';
        focusDefault(); return;
      }
      if (result.watching) {
        state.lockReason = '';
        show('lock'); $('lockTitle').textContent = '已解锁使用时间';
        $('lockDesc').textContent = '还可以使用 ' + Math.ceil(state.learning.remainingSeconds / 60) + ' 分钟';
        icon($('lockIcon'), 'check'); $('lockNote').textContent = '';
        $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="home">返回桌面</button>';
        focusDefault(); return;
      }
      state.round = result;
      show('quiz'); renderQuestion();
    });
  }
  function renderQuestion() {
    var round = state.round, item = round.question;
    if (window._pendingInput) { state.draft = { nonce: round.nonce, text: window._pendingInput.text || '', drawing: window._pendingInput.drawing || '' }; window._pendingInput = null; }
    else if (!state.draft || state.draft.nonce !== round.nonce) state.draft = { nonce: round.nonce, text: '', drawing: '' };
    state.selected = [];
    $('qText').textContent = item.q;
    $('qIndex').textContent = round.mode === 'streak' ? round.streak : round.mode === 'practice' ? round.answered + 1 : Math.min(round.answered + 1, round.target);
    $('qTotal').textContent = round.mode === 'practice' ? '题' : '/ ' + round.target;
    $('qMode').textContent = (round.extra ? '加练任务' : round.mode === 'streak' ? '连续答对' : round.mode === 'practice' ? '自由练习' : '本组任务') + ' · ' + new Array((item.difficulty || 2) + 1).join('★');
    $('qPool').textContent = round.extraPool > 0 ? '加练池还剩 ' + round.extraPool + ' 题' : '';
    $('qDots').textContent = round.mode === 'streak' ? '连续答对 ' + round.streak + ' 题' : '';
    var qLength = (item.q || '').length;
    $('qText').className = 'question' + (qLength > 120 ? ' longer' : qLength > 56 ? ' long' : '');
    try { if (isTv() && window.localStorage.getItem('sotv_tts') === '1') { nativeCall('readAloud', [item.q]); } } catch (e) {}
    var options = $('qOptions'); options.innerHTML = '';
    var longest = 0;
    (item.a || []).forEach(function (value) { longest = Math.max(longest, String(value).length); });
    options.className = 'options' + ((item.a || []).length > 4 ? ' many' : '') + (longest > 26 ? ' wordy' : '');
    if (item.type === 'text' || item.type === 'subjective') {
      var input = document.createElement('textarea'); input.id = 'writtenAnswer'; input.className = 'written-answer';
      input.placeholder = item.type === 'subjective' ? '写下您的思路和答案…' : '请输入答案'; input.maxLength = 10000; input.autocomplete = 'off'; input.rows = 4;
      input.value = state.draft.text;
      input.oninput = function () { state.draft.text = input.value; };
      if (isTv()) {
        // 遥控器无法直接在网页输入框里打字：按 OK 打开原生输入框（可调出系统输入法 / 语音）
        input.readOnly = true; input.className += ' focusable'; input.setAttribute('data-action', 'type-answer');
        input.placeholder = '按 OK 键输入答案';
      }
      options.appendChild(input);
      var tools = document.createElement('div'); tools.className = 'input-tools';
      if (!isTv()) {
        tools.innerHTML = '<button class="btn btn-ghost focusable" data-action="voice">语音输入</button><button class="btn btn-ghost focusable" data-action="handwrite">手写作答</button>';
        options.appendChild(tools);
      }
      if (state.draft.drawing) {
        var image = document.createElement('img'); image.className = 'drawing-preview'; image.src = state.draft.drawing; options.appendChild(image);
        var clear = document.createElement('button'); clear.className = 'btn btn-ghost focusable'; clear.setAttribute('data-action', 'clear-drawing'); clear.textContent = '清除手写'; options.appendChild(clear);
      }
      if (isTv()) { var readBtn = document.createElement('button'); readBtn.className = 'btn btn-ghost focusable'; readBtn.setAttribute('data-action', 'read-aloud'); readBtn.textContent = '朗读题目'; options.appendChild(readBtn); }
    addSubmit(options, '提交答案');
    } else {
      item.a.forEach(function (value, index) {
        var button = document.createElement('button'); button.className = 'option focusable';
        button.setAttribute('data-opt', index);
        var letter = document.createElement('span'); letter.className = 'letter'; letter.textContent = String.fromCharCode(65 + index);
        var label = document.createElement('span'); label.className = 'opt-text'; label.textContent = value;
        button.appendChild(letter); button.appendChild(label); options.appendChild(button);
      });
      if (item.type === 'multiple') addSubmit(options, '确认所选答案');
    }
    window.SOTV_MATH($('qText')); window.SOTV_MATH(options);
    focusDefault();
  }
  function addSubmit(parent, value) {
    var button = document.createElement('button'); button.className = 'btn btn-primary focusable answer-submit';
    button.setAttribute('data-action', 'submit'); button.textContent = value; parent.appendChild(button);
  }
  function choose(index) {
    if (state.busy || !state.round) return;
    if (state.round.question.type === 'multiple') {
      var pos = state.selected.indexOf(index);
      if (pos < 0) state.selected.push(index); else state.selected.splice(pos, 1);
      all('#qOptions [data-opt]').forEach(function (el) {
        el.classList.toggle('selected', state.selected.indexOf(+el.getAttribute('data-opt')) >= 0);
      });
    } else { state.selected = [index]; submit(); }
  }
  function submit() {
    if (state.busy || !state.round) return;
    var round = state.round, item = round.question, written = $('writtenAnswer');
    if (written ? (!written.value.trim() && !state.draft.drawing) : !state.selected.length) { toast('请先填写或选择答案'); return; }
    state.busy = true;
    request('/api/quiz/answer', { roundId: round.id, nonce: round.nonce, selections: state.selected, text: written ? written.value : '', drawing: state.draft ? state.draft.drawing : '' }, function (error, result) {
      if (error) { state.busy = false; toast(error); return; }
      if (result.pending) { state.busy = false; pending(result.reviewId); return; }
      if (result.blocked) { state.busy = false; lock(result.blocked); return; }
      state.learning = result.status; pill();
      all('#qOptions [data-opt]').forEach(function (el) {
        var i = +el.getAttribute('data-opt');
        if (result.correctIndices.indexOf(i) >= 0) el.className += ' right';
        else if (state.selected.indexOf(i) >= 0) el.className += ' wrong';
      });
      state.feedback = result;
      showFeedback(result, item, written);
      var auto = +state.config.autoNextSeconds || 0;
      if (auto > 0) nextTimer = window.setTimeout(proceed, auto * 1000);
    });
  }
  var PRAISE = ['太棒了！', '答对啦！', '完全正确！', '真厉害！', '好样的，继续保持！', '漂亮！你越来越熟练了'];
  var ENCOURAGE = ['没关系，记住正确答案就好', '差一点点，下一题加油！', '错误也是学习的一部分', '别灰心，再接再厉！'];
  function phrase(list, fallback) {
    var pool = (list && list.length ? list : fallback).filter(function (v) { return v && String(v).trim(); });
    if (!pool.length) pool = fallback;
    return String(pool[Math.floor(Math.random() * pool.length)]);
  }
  // 答题结果显示在题目下方（参考多邻国），孩子看完后自己按“继续”进入下一题（Issue #3）
  function showFeedback(result, item, written) {
    var box = $('qFeedback'), c = state.config, right = !!result.correct;
    var title = right ? phrase(c.praisePhrases, PRAISE) : phrase(c.encouragePhrases, ENCOURAGE);
    if (right && state.round && state.round.mode !== 'practice' && result.next && result.next.streak >= 3) title = '连续答对 ' + result.next.streak + ' 题！' + title;
    var answer = '';
    if (!right) {
      if (written) answer = result.answerText;
      else answer = (result.correctIndices || []).map(function (i) { return String.fromCharCode(65 + i) + '. ' + (item.a || [])[i]; }).join('　');
    }
    box.className = 'feedback show ' + (right ? 'is-right' : 'is-wrong');
    $('fbIcon').textContent = right ? '✓' : '!';
    $('fbTitle').textContent = title;
    $('fbAnswer').textContent = answer ? '正确答案：' + answer : '';
    $('fbTip').textContent = result.tip ? '解析：' + result.tip : '';
    var go = $('fbNext');
    go.textContent = result.finished ? '查看结果' : '继续';
    go.className = 'btn ' + (right ? 'btn-primary' : 'btn-danger') + ' focusable';
    if (window.SOTV_MATH) { window.SOTV_MATH($('fbAnswer')); window.SOTV_MATH($('fbTip')); }
    // 选项已经作答，焦点只留在“继续”上
    all('#qOptions .focusable').forEach(function (el) { el.classList.remove('focusable'); el.classList.add('answered'); });
    var list = focusables(); for (var i = 0; i < list.length; i++) if (list[i] === go) { setFocus(i); break; }
    if (!isTv()) go.focus();
  }
  function hideFeedback() { $('qFeedback').className = 'feedback'; state.feedback = null; }
  function proceed() {
    var result = state.feedback;
    if (!result) return;
    window.clearTimeout(nextTimer);
    hideFeedback();
    state.busy = false;
    if (state.screen !== 'quiz') return;
    if (result.finished) {
      state.round = null;
      if (result.passed) {
        if (result.status.reason) { lock(result.status.reason); toast('奖励已保存，达到可用时段后使用'); return; }
        show('lock'); $('lockTitle').textContent = '任务完成'; icon($('lockIcon'), 'check');
        $('lockDesc').textContent = '已解锁 ' + Math.round(result.rewardMinutes * 10) / 10 + ' 分钟'; $('lockNote').textContent = '';
        $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="resume">开始使用</button>';
        focusDefault();
        // 解锁后回到解题前正在看的应用，不要跳回桌面（#68 / #74）
        window.setTimeout(function () { if (state.screen === 'lock') nativeCall('resumePrevious'); }, 2500);
      } else { toast('本组未通过，再试一组'); start(); }
    } else { state.round = result.next; renderQuestion(); }
  }
  function reviewed(result) {
    state.awaiting = false; state.reviewed = true; state.review = result;
    show('lock'); icon($('lockIcon'), result.result.correct ? 'check' : 'pencil');
    $('lockTitle').textContent = '批改完成 · ' + result.score + ' 分';
    $('lockDesc').textContent = result.feedback || '已保存本次批改结果';
    $('lockNote').textContent = result.result.correct ? '本题通过' : '查看反馈后继续练习';
    $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="review-continue">' + (result.result.finished && result.result.passed ? '继续使用' : '继续答题') + '</button>';
    if (window.SOTV_MATH) window.SOTV_MATH($('lockDesc'));
    focusDefault();
  }
  function pending(id) {
    state.awaiting = true; state.reviewed = false;
    show('lock'); icon($('lockIcon'), 'check'); $('lockTitle').textContent = '答案已保存';
    $('lockDesc').textContent = '等待家长或 AI 批改，通过后继续任务';
    $('lockNote').textContent = '';
    $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="retry">查看批改结果</button><button class="btn btn-ghost focusable" data-action="skip-review">先跳过这题</button>';
    focusDefault();
  }
  function lock(reason) {
    state.lockReason = reason;
    show('lock'); state.round = null;
    if (reason === 'settings') {
      icon($('lockIcon'), 'hourglass');
      $('lockTitle').textContent = '管控期间暂不能打开系统设置';
      $('lockDesc').textContent = '家长验证后可临时进入，或返回学习页继续。';
      $('lockNote').textContent = '';
      $('lockActions').innerHTML = '<button class="btn btn-primary focusable" data-action="return-learning">返回学习页</button>';
      focusDefault(); return;
    }
    if (reason === 'locked') {
      var ov = state.learning.override || {};
      icon($('lockIcon'), 'lock');
      $('lockTitle').textContent = '家长已暂停使用';
      $('lockDesc').textContent = ov.remainingSeconds > 0 ? left2(ov.remainingSeconds) + '后自动恢复' : '需要使用时，请找家长解锁';
      $('lockNote').textContent = '';
      $('lockActions').innerHTML = '';
      focusDefault(); return;
    }
    icon($('lockIcon'), reason === 'schedule' ? 'clock' : 'hourglass');
    $('lockTitle').textContent = reason === 'schedule' ? '还没到可用时间' : reason === 'rounds' ? '今天的任务已全部完成' : reason === 'quota' ? '今天的使用时间已用完' : '使用时间到了';
    var when = '';
    // 家长把一周时段全部关掉时后端返回 -1：此时不能显示"下次可用：现在"
    if (state.learning.nextAvailableAt > 0) {
      var date = new Date(state.learning.nextAvailableAt);
      when = (date.getMonth() + 1) + ' 月 ' + date.getDate() + ' 日 ' + ('0' + date.getHours()).slice(-2) + ':' + ('0' + date.getMinutes()).slice(-2);
    }
    $('lockDesc').textContent = when ? '下次可用：' + when
      : state.learning.nextAvailableAt < 0 ? '暂无可用时段，请联系家长调整规则'
      : reason === 'timeup' ? '完成学习任务后才能使用设备' : '现在暂不可用';
    $('lockNote').textContent = state.config.allDay ? '' : '可用时段 ' + state.config.windowStart + ' — ' + state.config.windowEnd;
    $('lockActions').innerHTML = reason === 'timeup' ? '<button class="btn btn-primary focusable" data-action="retry">开始答题</button>' : '';
    focusDefault();
  }
  function pair() {
    var raw = nativeCall('lanInfo'), info;
    try { info = JSON.parse(raw); } catch (e) {}
    if (!info) { nativeCall('openParent'); return; }
    show('pair');
    if (!info.ip) { $('pairQr').textContent = '请先连接 WiFi'; $('pairUrl').textContent = '暂无局域网地址，学习功能可离线使用'; focusDefault(); return; }
    var url = 'http://' + info.ip + ':' + info.port + '/admin/';
    $('pairUrl').textContent = url;
    var qr = window.qrcode(0, 'M'); qr.addData(url); qr.make(); $('pairQr').innerHTML = qr.createImgTag(8, 8);
    focusDefault();
  }
  // 深色 / 浅色 / 跟随系统（电视系统的深色模式设置）
  function applyTheme(name) {
    if (name === 'light' || name === 'daylight') name = 'light';
    else if (name === 'system') { var info = {}; try { info = JSON.parse(nativeCall('deviceInfo') || '{}'); } catch (e) {} name = info.night === false ? 'light' : 'dark'; }
    else name = 'dark';
    if (document.documentElement.getAttribute('data-mode') === name) return;
    document.documentElement.setAttribute('data-mode', name);
    try { window.localStorage.setItem('sotv_mode', name); } catch (e) {}
  }
  // 用内联 SVG 代替 ⌛ 等字符：很多电视的字体里没有这些符号，会显示成空白
  var ICONS = {
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    hourglass: '<path d="M7 3h10M7 21h10M8 3c0 5 8 5 8 9s-8 4-8 9M16 3c0 5-8 5-8 9s8 4 8 9"/>',
    check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
    lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
    pencil: '<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="M13.5 6.5l4 4"/>'
  };
  function icon(el, name) {
    el.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' + ICONS[name] + '</svg>';
  }
  function isTv() { return document.documentElement.classList.contains('tv-device'); }
  function focusables() {
    var screen = document.querySelector('.screen.active');
    var list = screen ? Array.prototype.slice.call(screen.querySelectorAll('.focusable')) : [];
    // 右上角更新提示也要能用遥控器选中
    if ($('updateTip').className.indexOf('show') >= 0) list = list.concat(all('#updateTip .focusable'));
    return list.filter(function (el) { return el.offsetWidth > 0 || el.offsetHeight > 0; });
  }
  function reveal(el) {
    // 旧版电视 WebView 不支持 scrollIntoView({block}) 参数，手动滚动到可见
    var r = el.getBoundingClientRect(), h = window.innerHeight || document.documentElement.clientHeight;
    if (r.top < 0) window.scrollBy(0, r.top - 24); else if (r.bottom > h) window.scrollBy(0, r.bottom - h + 24);
  }
  function setFocus(index) {
    var list = focusables();
    all('.focused').forEach(function (el) { el.classList.remove('focused'); });
    if (list[index]) { list[index].classList.add('focused'); reveal(list[index]); state.focusKey = focusKey(list[index]); }
  }
  function focusKey(el) { return el.getAttribute('data-opt') !== null ? 'opt' + el.getAttribute('data-opt') : el.getAttribute('data-action') || el.id || ''; }
  function focusDefault() { if (isTv()) setFocus(0); }
  // 返回 true 表示焦点移动了；false 表示已到边缘（原生层据此判断“在最上方连按 ↑”打开家长入口）
  function move(dir) {
    var list = focusables(), index = -1, best = -1, score = 1e12;
    list.forEach(function (el, i) { if (el.classList.contains('focused')) index = i; });
    if (index < 0) { if (!list.length) return false; setFocus(0); return true; }
    var rect = list[index].getBoundingClientRect();
    list.forEach(function (el, i) {
      if (i === index) return;
      var r = el.getBoundingClientRect(), dx = r.left + r.width / 2 - rect.left - rect.width / 2, dy = r.top + r.height / 2 - rect.top - rect.height / 2;
      var primary = dir === 'left' ? -dx : dir === 'right' ? dx : dir === 'up' ? -dy : dy;
      var secondary = dir === 'left' || dir === 'right' ? Math.abs(dy) : Math.abs(dx);
      if (primary > 8 && primary + secondary * 1.8 < score) { score = primary + secondary * 1.8; best = i; }
    });
    if (best >= 0) { setFocus(best); return true; }
    return false;
  }
  function activate(el) {
    if (!el) el = document.querySelector('.screen.active .focused') || document.querySelector('.focused');
    if (!el) return;
    if (el.hasAttribute('data-opt')) return choose(+el.getAttribute('data-opt'));
    // 用 switch 而不是一串独立 if：action 是单值，串行判断既慢又容易在新增分支时漏掉 return
    switch (el.getAttribute('data-action')) {
      case 'submit': return submit();
      case 'next': return proceed();
      case 'review-continue':
        if (state.busy) return;
        state.busy = true;
        return request('/api/quiz/review', { reviewId: state.review.reviewId }, function (error, result) {
          state.busy = false;
          if (error) { toast(error); return; }
          var finished = state.review.result.finished && state.review.result.passed;
          state.reviewed = false;
          if (finished && !result.status.reason && result.status.remainingSeconds > 0) nativeCall('resumePrevious');
          else start();
        });
      case 'voice': return nativeCall('answerInput', [true]);
      case 'handwrite': return nativeCall('answerInput', [false]);
      case 'type-answer':
        if (state.busy) return;
        return nativeCall('answerText', [state.draft ? state.draft.text : '', state.round ? state.round.question.q : '']);
      case 'update-go':
        var u = window.currentUpdate;
        if (u) nativeCall('installUpdate', [u.url, u.latest, u.notes || '']);
        return;
      case 'update-close':
        $('updateTip').className = 'update-tip';
        try { if (window.currentUpdate) window.localStorage.setItem('sotv_update_dismissed', window.currentUpdate.latest); } catch (e) {}
        if (el.classList.contains('focused')) focusDefault();
        return;
      case 'permissions': return nativeCall('openPermissions');
      case 'password': return nativeCall('changePassword');
      case 'clear-drawing': state.draft.drawing = ''; return renderQuestion();
      case 'read-aloud': try { nativeCall('readAloud', [state.round ? state.round.question.q : '']); } catch (e) {} return;
      case 'retry': return start();
      case 'skip-review':
        return request('/api/quiz/skip', {}, function (error, result) {
          if (error) { toast(error); return; }
          state.awaiting = false; state.reviewed = false; state.busy = false; start();
        });
      case 'return-learning': return nativeCall('backToQuiz');
      case 'home': return nativeCall('exitToLauncher');
      case 'resume': return nativeCall('resumePrevious');
      case 'quiz': return state.screen === 'pair' ? nativeCall('backToQuiz') : start();
    }
  }
  function route() {
    window.clearTimeout(nextTimer); state.busy = false; hideFeedback();
    var hash = location.hash || '#/quiz';
    state.settingsGate = hash.indexOf('reason=settings') >= 0;
    if (hash.indexOf('#/pair') === 0) return pair();
    loadState(function (error) {
      if (error) { $('qText').textContent = error; return; }
      if (state.settingsGate) {
        if (state.config.blockSettingsDuringControl !== false && !state.learning.free) lock('settings');
        else { state.settingsGate = false; start(); }
      } else if (hash.indexOf('#/lock') === 0 && !state.learning.free) lock(state.learning.reason || 'timeup');
      else start();
    });
  }
  function boot() {
    show('quiz');
    document.querySelector('.brand').addEventListener('click', function () {
      var now = Date.now(); taps = now - lastTap < 900 ? taps + 1 : 1; lastTap = now;
      if (taps >= 5) { taps = 0; nativeCall('openParent'); }
    });
    document.addEventListener('click', function (event) {
      var el = event.target;
      while (el && el !== document.body) {
        if (el.classList && el.classList.contains('focusable')) { activate(el); return; }
        el = el.parentNode;
      }
    });
    // 只有家长页 / 实体键盘会走到这里（学习页的遥控器按键由原生层转发给 window.TV）
    document.addEventListener('keydown', function (event) {
      if (event.target.tagName === 'INPUT' || (event.target.tagName === 'TEXTAREA' && !event.target.readOnly)) return;
      var dir = {37:'left',38:'up',39:'right',40:'down'}[event.keyCode];
      if (dir) {
        var moved = move(dir);
        if (dir === 'up' && !moved && !event.repeat && state.screen !== 'pair') {
          var now = Date.now(); upTaps = now - lastUpTap < 900 ? upTaps + 1 : 1; lastUpTap = now;
          if (upTaps >= 5) { upTaps = 0; nativeCall('openParent'); }
        } else if (!event.repeat) upTaps = 0;
      } else if (event.keyCode === 13) { if (!event.repeat) activate(); } else return;
      event.preventDefault();
    });
    var info = nativeCall('deviceInfo');
    if (info && JSON.parse(info).platform === 'android-tv') document.documentElement.classList.add('tv-device');
    else if (info) { var analytics = document.createElement('script'); analytics.src = 'assets/js/analytics.js'; analytics.async = true; document.head.appendChild(analytics); }
    // 先用上次的主题，避免加载配置前闪一下默认配色
    try { applyTheme(window.localStorage.getItem('sotv_mode')); } catch (e) { applyTheme('dark'); }
    // 启动时显示已缓存的更新信息（原生层在页面加载前拿到结果时，回调会丢失）
    try { var cached = nativeCall('updateInfo'); if (cached) window.SOTV_UPDATE(JSON.parse(cached)); } catch (e) {}
    route();
    window.setInterval(function () {
      $('clock').textContent = new Date().toTimeString().slice(0, 5);
      if ((state.busy && !state.feedback) || state.screen === 'pair') return;
      var version = state.config.version, wasFree = !!state.learning.free;
      loadState(function (error) {
        if (error) return;
        if (state.settingsGate) {
          if (!state.learning.free && state.config.blockSettingsDuringControl !== false) return;
          state.settingsGate = false; start(); return;
        }
        if (wasFree !== !!state.learning.free && state.config.playMode !== 'practice') {
          window.clearTimeout(nextTimer); state.busy = false; state.awaiting = false; state.reviewed = false;
          hideFeedback(); start(); return;
        }
        var ovKey = state.learning.override ? state.learning.override.mode + state.learning.override.at : '';
        if (ovKey !== (state.ovKey || '')) {
          state.ovKey = ovKey;
          window.clearTimeout(nextTimer); state.busy = false; state.awaiting = false; state.reviewed = false;
          hideFeedback(); start(); return;
        }
        if (state.reviewed || state.feedback) return;
        if (state.awaiting) { if (!state.learning.awaitingReview) start(); return; }
        if (state.screen === 'lock' && (state.lockReason !== state.learning.reason || state.wasFree !== !!state.learning.free)) {
          state.wasFree = !!state.learning.free;
          if (!state.learning.reason) start(); else lock(state.learning.reason);
          return;
        }
        if (state.screen === 'quiz' && state.learning.reason && ['quota','rounds','schedule'].indexOf(state.learning.reason) >= 0 && state.config.playMode !== 'practice') lock(state.learning.reason);
        else if (version !== state.config.version && state.screen === 'quiz') start();
      });
    }, 2000);
  }
  window.SOTV_INPUT = function (result, token) {
    // 原生侧靠这个标记确认注入成功，失败会重试（部分电视 WebView 切回来时 JS 尚未就绪）
    if (token) window.__sotvAck = token;
    if (!state.round) { window._pendingInput = result; return; }
    state.draft = { nonce: state.round.nonce, text: result.text || '', drawing: result.drawing || '' };
    window._pendingInput = null;
    renderQuestion();
    // 遥控器输入完成后直接把焦点放到“提交答案”
    if (isTv() && state.draft.text) { var list = focusables(); for (var i = 0; i < list.length; i++) if (list[i].getAttribute('data-action') === 'submit') { setFocus(i); break; } }
  };
  window.SOTV_UPDATE = function (info) { if (!info || !info.latest) return; window.currentUpdate = info;
    try { if (!info.force && window.localStorage.getItem('sotv_update_dismissed') === info.latest) return; } catch (e) {} $('updateText').textContent = '发现新版本 v' + info.latest; $('updateTip').className = 'update-tip show'; };
  window.TV = { move: move, select: function () { activate(); }, back: function () { if (!state.learning.reason && (state.learning.remainingSeconds > 0 || state.learning.free)) nativeCall('exitToLauncher'); else toast('现在暂不可用'); }, state: state };
  window.SOTV_RESUME = function () { if (document.readyState === 'complete' && !state.busy && state.screen !== 'pair') route(); };
  window.addEventListener('hashchange', route);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot); else boot();
})();
