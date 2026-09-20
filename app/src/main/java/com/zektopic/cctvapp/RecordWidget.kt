package com.zektopic.cctvapp

/**
 * The record control: Record now with a length, live state, Hold / +5 min / Stop /
 * Discard (each also "& pause auto 10 min"), Resume when paused. Polls <apiBase>/record
 * every 2 s. The marvin review page embeds the same markup against its proxy.
 */
object RecordWidget {
    fun html(apiBase: String): String = """
<div id="rec" style="background:var(--surface,#1a1d27);border:1px solid rgba(255,255,255,0.06);border-radius:14px;padding:12px;margin-bottom:14px;font-family:inherit">
  <div id="rec-status" style="font-weight:700;margin-bottom:8px">…</div>
  <div id="rec-actions" style="display:flex;flex-wrap:wrap;gap:6px"></div>
</div>
<script>
(function () {
  var base = '""" + apiBase + """';
  var len = 15;
  function mmss(ms) { var s = Math.max(0, Math.round(ms / 1000)); return Math.floor(s / 60) + ':' + (s % 60 < 10 ? '0' : '') + (s % 60); }
  function clock(ms) { var d = new Date(ms); return d.getHours() + ':' + (d.getMinutes() < 10 ? '0' : '') + d.getMinutes(); }
  function btn(label, path, primary) {
    var b = document.createElement('button');
    b.textContent = label;
    b.style.cssText = 'border:1px solid rgba(255,255,255,0.1);border-radius:999px;padding:6px 12px;font-weight:600;cursor:pointer;' +
      (primary ? 'background:#e74c3c;color:#fff' : 'background:transparent;color:inherit');
    b.onclick = function () { fetch(base + path, { method: 'POST' }).then(function (r) { return r.json(); }).then(render); };
    return b;
  }
  function render(s) {
    var st = document.getElementById('rec-status'), a = document.getElementById('rec-actions');
    a.innerHTML = '';
    if (s.state === 'recording') {
      var tags = (s.tags || []).join('+');
      var tail = s.hold_remaining_ms ? 'held until ' + clock(Date.now() + s.hold_remaining_ms) : 'ends in ~' + mmss(s.ends_in_ms);
      st.textContent = '● REC · ' + tags + ' · ' + mmss(s.elapsed_ms) + ' · ' + tail;
      st.style.color = '#e74c3c';
      a.appendChild(btn(s.hold_remaining_ms ? '+5 min' : 'Hold 5 min', '/record/hold?minutes=5'));
      a.appendChild(btn('Stop', '/record/stop?pauseAuto=0'));
      a.appendChild(btn('Stop & pause auto 10 min', '/record/stop?pauseAuto=10'));
      a.appendChild(btn('Discard', '/record/discard?pauseAuto=0'));
      a.appendChild(btn('Discard & pause auto 10 min', '/record/discard?pauseAuto=10'));
      return;
    }
    st.style.color = '';
    st.textContent = s.auto_paused_until_ms ? '○ Auto paused · resumes ' + clock(s.auto_paused_until_ms) : 'Idle';
    if (s.auto_paused_until_ms) a.appendChild(btn('Resume', '/record/resume'));
    [1, 5, 15, 30].forEach(function (m) {
      var c = document.createElement('button');
      c.textContent = m + ' min';
      c.style.cssText = 'border:1px solid rgba(255,255,255,0.1);border-radius:999px;padding:6px 10px;cursor:pointer;' +
        (m === len ? 'background:#00b894;color:#06231c' : 'background:transparent;color:inherit');
      c.onclick = function () { len = m; render(s); };
      a.appendChild(c);
    });
    a.appendChild(btn('● Record now', '/record/start?minutes=' + len, true));
  }
  function poll() { fetch(base + '/record').then(function (r) { return r.json(); }).then(render).catch(function () {}); }
  poll();
  setInterval(poll, 2000);
})();
</script>
"""
}
