// Copyright (c) 2026 Victor Sima
// SPDX-License-Identifier: Apache-2.0

// The storefront page — the dApp's frontend, served by its backend. Browse the
// catalog, build a cart, and check out: the checkout screen shows a QR to scan
// with a Canton wallet beside a friendly order summary, and polls the order
// until the ledger watcher settles it and celebrates. Self-contained.

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
  header { padding:26px 20px 6px; text-align:center; }
  header h1 { margin:0; font-size:1.6rem; }
  header p { margin:4px 0 0; color:var(--muted); font-size:.9rem; }
  main { max-width:820px; margin:0 auto; padding:20px 20px 120px; }
  h2 { margin:0 0 12px; }
  .grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(160px,1fr)); gap:14px; }
  .card { background:var(--card); border:1px solid var(--line); border-radius:14px; padding:18px; text-align:center; display:flex; flex-direction:column; align-items:center; gap:2px; }
  .card .emoji { font-size:2.4rem; }
  .card h3 { margin:8px 0 2px; font-size:1.05rem; }
  .card .price { color:var(--muted); font-size:.9rem; margin-bottom:12px; }
  .card .control { margin-top:auto; }
  button { font:inherit; border:0; border-radius:10px; padding:10px 16px; background:var(--accent); color:#fff; cursor:pointer; }
  button.secondary { background:transparent; color:var(--accent); border:1px solid var(--line); }
  button.ghost { background:transparent; color:var(--muted); border:0; padding:8px 10px; font-size:.85rem; }
  button:disabled { opacity:.5; cursor:default; }
  button.full { width:100%; }
  .stepper { display:inline-flex; align-items:center; gap:2px; border:1px solid var(--line); border-radius:10px; padding:2px; }
  .stepper .step { background:transparent; color:var(--ink); padding:4px 12px; font-size:1.1rem; border-radius:8px; }
  .stepper .qty { min-width:26px; text-align:center; font-variant-numeric:tabular-nums; }
  .cartbar { position:fixed; left:0; right:0; bottom:0; padding:14px 20px; background:var(--card); border-top:1px solid var(--line); display:flex; justify-content:center; }
  .cartbar button { max-width:520px; width:100%; display:flex; justify-content:space-between; align-items:center; gap:12px; }
  .panel { background:var(--card); border:1px solid var(--line); border-radius:14px; padding:20px; }
  .row-item { display:flex; align-items:center; gap:12px; padding:12px 0; border-bottom:1px solid var(--line); }
  .row-item:last-of-type { border-bottom:0; }
  .row-item .name { flex:1; }
  .row-item .name small { color:var(--muted); }
  .row-item .sub { min-width:70px; text-align:right; font-variant-numeric:tabular-nums; }
  .row-item .rm { background:transparent; color:var(--muted); border:0; padding:6px 8px; font-size:1.1rem; }
  .totline { display:flex; justify-content:space-between; align-items:baseline; margin-top:14px; padding-top:14px; border-top:2px solid var(--line); font-size:1.2rem; font-weight:700; }
  .totline .big { font-size:1.35rem; }
  .checkout-cols { display:flex; flex-wrap:wrap; gap:18px; align-items:flex-start; }
  .checkout-cols > * { flex:1 1 260px; }
  .qrbox { text-align:center; }
  .qrbox svg { width:230px; height:230px; background:#fff; border-radius:12px; padding:10px; }
  .qrbox .cap { color:var(--muted); font-size:.85rem; margin-top:8px; }
  .wcuri { display:flex; align-items:center; gap:8px; margin:12px auto 0; max-width:300px; }
  .wcuri-input { flex:1; min-width:0; font-family:ui-monospace,SFMono-Regular,Menlo,monospace; font-size:.72rem; color:var(--ink); background:var(--bg); border:1px solid var(--line); border-radius:8px; padding:7px 9px; }
  .pushbox { text-align:center; padding:18px 8px; }
  .pushbox .push-emoji { font-size:3.4rem; line-height:1; display:inline-block; animation:pushwiggle 1.6s ease-in-out infinite; }
  .pushbox .push-title { font-size:1.15rem; font-weight:700; margin-top:12px; }
  .pushbox .push-sub { color:var(--muted); font-size:.9rem; margin-top:6px; }
  @keyframes pushwiggle { 0%,100%{transform:rotate(-7deg)} 50%{transform:rotate(7deg)} }
  .manual .qrbox { margin:14px 0 6px; }
  .manual .qrbox svg { width:180px; height:180px; }
  .li { display:flex; justify-content:space-between; gap:10px; padding:6px 0; }
  .li .q { color:var(--muted); }
  .manual { margin-top:18px; }
  .manual summary { cursor:pointer; color:var(--muted); font-size:.9rem; }
  .manual .kv { display:flex; align-items:center; gap:8px; margin:8px 0; }
  .manual .kv .k { color:var(--muted); width:64px; flex:none; font-size:.85rem; }
  .manual .kv .v { font-family:ui-monospace,SFMono-Regular,Menlo,monospace; font-size:.8rem; word-break:break-all; flex:1; }
  .statusbar { display:flex; align-items:center; justify-content:center; gap:10px; padding:14px; border-radius:12px; background:rgba(59,91,219,.10); font-weight:600; margin-bottom:18px; }
  .spinner { width:16px; height:16px; border:2px solid var(--accent); border-top-color:transparent; border-radius:50%; animation:spin .8s linear infinite; }
  @keyframes spin { to { transform:rotate(360deg); } }
  .note { background:var(--card); border:1px dashed var(--line); border-radius:12px; padding:16px; color:var(--muted); font-size:.9rem; }
  .success { text-align:center; padding:28px 22px; }
  .checkmark { width:128px; height:128px; }
  .ck-circle { fill:none; stroke:var(--ok); stroke-width:3; stroke-dasharray:151; stroke-dashoffset:151; animation:ckdraw .5s cubic-bezier(.65,0,.45,1) forwards; }
  .ck-check { fill:none; stroke:var(--ok); stroke-width:4; stroke-linecap:round; stroke-linejoin:round; stroke-dasharray:40; stroke-dashoffset:40; animation:ckdraw .35s .5s cubic-bezier(.65,0,.45,1) forwards; }
  @keyframes ckdraw { to { stroke-dashoffset:0; } }
  .paid-title { font-size:2.4rem; margin:14px 0 4px; color:var(--ok); animation:pop .45s .35s both; }
  .paid-sub { color:var(--muted); margin:0 auto 18px; max-width:380px; }
  @keyframes pop { 0%{transform:scale(.5);opacity:0} 70%{transform:scale(1.08)} 100%{transform:scale(1);opacity:1} }
  .confetti { position:fixed; top:-14px; width:9px; height:15px; border-radius:2px; opacity:.9; pointer-events:none; z-index:50; animation-name:confall; animation-timing-function:linear; animation-fill-mode:forwards; }
  @keyframes confall { to { transform:translateY(110vh) rotate(720deg); } }
  code { font-family:ui-monospace,SFMono-Regular,Menlo,monospace; }
  dialog.modal { border:0; border-radius:16px; padding:0; max-width:420px; width:calc(100% - 40px); background:var(--card); color:var(--ink); box-shadow:0 24px 64px rgba(0,0,0,.35); }
  dialog.modal::backdrop { background:rgba(0,0,0,.5); }
  .modal-body { padding:24px; }
  .modal-body h2 { text-align:center; }
</style>
</head>
<body>
<header>
  <h1 id="shopname">🛍️ Canton Corner</h1>
  <p>A reference dApp shop — scan to pay from your Canton wallet, settled on-ledger.</p>
  <button id="signin" class="secondary" style="margin-top:10px">🔐 Sign in with your wallet</button>
</header>
<main id="app"><p>Loading…</p></main>
<dialog id="signin-dialog" class="modal"></dialog>
<script>
var CONFIG = ${config};
var PRODUCTS = [];
var cart = {};    // productId -> quantity
var view = 'catalog';
var signinTimer = null;   // poll handle for the open sign-in dialog

function h(tag, attrs, children) {
  var el = document.createElement(tag);
  if (attrs) for (var k in attrs) { if (k === 'text') el.textContent = attrs[k]; else el.setAttribute(k, attrs[k]); }
  (children || []).forEach(function (c) { if (c) el.appendChild(c); });
  return el;
}
function copyBtn(value) {
  var b = h('button', { 'class': 'secondary', text: 'Copy' });
  b.style.padding = '6px 10px'; b.style.fontSize = '.8rem';
  b.onclick = function () { navigator.clipboard.writeText(value); b.textContent = 'Copied'; setTimeout(function () { b.textContent = 'Copy'; }, 1200); };
  return b;
}
function fmtCc(n) { return (Math.round(n * 1e10) / 1e10).toString(); }
function productById(id) { return PRODUCTS.filter(function (p) { return p.id === id; })[0]; }
function cartCount() { var n = 0; for (var k in cart) n += cart[k]; return n; }
function cartTotal() { var t = 0; for (var k in cart) { var p = productById(k); if (p) t += Number(p.priceCc) * cart[k]; } return t; }
function cartIds() { return Object.keys(cart); }

function setQty(id, qty) {
  if (qty <= 0) delete cart[id]; else cart[id] = qty;
  render();
}
function render() { view === 'cart' ? showCart() : showCatalog(); }

// --- catalog ---------------------------------------------------------------

function stepper(id) {
  var qty = cart[id] || 0;
  var minus = h('button', { 'class': 'step', text: '−' }); minus.onclick = function () { setQty(id, qty - 1); };
  var plus = h('button', { 'class': 'step', text: '+' }); plus.onclick = function () { setQty(id, qty + 1); };
  return h('div', { 'class': 'stepper' }, [minus, h('span', { 'class': 'qty', text: String(qty) }), plus]);
}

function showCatalog() {
  view = 'catalog';
  var app = document.getElementById('app');
  app.innerHTML = '';
  if (!CONFIG.merchantParty) {
    var note = h('div', { 'class': 'note' });
    note.innerHTML = 'No merchant party configured. Start the server with <code>MERCHANT_PARTY=&lt;party&gt;</code> so orders are payable and settle automatically.';
    app.appendChild(note);
  }
  var grid = h('div', { 'class': 'grid' });
  PRODUCTS.forEach(function (p) {
    var control;
    if ((cart[p.id] || 0) > 0) {
      control = stepper(p.id);
    } else {
      control = h('button', { text: 'Add to cart' });
      control.disabled = !CONFIG.merchantParty;
      control.onclick = function () { setQty(p.id, 1); };
    }
    grid.appendChild(h('div', { 'class': 'card' }, [
      h('div', { 'class': 'emoji', text: p.emoji }),
      h('h3', { text: p.name }),
      h('div', { 'class': 'price', text: p.priceCc + ' CC' }),
      h('div', { 'class': 'control' }, [control]),
    ]));
  });
  app.appendChild(grid);
  renderCartBar();
}

function renderCartBar() {
  var existing = document.getElementById('cartbar');
  if (existing) existing.remove();
  if (cartCount() === 0) return;
  var btn = h('button', {}, [
    h('span', { text: '🛒 View cart · ' + cartCount() + (cartCount() === 1 ? ' item' : ' items') }),
    h('span', { text: fmtCc(cartTotal()) + ' CC' }),
  ]);
  btn.onclick = showCart;
  var bar = h('div', { 'class': 'cartbar', id: 'cartbar' }, [btn]);
  document.body.appendChild(bar);
}

// --- cart ------------------------------------------------------------------

function showCart() {
  view = 'cart';
  var cb = document.getElementById('cartbar'); if (cb) cb.remove();
  var app = document.getElementById('app');
  app.innerHTML = '';
  if (cartCount() === 0) {
    var back0 = h('button', { 'class': 'secondary', text: '← Continue shopping' }); back0.onclick = showCatalog;
    app.appendChild(h('div', { 'class': 'panel' }, [h('h2', { text: 'Your cart' }), h('p', { 'class': 'paid-sub', text: 'Your cart is empty.' }), back0]));
    return;
  }
  var rows = cartIds().map(function (id) {
    var p = productById(id); var qty = cart[id];
    var rm = h('button', { 'class': 'rm', text: '✕' }); rm.onclick = function () { setQty(id, 0); };
    return h('div', { 'class': 'row-item' }, [
      h('div', { text: p.emoji }),
      h('div', { 'class': 'name' }, [h('div', { text: p.name }), h('small', { text: p.priceCc + ' CC each' })]),
      stepper(id),
      h('div', { 'class': 'sub', text: fmtCc(Number(p.priceCc) * qty) + ' CC' }),
      rm,
    ]);
  });
  var total = h('div', { 'class': 'totline' }, [h('span', { text: 'Total' }), h('span', { 'class': 'big', text: fmtCc(cartTotal()) + ' CC' })]);
  var pay = h('button', { 'class': 'full', text: 'Checkout · ' + fmtCc(cartTotal()) + ' CC' }); pay.onclick = doCheckout;
  var back = h('button', { 'class': 'ghost full', text: '← Continue shopping' }); back.onclick = showCatalog;
  app.appendChild(h('div', { 'class': 'panel' }, [h('h2', { text: 'Your cart' })].concat(rows).concat([total, h('div', { style: 'margin-top:16px' }, [pay, back])])));
}

function doCheckout() {
  var items = cartIds().map(function (id) { return { productId: id, quantity: cart[id] }; });
  if (items.length === 0) return;
  // Include this browser's own sign-in session id so a connected wallet gets the
  // payment pushed to it (one-tap pay); otherwise the server just returns the
  // scan-to-pay QR. The id — not the party — is what proves we own the session.
  var payload = { items: items };
  if (signedInSessionId) payload.sessionId = signedInSessionId;
  fetch('/shop/checkout', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(payload) })
    .then(function (r) { return r.json(); })
    .then(function (data) {
      if (data.error) { alert(data.error); return; }
      showCheckout(data);
      poll(data.order.id);
    });
}

// --- checkout --------------------------------------------------------------

function summaryPanel(lineItems, total) {
  var lines = (lineItems || []).map(function (li) {
    return h('div', { 'class': 'li' }, [
      h('span', {}, [h('span', { 'class': 'q', text: li.quantity + '× ' }), h('span', { text: li.emoji + ' ' + li.name })]),
      h('span', { text: li.subtotal + ' CC' }),
    ]);
  });
  return h('div', { 'class': 'panel' }, [h('h2', { text: 'Order summary' })].concat(lines).concat([
    h('div', { 'class': 'totline' }, [h('span', { text: 'Total' }), h('span', { 'class': 'big', text: total + ' CC' })]),
  ]));
}

// The manual-payment fallback: always the raw payment details (amount / to /
// memo). When the payment was pushed to a wallet (one-tap), the scan-to-pay QR
// is demoted into here too, since it is no longer the primary path.
function manualPanel(payment, checkout) {
  function kv(k, v) { return h('div', { 'class': 'kv' }, [h('span', { 'class': 'k', text: k }), h('span', { 'class': 'v', text: v }), copyBtn(v)]); }
  var d = document.createElement('details');
  d.className = 'manual panel';
  var s = document.createElement('summary'); s.textContent = 'Prefer to pay manually? Show payment details';
  d.appendChild(s);
  if (checkout && checkout.qrSvg) {
    var qr = document.createElement('div'); qr.className = 'qrbox';
    qr.innerHTML = checkout.qrSvg + '<div class="cap">…or scan with your Canton wallet to review &amp; pay</div>';
    d.appendChild(qr);
  }
  d.appendChild(kv('Amount', payment.amount + ' CC'));
  d.appendChild(kv('To', payment.payTo));
  d.appendChild(kv('Memo', payment.memo));
  return d;
}

function showCheckout(data) {
  var app = document.getElementById('app');
  app.innerHTML = '';
  var back = h('button', { 'class': 'ghost', text: '← back to cart' }); back.onclick = showCart;

  if (data.pushed) {
    // One-tap pay: the payment was pushed to the signed-in wallet. Primary state
    // is "check your phone"; the QR moves into the manual fallback panel.
    var status = h('div', { 'class': 'statusbar', id: 'status' }, [h('div', { 'class': 'spinner' }), h('span', { text: 'Waiting for approval…' })]);
    // shortParty carries the wallet-supplied party hint, which is attacker-
    // controllable — build these nodes with textContent (h's text attr), never
    // innerHTML, so a crafted hint can't inject markup.
    var who = signedInParty ? ' (' + shortParty(signedInParty) + ')' : '';
    var push = h('div', { 'class': 'pushbox' }, [
      h('div', { 'class': 'push-emoji', text: '📲' }),
      h('div', { 'class': 'push-title', text: 'Check your phone' }),
      h('div', { 'class': 'push-sub', text: 'Approve the payment in your wallet' + who + '.' }),
    ]);
    var cols = h('div', { 'class': 'checkout-cols' }, [push, summaryPanel(data.lineItems, data.total)]);
    app.appendChild(h('div', {}, [status, cols, manualPanel(data.payment, data.checkout), h('div', { style: 'margin-top:14px' }, [back])]));
    return;
  }

  // No live wallet session — scan the QR to pay (the payment details stay in the
  // manual panel, without a duplicate QR).
  var status2 = h('div', { 'class': 'statusbar', id: 'status' }, [h('div', { 'class': 'spinner' }), h('span', { text: 'Waiting for payment…' })]);
  var qrbox = h('div', { 'class': 'qrbox' });
  qrbox.innerHTML = data.checkout.qrSvg + '<div class="cap">Scan with your Canton wallet to review &amp; pay</div>';
  var cols2 = h('div', { 'class': 'checkout-cols' }, [qrbox, summaryPanel(data.lineItems, data.total)]);
  app.appendChild(h('div', {}, [status2, cols2, manualPanel(data.payment, null), h('div', { style: 'margin-top:14px' }, [back])]));
}

function poll(orderId) {
  var timer = setInterval(function () {
    fetch('/orders/' + orderId).then(function (r) { return r.json(); }).then(function (data) {
      if (data.order && data.order.status === 'settled') { clearInterval(timer); renderPaid(data.order); }
    }).catch(function () {});
  }, 2500);
}

// --- paid ------------------------------------------------------------------

function renderPaid(order) {
  cart = {}; // order placed — empty the cart
  var app = document.getElementById('app');
  app.innerHTML = '';
  var check = h('div', {});
  check.innerHTML = '<svg viewBox="0 0 52 52" class="checkmark"><circle class="ck-circle" cx="26" cy="26" r="24"/><path class="ck-check" d="M14 27l7 7 16-16"/></svg>';
  var more = h('button', { text: 'Back to shop' }); more.onclick = showCatalog;
  app.appendChild(h('div', { 'class': 'success' }, [
    check,
    h('h2', { 'class': 'paid-title', text: 'Paid!' }),
    h('p', { 'class': 'paid-sub', text: (order.description ? order.description + ' — ' : '') + order.amount + ' CC settled on-ledger. Thank you!' }),
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

// --- sign in with wallet (WalletConnect) -----------------------------------

var signedInParty = null;
var signedInSessionId = null;

function shortParty(p) {
  var i = p.indexOf('::');
  return i < 0 ? (p.length > 18 ? p.slice(0, 18) + '…' : p) : p.slice(0, i) + '::' + p.slice(i + 2, i + 8) + '…';
}

function renderSigninButton() {
  var b = document.getElementById('signin');
  if (!b) return;
  if (signedInParty) {
    b.textContent = '✓ ' + shortParty(signedInParty) + ' · Sign out';
    b.title = signedInParty;
    b.onclick = signOut;
  } else {
    b.textContent = '🔐 Sign in with your wallet';
    b.title = '';
    b.onclick = openSignInDialog;
  }
}

function setSignedIn(party, sessionId) {
  signedInParty = party;
  signedInSessionId = sessionId || null;
  try {
    localStorage.setItem('siwc-party', party);
    if (signedInSessionId) localStorage.setItem('siwc-session', signedInSessionId);
  } catch (e) {}
  renderSigninButton();
}

function signOut() {
  signedInParty = null;
  signedInSessionId = null;
  try { localStorage.removeItem('siwc-party'); localStorage.removeItem('siwc-session'); } catch (e) {}
  renderSigninButton();
  showCatalog();
}

function setSiStatus(text, spinning) {
  var s = document.getElementById('si-status'); if (!s) return;
  s.innerHTML = '';
  if (spinning) s.appendChild(h('div', { 'class': 'spinner' }));
  s.appendChild(h('span', { text: text }));
}

// Sign-in runs in a modal dialog over the catalog, so you never leave the shop.
function openSignInDialog() {
  var dlg = document.getElementById('signin-dialog');
  var status = h('div', { 'class': 'statusbar', id: 'si-status' }, [h('div', { 'class': 'spinner' }), h('span', { text: 'Starting a WalletConnect session…' })]);
  var qrbox = h('div', { 'class': 'qrbox', id: 'si-qr' });
  var cancel = h('button', { 'class': 'ghost', text: 'Cancel' }); cancel.onclick = closeSignInDialog;
  dlg.innerHTML = '';
  dlg.appendChild(h('div', { 'class': 'modal-body' }, [
    h('h2', { text: 'Sign in with your Canton wallet' }),
    status, qrbox,
    h('div', { style: 'margin-top:12px;text-align:center' }, [cancel]),
  ]));
  // Esc / backdrop dismiss stops the poll too.
  dlg.onclose = function () { if (signinTimer) { clearInterval(signinTimer); signinTimer = null; } };
  if (typeof dlg.showModal === 'function') dlg.showModal(); else dlg.setAttribute('open', '');
  fetch('/siwc-wc/start', { method: 'POST' }).then(function (r) { return r.json(); }).then(function (data) {
    if (data.error) { setSiStatus(data.error, false); return; }
    var qrEl = document.getElementById('si-qr');
    qrEl.innerHTML = data.qrSvg + '<div class="cap">Open your wallet → Connect tab → scan this</div>';
    // A simulator / desktop wallet can't scan the QR — expose the wc: link as
    // copyable text so it can be pasted into the wallet's Connect field.
    if (data.uri) {
      var uriInput = h('input', { 'class': 'wcuri-input', type: 'text', readonly: 'readonly', value: data.uri });
      uriInput.onclick = function () { this.select(); };
      qrEl.appendChild(h('div', { 'class': 'wcuri' }, [uriInput, copyBtn(data.uri)]));
    }
    setSiStatus('Waiting for your wallet to connect and sign…', true);
    pollSignIn(data.id);
  }).catch(function (e) { setSiStatus(String(e), false); });
}

function closeSignInDialog() {
  if (signinTimer) { clearInterval(signinTimer); signinTimer = null; }
  var dlg = document.getElementById('signin-dialog');
  if (dlg.open) dlg.close(); else dlg.removeAttribute('open');
}

function pollSignIn(id) {
  signinTimer = setInterval(function () {
    fetch('/siwc-wc/status/' + id).then(function (r) { return r.json(); }).then(function (data) {
      if (data.status === 'signed-in') { clearInterval(signinTimer); signinTimer = null; onSignedIn(data.party, id); }
      else if (data.status === 'failed') { clearInterval(signinTimer); signinTimer = null; setSiStatus('Sign-in failed: ' + (data.reason || 'declined'), false); }
    }).catch(function () {});
  }, 1500);
}

// Flash a success state in the dialog, then close it — the catalog is still
// there underneath, and the sign-in button now shows the address.
function onSignedIn(party, sessionId) {
  setSignedIn(party, sessionId);
  var dlg = document.getElementById('signin-dialog');
  var check = h('div', {});
  check.innerHTML = '<svg viewBox="0 0 52 52" class="checkmark"><circle class="ck-circle" cx="26" cy="26" r="24"/><path class="ck-check" d="M14 27l7 7 16-16"/></svg>';
  dlg.innerHTML = '';
  dlg.appendChild(h('div', { 'class': 'modal-body', style: 'text-align:center' }, [
    check,
    h('h2', { 'class': 'paid-title', text: 'Signed in!' }),
    h('p', { 'class': 'paid-sub', text: shortParty(party) }),
  ]));
  confetti();
  setTimeout(closeSignInDialog, 1600);
}

// --- boot ------------------------------------------------------------------

document.title = CONFIG.shop;
var sn = document.getElementById('shopname');
if (sn) sn.textContent = '🛍️ ' + CONFIG.shop;
try { signedInParty = localStorage.getItem('siwc-party'); signedInSessionId = localStorage.getItem('siwc-session'); } catch (e) {}
renderSigninButton();
fetch('/shop').then(function (r) { return r.json(); }).then(function (data) { PRODUCTS = data.products || []; showCatalog(); });
</script>
</body>
</html>
`;
}
