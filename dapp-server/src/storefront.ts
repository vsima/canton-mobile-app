// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// The storefront page — the dApp's frontend, served by its own backend. Browse
// the catalog, buy, and the page shows what to pay and polls the order until
// the ledger watcher settles it. Self-contained (no external assets), so it
// works offline next to LocalNet.

export interface StorefrontOptions {
  merchantParty?: string;
  networkId: string;
}

export function storefrontHtml(options: StorefrontOptions): string {
  const config = JSON.stringify({ merchantParty: options.merchantParty ?? null, networkId: options.networkId });
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
  .row { display:flex; align-items:center; gap:8px; margin:10px 0; }
  .row .k { color:var(--muted); width:76px; flex:none; font-size:.85rem; }
  .row .v { font-family:ui-monospace,SFMono-Regular,Menlo,monospace; font-size:.82rem; word-break:break-all; }
  .status { margin-top:16px; padding:12px; border-radius:10px; background:rgba(59,91,219,.08); text-align:center; }
  .status.paid { background:rgba(46,125,50,.12); color:var(--ok); font-weight:600; }
  .note { background:var(--card); border:1px dashed var(--line); border-radius:12px; padding:16px; color:var(--muted); font-size:.9rem; }
  a { color:var(--accent); }
</style>
</head>
<body>
<header>
  <h1>🛍️ Canton Corner</h1>
  <p>A reference dApp shop — pay from your Canton wallet, settled on-ledger.</p>
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
      renderPay(data.product, data.order, data.payment);
      poll(data.order.id);
    });
}

function renderPay(product, order, payment) {
  var app = document.getElementById('app');
  app.innerHTML = '';
  var back = h('button', { 'class': 'secondary', text: '← back to shop' });
  back.onclick = load;
  var status = h('div', { 'class': 'status', id: 'status', text: 'Waiting for payment…' });
  app.appendChild(h('div', { 'class': 'pay' }, [
    h('h2', { text: product.emoji + '  ' + product.name }),
    h('p', { text: 'Send this payment from your Canton wallet. The shop settles it the moment it lands on the ledger.' }),
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
        var s = document.getElementById('status');
        if (s) { s.className = 'status paid'; s.textContent = '✓ Paid — thank you!'; }
      }
    }).catch(function () {});
  }, 2500);
}

function load() {
  fetch('/shop').then(function (r) { return r.json(); }).then(function (data) { renderCatalog(data.products || []); });
}
load();
</script>
</body>
</html>
`;
}
