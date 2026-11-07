/**
 * 管理面板的登录加固垫片（不修改打包产物）。
 *
 * 面板仍然提交 { passphrase }，这里在发出前把它换成一次性挑战的 HMAC 应答：
 *   1. GET  /api/auth/challenge  → { challenge, salt }
 *   2. key   = sha256(密码 + ":" + salt)
 *   3. POST /api/auth            → { challenge, proof: HMAC-SHA256(key, challenge) }
 * 密码本身不再上网，抓包只能看到一次性的 challenge 与 proof，无法重放。
 *
 * 为什么自带 SHA-256：局域网是 http://192.168.x.x，不是安全上下文（secure context），
 * 浏览器不提供 crypto.subtle。任何一步失败都会退回原来的请求方式，不会让面板登不进来。
 */
(function (root) {
  'use strict';

  var K = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  ];

  function rotr(x, n) { return (x >>> n) | (x << (32 - n)); }

  function utf8(str) {
    if (typeof TextEncoder !== 'undefined') return new TextEncoder().encode(str);
    var out = [], i, c;
    for (i = 0; i < str.length; i++) {
      c = str.charCodeAt(i);
      if (c < 0x80) out.push(c);
      else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
      else if (c < 0xd800 || c >= 0xe000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
      else {
        i++;
        c = 0x10000 + (((c & 0x3ff) << 10) | (str.charCodeAt(i) & 0x3ff));
        out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
      }
    }
    return new Uint8Array(out);
  }

  function sha256(bytes) {
    var H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    var l = bytes.length;
    var total = (((l + 8) >> 6) + 1) << 6;
    var block = new Uint8Array(total);
    block.set(bytes);
    block[l] = 0x80;
    var view = new DataView(block.buffer);
    view.setUint32(total - 8, Math.floor(l / 536870912), false);
    view.setUint32(total - 4, (l << 3) >>> 0, false);
    var w = new Int32Array(64);
    for (var off = 0; off < total; off += 64) {
      for (var t = 0; t < 16; t++) w[t] = view.getInt32(off + t * 4, false);
      for (t = 16; t < 64; t++) {
        var x = w[t - 15], y = w[t - 2];
        var s0 = rotr(x, 7) ^ rotr(x, 18) ^ (x >>> 3);
        var s1 = rotr(y, 17) ^ rotr(y, 19) ^ (y >>> 10);
        w[t] = (w[t - 16] + s0 + w[t - 7] + s1) | 0;
      }
      var a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
      for (t = 0; t < 64; t++) {
        var S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
        var ch = (e & f) ^ (~e & g);
        var t1 = (h + S1 + ch + K[t] + w[t]) | 0;
        var S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
        var maj = (a & b) ^ (a & c) ^ (b & c);
        var t2 = (S0 + maj) | 0;
        h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
      }
      H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0;
      H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
    }
    var out = new Uint8Array(32), ov = new DataView(out.buffer);
    for (var i = 0; i < 8; i++) ov.setInt32(i * 4, H[i], false);
    return out;
  }

  function toHex(bytes) {
    var out = '';
    for (var i = 0; i < bytes.length; i++) out += (bytes[i] < 16 ? '0' : '') + bytes[i].toString(16);
    return out;
  }

  function sha256Hex(str) { return toHex(sha256(utf8(str))); }

  function concat(a, b) {
    var out = new Uint8Array(a.length + b.length);
    out.set(a); out.set(b, a.length);
    return out;
  }

  function hmacHex(keyStr, message) {
    var key = utf8(keyStr);
    if (key.length > 64) key = sha256(key);
    var ipad = new Uint8Array(64), opad = new Uint8Array(64), i, k;
    for (i = 0; i < 64; i++) { k = i < key.length ? key[i] : 0; ipad[i] = k ^ 0x36; opad[i] = k ^ 0x5c; }
    var inner = sha256(concat(ipad, utf8(message)));
    return toHex(sha256(concat(opad, inner)));
  }

  root.SOTV_AUTH = { sha256Hex: sha256Hex, hmacHex: hmacHex };

  if (typeof root.fetch !== 'function') return;
  var nativeFetch = root.fetch.bind(root);

  function loginBody(init) {
    if (!init || typeof init.body !== 'string' || init.body.indexOf('passphrase') < 0) return null;
    try {
      var data = JSON.parse(init.body);
      if (data && typeof data.passphrase === 'string') return data;
    } catch (e) {}
    return null;
  }

  function endpointOf(input) {
    try {
      var href = typeof input === 'string' ? input : (input && input.url);
      return new URL(href, root.location.href);
    } catch (e) { return null; }
  }

  function upgrade(url, data, input, init) {
    return nativeFetch(url.origin + '/api/auth/challenge', { method: 'GET', cache: 'no-store', signal: init.signal })
      .then(function (res) { return res.ok ? res.json() : null; })
      .then(function (chal) {
        if (!chal || !chal.challenge || !chal.salt) return nativeFetch(input, init);   // 设备不支持：沿用原协议
        var key = sha256Hex(data.passphrase + ':' + chal.salt);
        var body = JSON.stringify({
          challenge: chal.challenge,
          proof: hmacHex(key, chal.challenge),
          remember: !!data.remember
        });
        return nativeFetch(input, { method: 'POST', headers: init.headers, body: body, cache: 'no-store', signal: init.signal });
      })
      .catch(function () { return nativeFetch(input, init); });
  }

  root.fetch = function (input, init) {
    try {
      var data = loginBody(init);
      if (data) {
        var url = endpointOf(input);
        if (url && /\/api\/auth$/.test(url.pathname)) return upgrade(url, data, input, init);
      }
    } catch (e) {}
    return nativeFetch(input, init);
  };
})(typeof window !== 'undefined' ? window : this);
