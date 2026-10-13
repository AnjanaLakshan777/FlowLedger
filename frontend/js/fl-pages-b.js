/* FlowLedger — screens: Cash-Flow Forecast, Forecast vs Actual, Scenario Analysis, Users & Settings, Audit History */
(function () {
  const FL = window.FL, h = FL.h, f = FL.fmt;
  const mark = (source, field, where, text, extra = {}) => h('span', Object.assign({ 'data-source': source, 'data-field': field, 'data-where': where }, extra), text);
  const kmark = (field, text, extra = {}) => h('span', Object.assign({ 'data-source': 'kpi', 'data-field': field, 'data-row': '0' }, extra), text);

  // ================================================================ CASH-FLOW FORECAST
  const fcState = { view: 'weekly', week: 'all', onlyShort: false };
  FL.register('forecast', {
    init(el) {
      const seg = FL.seg([['weekly', 'Weekly summary'], ['daily', 'Daily detail']], fcState.view, (v) => { fcState.view = v; FL.pages.forecast.draw(el, FL.access('forecast')); });
      el.append(FL.pageHead('Cash-Flow Forecast', '13 weeks, Monday to Sunday. Every day is checked — a week can end positive and still go negative in the middle.', [seg, FL.btn('Export', { variant: 'outline', icon: 'download', onClick: () => FL.toast('Export prepared', 'Weekly summary as Excel (prototype)') })]),
        h('div', { id: 'fc-basis' }), h('div', { id: 'fc-short' }), h('div', { id: 'fc-chart' }), h('div', { id: 'fc-table' }));
      el._weekly = FL.table({ source: 'forecast_weekly', key: 'week', cap: false, id: 'fc-weekly', sort: 'week', dir: 'asc', rowCls: (r) => (r.shortage_days > 0 ? 'crit' : ''), cols: [
        { field: 'week', label: 'Week', num: false, sortVal: (r) => +r.week, render: (r) => h('span', { class: 'nowrap' }, h('b', null, 'W' + r.week), ' ', h('span', { class: 'muted' }, f.range(r.week_start, r.week_end))) },
        { field: 'opening', label: 'Opening', num: true, fmt: f.amt },
        { field: 'cash_in', label: 'Cash sales', num: true, fmt: f.amt },
        { field: 'card_in', label: 'Card payments', num: true, fmt: f.amt },
        { field: 'cheque_out', label: 'Cheque outflows', num: true, fmt: (v) => (+v ? '−' + f.amt(v) : '0.00') },
        { field: 'closing', label: 'Expected closing', num: true, render: (r) => h('span', { class: 'strong' + (+r.closing < 0 ? ' neg' : '') }, f.amt(r.closing)) },
        { field: 'low', label: 'Likely range', num: true, render: (r) => h('span', { class: 'muted' }, f.m(r.low) + ' – ' + f.m(r.high)) },
        { field: 'lowest_balance', label: 'Lowest point', num: true, render: (r) => h('span', null, h('span', { class: +r.lowest_balance < 0 ? 'neg strong' : '' }, f.amt(r.lowest_balance)), h('span', { class: 'sub' }, f.dday(r.lowest_date))) },
        { field: 'shortage_days', label: 'Cash check', render: (r) => (+r.shortage_days > 0 ? h('span', { class: 'fl-badge destructive' }, FL.icon('alert', 12), `Shortage · ${r.shortage_days} ${r.shortage_days > 1 ? 'days' : 'day'}`) : h('span', { class: 'fl-status ok' }, h('span', { class: 'fl-dot' }), 'Positive all week')) },
      ], foot: (rs) => rs.length ? [h('span', null, '13 weeks · amounts in LKR'), h('span', { class: 'tnum' }, `Inflows ${f.lkr0(d3.sum(rs, (r) => +r.inflow))} · Outflows ${f.lkr0(d3.sum(rs, (r) => +r.cheque_out))}`)] : [] });
      el._daily = FL.table({ source: 'forecast_daily', key: 'date', id: 'fc-daily', sort: null, groupBy: (r) => {
        const w = FL.rows('forecast_weekly').find((x) => +x.week === +r.week); return w ? `Week ${r.week} · ${f.range(w.week_start, w.week_end)}` : 'Week ' + r.week; },
        rowCls: (r) => (+r.closing < 0 ? 'crit' : ''), cols: [
          { field: 'date', label: 'Date', sortable: false, render: (r) => h('span', { class: 'nowrap strong' }, f.dday(r.date)) },
          { field: 'opening', label: 'Opening', num: true, sortable: false, fmt: f.amt },
          { field: 'cash_in', label: 'Cash sales', num: true, sortable: false, fmt: f.amt },
          { field: 'card_in', label: 'Card payments', num: true, sortable: false, fmt: f.amt },
          { field: 'cheque_out', label: 'Cheques', num: true, sortable: false, render: (r) => h('span', null, +r.cheque_out ? '−' + f.amt(r.cheque_out) : h('span', { class: 'muted' }, '—'), +r.cheques ? h('span', { class: 'sub' }, r.cheques + (r.cheques > 1 ? ' cheques' : ' cheque')) : null) },
          { field: 'closing', label: 'Expected closing', num: true, sortable: false, render: (r) => h('span', { class: 'strong' + (+r.closing < 0 ? ' neg' : '') }, f.amt(r.closing)) },
          { field: 'low', label: 'Likely range', num: true, sortable: false, render: (r) => h('span', { class: 'muted' }, f.m(r.low) + ' – ' + f.m(r.high)) },
          { field: 'top_outflows', label: 'Largest outflows', sortable: false, wrap: true, render: (r) => h('span', { class: 'muted', style: 'font-size:12px' }, r.top_outflows || '') },
        ] });
    },
    draw(el) {
      const wk = FL.rows('forecast_weekly'), days = FL.rows('forecast_daily'), sh = FL.rows('shortages'), k = FL.one('kpi');
      FL.$('#fc-basis', el).replaceChildren(h('div', { class: 'fl-grid kpis', id: 'fc-kpis' },
        h('div', { class: 'fl-card fl-kpi' }, h('div', { class: 'lbl' }, h('span', { class: 'fl-badge soft' }, 'Actual'), 'Opening balance'), h('div', { class: 'val' }, h('span', { class: 'cur' }, 'LKR'), k ? kmark('confirmed_balance', f.amt(k.confirmed_balance)) : '—'), h('div', { class: 'sub' }, 'Confirmed bank balance, ', k ? kmark('last_sync', f.dt(k.last_sync)) : '—')),
        h('div', { class: 'fl-card fl-kpi forecast' }, h('div', { class: 'lbl' }, h('span', { class: 'fl-badge dashed' }, 'Forecast'), 'Expected sales inflows'), h('div', { class: 'val' }, h('span', { class: 'cur' }, 'LKR'), k ? kmark('forecast_inflow', f.amt(k.forecast_inflow)) : '—'), h('div', { class: 'sub' }, 'Cash + card sales only · same-weekday average of the last 12 weeks')),
        h('div', { class: 'fl-card fl-kpi forecast' }, h('div', { class: 'lbl' }, h('span', { class: 'fl-badge dashed' }, 'Forecast'), 'Cheque outflows'), h('div', { class: 'val' }, h('span', { class: 'cur' }, 'LKR'), k ? kmark('forecast_outflow', f.amt(k.forecast_outflow)) : '—'), h('div', { class: 'sub' }, 'Only cheques entered by the Finance Controller')),
        h('div', { class: 'fl-card fl-kpi forecast' }, h('div', { class: 'lbl' }, h('span', { class: 'fl-badge dashed' }, 'Forecast'), 'Expected balance, 10 Jan'), h('div', { class: 'val' }, h('span', { class: 'cur' }, 'LKR'), k ? kmark('forecast_closing', f.amt(k.forecast_closing)) : '—'), h('div', { class: 'sub' }, 'End of week 13'))));
      FL.$('#fc-short', el).replaceChildren(sh.length ? FL.shortageCard(sh, { id: 'fc-shortages' }) : '');
      const chartBox = h('div', { class: 'fl-chart', 'data-source': 'forecast_daily', id: 'fc-balance-chart' });
      FL.$('#fc-chart', el).replaceChildren(FL.card('Expected daily closing balance', 'All accounts combined · shaded area is the likely range, which widens further out', [
        h('div', { class: 'fl-legend', style: 'margin-bottom:8px' }, h('span', null, h('i', { class: 'line', style: 'background:var(--chart-4)' }), 'Expected closing balance'), h('span', null, h('i', { class: 'band', style: 'background:var(--chart-2)' }), 'Likely range (80%)'), h('span', null, h('i', { style: 'background:var(--destructive);border-radius:99px' }), 'Below zero')),
        chartBox], { id: 'fc-chart-card' }));
      if (days.length) FL.balanceChart(chartBox, days, { height: 280 }); else chartBox.replaceChildren(FL.stateLine(['forecast_daily'], 280));
      const tb = FL.$('#fc-table', el);
      if (fcState.view === 'weekly') {
        el._weekly.draw(wk);
        tb.replaceChildren(FL.card('Weekly summary', 'Opening + sales inflows − cheque outflows = expected closing. Lowest point checks every day of the week.', wk.length ? el._weekly : FL.stateLine(['forecast_weekly'], 300), { id: 'fc-weekly-card' }));
      } else {
        const wsel = FL.select([['all', 'All 13 weeks']].concat(wk.map((w) => [String(w.week), `Week ${w.week} · ${f.range(w.week_start, w.week_end)}`])), fcState.week, { 'aria-label': 'Week' });
        wsel.addEventListener('change', () => { fcState.week = wsel.value; FL.pages.forecast.draw(el); });
        const only = h('input', { type: 'checkbox', checked: fcState.onlyShort });
        only.addEventListener('change', () => { fcState.onlyShort = only.checked; FL.pages.forecast.draw(el); });
        const rows = days.filter((d) => (fcState.week === 'all' || String(d.week) === fcState.week) && (!fcState.onlyShort || +d.closing < 0));
        el._daily.draw(rows);
        tb.replaceChildren(FL.card('Daily detail', 'Each day\'s expected movements. Shortage days are marked in red.', h('div', { style: 'display:grid;gap:12px' }, h('div', { class: 'fl-toolbar' }, wsel, h('label', { class: 'fl-check' }, only, 'Only shortage days')), days.length ? el._daily : FL.stateLine(['forecast_daily'], 300)), { id: 'fc-daily-card' }));
      }
    },
  });

  // ================================================================ FORECAST VS ACTUAL
  const fvaState = { from: null, to: null, preset: 'all' };
  FL.register('forecast-vs-actual', {
    init(el) {
      const from = FL.dateInput(null, (v) => { fvaState.from = v; fvaState.preset = 'custom'; seg.set('custom'); FL.pages['forecast-vs-actual'].draw(el); }, 'From');
      const to = FL.dateInput(null, (v) => { fvaState.to = v; fvaState.preset = 'custom'; seg.set('custom'); FL.pages['forecast-vs-actual'].draw(el); }, 'To');
      const seg = FL.seg([['4', 'Last 4 weeks'], ['8', 'Last 8 weeks'], ['all', 'All']], 'all', (v) => {
        fvaState.preset = v; const rows = FL.rows('forecast_vs_actual');
        if (v === 'all' || !rows.length) { fvaState.from = null; fvaState.to = null; }
        else { const last = rows[rows.length - 1]; fvaState.to = last.week_end; fvaState.from = rows[Math.max(0, rows.length - +v)].week_start; }
        from.value = fvaState.from || ''; to.value = fvaState.to || ''; FL.pages['forecast-vs-actual'].draw(el);
      });
      el.append(FL.pageHead('Forecast vs Actual', 'How close past forecasts were to what the bank actually confirmed. Use it to judge how much to trust the forecast.'),
        h('div', { class: 'fl-toolbar', id: 'fva-filters' }, seg, h('span', { class: 'muted', style: 'font-size:12.5px;margin-left:8px' }, 'Weeks starting'), from, h('span', { class: 'muted' }, '–'), to),
        h('div', { id: 'fva-kpis' }), h('div', { class: 'fl-grid two', id: 'fva-charts' }), h('div', { id: 'fva-table' }));
      el._t = FL.table({ source: 'forecast_vs_actual', key: 'week_start', cap: false, id: 'fva-weeks', sort: 'week_start', dir: 'desc', cols: [
        { field: 'week_start', label: 'Week', render: (r) => h('span', { class: 'nowrap strong' }, f.range(r.week_start, r.week_end)) },
        { field: 'forecast_in', label: 'Forecast in', num: true, fmt: f.amt },
        { field: 'actual_in', label: 'Actual in', num: true, fmt: f.amt, cls: () => 'strong' },
        { field: 'diff_in', label: 'Difference', num: true, render: (r) => h('span', null, f.signed(r.diff_in), h('span', { class: 'sub' }, f.pct(r.diff_in / r.forecast_in))) },
        { field: 'forecast_out', label: 'Forecast out', num: true, fmt: f.amt },
        { field: 'actual_out', label: 'Actual out', num: true, fmt: f.amt, cls: () => 'strong' },
        { field: 'diff_out', label: 'Difference', num: true, render: (r) => h('span', null, f.signed(r.diff_out), h('span', { class: 'sub' }, f.pct(r.diff_out / r.forecast_out))) },
        { field: 'net_diff', label: 'Effect on balance', num: true, render: (r) => h('span', { class: 'strong' }, f.signed(r.net_diff)) },
        { field: 'main_reason', label: 'Main reason', wrap: true, render: (r) => h('span', { class: 'muted' }, r.main_reason) },
      ] });
    },
    draw(el) {
      const all = FL.rows('forecast_vs_actual');
      const rows = all.filter((r) => (!fvaState.from || r.week_start >= fvaState.from) && (!fvaState.to || r.week_start <= fvaState.to));
      const s = (k) => d3.sum(rows, (r) => +r[k]);
      const fi = s('forecast_in'), ai = s('actual_in'), fo = s('forecast_out'), ao = s('actual_out');
      FL.$('#fva-kpis', el).replaceChildren(all.length ? h('div', { class: 'fl-stat-row', id: 'fva-totals', 'data-source': 'forecast_vs_actual' },
        h('div', null, h('span', null, 'Forecast inflows'), h('b', null, f.m(fi))), h('div', null, h('span', null, 'Actual inflows'), h('b', null, f.m(ai))), h('div', null, h('span', null, 'Inflow difference'), h('b', null, f.pct(fi ? (ai - fi) / fi : 0))),
        h('div', null, h('span', null, 'Forecast outflows'), h('b', null, f.m(fo))), h('div', null, h('span', null, 'Actual outflows'), h('b', null, f.m(ao))), h('div', null, h('span', null, 'Outflow difference'), h('b', null, f.pct(fo ? (ao - fo) / fo : 0))),
        h('div', null, h('span', null, 'Weeks'), h('b', null, rows.length))) : FL.stateLine(['forecast_vs_actual'], 64));
      const cats = rows.map((r) => f.dshort(r.week_start));
      const legend = h('div', { class: 'fl-legend', style: 'margin-bottom:8px' }, h('span', null, h('i', { style: 'background:var(--chart-2)' }), 'Forecast'), h('span', null, h('i', { style: 'background:var(--chart-5)' }), 'Actual (bank-confirmed)'));
      const c1 = h('div', { class: 'fl-chart', 'data-source': 'forecast_vs_actual', id: 'fva-in-chart' }), c2 = h('div', { class: 'fl-chart', 'data-source': 'forecast_vs_actual', id: 'fva-out-chart' });
      FL.$('#fva-charts', el).replaceChildren(FL.card('Inflows by week', 'Cash and card sales receipts', [legend, c1], { id: 'fva-in' }), FL.card('Outflows by week', 'Cheques cleared vs. scheduled', [legend.cloneNode(true), c2], { id: 'fva-out' }));
      const head = (i) => 'Week of ' + f.dday(rows[i].week_start);
      if (rows.length) {
        FL.barChart(c1, cats, [{ name: 'Forecast', color: 'var(--chart-2)', values: rows.map((r) => +r.forecast_in) }, { name: 'Actual', color: 'var(--chart-5)', values: rows.map((r) => +r.actual_in) }], { height: 220, tipHead: head, aria: 'Forecast vs actual inflows' });
        FL.barChart(c2, cats, [{ name: 'Forecast', color: 'var(--chart-2)', values: rows.map((r) => +r.forecast_out) }, { name: 'Actual', color: 'var(--chart-5)', values: rows.map((r) => +r.actual_out) }], { height: 220, tipHead: head, aria: 'Forecast vs actual outflows' });
      } else { c1.replaceChildren(FL.stateLine(['forecast_vs_actual'], 220)); c2.replaceChildren(FL.stateLine(['forecast_vs_actual'], 220)); }
      el._t.draw(rows);
      FL.$('#fva-table', el).replaceChildren(FL.card('Weekly comparison', 'Positive difference = more than forecast. Amounts in LKR.', el._t, { id: 'fva-table-card' }));
    },
  });

  // ================================================================ SCENARIO ANALYSIS
  const scState = { delayOn: true, day: null, part: 'card', delay: 5, expOn: false, expDesc: 'Emergency generator repair', expAmt: 3500000, expDate: '2026-10-21' };
  function runs(series) {
    const out = []; let cur = null;
    series.forEach((d) => { if (d.v < 0) { if (!cur) cur = { start: d.date, end: d.date, min: d.v, minDate: d.date, n: 0 }; cur.end = d.date; cur.n++; if (d.v < cur.min) { cur.min = d.v; cur.minDate = d.date; } } else if (cur) { out.push(cur); cur = null; } });
    if (cur) out.push(cur); return out;
  }
  FL.register('scenarios', {
    init(el) {
      el.append(FL.pageHead('Scenario Analysis', 'Test "what if" changes against the current forecast. Scenarios are not saved and never change the real forecast.'),
        h('div', { class: 'fl-grid side-l' }, h('div', { id: 'sc-controls' }), h('div', { id: 'sc-results', style: 'display:grid;gap:16px;align-content:start' })));
      const rerun = () => FL.pages.scenarios.draw(el, FL.access('scenarios'));
      // delayed receipt
      const delayOn = h('input', { type: 'checkbox', checked: scState.delayOn }); delayOn.addEventListener('change', () => { scState.delayOn = delayOn.checked; rerun(); });
      const daySel = FL.select([], null, { 'aria-label': 'Expected receipt' }); daySel.addEventListener('change', () => { scState.day = daySel.value; rerun(); });
      const part = FL.seg([['card', 'Card payments'], ['cash', 'Cash sales'], ['both', 'Both']], scState.part, (v) => { scState.part = v; rerun(); });
      const delay = FL.select([1, 2, 3, 5, 7, 10, 14].map((n) => [String(n), n + (n > 1 ? ' days' : ' day')]), String(scState.delay), { 'aria-label': 'Delay' }); delay.addEventListener('change', () => { scState.delay = +delay.value; rerun(); });
      // expense
      const expOn = h('input', { type: 'checkbox', checked: scState.expOn }); expOn.addEventListener('change', () => { scState.expOn = expOn.checked; rerun(); });
      const desc = h('input', { class: 'fl-input', value: scState.expDesc }); desc.addEventListener('input', () => { scState.expDesc = desc.value; });
      const amt = FL.amountInput({ value: '3,500,000.00', 'aria-label': 'Expense amount' }); amt.input.addEventListener('change', () => { const v = FL.parseAmt(amt.input.value); scState.expAmt = v > 0 ? v : 0; rerun(); });
      const edate = h('input', { class: 'fl-input', type: 'date', min: '2026-10-12', max: '2027-01-10', value: scState.expDate }); edate.addEventListener('change', () => { if (edate.validity.valid && edate.value) { scState.expDate = edate.value; rerun(); } });
      el._daySel = daySel;
      const fAmt = FL.field('Amount', amt); fAmt.querySelector('label').htmlFor = amt.input.id = 'sc-amt';
      FL.$('#sc-controls', el).append(FL.card('Scenario', 'Turn on one or both changes', h('div', { style: 'display:grid;gap:16px' },
        h('div', { style: 'display:grid;gap:12px' }, h('label', { class: 'fl-check strong' }, delayOn, 'Delay an expected receipt'),
          FL.field('Expected receipt', daySel, { help: 'Expected sales inflow for that day' }), h('div', { class: 'fl-field' }, h('span', { class: 'fl-label' }, 'Which part'), part), FL.field('Delay by', delay)),
        h('hr', { class: 'fl-sep' }),
        h('div', { style: 'display:grid;gap:12px' }, h('label', { class: 'fl-check strong' }, expOn, 'Add a future expense'),
          FL.field('Description', desc), h('div', { class: 'fl-form-grid' }, fAmt, FL.field('Date it leaves the account', edate))),
        h('hr', { class: 'fl-sep' }),
        h('div', { style: 'display:flex;gap:8px' }, FL.btn('Reset', { variant: 'outline', onClick: () => { Object.assign(scState, { delayOn: true, part: 'card', delay: 5, expOn: false, day: null }); delayOn.checked = true; expOn.checked = false; part.set('card'); delay.value = '5'; rerun(); } }))), { id: 'sc-form' }));
    },
    draw(el) {
      const days = FL.rows('forecast_daily');
      const res = FL.$('#sc-results', el);
      if (!days.length) { res.replaceChildren(FL.card('Result', null, FL.stateLine(['forecast_daily'], 300))); return; }
      const opts = days.slice(0, 35).filter((d) => +d.inflow > 0);
      if (el._daySel.options.length !== opts.length) { el._daySel.replaceChildren(...opts.map((d) => new Option(`${f.dday(d.date)} — ${f.lkr0(d.inflow)}`, d.date))); }
      if (!scState.day) scState.day = (opts.find((d) => d.date === '2026-10-24') || opts[0]).date;
      el._daySel.value = scState.day;
      // apply changes as daily deltas
      const idx = Object.fromEntries(days.map((d, i) => [d.date, i]));
      const delta = new Array(days.length).fill(0), dOut = new Array(days.length).fill(0);
      const notes = [];
      if (scState.delayOn) {
        const d = days[idx[scState.day]];
        const amt = scState.part === 'card' ? +d.card_in : scState.part === 'cash' ? +d.cash_in : +d.inflow;
        const to = FL.addDays(d.date, scState.delay);
        delta[idx[d.date]] -= amt; if (idx[to] != null) delta[idx[to]] += amt;
        notes.push(`${f.lkr0(amt)} of ${scState.part === 'both' ? 'sales' : scState.part === 'card' ? 'card payments' : 'cash sales'} expected ${f.dday(d.date)} arrives ${f.dday(to)}`);
      }
      if (scState.expOn && scState.expAmt > 0 && idx[scState.expDate] != null) { dOut[idx[scState.expDate]] -= scState.expAmt; notes.push(`${scState.expDesc || 'Expense'}: −${f.lkr0(scState.expAmt)} on ${f.dday(scState.expDate)}`); }
      let cum = 0;
      const sc = days.map((d, i) => { cum += delta[i] + dOut[i]; const c = +d.closing + cum; return Object.assign({}, d, { alt: +d.closing, closing: c, low: c, high: c, inflow: +d.inflow + delta[i], cheque_out: +d.cheque_out - dOut[i] }); });
      const baseRuns = runs(days.map((d) => ({ date: d.date, v: +d.closing }))), scRuns = runs(sc.map((d) => ({ date: d.date, v: d.closing })));
      const lowB = d3.least(days, (d) => +d.closing), lowS = d3.least(sc, (d) => d.closing);
      const sumN = (rs) => d3.sum(rs, (r) => r.n);
      const col = (title, low, lowDate, rs, cls) => h('div', null, h('h4', null, title),
        h('div', null, h('div', { class: 'muted', style: 'font-size:12px' }, 'Lowest balance'), h('div', { class: 'amt' + (low < 0 ? ' neg' : '') }, f.lkr0(low)), h('div', { class: 'muted', style: 'font-size:12px' }, 'on ' + f.dday(lowDate))),
        h('dl', { class: 'fl-dl' }, h('dt', null, 'Shortage periods'), h('dd', null, rs.length), h('dt', null, 'Days below zero'), h('dd', { class: sumN(rs) ? 'neg' : null }, sumN(rs)), h('dt', null, 'First shortage'), h('dd', null, rs[0] ? f.dday(rs[0].start) : 'None')));
      const newRuns = scRuns.filter((r) => !baseRuns.some((b) => b.start <= r.end && r.start <= b.end));
      const verdict = !notes.length ? 'Turn on a change to see its effect.' : sumN(scRuns) > sumN(baseRuns) ? `This scenario adds ${sumN(scRuns) - sumN(baseRuns)} day(s) below zero${newRuns.length ? ', including a new shortage from ' + f.dday(newRuns[0].start) : ''}.` : sumN(scRuns) < sumN(baseRuns) ? 'This scenario reduces days below zero.' : `No change to shortage days. Lowest balance moves by ${f.lkr0(lowS.closing - +lowB.closing)}.`;
      const chart = h('div', { class: 'fl-chart', 'data-source': 'forecast_daily', id: 'sc-chart' });
      const tbl = h('table', { class: 'fl-table compact', id: 'sc-runs' }, h('thead', null, h('tr', null, h('th', null, 'Shortage period'), h('th', { class: 'num' }, 'Days'), h('th', { class: 'num' }, 'Worst balance'), h('th', null, 'Compared with baseline'))),
        h('tbody', null, scRuns.length ? scRuns.map((r) => { const b = baseRuns.find((x) => x.start <= r.end && r.start <= x.end);
          return h('tr', { class: 'crit' }, h('td', { class: 'strong' }, r.start === r.end ? f.dday(r.start) : f.dday(r.start) + ' – ' + f.dday(r.end)), h('td', { class: 'num' }, r.n), h('td', { class: 'num neg' }, f.lkr0(r.min)), h('td', { class: 'muted' }, b ? (Math.abs(r.min - b.min) < 1 ? 'Unchanged' : `Worse by ${f.lkr0(b.min - r.min)}`.replace('Worse by −', 'Better by ')) : h('span', { class: 'fl-badge destructive-soft' }, 'New shortage'))); })
          : h('tr', null, h('td', { colspan: 4, class: 'fl-table-empty' }, 'No shortages in this scenario.'))));
      res.replaceChildren(
        h('div', { class: notes.length && sumN(scRuns) > sumN(baseRuns) ? 'fl-alert destructive' : 'fl-alert info', id: 'sc-verdict' }, FL.icon(notes.length && sumN(scRuns) > sumN(baseRuns) ? 'alert' : 'info', 18), h('div', { class: 't' }, verdict), h('div', { class: 'd' }, notes.length ? notes.join(' · ') : 'Baseline is the current forecast.')),
        FL.card('Effect on projected cash', 'Baseline forecast vs. this scenario, all accounts', h('div', { class: 'fl-compare', 'data-source': 'forecast_daily' }, col('Baseline', +lowB.closing, lowB.date, baseRuns), col('Scenario', lowS.closing, lowS.date, scRuns)), { id: 'sc-compare' }),
        FL.card('Projected daily balance', null, [h('div', { class: 'fl-legend', style: 'margin-bottom:8px' }, h('span', null, h('i', { class: 'line', style: 'background:var(--chart-4)' }), 'Scenario'), h('span', null, h('i', { class: 'line', style: 'background:var(--muted-foreground)' }), 'Baseline (dashed)')), chart], { id: 'sc-chart-card' }),
        FL.card('Cash-shortage dates in this scenario', null, h('div', { class: 'fl-table-wrap' }, tbl), { id: 'sc-runs-card' }));
      FL.balanceChart(chart, sc, { height: 240, alt: true, noBand: true, aria: 'Scenario vs baseline daily balance' });
    },
  });

  // ================================================================ USERS & SETTINGS
  const usState = { tab: 'users', q: '', local: {} };
  FL.register('users-settings', {
    init(el) {
      el.append(FL.pageHead('Users & Settings', 'Who can use FlowLedger, what each role can do, and company-wide settings.', h('span', { id: 'us-actions' })),
        h('div', { class: 'fl-tabs', role: 'tablist', id: 'us-tabs' }), h('div', { id: 'us-body' }));
      el._users = FL.table({ source: 'users', key: 'user_id', cap: false, id: 'us-users', sort: 'name', dir: 'asc', cols: [
        { field: 'name', label: 'Name', render: (r) => h('span', { style: 'display:flex;gap:10px;align-items:center' }, h('span', { class: 'fl-avatar' }, FL.initials(r.name)), h('span', null, h('span', { class: 'strong' }, r.name), h('span', { class: 'sub' }, r.email))) },
        { field: 'role', label: 'Role', render: (r) => h('span', { class: 'fl-badge ' + (r.role === 'Cashier' ? 'outline' : 'secondary') }, usState.local[r.user_id]?.role || r.role) },
        { field: 'status', label: 'Status', render: (r) => FL.badge(usState.local[r.user_id]?.status || r.status) },
        { field: 'mfa', label: 'Two-step', render: (r) => h('span', { class: 'fl-status ' + (r.mfa === 'On' ? 'ok' : 'idle') }, h('span', { class: 'fl-dot' }), r.mfa) },
        { field: 'last_active', label: 'Last active', render: (r) => h('span', { class: 'muted nowrap' }, r.last_active ? f.dt(r.last_active) : 'Never') },
        { label: '', render: (r) => FL.state.role === 'Company Admin' ? FL.btn('', { variant: 'ghost', size: 'sm', icon: 'more', aria: 'Actions for ' + r.name, onClick: (e) => userActions(r, e.currentTarget, el) }) : null },
      ] });
    },
    draw(el, acc) {
      const isAdmin = FL.state.role === 'Company Admin';
      FL.$('#us-actions', el).replaceChildren(usState.tab === 'users' ? FL.btn('Invite user', { icon: 'plus', disabled: !isAdmin, onClick: (e) => invite(e.currentTarget) }) : '');
      const tabs = [['users', 'Users'], ['roles', 'Roles & permissions'], ['company', 'Company'], ['banks', 'Bank connections']];
      FL.$('#us-tabs', el).replaceChildren(...tabs.map(([id, l]) => h('button', { type: 'button', role: 'tab', 'aria-selected': usState.tab === id ? 'true' : 'false', id: 'us-tab-' + id, on: { click: () => { usState.tab = id; FL.pages['users-settings'].draw(el, acc); } } }, l)));
      const body = FL.$('#us-body', el);
      const ro = !isAdmin ? h('div', { class: 'fl-alert', style: 'margin-bottom:16px' }, FL.icon('lock', 18), h('div', { class: 't' }, 'View only'), h('div', { class: 'd' }, 'Only a Company Admin can invite users, change roles or edit settings. Preview as Company Admin from the account menu.')) : null;
      if (usState.tab === 'users') {
        const rows = FL.rows('users').filter((u) => !usState.q || (u.name + ' ' + u.email + ' ' + u.role).toLowerCase().includes(usState.q));
        el._users.draw(rows);
        if (!el._uq) el._uq = FL.search('Search name, email or role', (q) => { usState.q = q; FL.pages['users-settings'].draw(el, acc); });
        body.replaceChildren(ro || '', FL.card(null, null, h('div', { style: 'display:grid;gap:12px' }, h('div', { class: 'fl-toolbar' }, el._uq), FL.rows('users').length ? el._users : FL.stateLine(['users'], 240)), { id: 'us-users-card' }));
      } else if (usState.tab === 'roles') {
        const P = [['View dashboard & balances', 'v', 'v', 'v'], ['Enter & edit daily sales', 'e', '', ''], ['Approve historical import', 'e', '', ''], ['Upload historical file', 'e', 'e', ''], ['Add & update cheque payments', 'e', 'e', 'v'],
          ['Confirm reconciliation matches', 'e', '', ''], ['View forecast & scenarios', 'v', 'v', ''], ['Manage users & roles', '', 'e', ''], ['Connect bank accounts', '', 'e', ''], ['View audit history (read-only)', 'v', 'v', '']];
        const cell = (x) => h('td', { class: 'c' }, x === 'e' ? h('span', { class: 'yes', title: 'Can do' }, FL.icon('check', 16)) : x === 'v' ? h('span', { class: 'muted', style: 'font-size:12px' }, 'View') : h('span', { class: 'muted' }, '—'));
        body.replaceChildren(FL.card('What each role can do', 'Roles are set per user by a Company Admin', h('div', { class: 'fl-table-wrap' }, h('table', { class: 'fl-table fl-perm', id: 'us-perm' },
          h('thead', null, h('tr', null, h('th', null, 'Permission'), h('th', { class: 'c' }, 'Finance Controller'), h('th', { class: 'c' }, 'Company Admin'), h('th', { class: 'c' }, 'Cashier'))),
          h('tbody', null, P.map(([l, a, b, c]) => h('tr', null, h('td', null, l), cell(a), cell(b), cell(c)))))), { id: 'us-roles' }));
      } else if (usState.tab === 'company') {
        const S = FL.rows('settings');
        const sections = [...new Set(S.map((s) => s.section))];
        body.replaceChildren(ro || '', h('div', { class: 'fl-grid two', 'data-source': 'settings' }, sections.map((sec) => FL.card(sec, null, h('dl', { class: 'fl-dl' }, S.filter((s) => s.section === sec).map((s) => [h('dt', null, s.label), h('dd', null, mark('settings', 'value', 'key=' + s.key, s.value))])),
          { id: 'us-set-' + sec.toLowerCase(), actions: FL.btn('Edit', { variant: 'outline', size: 'sm', icon: 'pen', disabled: !isAdmin, onClick: (e) => editSettings(sec, S.filter((s) => s.section === sec), e.currentTarget) }) }))));
      } else {
        const t = FL.table({ source: 'accounts', key: 'account_id', cap: false, id: 'us-banks', cols: [
          { field: 'bank', label: 'Bank', render: (r) => h('span', null, h('span', { class: 'strong' }, r.bank), h('span', { class: 'sub mono' }, r.account_masked)) },
          { field: 'connection', label: 'Connection' }, { field: 'sync_frequency', label: 'Sync' },
          { field: 'consent_expires', label: 'Access expires', render: (r) => h('span', { class: r.sync_status !== 'Synced' ? 'neg' : null }, r.consent_expires ? f.date(r.consent_expires) : '—') },
          { field: 'sync_status', label: 'Status', render: (r) => (r.sync_status === 'Synced' ? FL.badge('Synced') : h('span', { class: 'fl-badge destructive-soft' }, FL.icon('alert', 12), 'Access expired')) },
          { label: '', render: (r) => FL.btn(r.sync_status === 'Synced' ? 'Manage' : 'Renew', { variant: r.sync_status === 'Synced' ? 'outline' : 'destructive', size: 'sm', disabled: !isAdmin }) },
        ] });
        t.draw(FL.rows('accounts'));
        body.replaceChildren(ro || '', FL.card('Bank integrations', 'Read-only access. FlowLedger cannot make payments or move money.', t, { id: 'us-bank-card', actions: FL.btn('Connect bank', { icon: 'plus', size: 'sm', disabled: !isAdmin }) }));
      }
    },
  });
  function userActions(u, anchor, el) {
    const set = (k, v, msg) => { usState.local[u.user_id] = Object.assign({}, usState.local[u.user_id], { [k]: v }); FL.toast(msg, 'Recorded in Audit History (prototype)'); FL.pages['users-settings'].draw(el, 'edit'); };
    FL.popover(anchor, [h('div', { class: 'lbl' }, u.name), ...['Finance Controller', 'Company Admin', 'Cashier'].map((r) => FL.menuItem('Make ' + r, () => set('role', r, `${u.name} is now ${r}`), { checked: (usState.local[u.user_id]?.role || u.role) === r })), h('hr'),
      u.status === 'Invited' ? FL.menuItem('Resend invite', () => FL.toast('Invite resent', u.email)) : null,
      FL.menuItem('Deactivate', () => set('status', 'Deactivated', `${u.name} deactivated`), { danger: true, icon: 'xc' })].filter(Boolean));
  }
  function invite(anchor) {
    const email = h('input', { class: 'fl-input', type: 'email', placeholder: 'name@lotuslane.lk' });
    const name = h('input', { class: 'fl-input', placeholder: 'Full name' });
    const role = FL.select(['Cashier', 'Finance Controller', 'Company Admin'], 'Cashier');
    const fe = FL.field('Work email', email), fn = FL.field('Name', name);
    FL.dialog({ title: 'Invite user', desc: 'They\'ll get an email to set a password and turn on two-step verification.', anchor, body: [fn, fe, FL.field('Role', role, { help: 'Cashiers can view balances and cheques but not change forecasts.' })],
      footer: [FL.btn('Cancel', { variant: 'outline', onClick: () => FL.closeDialog() }), FL.btn('Send invite', { onClick: () => { if (!/.+@.+\..+/.test(email.value)) return FL.fieldErr(fe, 'Enter a valid email.'); FL.closeDialog(); FL.toast('Invite sent', email.value + ' · ' + role.value + ' (prototype)'); } })] });
  }
  function editSettings(sec, items, anchor) {
    const inputs = items.map((s) => FL.field(s.label, h('input', { class: 'fl-input', value: s.value })));
    FL.dialog({ title: 'Edit ' + sec.toLowerCase() + ' settings', desc: 'Changes are recorded in Audit History.', anchor, body: [h('div', { style: 'display:grid;gap:14px' }, inputs), FL.field('Reason for change', h('input', { class: 'fl-input', placeholder: 'Optional' }))],
      footer: [FL.btn('Cancel', { variant: 'outline', onClick: () => FL.closeDialog() }), FL.btn('Save changes', { onClick: () => { FL.closeDialog(); FL.toast('Settings saved', 'Prototype — not stored'); } })] });
  }

  // ================================================================ AUDIT HISTORY
  const auState = { q: '', user: 'all', action: 'all', from: null, to: null };
  FL.register('audit', {
    init(el) {
      el.append(FL.pageHead('Audit History', 'Every change made in FlowLedger: who did it, what changed, when, and why.'),
        h('div', { class: 'fl-alert', id: 'au-readonly' }, FL.icon('lock', 18), h('div', { class: 't' }, 'Read-only record'), h('div', { class: 'd' }, 'Entries can\'t be edited or deleted by anyone, including admins. Times are Asia/Colombo.')),
        h('div', { id: 'au-list' }));
      el._t = FL.table({ source: 'audit', key: 'event_id', id: 'au-table', sort: 'at', dir: 'desc', cols: [
        { field: 'at', label: 'When', render: (r) => h('span', { class: 'nowrap' }, f.dt(r.at), h('span', { class: 'sub' }, r.event_id)) },
        { field: 'user', label: 'Who', render: (r) => h('span', null, r.user, h('span', { class: 'sub' }, r.role)) },
        { field: 'action', label: 'Action', render: (r) => h('span', { class: 'fl-badge ' + (r.user === 'System' ? 'outline muted' : r.action.includes('fail') ? 'destructive-soft' : 'outline') }, r.action) },
        { field: 'entity', label: 'Item', wrap: true },
        { field: 'change', label: 'What changed', wrap: true, render: (r) => h('span', { class: 'mono', style: 'font-size:12px' }, r.change) },
        { field: 'reason', label: 'Reason', wrap: true, render: (r) => h('span', { class: r.reason ? '' : 'muted' }, r.reason || 'Not given') },
      ], foot: (rs) => [h('span', null, rs.length + ' events'), h('span', null, 'Retained for 7 years')] });
    },
    draw(el) {
      const all = FL.rows('audit');
      if (!el._tools && all.length) {
        const us = FL.select([['all', 'All people']].concat([...new Set(all.map((a) => a.user))].map((u) => [u, u])), 'all', { 'aria-label': 'Person' });
        us.addEventListener('change', () => { auState.user = us.value; FL.pages.audit.draw(el); });
        const ac = FL.select([['all', 'All actions']].concat([...new Set(all.map((a) => a.action))].sort().map((a) => [a, a])), 'all', { 'aria-label': 'Action' });
        ac.addEventListener('change', () => { auState.action = ac.value; FL.pages.audit.draw(el); });
        const from = FL.dateInput(null, (v) => { auState.from = v; FL.pages.audit.draw(el); }, 'From'), to = FL.dateInput(null, (v) => { auState.to = v; FL.pages.audit.draw(el); }, 'To');
        el._tools = h('div', { class: 'fl-toolbar' }, FL.search('Search item, change or reason', (q) => { auState.q = q; FL.pages.audit.draw(el); }), us, ac, from, h('span', { class: 'muted' }, '–'), to,
          FL.btn('Export CSV', { variant: 'outline', size: 'sm', icon: 'download', onClick: () => FL.toast('Export prepared', 'Filtered events as CSV (prototype)') }));
      }
      const rows = all.filter((a) => (auState.user === 'all' || a.user === auState.user) && (auState.action === 'all' || a.action === auState.action) &&
        (!auState.from || a.at.slice(0, 10) >= auState.from) && (!auState.to || a.at.slice(0, 10) <= auState.to) &&
        (!auState.q || [a.entity, a.change, a.reason, a.user, a.action].join(' ').toLowerCase().includes(auState.q)));
      el._t.draw(rows);
      FL.$('#au-list', el).replaceChildren(FL.card(null, null, h('div', { style: 'display:grid;gap:12px' }, el._tools || '', all.length ? el._t : FL.stateLine(['audit'], 300)), { id: 'au-card' }));
    },
  });

  FL.boot();
})();
