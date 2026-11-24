// PaySwitch demo UI. Plain JavaScript, no framework, no build step.
// Every call goes through the gateway. Public calls: /api/psp/pay, /api/switch/... (see web/nginx.conf).
// Admin calls: /api/admin/{psp|switch|banka|bankb|bankc}/..., behind the gateway's admin password.
'use strict';

const USERS = [
  { vpa: 'ravi@banka', name: 'Ravi', bank: 'Bank A' },
  { vpa: 'meena@banka', name: 'Meena', bank: 'Bank A' },
  { vpa: 'anu@bankb', name: "Anu's shop", bank: 'Bank B' },
  { vpa: 'kiran@bankb', name: 'Kiran', bank: 'Bank B' },
  { vpa: 'kumar@bankc', name: 'Kumar', bank: 'Bank C' },
  { vpa: 'priya@bankc', name: 'Priya', bank: 'Bank C' },
];
const BANKS = [
  { code: 'banka', name: 'Bank A' },
  { code: 'bankb', name: 'Bank B' },
  { code: 'bankc', name: 'Bank C' },
];
const FINAL = ['SUCCESS', 'FAILED', 'REVERSED'];
const MAX_ROWS = 50;

const $ = (sel, el = document) => el.querySelector(sel);

// ---- small helpers -------------------------------------------------------------------------

function esc(text) {
  return String(text ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

/** Paise -> "Rs 1,23,456.78" with BigInt: money is never turned into a floating-point number. */
function rs(paise) {
  if (paise === null || paise === undefined) return '-';
  let p = BigInt(paise);
  const sign = p < 0n ? '-' : '';
  if (p < 0n) p = -p;
  const rupees = (p / 100n).toLocaleString('en-IN');
  const rest = String(p % 100n).padStart(2, '0');
  return `${sign}Rs ${rupees}.${rest}`;
}

function time(iso) {
  return iso ? new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '';
}

function pill(state) {
  return `<span class="pill ${esc(state)}">${esc(state)}</span>`;
}

/** fetch + JSON + a readable error (the services answer RFC 7807 problem JSON). */
async function api(method, url, body) {
  const res = await fetch(url, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
  if (!res.ok) {
    const err = new Error(json?.detail || json?.title || `HTTP ${res.status}`);
    err.status = res.status;
    err.body = json;
    throw err;
  }
  return json;
}

// ---- 1. TOTAL MONEY ------------------------------------------------------------------------

let baseline = null;

async function refreshMoney() {
  const total = $('#total');
  try {
    const m = await api('GET', '/api/switch/admin/money-summary');
    total.classList.remove('unknown');
    total.textContent = rs(m.total);
    $('#customers').textContent = rs(m.customerMoney);
    $('#in-transit').textContent = rs(m.inTransit);
    if (baseline === null) baseline = BigInt(m.total);
    const diff = BigInt(m.total) - baseline;
    if (diff !== 0n) {
      // Must never happen: payments only move money between customers and "in transit".
      total.classList.add('alarm');
      const alarm = $('#total-alarm');
      alarm.hidden = false;
      alarm.innerHTML = `TOTAL changed by ${esc(rs(diff))}: money was created or destroyed! `
        + '<button type="button" class="secondary" id="accept-total">Accept new total</button>';
      $('#accept-total').onclick = () => { baseline = BigInt(m.total); total.classList.remove('alarm'); alarm.hidden = true; };
    }
    renderBanks(m.banks);
    renderStates(m.txnsByState);
  } catch (e) {
    total.classList.add('unknown');
    total.textContent = 'unknown';
    $('#customers').textContent = '-';
    $('#in-transit').textContent = e.message;
  }
}

function renderBanks(banks) {
  $('#banks').innerHTML = `<table><thead><tr><th>Bank</th><th class="num">Customers</th>
    <th class="num">Settlement a/c</th><th class="num">Central reserve</th></tr></thead><tbody>${
    banks.map(b => `<tr><td>${esc(b.bank)}</td><td class="num">${rs(b.customerMoney)}</td>
      <td class="num">${rs(b.switchSettlement)}</td><td class="num">${rs(b.centralReserve)}</td></tr>`).join('')
  }</tbody></table>`;
}

function renderStates(byState) {
  const entries = Object.entries(byState || {});
  $('#states').innerHTML = entries.length
    ? entries.map(([s, n]) => `<span class="chip">${pill(s)} <strong>${n}</strong></span>`).join('')
    : '<span class="hint">No payments yet.</span>';
}

// ---- 2. Pay form ----------------------------------------------------------------------------

function fillUsers() {
  const options = USERS.map(u => `<option value="${u.vpa}">${esc(u.name)} - ${esc(u.bank)}</option>`).join('');
  $('#payer').innerHTML = options;
  $('#payee').innerHTML = options;
  $('#payer').value = 'ravi@banka';
  $('#payee').value = 'anu@bankb';
}

$('#pay-form').addEventListener('submit', async ev => {
  ev.preventDefault();
  const out = $('#pay-result');
  const payer = $('#payer').value;
  const payee = $('#payee').value;
  if (payer === payee) {
    out.innerHTML = '<p class="error">Payer and payee must be different.</p>';
    return;
  }
  const button = ev.submitter || $('#pay-form button');
  button.disabled = true;
  out.textContent = 'Paying...';
  try {
    const r = await api('POST', '/api/psp/pay', { payerVpa: payer, payeeVpa: payee, amount: $('#amount').value.trim() });
    out.innerHTML = `${pill(r.state)} ${esc(r.reason || '')}<br>${txnLink(r.txnId)}`;
  } catch (e) {
    out.innerHTML = `<p class="error">${esc(e.message)}</p>${e.body?.txnId ? txnLink(e.body.txnId) : ''}`;
  } finally {
    button.disabled = false;
  }
});

function txnLink(txnId) {
  return `<button type="button" class="link" data-txn="${esc(txnId)}">${esc(txnId)}</button>`;
}

document.addEventListener('click', ev => {
  const link = ev.target.closest('[data-txn]');
  if (link) openDetails(link.dataset.txn);
});

// ---- 3. Live monitor (Server-Sent Events) --------------------------------------------------

const rows = new Map();        // txnId -> { txnId, payerVpa, payeeVpa, amount, state, createdAt, path: [{state, reason, at}] }
const fresh = new Set();       // rows to highlight once
let renderQueued = false;

function connectLive() {
  const conn = $('#conn');
  // EventSource reconnects by itself if the connection drops (e.g. the switch-crash scenario).
  const source = new EventSource('/api/switch/events/txns');
  source.onopen = () => { conn.textContent = 'live'; conn.className = 'conn conn-on'; };
  source.onerror = () => { conn.textContent = 'reconnecting...'; conn.className = 'conn conn-off'; };

  // Once per connection: the newest payments, straight from the database.
  source.addEventListener('snapshot', ev => {
    rows.clear();
    for (const t of JSON.parse(ev.data)) rows.set(t.txnId, { ...t, path: t.path || [] });
    queueRender();
  });

  // Every 200 ms at most: a batch of committed state changes.
  source.addEventListener('txns', ev => {
    const batch = JSON.parse(ev.data);
    for (const e of batch) apply(e);
    $('#mon-status').textContent = `${batch.length} update${batch.length === 1 ? '' : 's'}`;
    queueRender();
  });
}

/** One committed change. Safe to receive twice (snapshot and batch may overlap). */
function apply(e) {
  let row = rows.get(e.txnId);
  if (!row) {
    row = { txnId: e.txnId, payerVpa: e.payerVpa, payeeVpa: e.payeeVpa, amount: e.amount,
      state: e.to, createdAt: e.at, path: [] };
    rows.set(e.txnId, row);
  }
  if (!row.path.some(step => step.state === e.to)) {     // a state never repeats in one payment
    row.path.push({ state: e.to, reason: e.reason, at: e.at });
    row.state = e.to;
    fresh.add(e.txnId);
  }
}

function queueRender() {
  if (!renderQueued) {
    renderQueued = true;
    requestAnimationFrame(renderTxns);
  }
}

function renderTxns() {
  renderQueued = false;
  const list = [...rows.values()].sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)));
  for (const old of list.slice(MAX_ROWS)) rows.delete(old.txnId);       // keep memory bounded
  const shown = list.slice(0, MAX_ROWS);
  $('#txns').innerHTML = shown.length ? shown.map(t => `
    <li class="txn ${fresh.has(t.txnId) ? 'fresh' : ''}" data-txn="${esc(t.txnId)}" tabindex="0" role="button"
        aria-label="${esc(t.payerVpa)} pays ${esc(t.payeeVpa)} ${esc(rs(t.amount))}, ${esc(t.state)}">
      <div><div class="who">${esc(t.payerVpa)} &rarr; ${esc(t.payeeVpa)}</div>
           <div class="meta">${time(t.createdAt)} &middot; ${esc(t.txnId.slice(0, 14))}...</div></div>
      <div><div class="amt">${rs(t.amount)}</div><div class="state">${pill(t.state)}</div></div>
      <div class="path" aria-hidden="true">${t.path.map(s => `<span title="${esc(s.reason)}">${esc(s.state)}</span>`).join('')}</div>
    </li>`).join('') : '<li class="empty">No payments yet. Make one!</li>';
  fresh.clear();
}

// Keyboard: Enter or Space on a row opens it.
$('#txns').addEventListener('keydown', ev => {
  const row = ev.target.closest('[data-txn]');
  if (row && (ev.key === 'Enter' || ev.key === ' ')) { ev.preventDefault(); openDetails(row.dataset.txn); }
});

async function openDetails(txnId) {
  const dialog = $('#details');
  const body = $('#details-body');
  body.textContent = 'Loading...';
  if (!dialog.open) dialog.showModal();
  try {
    const d = await api('GET', `/api/switch/txns/${encodeURIComponent(txnId)}`);
    body.innerHTML = `
      <dl class="kv">
        <dt>txnId</dt><dd>${esc(d.txnId)}</dd>
        <dt>From</dt><dd>${esc(d.payerVpa)} (${esc(d.payerBank)})</dd>
        <dt>To</dt><dd>${esc(d.payeeVpa)} (${esc(d.payeeBank)})</dd>
        <dt>Amount</dt><dd>${rs(d.amount)}</dd>
        <dt>State</dt><dd>${pill(d.state)}</dd>
      </dl>
      <table><thead><tr><th>Time</th><th>From</th><th>To</th><th>Reason</th></tr></thead><tbody>${
        d.events.map(e => `<tr><td>${time(e.at)}</td><td>${esc(e.fromState || '')}</td>
          <td>${e.fromState === e.toState ? '<em>(note)</em>' : pill(e.toState)}</td><td>${esc(e.reason)}</td></tr>`).join('')
      }</tbody></table>`;
  } catch (e) {
    body.innerHTML = `<p class="error">${esc(e.message)}</p>`;
  }
}

// ---- 4. Scenarios --------------------------------------------------------------------------

const SCENARIOS = [
  { name: 'happy', label: 'Happy path', text: 'Ravi pays Anu Rs 200. Debit, credit, SUCCESS.' },
  { name: 'insufficient-funds', label: 'Insufficient funds', text: 'Meena tries Rs 1,00,000. Bank A declines; nothing moves.' },
  { name: 'debit-reply-lost', label: 'Debit reply lost', text: "Bank A debits but the reply is lost. A status check finds the debit. (~5 s)" },
  { name: 'credit-unknown-deemed', label: 'Credit unknown', text: 'Bank B credits but cannot confirm. DEEMED_APPROVED, never refunded on a guess; recon settles it. (~15 s)' },
  { name: 'switch-crash', label: 'Switch crash', text: 'The switch dies right after the debit. Docker restarts it; the resolver finishes the payment. (~25 s)' },
];

function fillScenarios() {
  $('#scenarios').innerHTML = SCENARIOS.map(s => `
    <li><button type="button" class="secondary" data-scenario="${s.name}">${esc(s.label)}</button>
        <p>${esc(s.text)}</p></li>`).join('');
  $('#scenarios').addEventListener('click', ev => {
    const b = ev.target.closest('[data-scenario]');
    if (b) runScenario(b.dataset.scenario);
  });
}

async function runScenario(name) {
  const buttons = document.querySelectorAll('[data-scenario]');
  const out = $('#scenario-result');
  buttons.forEach(b => { b.disabled = true; });
  out.textContent = `Running ${name}...`;
  try {
    const r = await api('POST', `/api/switch/admin/scenario/${name}`);
    out.innerHTML = `<p>${pill(r.state)} ${esc(r.reason || '')} ${txnLink(r.txnId)}</p><p>${esc(r.explanation)}</p>`
      + (name === 'credit-unknown-deemed' && isAdmin
        ? '<button type="button" class="primary" id="scenario-recon">Run recon now</button>' : '');
    const recon = $('#scenario-recon');
    if (recon) recon.onclick = () => runRecon(out);
  } catch (e) {
    out.innerHTML = `<p class="error">${esc(e.message)}</p>`;
  } finally {
    buttons.forEach(b => { b.disabled = false; });
  }
}

// ---- 5. Chaos ------------------------------------------------------------------------------

const KNOBS = [
  { key: 'declineRate', label: 'Decline', kind: 'rate' },
  { key: 'errorRate', label: 'HTTP 500', kind: 'rate' },
  { key: 'dropResponseRate', label: 'Drop reply', kind: 'rate' },
  { key: 'statusErrorRate', label: 'Status check error', kind: 'rate' },
  { key: 'delayMs', label: 'Delay', kind: 'ms' },
];
const chaosState = {};          // bank code -> current settings
const chaosTimers = {};

function fmtKnob(knob, value) {
  return knob.kind === 'rate' ? `${Math.round(value * 100)}%` : `${value} ms`;
}

async function loadChaos() {
  const box = $('#chaos');
  box.innerHTML = '';
  for (const bank of BANKS) {
    let s;
    try {
      s = await api('GET', `/api/admin/${bank.code}/admin/chaos`);
    } catch (e) {
      box.insertAdjacentHTML('beforeend', `<div class="bank"><h3>${bank.name}</h3><p class="error">${esc(e.message)}</p></div>`);
      continue;
    }
    chaosState[bank.code] = s;
    box.insertAdjacentHTML('beforeend', `<div class="bank" data-bank="${bank.code}"><h3>${bank.name}</h3>${
      KNOBS.map(k => {
        const id = `${bank.code}-${k.key}`;
        const max = k.kind === 'rate' ? 1 : 2000;
        const step = k.kind === 'rate' ? 0.01 : 50;
        return `<div class="slider"><label for="${id}">${k.label}</label>
          <output for="${id}" id="${id}-out">${fmtKnob(k, s[k.key] ?? 0)}</output>
          <input type="range" id="${id}" min="0" max="${max}" step="${step}" value="${s[k.key] ?? 0}"
                 data-bank="${bank.code}" data-key="${k.key}"></div>`;
      }).join('')}</div>`);
  }
}

$('#chaos').addEventListener('input', ev => {
  const input = ev.target.closest('input[type=range]');
  if (!input) return;
  const { bank, key } = input.dataset;
  const knob = KNOBS.find(k => k.key === key);
  const value = knob.kind === 'rate' ? Number(input.value) : parseInt(input.value, 10);
  $(`#${bank}-${key}-out`).textContent = fmtKnob(knob, value);
  chaosState[bank] = { ...chaosState[bank], [key]: value };
  clearTimeout(chaosTimers[bank]);
  // Send once the slider stops moving, not on every pixel.
  chaosTimers[bank] = setTimeout(() => api('POST', `/api/admin/${bank}/admin/chaos`, chaosState[bank]).catch(e => alert(e.message)), 300);
});

$('#chaos-reset').addEventListener('click', async () => {
  await Promise.all(BANKS.map(b => api('POST', `/api/admin/${b.code}/admin/chaos`, {}).catch(() => null)));
  await loadChaos();
});

// ---- 6. Load -------------------------------------------------------------------------------

let loadTimer = null;

async function refreshLoad() {
  try {
    const s = await api('GET', '/api/admin/psp/admin/load');
    const bar = $('#load-progress');
    bar.max = Math.max(1, s.requested);
    bar.value = s.done;
    $('#load-progress-text').textContent = s.requested
      ? `${s.done} / ${s.requested} done${s.running ? '' : ' (finished)'}` : '';
    $('#load-counts').innerHTML = Object.entries(s.byResult || {})
      .map(([r, n]) => `<span class="chip">${pill(r)} <strong>${n}</strong></span>`).join('');
    $('#load-start').disabled = s.running;
    if (!s.running && loadTimer) { clearInterval(loadTimer); loadTimer = null; }
  } catch (e) {
    $('#load-progress-text').textContent = e.message;
  }
}

$('#load-start').addEventListener('click', async () => {
  try {
    await api('POST', '/api/admin/psp/admin/load?count=1000');
  } catch (e) {
    if (e.status !== 409) { $('#load-progress-text').textContent = e.message; return; }
  }
  if (!loadTimer) loadTimer = setInterval(refreshLoad, 1000);
  refreshLoad();
});

// ---- 7. Admin ------------------------------------------------------------------------------

async function runRecon(out = $('#admin-result')) {
  out.textContent = 'Reconciling the last 24 hours...';
  const to = new Date();
  const from = new Date(to.getTime() - 24 * 3600 * 1000);
  try {
    const r = await api('POST', `/api/admin/switch/admin/recon/run?from=${from.toISOString()}&to=${to.toISOString()}`);
    out.innerHTML = `<p>Recon run ${r.runId}: <strong>${esc(r.status)}</strong></p><div class="chips">${
      Object.entries(r.counts || {}).filter(([, n]) => n > 0)
        .map(([c, n]) => `<span class="chip">${esc(c)} <strong>${n}</strong></span>`).join('')
    }</div>${r.status === 'PARTIAL' ? '<p class="hint">Some payments were too new (under 10 s). Run it again in a moment.</p>' : ''}`;
  } catch (e) {
    out.innerHTML = `<p class="error">${esc(e.message)}</p>`;
  }
}

function renderSettlement(r) {
  if (!r) { $('#settlement').innerHTML = '<p class="hint">Nothing settled yet.</p>'; return; }
  const g = r.grossVsNet || {};
  $('#settlement').innerHTML = `<p>Cycle ${r.cycleId}: ${pill(r.status)} ${r.items?.length ?? 0} payments,
    gross ${rs(g.grossAmount)}, net moved ${rs(g.netAmountMoved)} (${esc(g.percentSaved ?? 0)}% saved)</p>
    <table><thead><tr><th>Bank</th><th class="num">Net position</th><th>Meaning</th></tr></thead><tbody>${
      (r.netPositions || []).map(p => `<tr><td>${esc(p.bankCode)}</td><td class="num">${rs(p.netAmount)}</td>
        <td>${p.netAmount > 0 ? 'receives' : p.netAmount < 0 ? 'pays in' : 'nothing'}</td></tr>`).join('')
    }</tbody></table>`;
}

$('#recon-run').addEventListener('click', () => runRecon());

$('#settle').addEventListener('click', async () => {
  const out = $('#admin-result');
  out.textContent = 'Closing and settling the open cycle...';
  try {
    const r = await api('POST', '/api/admin/switch/admin/settlement/close');
    out.innerHTML = `<p>Cycle ${r.cycleId}: ${pill(r.status)}${r.failureReason ? ' ' + esc(r.failureReason) : ''}</p>`;
    if (r.status === 'SETTLED') renderSettlement(r);
  } catch (e) {
    out.innerHTML = `<p class="error">${esc(e.message)}</p>`;
  }
});

async function loadLastSettlement() {
  try {
    renderSettlement(await api('GET', '/api/admin/switch/admin/settlement/last'));
  } catch (e) {
    renderSettlement(e.status === 404 ? null : undefined);
    if (e.status !== 404) $('#settlement').innerHTML = `<p class="error">${esc(e.message)}</p>`;
  }
}

$('#reseed').addEventListener('click', async () => {
  const out = $('#admin-result');
  out.textContent = 'Reseeding: paying money back between the demo customers...';
  try {
    const r = await api('POST', '/api/admin/switch/admin/demo/reseed');
    out.innerHTML = r.payments.length
      ? `<p>${r.payments.length} payment${r.payments.length === 1 ? '' : 's'}:</p><ul>${
          r.payments.map(p => `<li>${esc(p.from)} &rarr; ${esc(p.to)} ${rs(p.amount)} ${pill(p.state)}</li>`).join('')}</ul>`
      : '<p>Everyone already has their starting balance. Nothing to do.</p>';
  } catch (e) {
    out.innerHTML = `<p class="error">${esc(e.message)}</p>`;
  }
});

// ---- 8. Admin log in -----------------------------------------------------------------------
// HTTP basic auth, done by the BROWSER: the first request to /api/admin/login gets 401, the browser
// shows its own login box, and from then on sends the password by itself with every /api/admin/
// request until the browser is closed. This page never sees or stores the password; it only
// remembers (per tab) that the admin parts should be shown.

let isAdmin = false;

function showAdmin(on) {
  isAdmin = on;
  document.querySelectorAll('[data-admin]').forEach(el => { el.hidden = !on; });
  $('#admin-login').hidden = on;
  $('#admin-status').hidden = !on;
  try { on ? sessionStorage.setItem('admin', '1') : sessionStorage.removeItem('admin'); } catch { /* storage off */ }
  if (on) {
    loadChaos();
    refreshLoad();
    loadLastSettlement();
  }
}

async function adminLogin() {
  try {
    const res = await fetch('/api/admin/login', { cache: 'no-store' });
    showAdmin(res.ok);
    if (!res.ok) $('#scenario-result').innerHTML = '<p class="error">Admin log in failed or was cancelled.</p>';
  } catch {
    showAdmin(false);
  }
}

$('#admin-login').addEventListener('click', adminLogin);
// Hides the admin parts. The browser keeps the password until it is closed (basic auth has no log out).
$('#admin-hide').addEventListener('click', () => showAdmin(false));

// ---- start ---------------------------------------------------------------------------------

fillUsers();
fillScenarios();
connectLive();
refreshMoney();
setInterval(refreshMoney, 1000);
let rememberedAdmin = false;
try { rememberedAdmin = sessionStorage.getItem('admin') === '1'; } catch { /* storage off */ }
if (rememberedAdmin) adminLogin();          // this tab logged in before a reload
