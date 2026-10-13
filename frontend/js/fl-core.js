/* FlowLedger — core: helpers, icons, components, shell */
(function () {
  const FL = (window.FL = window.FL || {});
  FL.TODAY = '2026-10-10';
  FL.NOW_LABEL = '10 Oct 2026, 17:24';

  // ------------------------------------------------------------ calcs (shown in Sources)
  dash.calc('kpi', {
    title: 'Dashboard headline figures',
    description: 'Confirmed balances, today\'s bank movements, upcoming cheques and 13-week forecast totals, worked out from the sources below.',
    inputs: ['accounts', 'transactions', 'cheques', 'forecast_weekly', 'shortages'],
    fn: (accounts, transactions, cheques, forecast_weekly, shortages) => {
      const today = '2026-10-10', in7 = '2026-10-17';
      const todays = transactions.filter((t) => t.txn_date === today && t.match_status !== 'Pending' && t.txn_type !== 'Internal transfer');
      const rec = todays.filter((t) => Number(t.amount) > 0), pay = todays.filter((t) => Number(t.amount) < 0);
      const open = cheques.filter((c) => c.status === 'Issued' || c.status === 'Presented');
      const next7 = open.filter((c) => c.expected_clear_date <= in7);
      const synced = accounts.filter((a) => a.sync_status === 'Synced');
      const lastSync = synced.map((a) => a.last_sync).sort().slice(-1)[0];
      const low = forecast_weekly.reduce((m, w) => (Number(w.lowest_balance) < Number(m.lowest_balance) ? w : m), forecast_weekly[0]);
      const sum = (xs, f) => xs.reduce((s, x) => s + Number(x[f]), 0);
      return [{
        confirmed_balance: Math.round(sum(accounts, 'balance') * 100) / 100,
        accounts_total: accounts.length,
        accounts_synced: synced.length,
        accounts_error: accounts.length - synced.length,
        last_sync: lastSync,
        receipts_today: Math.round(sum(rec, 'amount') * 100) / 100,
        receipts_count: rec.length,
        payments_today: Math.round(-sum(pay, 'amount') * 100) / 100,
        payments_count: pay.length,
        cheques_7d_amount: Math.round(sum(next7, 'amount') * 100) / 100,
        cheques_7d_count: next7.length,
        outstanding_amount: Math.round(sum(open, 'amount') * 100) / 100,
        outstanding_count: open.length,
        bounced_count: cheques.filter((c) => c.status === 'Bounced').length,
        forecast_inflow: Math.round(sum(forecast_weekly, 'inflow') * 100) / 100,
        forecast_outflow: Math.round(sum(forecast_weekly, 'cheque_out') * 100) / 100,
        forecast_closing: forecast_weekly.length ? Number(forecast_weekly[forecast_weekly.length - 1].closing) : null,
        lowest_balance: low ? Number(low.lowest_balance) : null,
        lowest_date: low ? low.lowest_date : null,
        shortage_count: shortages.length,
        unmatched_count: transactions.filter((t) => t.match_status === 'Unmatched').length,
        review_count: transactions.filter((t) => t.match_status === 'Review').length,
      }];
    },
  });
  dash.calc('import_summary', {
    title: 'Import validation summary',
    description: 'Counts of valid, error and warning rows in the uploaded historical sales file, and its date range.',
    inputs: ['import_rows'],
    fn: (import_rows) => {
      const dates = import_rows.map((r) => r.date).filter(Boolean).sort();
      const dupes = import_rows.filter((r) => String(r.issue).startsWith('Duplicate date')).length;
      return [{
        rows: import_rows.length,
        valid: import_rows.filter((r) => r.status === 'Valid').length,
        errors: import_rows.filter((r) => r.status === 'Error').length,
        warnings: import_rows.filter((r) => r.status === 'Warning').length,
        duplicate_rows: dupes,
        first_date: dates[0],
        last_date: dates[dates.length - 1],
      }];
    },
  });

  // ------------------------------------------------------------ helpers
  FL.$ = (s, r = document) => r.querySelector(s);
  FL.$$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  FL.h = function h(tag, props, ...kids) {
    const el = document.createElement(tag);
    if (props) {
      for (const [k, v] of Object.entries(props)) {
        if (v == null || v === false) continue;
        if (k === 'class') el.className = v;
        else if (k === 'text') el.textContent = v;
        else if (k === 'on') for (const [ev, fn] of Object.entries(v)) el.addEventListener(ev, fn);
        else if (k === 'style') el.setAttribute('style', v);
        else if (k === 'dataset') for (const [dk, dv] of Object.entries(v)) el.dataset[dk] = dv;
        else if (k in el && typeof v !== 'string' && k !== 'list') el[k] = v;
        else el.setAttribute(k, v === true ? '' : v);
      }
    }
    for (const k of kids.flat(Infinity)) {
      if (k == null || k === false) continue;
      el.append(k instanceof Node ? k : document.createTextNode(String(k)));
    }
    return el;
  };
  const h = FL.h;

  FL.rows = (id) => { const d = dash.data(id); return d.status === 'ok' && Array.isArray(d.data) ? d.data : []; };
  FL.status = (id) => dash.data(id).status;
  FL.one = (id) => FL.rows(id)[0] || null;
  FL.ready = (...ids) => ids.every((id) => dash.data(id).status === 'ok');

  const nf2 = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const nf0 = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 });
  const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const DAY = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  const pd = (s) => { const [y, m, d] = String(s).slice(0, 10).split('-').map(Number); return new Date(Date.UTC(y, m - 1, d)); };
  FL.pd = pd;
  FL.fmt = {
    amt: (v) => (v == null || v === '' || isNaN(v) ? '—' : (Number(v) < 0 ? '−' : '') + nf2.format(Math.abs(Number(v)))),
    signed: (v) => (v == null || isNaN(v) ? '—' : (Number(v) < 0 ? '−' : '+') + nf2.format(Math.abs(Number(v)))),
    lkr: (v) => (v == null || isNaN(v) ? '—' : (Number(v) < 0 ? '−' : '') + 'LKR ' + nf2.format(Math.abs(Number(v)))),
    lkr0: (v) => (v == null || isNaN(v) ? '—' : (Number(v) < 0 ? '−' : '') + 'LKR ' + nf0.format(Math.abs(Number(v)))),
    m: (v) => { if (v == null || isNaN(v)) return '—'; const a = Math.abs(v); const s = a >= 1e6 ? (a / 1e6).toFixed(2) + 'M' : a >= 1e3 ? (a / 1e3).toFixed(0) + 'K' : a.toFixed(0); return (v < 0 ? '−' : '') + s; },
    int: (v) => nf0.format(Number(v)),
    pct: (v) => (v == null || isNaN(v) ? '—' : (v > 0 ? '+' : v < 0 ? '−' : '') + Math.abs(v * 100).toFixed(1) + '%'),
    date: (s) => { if (!s) return '—'; const d = pd(s); return `${String(d.getUTCDate()).padStart(2, '0')} ${MON[d.getUTCMonth()]} ${d.getUTCFullYear()}`; },
    dshort: (s) => { if (!s) return '—'; const d = pd(s); return `${String(d.getUTCDate()).padStart(2, '0')} ${MON[d.getUTCMonth()]}`; },
    dday: (s) => { if (!s) return '—'; const d = pd(s); return `${DAY[d.getUTCDay()]} ${String(d.getUTCDate()).padStart(2, '0')} ${MON[d.getUTCMonth()]}`; },
    dt: (s) => { if (!s) return '—'; return FL.fmt.dshort(s) + ', ' + String(s).slice(11, 16); },
    dtFull: (s) => { if (!s) return '—'; return FL.fmt.date(s) + ', ' + String(s).slice(11, 16); },
    range: (a, b) => `${FL.fmt.dshort(a)} – ${FL.fmt.dshort(b)}`,
  };
  FL.daysBetween = (a, b) => Math.round((pd(b) - pd(a)) / 864e5);
  FL.addDays = (s, n) => { const d = pd(s); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };
  FL.initials = (n) => String(n).split(' ').map((p) => p[0]).slice(0, 2).join('').toUpperCase();

  // ------------------------------------------------------------ icons (lucide-style paths)
  const I = {
    dashboard: ['R3,3,7,9,1', 'R14,3,7,5,1', 'R14,12,7,9,1', 'R3,16,7,5,1'],
    sales: ['R2,6,20,12,2', 'C12,12,2', 'M6 12h.01M18 12h.01'],
    upload: ['M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4', 'm17 8-5-5-5 5', 'M12 3v12'],
    bank: ['M3 22h18', 'M6 18v-7', 'M10 18v-7', 'M14 18v-7', 'M18 18v-7', 'm12 2 8 5H4z'],
    recon: ['m8 3-4 4 4 4', 'M4 7h16', 'm16 21 4-4-4-4', 'M20 17H4'],
    cheque: ['M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z', 'M14 2v6h6', 'M16 13H8', 'M16 17H8'],
    forecast: ['m22 7-8.5 8.5-5-5L2 17', 'M16 7h6v6'],
    fva: ['M3 3v18h18', 'M18 17V9', 'M13 17V5', 'M8 17v-3'],
    scenario: ['M21 4h-7', 'M10 4H3', 'M21 12h-9', 'M8 12H3', 'M21 20h-5', 'M12 20H3', 'M14 2v4', 'M8 10v4', 'M16 18v4'],
    users: ['M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2', 'C9,7,4', 'M22 21v-2a4 4 0 0 0-3-3.87', 'M16 3.13a4 4 0 0 1 0 7.75'],
    audit: ['M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8', 'M3 3v5h5', 'M12 7v5l4 2'],
    bell: ['M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9', 'M10.3 21a1.94 1.94 0 0 0 3.4 0'],
    down: ['m6 9 6 6 6-6'], right: ['m9 18 6-6-6-6'], left: ['m15 18-6-6 6-6'],
    search: ['C11,11,8', 'm21 21-4.3-4.3'],
    plus: ['M5 12h14', 'M12 5v14'], x: ['M18 6 6 18', 'm6 6 12 12'],
    eye: ['M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7Z', 'C12,12,3'],
    eyeoff: ['M9.88 9.88a3 3 0 1 0 4.24 4.24', 'M10.73 5.08A10.43 10.43 0 0 1 12 5c7 0 10 7 10 7a13.16 13.16 0 0 1-1.67 2.68', 'M6.61 6.61A13.526 13.526 0 0 0 2 12s3 7 10 7a9.74 9.74 0 0 0 5.39-1.61', 'm2 2 20 20'],
    alert: ['m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z', 'M12 9v4', 'M12 17h.01'],
    check: ['M20 6 9 17l-5-5'],
    checkc: ['C12,12,10', 'm9 12 2 2 4-4'],
    xc: ['C12,12,10', 'm15 9-6 6', 'm9 9 6 6'],
    refresh: ['M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8', 'M3 3v5h5', 'M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16', 'M16 16h5v5'],
    logout: ['M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4', 'm16 17 5-5-5-5', 'M21 12H9'],
    sun: ['C12,12,4', 'M12 2v2', 'M12 20v2', 'm4.93 4.93 1.41 1.41', 'm17.66 17.66 1.41 1.41', 'M2 12h2', 'M20 12h2', 'm6.34 17.66-1.41 1.41', 'm19.07 4.93-1.41 1.41'],
    moon: ['M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z'],
    monitor: ['R2,3,20,14,2', 'M8 21h8', 'M12 17v4'],
    menu: ['M4 6h16', 'M4 12h16', 'M4 18h16'],
    info: ['C12,12,10', 'M12 16v-4', 'M12 8h.01'],
    lock: ['R3,11,18,11,2', 'M7 11V7a5 5 0 0 1 10 0v4'],
    more: ['C12,12,1', 'C19,12,1', 'C5,12,1'],
    download: ['M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4', 'm7 10 5 5 5-5', 'M12 15V3'],
    sheet: ['M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z', 'M14 2v6h6', 'M8 13h2', 'M14 13h2', 'M8 17h2', 'M14 17h2'],
    clock: ['C12,12,10', 'M12 6v6l4 2'],
    calendar: ['R3,4,18,18,2', 'M16 2v4', 'M8 2v4', 'M3 10h18'],
    copy: ['R8,8,14,14,2', 'M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2'],
    wallet: ['M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1', 'M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4'],
    link: ['M9 17H7A5 5 0 0 1 7 7h2', 'M15 7h2a5 5 0 1 1 0 10h-2', 'M8 12h8'],
    shield: ['M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z'],
    pen: ['M12 20h9', 'M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z'],
    flow: ['M3 12h4l3-9 4 18 3-9h4'],
  };
  const SVGNS = 'http://www.w3.org/2000/svg';
  FL.icon = (name, size = 16) => {
    const s = document.createElementNS(SVGNS, 'svg');
    s.setAttribute('viewBox', '0 0 24 24'); s.setAttribute('width', size); s.setAttribute('height', size);
    s.setAttribute('class', 'ic'); s.setAttribute('aria-hidden', 'true');
    for (const p of I[name] || []) {
      let el;
      if (p[0] === 'R') { const [x, y, w, hh, rx] = p.slice(1).split(','); el = document.createElementNS(SVGNS, 'rect'); Object.entries({ x, y, width: w, height: hh, rx }).forEach(([k, v]) => el.setAttribute(k, v)); }
      else if (p[0] === 'C') { const [cx, cy, r] = p.slice(1).split(','); el = document.createElementNS(SVGNS, 'circle'); Object.entries({ cx, cy, r }).forEach(([k, v]) => el.setAttribute(k, v)); }
      else { el = document.createElementNS(SVGNS, 'path'); el.setAttribute('d', p); }
      s.append(el);
    }
    return s;
  };

  // ------------------------------------------------------------ small components
  FL.btn = (label, opts = {}) => h('button', {
    type: 'button', class: 'fl-btn ' + (opts.variant || '') + (opts.size ? ' ' + opts.size : '') + (label ? '' : ' icon'),
    on: opts.onClick ? { click: opts.onClick } : null, disabled: opts.disabled || null, 'aria-label': opts.aria || null, title: opts.title || null,
  }, opts.icon ? FL.icon(opts.icon, opts.size === 'sm' ? 14 : 16) : null, label || null);

  const BADGE = {
    // reconciliation
    Matched: 'soft', Unmatched: 'secondary', Pending: 'dashed', Review: 'attn',
    // cheques
    Issued: 'secondary', Presented: 'outline', Cleared: 'soft', Bounced: 'destructive', Cancelled: 'outline muted',
    // general
    Valid: 'soft', Error: 'destructive-soft', Warning: 'attn', Active: 'soft', Invited: 'dashed', Deactivated: 'outline muted',
    Synced: 'soft', 'Not reconciled': 'attn', Reconciled: 'soft', Backdated: 'outline', Standard: 'outline muted',
    'Awaiting approval': 'attn', Approved: 'soft', Rejected: 'outline muted', Critical: 'destructive', 'Possible duplicate': 'outline',
  };
  const BADGE_LABEL = { Review: 'Review required' };
  FL.badge = (status, extra) => {
    const cls = BADGE[status] || 'outline';
    const kids = [];
    if (status === 'Review') kids.push(FL.icon('alert', 12));
    if (status === 'Bounced' || status === 'Error') kids.push(FL.icon('xc', 12));
    if (status === 'Possible duplicate') kids.push(FL.icon('copy', 12));
    if (status === 'Cleared' || status === 'Matched') kids.push(FL.icon('check', 12));
    kids.push(extra || BADGE_LABEL[status] || status);
    return h('span', { class: 'fl-badge ' + cls }, kids);
  };

  FL.card = (title, note, body, opts = {}) => h('section', { class: 'fl-card' + (opts.cls ? ' ' + opts.cls : ''), id: opts.id || null },
    (title || opts.actions) ? h('div', { class: 'fl-card-h' }, h('div', null, title ? h('h3', null, opts.icon ? FL.icon(opts.icon, 16) : null, title) : null, note ? h('p', null, note) : null), opts.actions || null) : null,
    h('div', { class: 'fl-card-b' + (opts.bodyCls ? ' ' + opts.bodyCls : '') }, body),
    opts.footer ? h('div', { class: 'fl-card-f' }, opts.footer) : null);

  FL.field = (label, control, opts = {}) => {
    const id = control.id || 'f' + Math.random().toString(36).slice(2, 8);
    control.id = id;
    return h('div', { class: 'fl-field' + (opts.cls ? ' ' + opts.cls : '') }, h('label', { for: id }, label), control,
      opts.help ? h('div', { class: 'help' }, opts.help) : null, h('div', { class: 'err', hidden: true }));
  };
  FL.fieldErr = (fieldEl, msg) => {
    const e = fieldEl.querySelector('.err'); const c = fieldEl.querySelector('input,select,textarea');
    e.hidden = !msg; e.textContent = msg || ''; if (c) c.setAttribute('aria-invalid', msg ? 'true' : 'false');
  };
  FL.select = (options, value, attrs = {}) => {
    const s = h('select', Object.assign({ class: 'fl-select' }, attrs));
    for (const o of options) { const [v, l] = Array.isArray(o) ? o : [o, o]; s.append(new Option(l, v)); }
    if (value != null) s.value = value;
    return s;
  };
  FL.seg = (options, value, onChange) => {
    const wrap = h('div', { class: 'fl-seg', role: 'group' });
    const set = (v) => { FL.$$('button', wrap).forEach((b) => b.setAttribute('aria-pressed', b.dataset.v === v ? 'true' : 'false')); };
    for (const o of options) {
      const [v, l, n] = Array.isArray(o) ? o : [o, o];
      wrap.append(h('button', { type: 'button', dataset: { v }, 'aria-pressed': v === value ? 'true' : 'false', on: { click: () => { set(v); onChange(v); } } }, l, n != null ? h('span', { class: 'n' }, n) : null));
    }
    wrap.set = set;
    return wrap;
  };
  FL.search = (placeholder, onInput) => {
    const i = h('input', { class: 'fl-input', type: 'search', placeholder, 'aria-label': placeholder });
    i.addEventListener('input', () => {
      const pos = i.selectionStart;
      onInput(i.value.trim().toLowerCase());
      if (document.activeElement !== i) { i.focus(); try { i.setSelectionRange(pos, pos); } catch (e) { /* type=search */ } }
    });
    return h('div', { class: 'fl-search grow' }, FL.icon('search', 15), i);
  };
  FL.dateInput = (value, onChange, aria) => {
    const i = h('input', { class: 'fl-input', type: 'date', min: '2000-01-01', max: '2099-12-31', value: value || '', 'aria-label': aria || 'Date' });
    i.addEventListener('change', () => { if (!i.value || i.validity.valid) onChange(i.value || null); });
    return i;
  };
  FL.amountInput = (attrs = {}) => {
    const i = h('input', Object.assign({ class: 'fl-input has-pre amt', inputmode: 'decimal', autocomplete: 'off', placeholder: '0.00' }, attrs));
    i.addEventListener('blur', () => { const v = FL.parseAmt(i.value); if (v != null && !isNaN(v)) i.value = nf2.format(v); });
    i.addEventListener('focus', () => { const v = FL.parseAmt(i.value); if (v != null && !isNaN(v)) i.value = String(v); i.select(); });
    const g = h('div', { class: 'fl-input-group' }, h('span', { class: 'pre' }, 'LKR'), i);
    g.input = i;
    return g;
  };
  FL.parseAmt = (s) => { if (s == null || String(s).trim() === '') return null; const v = Number(String(s).replace(/[, ]/g, '')); return isNaN(v) ? NaN : v; };

  // ------------------------------------------------------------ data table (marked once per the dashboard rules)
  // cols: {field, label, num, fmt(v,row), render(row) -> node, calc (marks th with calc source), sort, cls(row), wrap}
  FL.table = function (opts) {
    const st = { sortKey: opts.sort || null, dir: opts.dir || 'desc' };
    const wrap = h('div', { class: 'fl-table-wrap' + (opts.cap === false ? '' : ' capped'), style: opts.maxH ? `max-height:${opts.maxH}px` : null });
    const table = h('table', { class: 'fl-table' + (opts.compact ? ' compact' : ''), id: opts.id || null });
    if (opts.source) { table.dataset.source = opts.source; table.dataset.rowKey = opts.key; }
    const thead = h('thead'); const tbody = h('tbody');
    table.append(thead, tbody); wrap.append(table);
    const foot = h('div', { class: 'fl-table-foot', hidden: !opts.foot });
    const outer = h('div', null, wrap, foot);

    function head() {
      const tr = h('tr');
      for (const c of opts.cols) {
        const th = h('th', { class: c.num ? 'num' : null, scope: 'col', style: c.w ? `width:${c.w}` : null });
        if (c.field && !c.calc) th.dataset.field = c.field;
        if (c.calc) { th.dataset.source = c.calc; th.dataset.field = c.field; }
        if (c.sortable === false || !c.field) th.textContent = c.label;
        else {
          const arr = h('span', { class: 'arr' }, st.sortKey === c.field ? (st.dir === 'asc' ? '▲' : '▼') : '↕');
          if (st.sortKey === c.field) th.setAttribute('aria-sort', st.dir === 'asc' ? 'ascending' : 'descending');
          th.append(h('button', { type: 'button', on: { click: () => { if (st.sortKey === c.field) st.dir = st.dir === 'asc' ? 'desc' : 'asc'; else { st.sortKey = c.field; st.dir = c.num ? 'desc' : 'asc'; } draw(last); } } }, c.label, arr));
        }
        tr.append(th);
      }
      thead.replaceChildren(tr);
    }
    let last = [];
    function draw(rows) {
      last = rows;
      head();
      let rs = rows.slice();
      if (st.sortKey) {
        const c = opts.cols.find((x) => x.field === st.sortKey) || {};
        const val = c.sortVal || ((r) => r[st.sortKey]);
        rs.sort((a, b) => {
          let x = val(a), y = val(b);
          if (c.num) { x = Number(x); y = Number(y); return st.dir === 'asc' ? x - y : y - x; }
          return st.dir === 'asc' ? String(x ?? '').localeCompare(String(y ?? '')) : String(y ?? '').localeCompare(String(x ?? ''));
        });
      }
      const frag = document.createDocumentFragment();
      if (opts.groupBy) {
        let g = null;
        for (const r of rs) {
          const gv = opts.groupBy(r);
          if (gv !== g) { g = gv; frag.append(h('tr', { class: 'week-sep' }, h('td', { colspan: opts.cols.length }, gv))); }
          frag.append(row(r));
        }
      } else for (const r of rs) frag.append(row(r));
      if (!rs.length) frag.append(h('tr', null, h('td', { colspan: opts.cols.length, class: 'fl-table-empty' }, opts.empty || 'No records match these filters.')));
      tbody.replaceChildren(frag);
      if (opts.foot) foot.replaceChildren(...[].concat(opts.foot(rs)));
    }
    function row(r) {
      const tr = h('tr', { class: [opts.rowCls ? opts.rowCls(r) : '', opts.onRow ? 'clickable' : ''].join(' ').trim() || null });
      if (opts.key) tr.dataset.key = opts.key.split(',').map((k) => (opts.key.includes(',') ? encodeURIComponent(r[k]) : r[k])).join(',');
      if (opts.onRow) { tr.tabIndex = 0; tr.addEventListener('click', (e) => { if (!e.target.closest('button,a,input,select')) opts.onRow(r, tr); }); tr.addEventListener('keydown', (e) => { if (e.key === 'Enter') opts.onRow(r, tr); }); }
      for (const c of opts.cols) {
        const td = h('td', { class: [c.num ? 'num' : '', c.wrap ? 'wrap' : '', c.cls ? c.cls(r) : ''].join(' ').trim() || null });
        if (c.render) { const n = c.render(r); if (n != null) td.append(n); }
        else td.textContent = c.fmt ? c.fmt(r[c.field], r) : (r[c.field] ?? '—');
        tr.append(td);
      }
      return tr;
    }
    outer.draw = draw; outer.table = table;
    return outer;
  };

  // ------------------------------------------------------------ charts
  FL.css = (name) => getComputedStyle(FL.root).getPropertyValue(name).trim();
  FL.tip = (box) => { let t = box.querySelector('.fl-tip'); if (!t) { t = h('div', { class: 'fl-tip', role: 'status' }); box.append(t); } return t; };
  FL.placeTip = (box, tip, x, y) => {
    tip.style.display = 'block';
    const bw = box.clientWidth, tw = tip.offsetWidth, th = tip.offsetHeight;
    let left = x + 14; if (left + tw > bw) left = x - tw - 14; if (left < 0) left = 0;
    let top = y - th / 2; if (top < 0) top = 0;
    tip.style.left = left + 'px'; tip.style.top = top + 'px';
  };
  FL.tipHtml = (tip, head, rows) => {
    tip.replaceChildren(h('div', { class: 'h' }, head), ...rows.map(([sw, lbl, val, cls]) => h('div', { class: 'r' }, h('span', null, sw ? h('i', { style: `width:8px;height:8px;border-radius:2px;background:${sw};display:inline-block` }) : null, lbl), h('b', { class: cls || null }, val))));
  };
  FL.yTicksFmt = (v) => { const a = Math.abs(v); return (v < 0 ? '−' : '') + (a >= 1e6 ? (a / 1e6).toFixed(a % 1e6 === 0 ? 0 : 1) + 'M' : a >= 1e3 ? (a / 1e3).toFixed(0) + 'K' : a); };
  FL.leftMargin = (svg, ticks) => {
    const t = svg.append('text').attr('class', 'lbl').attr('x', -999);
    let w = 0; for (const v of ticks) { t.text(FL.yTicksFmt(v)); w = Math.max(w, t.node().getComputedTextLength()); }
    t.remove(); return Math.ceil(w) + 10;
  };

  // ------------------------------------------------------------ overlays: popovers, dialogs, toasts
  let openPop = null;
  FL.popover = (anchor, content, opts = {}) => {
    if (openPop) { const same = openPop.anchor === anchor; FL.closePop(); if (same) return; }
    const pop = h('div', { class: 'fl-pop ' + (opts.cls || ''), role: opts.role || 'menu' }, content);
    FL.root.append(pop);
    const ar = anchor.getBoundingClientRect(), rr = FL.root.getBoundingClientRect();
    pop.style.top = ar.bottom - rr.top + 6 + 'px';
    const left = opts.align === 'start' ? ar.left - rr.left : ar.right - rr.left - pop.offsetWidth;
    pop.style.left = Math.max(8, Math.min(left, rr.width - pop.offsetWidth - 8)) + 'px';
    openPop = { pop, anchor };
    anchor.setAttribute('aria-expanded', 'true');
    const first = pop.querySelector('button'); if (first && opts.focus !== false) first.focus();
    return pop;
  };
  FL.closePop = () => { if (!openPop) return; openPop.anchor.setAttribute('aria-expanded', 'false'); openPop.pop.remove(); openPop = null; };
  document.addEventListener('click', (e) => { if (openPop && !openPop.pop.contains(e.target) && !openPop.anchor.contains(e.target)) FL.closePop(); });
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') { FL.closePop(); FL.closeDialog(); } });
  FL.menuItem = (label, onClick, opts = {}) => h('button', { type: 'button', class: 'item' + (opts.danger ? ' danger' : ''), role: 'menuitem', on: { click: () => { FL.closePop(); onClick(); } } }, opts.icon ? FL.icon(opts.icon, 15) : null, label, opts.checked ? h('span', { class: 'chk' }, FL.icon('check', 14)) : null);

  let dlg = null, lastFocus = null;
  FL.dialog = ({ title, desc, body, footer, wide, anchor }) => {
    FL.closeDialog();
    lastFocus = document.activeElement;
    const overlay = h('div', { class: 'fl-overlay' });
    const box = h('div', { class: 'fl-dialog' + (wide ? ' wide' : ''), role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
      h('div', { class: 'dh' }, h('h2', null, title), desc ? h('p', null, desc) : null),
      FL.btn('', { variant: 'ghost', size: 'sm', icon: 'x', aria: 'Close', onClick: () => FL.closeDialog() }),
      h('div', { class: 'db' }, body), footer ? h('div', { class: 'df' }, footer) : null);
    box.querySelector('.fl-btn.ghost').classList.add('x');
    overlay.addEventListener('mousedown', (e) => { if (e.target === overlay) FL.closeDialog(); });
    overlay.append(box); FL.root.append(overlay);
    // place near what opened it (this page grows with content, so fixed positioning would land off-screen)
    const rr = FL.root.getBoundingClientRect();
    const ref = anchor || lastFocus;
    let top = ref && ref.getBoundingClientRect ? ref.getBoundingClientRect().top - rr.top - 80 : 80;
    top = Math.max(24, Math.min(top, rr.height - box.offsetHeight - 24));
    box.style.top = top + 'px';
    box.scrollIntoView({ block: 'nearest' });
    const f = box.querySelector('input,select,textarea,.df .fl-btn'); if (f) f.focus();
    box.addEventListener('keydown', (e) => {
      if (e.key !== 'Tab') return;
      const fs = FL.$$('button,input,select,textarea,[tabindex="0"]', box).filter((x) => !x.disabled && x.offsetParent);
      if (!fs.length) return;
      if (e.shiftKey && document.activeElement === fs[0]) { e.preventDefault(); fs[fs.length - 1].focus(); }
      else if (!e.shiftKey && document.activeElement === fs[fs.length - 1]) { e.preventDefault(); fs[0].focus(); }
    });
    dlg = overlay;
    return box;
  };
  FL.closeDialog = () => { if (!dlg) return; dlg.remove(); dlg = null; if (lastFocus && lastFocus.focus) lastFocus.focus(); };

  FL.toast = (title, sub) => {
    FL.$$('.fl-toast', FL.root).forEach((t) => t.remove());
    const rr = FL.root.getBoundingClientRect();
    const top = Math.max(16, Math.min(-rr.top + 16, rr.height - 120));
    const t = h('div', { class: 'fl-toast', role: 'status', style: `top:${top}px` }, FL.icon('checkc', 18), h('div', null, h('div', null, title), sub ? h('div', { class: 's' }, sub) : null));
    FL.root.append(t);
    setTimeout(() => t.remove(), 4200);
  };

  // ------------------------------------------------------------ shell
  FL.NAV = [
    { group: 'Overview' },
    { id: 'dashboard', label: 'Dashboard', icon: 'dashboard' },
    { group: 'Sales' },
    { id: 'daily-sales', label: 'Daily Sales', icon: 'sales' },
    { id: 'historical-import', label: 'Historical Import', icon: 'upload' },
    { group: 'Banking' },
    { id: 'bank-accounts', label: 'Bank Accounts', icon: 'bank' },
    { id: 'reconciliation', label: 'Reconciliation', icon: 'recon' },
    { id: 'cheques', label: 'Cheque Payments', icon: 'cheque' },
    { group: 'Planning' },
    { id: 'forecast', label: 'Cash-Flow Forecast', icon: 'forecast' },
    { id: 'forecast-vs-actual', label: 'Forecast vs Actual', icon: 'fva' },
    { id: 'scenarios', label: 'Scenario Analysis', icon: 'scenario' },
    { group: 'Administration' },
    { id: 'users-settings', label: 'Users & Settings', icon: 'users' },
    { id: 'audit', label: 'Audit History', icon: 'audit' },
  ];
  // what each role can open; "view" = read-only
  FL.ACCESS = {
    'Finance Controller': { '*': 'edit', 'users-settings': 'view', audit: 'view' },
    'Company Admin': { '*': 'edit', audit: 'view' },
    Cashier: { dashboard: 'view', 'daily-sales': 'view', 'bank-accounts': 'view', cheques: 'view' },
  };
  FL.access = (page) => { const a = FL.ACCESS[FL.state.role]; return a[page] || a['*'] || null; };
  FL.state = { page: 'dashboard', role: 'Finance Controller', signedIn: true, theme: 'system' };
  FL.pages = {};
  FL.register = (id, def) => { FL.pages[id] = def; };

  FL.pageHead = (title, desc, actions) => h('div', { class: 'fl-page-head' }, h('div', null, h('h1', null, title), desc ? h('p', null, desc) : null), actions ? h('div', { class: 'actions' }, actions) : null);

  function buildShell() {
    const root = FL.$('#fl-app');
    FL.root = root;
    root.replaceChildren();
    // login
    FL.loginEl = h('div', { class: 'fl-login', id: 'login', hidden: true });
    // sidebar
    const nav = h('nav', { class: 'fl-nav', 'aria-label': 'Main' });
    for (const n of FL.NAV) {
      if (n.group) { nav.append(h('div', { class: 'fl-nav-group' }, n.group)); continue; }
      const a = h('a', { href: '#' + n.id, dataset: { page: n.id }, id: 'nav-' + n.id }, FL.icon(n.icon, 16), h('span', null, n.label));
      a.addEventListener('click', (e) => { e.preventDefault(); FL.go(n.id); });
      nav.append(a);
    }
    const sideFoot = h('div', { class: 'fl-side-foot', id: 'side-foot' });
    const sidebar = h('aside', { class: 'fl-sidebar' },
      h('div', { class: 'fl-brand' }, h('div', { class: 'fl-logo' }, FL.icon('flow', 16)), h('div', null, h('div', { class: 'fl-brand-name' }, 'FlowLedger'), h('div', { class: 'fl-brand-sub' }, 'Cash position & forecast'))),
      nav, sideFoot);
    // header
    const crumb = h('div', { class: 'fl-crumb', id: 'crumb' });
    const company = h('div', { class: 'fl-company', title: 'Company' }, h('span', { class: 'dot' }), h('span', { 'data-source': 'settings', 'data-field': 'value', 'data-where': 'key=company_name', id: 'hdr-company' }, '—'));
    const themeBtn = FL.btn('', { variant: 'ghost', icon: 'monitor', aria: 'Theme', onClick: (e) => themeMenu(e.currentTarget) });
    themeBtn.id = 'theme-btn';
    const bell = h('div', { class: 'fl-bell' }, FL.btn('', { variant: 'ghost', icon: 'bell', aria: 'Notifications', onClick: (e) => notifMenu(e.currentTarget) }), h('span', { class: 'pip', id: 'bell-pip', hidden: true }));
    const user = h('button', { type: 'button', class: 'fl-user', 'aria-haspopup': 'menu', 'aria-expanded': 'false', on: { click: (e) => userMenu(e.currentTarget) } },
      h('span', { class: 'fl-avatar', id: 'hdr-avatar' }, 'NP'), h('span', { class: 'who' }, h('b', { id: 'hdr-name' }, 'Nadeesha Perera'), h('span', { id: 'hdr-role' }, FL.state.role)), FL.icon('down', 14));
    const menuBtn = FL.btn('', { variant: 'ghost', icon: 'menu', aria: 'Open navigation', onClick: () => root.classList.toggle('nav-open') });
    menuBtn.classList.add('fl-menu-btn');
    const header = h('header', { class: 'fl-header' }, menuBtn, h('div', { class: 'title-wrap' }, crumb), h('div', { class: 'spacer' }), company, themeBtn, bell, user);
    const content = h('main', { class: 'fl-content', id: 'fl-content' });
    for (const n of FL.NAV) if (n.id) content.append(h('div', { class: 'fl-page', id: n.id, hidden: true }));
    content.append(h('div', { class: 'fl-page', id: 'no-access', hidden: true }));
    FL.shellEl = h('div', { class: 'fl-shell' }, sidebar, h('div', { class: 'fl-main' }, header, content));
    root.append(FL.loginEl, FL.shellEl);
    root.addEventListener('click', (e) => { if (root.classList.contains('nav-open') && !e.target.closest('.fl-sidebar,.fl-menu-btn')) root.classList.remove('nav-open'); });
  }

  function themeMenu(anchor) {
    const set = (t) => { FL.state.theme = t; applyTheme(); };
    FL.popover(anchor, [h('div', { class: 'lbl' }, 'Theme'),
      FL.menuItem('Light', () => set('light'), { icon: 'sun', checked: FL.state.theme === 'light' }),
      FL.menuItem('Dark', () => set('dark'), { icon: 'moon', checked: FL.state.theme === 'dark' }),
      FL.menuItem('Match Claude', () => set('system'), { icon: 'monitor', checked: FL.state.theme === 'system' })]);
  }
  function hostIsDark() {
    const p = h('div', { style: 'position:absolute;visibility:hidden;background:var(--color-bg, Canvas)' });
    document.body.append(p);
    const c = getComputedStyle(p).backgroundColor; p.remove();
    const m = c.match(/\d+(\.\d+)?/g);
    if (!m) return window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches;
    const [r, g, b] = m.map(Number);
    return (0.2126 * r + 0.7152 * g + 0.0722 * b) < 128;
  }
  function applyTheme() {
    const dark = FL.state.theme === 'dark' || (FL.state.theme === 'system' && hostIsDark());
    const was = FL.root.classList.contains('dark');
    FL.root.classList.toggle('dark', dark);
    const b = FL.$('#theme-btn'); if (b) b.replaceChildren(FL.icon(FL.state.theme === 'light' ? 'sun' : FL.state.theme === 'dark' ? 'moon' : 'monitor', 16));
    if (was !== dark) FL.redraw();
  }
  FL.applyTheme = applyTheme;

  function notifMenu(anchor) {
    const ns = FL.rows('notifications');
    const ic = { critical: 'alert', error: 'xc', warn: 'alert', info: 'info' };
    const target = { critical: 'forecast', error: 'bank-accounts', info: 'historical-import', warn: 'cheques' };
    const pop = FL.popover(anchor, [h('div', { class: 'hd' }, 'Notifications', h('span', { class: 'muted', style: 'font-weight:400;font-size:12px' }, ns.length + ' new')),
      h('div', { 'data-source': 'notifications' }, ns.map((n) => h('div', { class: 'n ' + n.level, role: 'menuitem', tabindex: '0', style: 'cursor:pointer', on: { click: () => { FL.closePop(); FL.go(target[n.level]); } } },
        FL.icon(ic[n.level], 16), h('div', { class: 't' }, n.title), h('div', { class: 'w' }, n.body + ' · ' + FL.fmt.dt(n.at)))))], { cls: 'fl-notif', role: 'dialog', focus: false });
    return pop;
  }
  function userMenu(anchor) {
    const roles = ['Finance Controller', 'Company Admin', 'Cashier'];
    FL.popover(anchor, [
      h('div', { class: 'lbl' }, 'nadeesha@lotuslane.lk'),
      h('hr'),
      h('div', { class: 'lbl' }, 'Preview as role'),
      ...roles.map((r) => FL.menuItem(r, () => { FL.state.role = r; FL.$('#hdr-role').textContent = r; FL.updateNav(); FL.go(FL.access(FL.state.page) ? FL.state.page : 'dashboard'); }, { checked: FL.state.role === r })),
      h('hr'),
      FL.menuItem('Users & Settings', () => FL.go('users-settings'), { icon: 'users' }),
      FL.menuItem('Sign out', () => FL.signOut(), { icon: 'logout' }),
    ]);
  }

  FL.updateNav = () => {
    const k = FL.one('kpi');
    for (const a of FL.$$('.fl-nav a')) {
      const id = a.dataset.page; const acc = FL.access(id);
      a.classList.toggle('locked', !acc);
      FL.$$('.fl-count,.fl-lock', a).forEach((x) => x.remove());
      if (!acc) { const l = FL.icon('lock', 13); l.classList.add('fl-lock'); a.append(l); continue; }
      if (k && id === 'reconciliation' && k.review_count + k.unmatched_count) a.append(h('span', { class: 'fl-count' }, k.review_count + k.unmatched_count));
      if (k && id === 'forecast' && k.shortage_count) a.append(h('span', { class: 'fl-count crit', title: 'Projected cash shortages' }, k.shortage_count));
      if (id === 'historical-import' && FL.rows('import_history').some((r) => r.status === 'Awaiting approval')) a.append(h('span', { class: 'fl-count' }, '1'));
    }
    const pip = FL.$('#bell-pip'); if (pip) pip.hidden = !FL.rows('notifications').length;
    // sidebar footer: sync status
    const foot = FL.$('#side-foot');
    if (foot && k) {
      foot.replaceChildren(
        h('div', { class: 'row' }, h('span', { class: 'fl-status ' + (k.accounts_error ? 'err' : 'ok') }, h('span', { class: 'fl-dot' }), h('span', null, h('span', { 'data-source': 'kpi', 'data-field': 'accounts_synced', 'data-row': '0' }, k.accounts_synced), ' of ', h('span', { 'data-source': 'kpi', 'data-field': 'accounts_total', 'data-row': '0' }, k.accounts_total), ' banks synced'))),
        h('div', { class: 'row' }, FL.icon('clock', 13), h('span', null, 'Last sync ', h('span', { 'data-source': 'kpi', 'data-field': 'last_sync', 'data-row': '0' }, FL.fmt.dt(k.last_sync)))));
    }
  };

  FL.go = (id, opts = {}) => {
    if (!FL.state.signedIn) return;
    if (!FL.pages[id]) id = 'dashboard';
    FL.state.page = id;
    FL.root.classList.remove('nav-open');
    for (const a of FL.$$('.fl-nav a')) a.setAttribute('aria-current', a.dataset.page === id ? 'page' : 'false');
    const nav = FL.NAV.find((n) => n.id === id);
    const grp = FL.NAV.slice(0, FL.NAV.indexOf(nav)).reverse().find((n) => n.group);
    FL.$('#crumb').replaceChildren(grp.group, ' / ', h('b', null, nav.label));
    FL.$$('.fl-page').forEach((p) => (p.hidden = true));
    const acc = FL.access(id);
    if (!acc) {
      const p = FL.$('#no-access'); p.hidden = false;
      p.replaceChildren(FL.card(null, null, h('div', { style: 'display:grid;justify-items:center;gap:10px;padding:48px 16px;text-align:center' }, FL.icon('lock', 28),
        h('h2', { style: 'font-size:17px;font-weight:600' }, `${nav.label} isn't available for the ${FL.state.role} role`),
        h('p', { class: 'muted', style: 'max-width:52ch' }, 'Ask a Company Admin to change your role if you need access. Cashiers can view the dashboard, daily sales, bank balances and cheque payments.'),
        FL.btn('Back to Dashboard', { variant: 'outline', onClick: () => FL.go('dashboard') }))));
    } else {
      const el = FL.$('#' + id); el.hidden = false;
      const def = FL.pages[id];
      if (!el.dataset.built) { el.dataset.built = '1'; def.init(el); }
      def.draw(el, acc);
    }
    dash.setLink(id);
    if (!opts.keepScroll) { const r = FL.root.getBoundingClientRect(); if (r.top < 0) FL.root.scrollIntoView({ block: 'start' }); }
  };
  FL.redraw = () => {
    if (!FL.state.signedIn) return;
    FL.updateNav();
    const company = FL.rows('settings').find((s) => s.key === 'company_name');
    FL.$('#hdr-company').textContent = company ? company.value : '—';
    const id = FL.state.page; const acc = FL.access(id);
    if (acc && FL.pages[id]) { const el = FL.$('#' + id); if (el.dataset.built) FL.pages[id].draw(el, acc); }
  };

  FL.signOut = () => { FL.state.signedIn = false; FL.shellEl.hidden = true; FL.loginEl.hidden = false; FL.renderLogin(); dash.setLink('login'); };
  FL.signIn = () => { FL.state.signedIn = true; FL.loginEl.hidden = true; FL.shellEl.hidden = false; FL.go('dashboard'); };

  FL.renderLogin = () => {
    const el = FL.loginEl;
    if (el.dataset.built) return;
    el.dataset.built = '1';
    const email = h('input', { class: 'fl-input', type: 'email', value: 'nadeesha@lotuslane.lk', autocomplete: 'username' });
    const pw = h('input', { class: 'fl-input', type: 'password', value: 'flowledger-demo', autocomplete: 'current-password', style: 'padding-right:40px' });
    const eye = FL.btn('', { variant: 'ghost', size: 'sm', icon: 'eye', aria: 'Show password' });
    eye.classList.add('pw-toggle');
    eye.addEventListener('click', () => { const show = pw.type === 'password'; pw.type = show ? 'text' : 'password'; eye.replaceChildren(FL.icon(show ? 'eyeoff' : 'eye', 14)); eye.setAttribute('aria-label', show ? 'Hide password' : 'Show password'); });
    const fEmail = FL.field('Work email', email);
    const fPw = FL.field('Password', pw);
    const grp = h('div', { class: 'fl-input-group' });
    fPw.replaceChild(grp, pw); grp.append(pw, eye);
    const forgot = h('button', { type: 'button', class: 'fl-link', style: 'font-size:13px' }, 'Forgot password?');
    const lblRow = h('div', { style: 'display:flex;justify-content:space-between;align-items:center' }, fPw.querySelector('label'), forgot);
    fPw.prepend(lblRow);
    const msg = h('div', { class: 'fl-alert info', hidden: true, role: 'status' }, FL.icon('info', 18), h('div', { class: 't' }, 'Check your email'), h('div', { class: 'd' }, 'If an account exists for this address, we\'ve sent a reset link. It expires in 30 minutes.'));
    forgot.addEventListener('click', () => { msg.hidden = false; });
    const go = () => {
      let ok = true;
      FL.fieldErr(fEmail, /.+@.+\..+/.test(email.value) ? null : (ok = false, 'Enter a valid work email address.'));
      FL.fieldErr(fPw, pw.value.length >= 8 ? null : (ok = false, 'Password must be at least 8 characters.'));
      if (ok) FL.signIn();
    };
    [email, pw].forEach((i) => i.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); }));
    const submit = FL.btn('Sign in', { onClick: go }); submit.style.width = '100%';
    el.append(
      h('div', { class: 'fl-login-side' },
        h('div', { class: 'fl-brand', style: 'border:0;padding:0;height:auto' }, h('div', { class: 'fl-logo' }, FL.icon('flow', 16)), h('div', null, h('div', { class: 'fl-brand-name' }, 'FlowLedger'), h('div', { class: 'fl-brand-sub' }, 'Cash position & forecast'))),
        h('div', { style: 'display:grid;gap:24px' }, h('h2', null, 'Know your cash position before the bank does.'),
          h('ul', null,
            h('li', null, FL.icon('bank', 18), h('div', null, h('b', null, 'Confirmed bank balances'), 'Read-only sync with your company accounts, refreshed through the day.')),
            h('li', null, FL.icon('cheque', 18), h('div', null, h('b', null, 'Every cheque tracked'), 'From issue to clearing, with bounced and outstanding cheques flagged.')),
            h('li', null, FL.icon('forecast', 18), h('div', null, h('b', null, '13-week cash-flow forecast'), 'Daily balances that warn you before an account goes negative.')))),
        h('div', { class: 'muted', style: 'font-size:12px' }, '© 2026 FlowLedger · Colombo, Sri Lanka')),
      h('div', { class: 'fl-login-form' }, h('div', { class: 'box' },
        h('div', { style: 'display:grid;gap:6px' }, h('h1', null, 'Sign in'), h('p', { class: 'muted' }, 'Use the email your company admin invited.')),
        msg, fEmail, fPw,
        h('label', { class: 'fl-check' }, h('input', { type: 'checkbox', checked: true }), 'Keep me signed in on this device'),
        submit,
        h('div', { class: 'fl-sep' }),
        h('p', { class: 'fl-proto-note' }, FL.icon('shield', 12), ' Two-step verification is required for Finance Controllers and Admins. Bank connections are read-only.'))));
  };

  // ------------------------------------------------------------ boot (called once the pages are registered)
  FL.boot = () => {
  buildShell();
  FL.applyTheme();
  const start = (location.hash || '').slice(1);
  if (start === 'login') FL.signOut();
  dash.onData(() => {
    if (!FL.booted) {
      FL.booted = true;
      FL.go(FL.pages[start] ? start : 'dashboard', { keepScroll: true });
    }
    if (FL.state.theme === 'system') { const was = FL.root.classList.contains('dark'); const now = hostIsDark(); if (was !== now) { FL.root.classList.toggle('dark', now); } }
    FL.redraw();
  });
  if (window.matchMedia) matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => { if (FL.state.theme === 'system') applyTheme(); });
  };
})();
