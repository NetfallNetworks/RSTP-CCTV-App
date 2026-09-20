package com.zektopic.cctvapp

/**
 * The /clips review page: every event newest first, filterable by tag, clips playable
 * inline. Served by [WebServer] behind the same auth as the dashboard.
 *
 * Recording is biased toward false positives -- plain motion records -- so this page is
 * how the real ones get found: by tag, with "Animal" and "Person" one tap away.
 *
 * The script avoids JS template literals on purpose: `${'$'}{...}` inside a Kotlin raw
 * string is Kotlin interpolation.
 */
object ClipsPage {
    val HTML = ("""
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Clips</title>
<style>
  :root {
    --bg: #0f1117; --surface: #1a1d27; --surface-hover: #22263a;
    --border: rgba(255,255,255,0.06); --text: #e8eaed; --text-secondary: #9aa0b0;
    --accent: #00b894; --animal: #f39c12; --person: #4a9eff; --motion: #6b7280;
    --radius: 14px;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: -apple-system, BlinkMacSystemFont, 'Inter', sans-serif; background: var(--bg); color: var(--text); }
  .wrap { max-width: 880px; margin: 0 auto; padding: 16px; }
  header { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
  h1 { font-size: 22px; font-weight: 700; }
  header a { color: var(--accent); font-size: 14px; font-weight: 600; text-decoration: none; }
  .bar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 14px; }
  .chip { border: 1px solid var(--border); background: var(--surface); color: var(--text-secondary);
          border-radius: 999px; padding: 7px 14px; font-size: 14px; font-weight: 600; cursor: pointer; }
  .chip.on { background: var(--accent); color: #06231c; border-color: var(--accent); }
  .count { margin-left: auto; font-size: 13px; color: var(--text-secondary); }
  .day { font-size: 13px; font-weight: 700; color: var(--text-secondary); text-transform: uppercase;
         letter-spacing: .06em; margin: 18px 0 8px; }
  .card { display: flex; gap: 12px; background: var(--surface); border: 1px solid var(--border);
          border-radius: var(--radius); padding: 10px; margin-bottom: 8px; cursor: pointer; }
  .card:hover { background: var(--surface-hover); }
  .thumb { width: 120px; aspect-ratio: 4 / 3; flex: none; border-radius: 10px; background: #000; object-fit: cover; }
  .meta { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
  .time { font-size: 16px; font-weight: 600; }
  .sub { font-size: 13px; color: var(--text-secondary); }
  .tags { display: flex; flex-wrap: wrap; gap: 6px; }
  .tag { font-size: 12px; font-weight: 700; padding: 2px 9px; border-radius: 999px; background: var(--motion); color: #fff; }
  .tag.animal { background: var(--animal); color: #2a1a00; }
  .tag.person { background: var(--person); color: #04172e; }
  .player { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius);
            padding: 10px; margin: -2px 0 10px; }
  .player video { width: 100%; border-radius: 10px; background: #000; display: block; }
  .player a { display: inline-block; margin-top: 8px; font-size: 13px; color: var(--accent); }
  .empty { color: var(--text-secondary); padding: 40px 0; text-align: center; }
  @media (max-width: 480px) { .thumb { width: 96px; } .time { font-size: 15px; } }
</style>
</head>
<body>
<div class="wrap">
  <header><h1>Clips</h1><a href="/">&larr; Dashboard</a></header>
  <div class="bar" id="bar">
    <button class="chip on" data-filter="all">All</button>
    <button class="chip" data-filter="animal">Animal</button>
    <button class="chip" data-filter="person">Person</button>
    <button class="chip" data-filter="motion">Motion only</button>
    <span class="count" id="count"></span>
  </div>
  """ + RecordWidget.html("") + """
  <div id="list"><div class="empty">Loading…</div></div>
</div>
<script>
  var events = [];
  var filter = 'all';

  function tagsOf(e) { return (e.tags && e.tags.length) ? e.tags : [e.type]; }

  function matches(e) {
    var t = tagsOf(e);
    if (filter === 'all') return true;
    if (filter === 'motion') return t.indexOf('animal') < 0 && t.indexOf('person') < 0;
    return t.indexOf(filter) >= 0;
  }

  function pad(n) { return (n < 10 ? '0' : '') + n; }

  function duration(e) {
    var ms = e.clip_duration_ms;
    // Events recorded before clip lengths were stored: fall back to their time span.
    if (ms == null && e.end_time > e.start_time) ms = e.end_time - e.start_time;
    if (ms == null) return null;
    var s = Math.round(ms / 1000);
    return Math.floor(s / 60) + ':' + pad(s % 60);
  }

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  function render() {
    var list = document.getElementById('list');
    list.innerHTML = '';
    var shown = events.filter(matches);
    document.getElementById('count').textContent = shown.length + ' of ' + events.length;
    if (!shown.length) { list.appendChild(el('div', 'empty', 'Nothing here yet.')); return; }

    var lastDay = null;
    shown.forEach(function (e) {
      var d = new Date(e.start_time);
      var day = d.toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' });
      if (day !== lastDay) { list.appendChild(el('div', 'day', day)); lastDay = day; }

      var card = el('div', 'card');
      var img = el('img', 'thumb');
      img.loading = 'lazy';
      if (e.has_snapshot) img.src = '/events/' + e.id + '/snapshot.jpg';
      card.appendChild(img);

      var meta = el('div', 'meta');
      meta.appendChild(el('div', 'time', pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds())));
      var dur = duration(e);
      meta.appendChild(el('div', 'sub', e.has_clip ? ('Clip' + (dur ? ' · ' + dur : '')) : (e.end_time ? 'No clip' : 'Recording…')));
      var tags = el('div', 'tags');
      tagsOf(e).forEach(function (t) { tags.appendChild(el('span', 'tag ' + t, t)); });
      meta.appendChild(tags);
      if (e.caption) meta.appendChild(el('div', 'sub', e.caption));
      card.appendChild(meta);
      list.appendChild(card);

      card.addEventListener('click', function () {
        var open = card.nextSibling && card.nextSibling.className === 'player' ? card.nextSibling : null;
        if (open) { open.remove(); return; }
        if (!e.has_clip) return;
        var player = el('div', 'player');
        var video = el('video');
        video.controls = true;
        video.autoplay = true;
        video.playsInline = true;
        video.src = '/events/' + e.id + '/clip.mp4';
        player.appendChild(video);
        var link = el('a', null, 'Download');
        link.href = video.src;
        link.download = 'clip-' + d.toISOString().replace(/[:.]/g, '-') + '.mp4';
        player.appendChild(link);
        card.after(player);
      });
    });
  }

  document.getElementById('bar').addEventListener('click', function (ev) {
    var b = ev.target.closest('.chip');
    if (!b) return;
    filter = b.getAttribute('data-filter');
    document.querySelectorAll('.chip').forEach(function (c) { c.classList.toggle('on', c === b); });
    render();
  });

  function load() {
    fetch('/events?limit=500').then(function (r) { return r.json(); }).then(function (data) {
      events = data.events || [];
      // Keep an open player: re-rendering would stop the video mid-review.
      if (!document.querySelector('.player video')) render();
    }).catch(function () {
      document.getElementById('list').innerHTML = '<div class="empty">Could not load events.</div>';
    });
  }

  load();
  setInterval(load, 30000);
</script>
</body>
</html>
""").trimIndent()
}
