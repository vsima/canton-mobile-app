// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// The storefront page — the dApp's frontend, served by its backend. Browse the
// catalog, buy, and the page shows a checkout QR: scan it with a Canton wallet
// to fetch and review the order, then pay. The page polls the order until the
// ledger watcher settles it. Self-contained (no external assets).

export interface StorefrontOptions {
  shop: string;
  merchantParty?: string;
  networkId: string;
}

export function storefrontHtml(options: StorefrontOptions): string {
  const config = JSON.stringify({
    shop: options.shop,
    merchantParty: options.merchantParty ?? null,
    networkId: options.networkId,
  });
  // The client script below uses string concatenation, not template literals,
  // so it carries no `${...}` that this outer template would try to interpolate.
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Canton Corner — reference dApp shop</title>
<style>
  :root { color-scheme: light dark; --bg:#f6f7f9; --card:#fff; --ink:#1a1c1e; --muted:#6b7280; --line:#e5e7eb; --accent:#3b5bdb; --ok:#2e7d32; }
  @media (prefers-color-scheme: dark) { :root { --bg:#16181c; --card:#1f2227; --ink:#e8eaed; --muted:#9aa0a6; --line:#2c2f36; --accent:#7891ff; --ok:#5bd977; } }
  * { box-sizing: border-box; }
  body { margin:0; font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif; background:var(--bg); color:var(--ink); }
  header { padding:28px 20px 8px; text-align:center; }
  header h1 { margin:0; font-size:1.6rem; }
  header p { margin:4px 0 0; color:var(--muted); font-size:.9rem; }
  main { max-width:760px; margin:0 auto; padding:20px; }
  .grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(160px,1fr)); gap:14px; }
  .card { background:var(--card); border:1px solid var(--line); border-radius:14px; padding:18px; text-align:center; }
  .card .emoji { font-size:2.4rem; }
  .card h3 { margin:8px 0 2px; font-size:1.05rem; }
  .card .price { color:var(--muted); font-size:.9rem; margin-bottom:12px; }
  button { font:inherit; border:0; border-radius:10px; padding:10px 16px; background:var(--accent); color:#fff; cursor:pointer; }
  button.secondary { background:transparent; color:var(--accent); border:1px solid var(--line); padding:6px 10px; font-size:.8rem; }
  button:disabled { opacity:.5; cursor:default; }
  .pay { background:var(--card); border:1px solid var(--line); border-radius:14px; padding:22px; }
  .pay h2 { margin:0 0 4px; }
  .qr { text-align:center; margin:14px 0; }
  .qr svg { width:220px; height:220px; background:#fff; border-radius:12px; padding:10px; }
  .muted { color:var(--muted); font-size:.8rem; margin-top:10px; }
  .row { display:flex; align-items:center; gap:8px; margin:8px 0; }
  .row .k { color:var(--muted); width:70px; flex:none; font-size:.85rem; }
  .row .v { font-family:ui-monospace,SFMono-Regular,Menlo,monospace; font-size:.8rem; word-break:break-all; }
  .status { margin-top:16px; padding:12px; border-radius:10px; background:rgba(59,91,219,.08); text-align:center; }
  .status.paid { background:rgba(46,125,50,.12); color:var(--ok); font-weight:600; }
  .success { text-align:center; padding:32px 22px; }
  .checkmark { width:128px; height:128px; }
  .ck-circle { fill:none; stroke:var(--ok); stroke-width:3; stroke-dasharray:151; stroke-dashoffset:151; animation:ckdraw .5s cubic-bezier(.65,0,.45,1) forwards; }
  .ck-check { fill:none; stroke:var(--ok); stroke-width:4; stroke-linecap:round; stroke-linejoin:round; stroke-dasharray:40; stroke-dashoffset:40; animation:ckdraw .35s .5s cubic-bezier(.65,0,.45,1) forwards; }
  @keyframes ckdraw { to { stroke-dashoffset:0; } }
  .paid-title { font-size:2.4rem; margin:14px 0 4px; color:var(--ok); animation:pop .45s .35s both; }
  .paid-sub { color:var(--muted); margin:0 0 20px; }
  @keyframes pop { 0%{transform:scale(.5);opacity:0} 70%{transform:scale(1.08)} 100%{transform:scale(1);opacity:1} }
  .confetti { position:fixed; top:-14px; width:9px; height:15px; border-radius:2px; opacity:.9; pointer-events:none; z-index:50; animation-name:confall; animation-timing-function:linear; animation-fill-mode:forwards; }
  @keyframes confall { to { transform:translateY(110vh) rotate(720deg); } }
  .note { background:var(--card); border:1px dashed var(--line); border-radius:12px; padding:16px; color:var(--muted); font-size:.9rem; }
  code { font-family:ui-monospace,SFMono-Regular,Menlo,monospace; }
</style>
</head>
<body>
<header>
  <h1 id="shopname">🛍️ Canton Corner</h1>
  <p>A reference dApp shop — scan to pay from your Canton wallet, settled on-ledger.</p>
</header>
<main id="app"><p>Loading…</p></main>
<script>
var CONFIG = ${config};

function h(tag, attrs, children) {
  var el = document.createElement(tag);
  if (attrs) for (var k in attrs) { if (k === 'text') el.textContent = attrs[k]; else el.setAttribute(k, attrs[k]); }
  (children || []).forEach(function (c) { el.appendChild(c); });
  return el;
}
function copyBtn(value) {
  var b = h('button', { 'class': 'secondary', text: 'Copy' });
  b.onclick = function () { navigator.clipboard.writeText(value); b.textContent = 'Copied'; setTimeout(function () { b.textContent = 'Copy'; }, 1200); };
  return b;
}
function row(k, v, withCopy) {
  var val = h('span', { 'class': 'v', text: v });
  var kids = [h('span', { 'class': 'k', text: k }), val];
  if (withCopy) kids.push(copyBtn(v));
  return h('div', { 'class': 'row' }, kids);
}

function renderCatalog(products) {
  var app = document.getElementById('app');
  app.innerHTML = '';
  if (!CONFIG.merchantParty) {
    var note = h('div', { 'class': 'note' });
    note.innerHTML = 'No merchant party configured. Start the server with <code>MERCHANT_PARTY=&lt;party&gt;</code> so orders are payable and settle automatically.';
    app.appendChild(note);
  }
  var grid = h('div', { 'class': 'grid' });
  products.forEach(function (p) {
    var buy = h('button', { text: 'Buy' });
    buy.disabled = !CONFIG.merchantParty;
    buy.onclick = function () { checkout(p.id); };
    grid.appendChild(h('div', { 'class': 'card' }, [
      h('div', { 'class': 'emoji', text: p.emoji }),
      h('h3', { text: p.name }),
      h('div', { 'class': 'price', text: p.priceCc + ' CC' }),
      buy,
    ]));
  });
  app.appendChild(grid);
}

function checkout(productId) {
  fetch('/shop/checkout', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ productId: productId }) })
    .then(function (r) { return r.json(); })
    .then(function (data) {
      if (data.error) { alert(data.error); return; }
      renderPay(data.product, data.order, data.payment, data.checkout);
      poll(data.order.id);
    });
}

function renderPay(product, order, payment, checkout) {
  var app = document.getElementById('app');
  app.innerHTML = '';
  var back = h('button', { 'class': 'secondary', text: '← back to shop' });
  back.onclick = load;
  var status = h('div', { 'class': 'status', id: 'status', text: 'Waiting for payment…' });
  var qr = h('div', { 'class': 'qr' });
  qr.innerHTML = checkout.qrSvg; // our own SVG for our own URL — safe to inline
  app.appendChild(h('div', { 'class': 'pay' }, [
    h('h2', { text: product.emoji + '  ' + product.name }),
    h('p', { text: 'Scan with your Canton wallet to review and pay. The shop settles the moment the payment lands on the ledger.' }),
    qr,
    h('div', { 'class': 'muted', text: 'or pay manually:' }),
    row('Amount', payment.amount + ' CC', true),
    row('To', payment.payTo, true),
    row('Memo', payment.memo, true),
    status,
    h('div', { 'class': 'row' }, [back]),
  ]));
}

function poll(orderId) {
  var timer = setInterval(function () {
    fetch('/orders/' + orderId).then(function (r) { return r.json(); }).then(function (data) {
      if (data.order && data.order.status === 'settled') {
        clearInterval(timer);
        renderPaid(data.order);
      }
    }).catch(function () {});
  }, 2500);
}

function renderPaid(order) {
  var app = document.getElementById('app');
  app.innerHTML = '';
  var check = h('div', {});
  check.innerHTML = '<svg viewBox="0 0 52 52" class="checkmark"><circle class="ck-circle" cx="26" cy="26" r="24"/><path class="ck-check" d="M14 27l7 7 16-16"/></svg>';
  var more = h('button', { text: 'Back to shop' });
  more.onclick = load;
  app.appendChild(h('div', { 'class': 'success' }, [
    check,
    h('h2', { 'class': 'paid-title', text: 'Paid!' }),
    h('p', { 'class': 'paid-sub', text: (order.description ? order.description + ' — ' : '') + order.amount + ' CC settled on-ledger.' }),
    more,
  ]));
  confetti();
}

function confetti() {
  var colors = ['#3b5bdb', '#2e7d32', '#f59f00', '#e64980', '#7891ff', '#5bd977'];
  for (var i = 0; i < 30; i++) {
    var c = document.createElement('div');
    c.className = 'confetti';
    c.style.left = (Math.random() * 100) + 'vw';
    c.style.background = colors[i % colors.length];
    c.style.animationDuration = (1.6 + Math.random() * 1.4) + 's';
    c.style.animationDelay = (Math.random() * 0.3) + 's';
    document.body.appendChild(c);
    (function (el) { setTimeout(function () { el.remove(); }, 3400); })(c);
  }
}

function load() {
  fetch('/shop').then(function (r) { return r.json(); }).then(function (data) { renderCatalog(data.products || []); });
}

document.title = CONFIG.shop;
var sn = document.getElementById('shopname');
if (sn) sn.textContent = '🛍️ ' + CONFIG.shop;
load();
</script>
</body>
</html>
`;
}
