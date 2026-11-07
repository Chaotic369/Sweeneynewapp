/* Native bridge for the Android app. Runs before the web app. Does nothing in a normal browser. */
(function () {
  var N = window.HPNative;
  if (!N) return;

  function $(id) { return document.getElementById(id); }
  function safe(fn) { try { return fn(); } catch (e) { return undefined; } }

  /* ---------- session (saved PIN → no typing) ---------- */
  var session = null;
  try { session = JSON.parse(N.getSession()); } catch (e) { session = null; }
  var hasSession = !!(session && session.pin && session.role);
  if (hasSession) {
    var st = document.createElement('style');
    st.id = 'hpAutoStyle';
    st.textContent = '#setupModal{visibility:hidden!important}';
    (document.head || document.documentElement).appendChild(st);
  }
  function revealLogin() {
    var s = $('hpAutoStyle');
    if (s && s.parentNode) s.parentNode.removeChild(s);
  }

  /* ---------- Notification API shim (WebView has none) ---------- */
  if (!('Notification' in window)) {
    var FakeNotification = function (title, opts) { this.title = title; this.options = opts || {}; this.onclick = null; };
    FakeNotification.prototype.close = function () {};
    FakeNotification.permission = 'granted';
    FakeNotification.requestPermission = function (cb) { if (cb) cb('granted'); return Promise.resolve('granted'); };
    window.Notification = FakeNotification;
  }

  /* ---------- incoming call helpers ---------- */
  function incoming() {
    var o = $('callOverlay');
    return !!(o && o.classList.contains('show') && o.classList.contains('incoming'));
  }

  /* Native ringtone + vibration replace the web beep while a call is ringing */
  var origVibrate = navigator.vibrate;
  if (origVibrate) {
    navigator.vibrate = function () {
      if (incoming()) return false;
      return origVibrate.apply(navigator, arguments);
    };
  }
  var AC = window.AudioContext || window.webkitAudioContext;
  if (AC && AC.prototype.createOscillator) {
    var origOsc = AC.prototype.createOscillator;
    AC.prototype.createOscillator = function () {
      var o = origOsc.apply(this, arguments);
      var s = o.start, p = o.stop, muted = false;
      o.start = function () { if (incoming()) { muted = true; return; } return s.apply(this, arguments); };
      o.stop = function () { if (muted) return; try { return p.apply(this, arguments); } catch (e) {} };
      return o;
    };
  }

  var ringing = false;
  var keepScreen = false;
  setInterval(function () {
    var on = incoming();
    if (on && !ringing) {
      ringing = true;
      var l = $('callIncomingLabel');
      safe(function () { N.ringStart(!!(l && /video/i.test(l.textContent || ''))); });
    } else if (!on && ringing) {
      ringing = false;
      safe(function () { N.ringStop(); });
    }
    var o = $('callOverlay');
    var inCall = !!(o && o.classList.contains('show'));
    if (inCall !== keepScreen) {
      keepScreen = inCall;
      safe(function () { N.setKeepScreen(inCall); });
    }
  }, 300);

  /* answered from the notification / lock-screen card */
  setInterval(function () {
    var want = safe(function () { return N.shouldAnswer(); });
    if (!want) return;
    var btn = $('callAccept');
    if (incoming() && btn && btn.style.display !== 'none') {
      safe(function () { N.answerDone(); });
      btn.click();
    }
  }, 400);

  /* ---------- auto login ---------- */
  function autoLogin() {
    if (!hasSession) return;
    var pin = $('entryPinNode'), join = $('joinBtn'), modal = $('setupModal');
    var tries = 0;
    var t = setInterval(function () {
      tries++;
      if (tries > 100 || !pin || !join) { clearInterval(t); revealLogin(); return; }
      if (typeof join.onclick !== 'function') return;
      clearInterval(t);
      var btn = document.querySelector('.role-btn[data-role="' + session.role + '"]');
      if (btn && typeof btn.onclick === 'function') btn.onclick();
      pin.value = session.pin;
      window.__hpPin = session.pin;
      join.onclick();
      var waited = 0;
      var w = setInterval(function () {
        waited += 250;
        var app = document.querySelector('.app');
        var err = $('pinErrorText');
        if (app && app.style.display === 'flex') { clearInterval(w); revealLogin(); return; }
        var msg = err ? (err.textContent || '') : '';
        var shown = err && err.style.opacity === '1';
        if (shown && /incorrect/i.test(msg)) {
          clearInterval(w);
          safe(function () { N.clearSession(); });
          hasSession = false;
          pin.value = '';
          revealLogin();
        } else if ((shown && msg.trim()) || waited > 12000) {
          clearInterval(w);
          revealLogin();
        }
      }, 250);
    }, 100);
  }
  if (document.readyState === 'complete') autoLogin();
  else window.addEventListener('load', autoLogin);

  /* ---------- remember PIN after a manual login / PIN change ---------- */
  function hookJoin() {
    var join = $('joinBtn'), pin = $('entryPinNode');
    if (join && pin) {
      join.addEventListener('click', function () { window.__hpPin = (pin.value || '').trim(); }, true);
    }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', hookJoin);
  else hookJoin();

  var lastSig = '';
  setInterval(function () {
    var app = document.querySelector('.app');
    if (!app || app.style.display !== 'flex') return;
    var role = safe(function () { return localStorage.getItem('hp_role'); }) || '';
    var allowed = safe(function () { return localStorage.getItem('hp_allowed_rooms'); }) || '2007';
    var pu = $('pinUpdateInput');
    var pin = window.__hpPin || '';
    if (pu && document.activeElement !== pu && pu.value && pu.value.trim()) pin = pu.value.trim();
    if (!pin || !role) return;
    var sig = role + '|' + pin + '|' + allowed;
    if (sig === lastSig) return;
    lastSig = sig;
    safe(function () { N.saveSession(role, pin, allowed); });
  }, 1500);

  /* ---------- settings row that opens the native settings ---------- */
  setInterval(function () {
    if ($('nativeNotifRow')) return;
    var t = $('soundToggle');
    var row = t && t.closest ? t.closest('.popup-row') : null;
    if (!row || !row.parentNode) return;
    var r = document.createElement('div');
    r.className = 'popup-row';
    r.id = 'nativeNotifRow';
    r.innerHTML = '<span class="popup-row-label">App</span>' +
      '<button class="wallpaper-choose-btn" id="nativeNotifBtn" style="margin-left:auto">Notifications &amp; calls</button>';
    row.parentNode.insertBefore(r, row.nextSibling);
    $('nativeNotifBtn').onclick = function () { safe(function () { N.openSettings(); }); };
  }, 800);

  /* ---------- Android back button ---------- */
  window.__hpBack = function () {
    var sm = $('settingsModal');
    if (sm && sm.style.display !== 'none' && getComputedStyle(sm).display !== 'none') {
      var done = $('closeSettingsBtn');
      if (done) { done.click(); return true; }
    }
    var layer = false;
    var cw = $('callWarn') || document.querySelector('.call-warn.show');
    if (document.querySelector('.call-warn.show') || document.querySelector('.call-confirm.show, #callConfirm.show') ||
        document.querySelector('.del-confirm.show')) layer = true;
    var viewer = $('viewer');
    if (viewer && viewer.style.display === 'flex') layer = true;
    var ctx = $('contextOverlay');
    if (ctx && ctx.style.display === 'block') layer = true;
    var am = $('attachMenu');
    if (am && am.classList.contains('show')) layer = true;
    var ib = $('inputActionBanner');
    if (ib && ib.style.display === 'flex') layer = true;
    var ep = $('emojiPanel');
    if (ep && ep.classList.contains('show')) { ep.classList.remove('show'); return true; }
    if (layer) {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      return true;
    }
    return false;
  };

  /* ---------- downloads of blob: files keep their name ---------- */
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (this.download) window.__hpDlName = this.download;
    return origClick.apply(this, arguments);
  };
})();
