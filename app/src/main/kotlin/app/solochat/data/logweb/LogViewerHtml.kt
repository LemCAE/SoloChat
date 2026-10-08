package app.solochat.data.logweb

/**
 * 日志查看页面的 HTML。
 *
 * 刻意**内联成一个 Kotlin 常量**，而不是塞进 assets 或单独起一套 web 前端：
 * 这个页面的唯一职责是"把日志显示出来"，几十行原生 JS 就够，
 * 为了它引入一个前端构建流程完全不划算。
 *
 * 几个按要求做的功能：标题栏显示版本号（含已运行时长）、增量拉取（每秒，只取新条目）、
 * tag 过滤、级别过滤、关键词搜索、暂停、清屏、下载、复制单条。
 * 长内容（完整提示词、模型原始输出）默认折叠，点一下展开。
 *
 * 注意这里是 **ASCII + 中文混排的纯 HTML**，不依赖任何外部资源（离线也能用）。
 */
object LogViewerHtml {

    val PAGE: String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SoloChat 日志</title>
<style>
  :root {
    --bg: #0f1219; --panel: #161a24; --line: #252b38;
    --fg: #d8dce8; --dim: #7b8296; --accent: #8c97ff;
    --debug: #7b8296; --info: #8c97ff; --warn: #f5c26b; --error: #ff8a8a;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; background: var(--bg); color: var(--fg);
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, "Cascadia Mono", monospace;
    font-size: 12.5px; line-height: 1.55;
  }
  header {
    position: sticky; top: 0; z-index: 10; background: var(--panel);
    border-bottom: 1px solid var(--line); padding: 10px 14px;
  }
  .row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
  .title { font-size: 14px; font-weight: 600; }
  .ver { color: var(--dim); font-size: 12px; }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--dim); }
  .dot.on { background: #5fd18b; }
  .dot.off { background: #ff8a8a; }
  input, select, button {
    background: #1e2432; color: var(--fg); border: 1px solid var(--line);
    border-radius: 6px; padding: 4px 8px; font: inherit;
  }
  button { cursor: pointer; }
  button:hover { border-color: var(--accent); }
  button.active { background: var(--accent); color: #10131f; border-color: var(--accent); }
  #log { padding: 8px 0 40px; }
  .e { padding: 5px 14px; border-bottom: 1px solid rgba(37,43,56,.5); }
  .e:hover { background: rgba(140,151,255,.05); }
  .h { display: flex; gap: 10px; align-items: baseline; }
  .t { color: var(--dim); }
  .tag { font-weight: 600; }
  .lv-DEBUG .tag { color: var(--debug); }
  .lv-INFO  .tag { color: var(--info); }
  .lv-WARN  .tag { color: var(--warn); }
  .lv-ERROR .tag { color: var(--error); }
  .lv-ERROR { background: rgba(255,138,138,.07); }
  .lv-WARN  { background: rgba(245,194,107,.05); }
  .txt { white-space: pre-wrap; word-break: break-word; margin-top: 2px; }
  .more { color: var(--accent); cursor: pointer; user-select: none; }
  .cp { color: var(--dim); cursor: pointer; margin-left: auto; }
  .cp:hover { color: var(--accent); }
  .empty { color: var(--dim); padding: 20px 14px; }
  .hint { color: var(--dim); font-size: 11.5px; margin-top: 4px; }
</style>
</head>
<body>
<header>
  <div class="row">
    <span class="dot" id="dot"></span>
    <span class="title">SoloChat 日志</span>
    <span class="ver" id="ver">连接中…</span>
    <span style="flex:1"></span>
    <button id="pause">暂停</button>
    <button id="clear">清屏</button>
    <button id="copy">复制全部</button>
    <button id="download">下载</button>
  </div>
  <div class="row" style="margin-top:8px">
    <select id="tag"><option value="">全部 tag</option></select>
    <select id="level">
      <option value="">全部级别</option>
      <option value="INFO">INFO 及以上</option>
      <option value="WARN">WARN 及以上</option>
      <option value="ERROR">只看 ERROR</option>
    </select>
    <input id="q" placeholder="搜索…" style="min-width:180px">
    <label class="ver"><input type="checkbox" id="follow" checked> 自动滚到底</label>
    <span id="count" class="ver"></span>
  </div>
  <div class="hint" id="hint"></div>
</header>
<div id="log"><div class="empty">等待日志…</div></div>

<script>
(function () {
  var entries = [];
  var maxSeq = 0;
  var paused = false;
  var all = [];

  var $ = function (id) { return document.getElementById(id); };
  var logEl = $('log');

  var token = new URLSearchParams(location.search).get('t') || localStorage.getItem('solochat-log-token') || '';
  if (token) localStorage.setItem('solochat-log-token', token);

  function qs(extra) {
    var p = new URLSearchParams(extra || {});
    if (token) p.set('t', token);
    return p.toString();
  }

  function levelRank(l) { return l === 'ERROR' ? 3 : l === 'WARN' ? 2 : l === 'INFO' ? 1 : 0; }

  function passes(e) {
    var tag = $('tag').value;
    var lv = $('level').value;
    var q = $('q').value.trim().toLowerCase();
    if (tag && e.tag !== tag) return false;
    if (lv && levelRank(e.level) < levelRank(lv)) return false;
    if (q && (e.text.toLowerCase().indexOf(q) < 0 && e.tag.toLowerCase().indexOf(q) < 0)) return false;
    return true;
  }

  function render() {
    var shown = entries.filter(passes);
    if (!shown.length) {
      logEl.innerHTML = '<div class="empty">没有匹配的日志</div>';
    } else {
      var html = '';
      for (var i = 0; i < shown.length; i++) {
        var e = shown[i];
        var lines = e.text.split('\n');
        var long = lines.length > 4 || e.text.length > 260;
        var body = long ? lines.slice(0, 4).join('\n').slice(0, 260) + ' …' : e.text;
        html += '<div class="e lv-' + e.level + '">'
             + '<div class="h"><span class="t">' + esc(e.clock) + '</span>'
             + '<span class="tag">' + esc(e.tag) + '</span>'
             + '<span class="cp" data-copy="' + e.seq + '">复制</span></div>'
             + '<div class="txt" data-seq="' + e.seq + '" data-full="' + (long ? '1' : '0') + '">'
             + esc(body) + '</div>'
             + (long ? '<span class="more" data-more="' + e.seq + '">展开全部（'
                 + e.text.length + ' 字符）</span>' : '')
             + '</div>';
      }
      logEl.innerHTML = html;
    }
    $('count').textContent = '显示 ' + shown.length + ' / 收到 ' + entries.length + ' 条';
    if ($('follow').checked) window.scrollTo(0, document.body.scrollHeight);
  }

  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
                    .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  logEl.addEventListener('click', function (ev) {
    var more = ev.target.getAttribute && ev.target.getAttribute('data-more');
    if (more) {
      var box = logEl.querySelector('[data-seq="' + more + '"]');
      var e = entries.filter(function (x) { return String(x.seq) === more; })[0];
      if (box && e) {
        var expanded = box.getAttribute('data-expanded') === '1';
        box.textContent = expanded ? box.textContent : e.text;
        box.setAttribute('data-expanded', expanded ? '0' : '1');
        ev.target.textContent = expanded ? ('展开全部（' + e.text.length + ' 字符）') : '收起';
        return;
      }
    }
    var cp = ev.target.getAttribute && ev.target.getAttribute('data-copy');
    if (cp) {
      var ent = entries.filter(function (x) { return String(x.seq) === cp; })[0];
      if (ent) navigator.clipboard.writeText('[' + ent.clock + '] ' + ent.tag + ': ' + ent.text);
    }
  });

  function refreshTags() {
    var sel = $('tag');
    var seen = {};
    var tags = [];
    for (var i = 0; i < entries.length; i++) {
      if (!seen[entries[i].tag]) { seen[entries[i].tag] = 1; tags.push(entries[i].tag); }
    }
    tags.sort();
    var cur = sel.value;
    sel.innerHTML = '<option value="">全部 tag</option>'
      + tags.map(function (t) {
          var n = entries.filter(function (e) { return e.tag === t; }).length;
          return '<option value="' + esc(t) + '">' + esc(t) + ' (' + n + ')</option>';
        }).join('');
    sel.value = cur;
  }

  function poll() {
    if (paused) { setTimeout(poll, 1000); return; }
    fetch('/api/logs?since=' + maxSeq + '&' + qs())
      .then(function (r) {
        if (r.status === 401) {
          $('dot').className = 'dot off';
          $('ver').textContent = '需要访问密码：请用带 ?t=密码 的地址打开';
          throw new Error('unauthorized');
        }
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.json();
      })
      .then(function (data) {
        $('dot').className = 'dot on';
        $('ver').textContent = data.version + ' · ' + new Date().toLocaleTimeString();
        var changed = false;
        for (var i = 0; i < data.entries.length; i++) {
          entries.push(data.entries[i]);
          changed = true;
        }
        if (data.maxSeq > maxSeq) maxSeq = data.maxSeq;
        if (changed) { refreshTags(); render(); }
        setTimeout(poll, 1000);
      })
      .catch(function () {
        $('dot').className = 'dot off';
        setTimeout(poll, 2000);
      });
  }

  $('pause').onclick = function () {
    paused = !paused;
    this.textContent = paused ? '继续' : '暂停';
    this.className = paused ? 'active' : '';
  };
  $('clear').onclick = function () { entries = []; refreshTags(); render(); };
  $('copy').onclick = function () {
    var txt = entries.map(function (e) {
      return e.clock + ' [' + e.level + '] ' + e.tag + ': ' + e.text;
    }).join('\n');
    navigator.clipboard.writeText(txt);
  };
  $('download').onclick = function () {
    var head = 'SoloChat ' + $('ver').textContent + '\n共 ' + entries.length + ' 条\n'
             + '────────────────────────────────────\n';
    var txt = head + entries.map(function (e) {
      return e.clock + ' [' + e.level + '] ' + e.tag + ': ' + e.text;
    }).join('\n');
    var a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([txt], { type: 'text/plain' }));
    a.download = 'solochat-log.txt';
    a.click();
  };
  ['tag', 'level', 'q'].forEach(function (id) {
    $(id).addEventListener('input', render);
    $(id).addEventListener('change', render);
  });

  poll();
})();
</script>
</body>
</html>
""".trimIndent()
}
