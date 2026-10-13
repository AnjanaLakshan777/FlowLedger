/* FlowLedger — charts + screens: Dashboard, Daily Sales, Historical Import, Bank Accounts, Reconciliation, Cheque Payments */
(function () {
  const FL = window.FL, h = FL.h, f = FL.fmt;
  const mark = (source, field, where, text, extra = {}) => h('span', Object.assign({ 'data-source': source, 'data-field': field, 'data-where': where }, extra), text);
  const kmark = (field, text, extra = {}) => h('span', Object.assign({ 'data-source': 'kpi', 'data-field': field, 'data-row': '0' }, extra), text);
  const failed = (...ids) => ids.some((id) => ['error', 'connect', 'declined', 'missing'].includes(FL.status(id)));
  FL.stateLine = (ids, minH) => h('div', { style: `min-height:${minH || 120}px;display:flex;align-items:center;justify-content:center;gap:8px;color:var(--muted-foreground);font-size:12.5px` },
    failed(...ids) ? [h('span', { style: 'width:6px;height:6px;border-radius:99px;background:var(--destructive)' }), 'Failed to load'] : h('span', { class: 'dash-skeleton', style: 'width:100%;height:' + (minH || 120) + 'px;display:block' }));

  // ================================================================ charts
  // Daily balance line with uncertainty band; negative days marked
  FL.balanceChart = function (box, days, opts = {}) {
    box.replaceChildren();
    const H = opts.height || 260;
    box.style.minHeight = H + 'px';
    if (!days.length) return;
    const W = Math.max(280, box.clientWidth);
    const svg = d3.select(box).append('svg').attr('width', '100%').attr('viewBox', `0 0 ${W} ${H}`).attr('height', H).attr('role', 'img').attr('aria-label', opts.aria || 'Projected daily closing balance');
    const xs = days.map((d) => FL.pd(d.date));
    const lo = d3.min(days, (d) => Math.min(+d.low, +d.closing, ...(opts.alt ? [+d.alt] : [])));
    const hi = d3.max(days, (d) => Math.max(+d.high, +d.closing, ...(opts.alt ? [+d.alt] : [])));
    const y = d3.scaleLinear([Math.min(lo, 0), hi], [H - 28, 10]).nice(5);
    const ticks = y.ticks(5);
    const ml = FL.leftMargin(svg, ticks), mr = 12;
    const x = d3.scaleUtc(d3.extent(xs), [ml, W - mr]);
    svg.append('g').attr('class', 'grid').selectAll('line').data(ticks).join('line').attr('x1', ml).attr('x2', W - mr).attr('y1', (v) => y(v)).attr('y2', (v) => y(v));
    svg.append('g').selectAll('text').data(ticks).join('text').attr('class', 'lbl').attr('x', ml - 8).attr('y', (v) => y(v) + 4).attr('text-anchor', 'end').text(FL.yTicksFmt);
    const xt = x.ticks(Math.max(2, Math.min(8, Math.floor((W - ml) / 90))));
    svg.append('g').selectAll('text').data(xt).join('text').attr('class', 'lbl').attr('x', (v) => x(v)).attr('y', H - 8).attr('text-anchor', 'middle').text(d3.utcFormat('%d %b'));
    // week shading for weekends? keep simple: zero line
    svg.append('line').attr('class', 'zero').attr('x1', ml).attr('x2', W - mr).attr('y1', y(0)).attr('y2', y(0));
    svg.append('text').attr('class', 'lbl').attr('x', W - mr).attr('y', y(0) - 5).attr('text-anchor', 'end').style('fill', 'var(--destructive)').text('LKR 0');
    if (!opts.noBand) svg.append('path').datum(days).attr('fill', 'var(--chart-1)').attr('opacity', FL.root.classList.contains('dark') ? 0.18 : 0.28)
      .attr('d', d3.area().x((d) => x(FL.pd(d.date))).y0((d) => y(+d.low)).y1((d) => y(+d.high)));
    if (opts.alt) svg.append('path').datum(days).attr('fill', 'none').attr('stroke', 'var(--muted-foreground)').attr('stroke-width', 2).attr('stroke-dasharray', '5 4')
      .attr('d', d3.line().x((d) => x(FL.pd(d.date))).y((d) => y(+d.alt)));
    svg.append('path').datum(days).attr('fill', 'none').attr('stroke', opts.color || 'var(--chart-4)').attr('stroke-width', 2).attr('stroke-linejoin', 'round').attr('stroke-linecap', 'round')
      .attr('d', d3.line().x((d) => x(FL.pd(d.date))).y((d) => y(+d.closing)));
    svg.append('g').selectAll('circle').data(days.filter((d) => +d.closing < 0)).join('circle').attr('cx', (d) => x(FL.pd(d.date))).attr('cy', (d) => y(+d.closing)).attr('r', 3.5).attr('fill', 'var(--destructive)');
    // hover
    const tip = FL.tip(box);
    const rule = svg.append('line').attr('y1', 10).attr('y2', H - 28).attr('stroke', 'var(--ring)').attr('opacity', 0);
    const dot = svg.append('circle').attr('r', 4).attr('fill', 'var(--background)').attr('stroke', 'var(--chart-4)').attr('stroke-width', 2).attr('opacity', 0);
    svg.append('rect').attr('x', ml).attr('y', 0).attr('width', W - ml - mr).attr('height', H - 20).attr('fill', 'transparent')
      .on('mousemove', (ev) => {
        const [mx] = d3.pointer(ev);
        const i = Math.max(0, Math.min(days.length - 1, d3.bisectCenter(xs.map(Number), +x.invert(mx))));
        const d = days[i], px = x(xs[i]), py = y(+d.closing);
        rule.attr('x1', px).attr('x2', px).attr('opacity', 1);
        dot.attr('cx', px).attr('cy', py).attr('opacity', 1).attr('stroke', +d.closing < 0 ? 'var(--destructive)' : 'var(--chart-4)');
        const rows = [[null, opts.alt ? 'Scenario closing' : 'Expected closing', f.lkr0(d.closing), +d.closing < 0 ? 'neg' : null]];
        if (opts.alt) rows.push([null, 'Baseline closing', f.lkr0(d.alt)]);
        if (!opts.noBand) rows.push([null, 'Likely range', f.m(d.low) + ' – ' + f.m(d.high)]);
        rows.push([null, 'Sales inflow', f.lkr0(d.inflow)], [null, 'Cheque outflow', f.lkr0(d.cheque_out)]);
        FL.tipHtml(tip, f.dday(d.date) + ' · Week ' + d.week, rows);
        FL.placeTip(box, tip, px, py);
      })
      .on('mouseleave', () => { tip.style.display = 'none'; rule.attr('opacity', 0); dot.attr('opacity', 0); });
  };

  // grouped/simple bars by category
  FL.barChart = function (box, cats, groups, opts = {}) {
    box.replaceChildren();
    const H = opts.height || 240;
    box.style.minHeight = H + 'px';
    if (!cats.length) return;
    const W = Math.max(280, box.clientWidth);
    const svg = d3.select(box).append('svg').attr('width', '100%').attr('viewBox', `0 0 ${W} ${H}`).attr('height', H).attr('role', 'img').attr('aria-label', opts.aria || 'Bar chart');
    const all = groups.flatMap((g) => g.values).concat(opts.markers ? opts.markers.values : []);
    const y = d3.scaleLinear([Math.min(0, d3.min(all)), Math.max(0, d3.max(all))], [H - 28, 10]).nice(5);
    const ticks = y.ticks(5);
    const ml = FL.leftMargin(svg, ticks), mr = 8;
    const x0 = d3.scaleBand(cats.map((_, i) => i), [ml, W - mr]).paddingInner(0.28).paddingOuter(0.1);
    const x1 = d3.scaleBand(groups.map((_, i) => i), [0, x0.bandwidth()]).padding(0.12);
    svg.append('g').attr('class', 'grid').selectAll('line').data(ticks).join('line').attr('x1', ml).attr('x2', W - mr).attr('y1', (v) => y(v)).attr('y2', (v) => y(v));
    svg.append('g').selectAll('text').data(ticks).join('text').attr('class', 'lbl').attr('x', ml - 8).attr('y', (v) => y(v) + 4).attr('text-anchor', 'end').text(FL.yTicksFmt);
    const every = Math.ceil(cats.length / Math.max(1, Math.floor((W - ml) / 56)));
    svg.append('g').selectAll('text').data(cats).join('text').attr('class', 'lbl').attr('x', (_, i) => x0(i) + x0.bandwidth() / 2).attr('y', H - 8).attr('text-anchor', 'middle').text((c, i) => (i % every === 0 ? c : ''));
    const bw = Math.min(32, x1.bandwidth());
    groups.forEach((g, gi) => {
      svg.append('g').selectAll('rect').data(g.values).join('rect')
        .attr('x', (_, i) => x0(i) + x1(gi) + (x1.bandwidth() - bw) / 2).attr('width', bw)
        .attr('y', (v) => y(Math.max(0, v))).attr('height', (v) => Math.max(1, Math.abs(y(v) - y(0)))).attr('rx', 3)
        .attr('fill', (v, i) => (g.colorFn ? g.colorFn(v, i) : g.color));
    });
    if (opts.markers) {
      const m = opts.markers;
      svg.append('g').selectAll('line').data(m.values).join('line')
        .attr('x1', (_, i) => x0(i) + 2).attr('x2', (_, i) => x0(i) + x0.bandwidth() - 2).attr('y1', (v) => y(v)).attr('y2', (v) => y(v))
        .attr('stroke', (v) => (v < 0 ? 'var(--destructive)' : 'var(--foreground)')).attr('stroke-width', 2).attr('stroke-linecap', 'round');
    }
    if (y.domain()[0] < 0) svg.append('line').attr('class', 'zero').attr('x1', ml).attr('x2', W - mr).attr('y1', y(0)).attr('y2', y(0));
    else svg.append('line').attr('class', 'axis-line').attr('x1', ml).attr('x2', W - mr).attr('y1', y(0)).attr('y2', y(0)).attr('stroke', 'var(--border)');
    const tip = FL.tip(box);
    svg.append('g').selectAll('rect').data(cats).join('rect').attr('x', (_, i) => x0(i) - x0.step() * x0.paddingInner() / 2).attr('width', x0.step()).attr('y', 0).attr('height', H - 20).attr('fill', 'transparent')
      .on('mousemove', (ev, c) => {
        const i = cats.indexOf(c);
        const rows = groups.map((g) => [g.colorFn ? g.colorFn(g.values[i], i) : g.color, g.name, f.lkr0(g.values[i]), g.values[i] < 0 ? 'neg' : null]);
        if (opts.markers) rows.push(['var(--foreground)', opts.markers.name, f.lkr0(opts.markers.values[i]), opts.markers.values[i] < 0 ? 'neg' : null]);
        if (opts.extraTip) rows.push(...opts.extraTip(i));
        FL.tipHtml(tip, opts.tipHead ? opts.tipHead(i) : c, rows);
        const [mx, my] = d3.pointer(ev, box);
        FL.placeTip(box, tip, mx, my);
      })
      .on('mouseleave', () => { tip.style.display = 'none'; });
  };

  // ================================================================ DASHBOARD
  FL.register('dashboard', {
    init(el) {
      el.append(
        h('div', { id: 'db-head' }),
        h('div', { id: 'db-short' }),
        h('div', { class: 'fl-section-title', id: 'sec-actual' }, h('span', { class: 'fl-badge soft' }, FL.icon('check', 12), 'Actual'), 'Confirmed by the bank', h('span', { class: 'hint' }, '· never includes expected receipts')),
        h('div', { class: 'fl-grid kpis', id: 'db-actual' }),
        h('div', { class: 'fl-section-title', id: 'sec-forecast' }, h('span', { class: 'fl-badge dashed' }, FL.icon('forecast', 12), 'Forecast'), 'Expected, not yet in the bank', h('span', { class: 'hint' }, '· next 13 weeks, Mon–Sun')),
        h('div', { class: 'fl-grid kpis', id: 'db-forecast' }),
        h('div', { class: 'fl-grid two' }, h('div', { id: 'db-chart' }), h('div', { id: 'db-cheques' })),
        h('div', { class: 'fl-grid two' }, h('div', { id: 'db-recent' }), h('div', { id: 'db-unmatched' })));
    },
    draw(el, acc) {
      const k = FL.one('kpi');
      const sh = FL.rows('shortages');
      const first = sh[0];
      // header + takeaway
      const take = h('p', { id: 'db-takeaway' });
      if (k && first) {
        take.append('Confirmed balance ', kmark('confirmed_balance', f.lkr0(k.confirmed_balance)), '. Expected shortage of ',
          mark('shortages', 'shortage_amount', 'shortage_id=' + first.shortage_id, f.lkr0(first.shortage_amount), { class: 'neg strong' }), ' from ',
          mark('shortages', 'first_date', 'shortage_id=' + first.shortage_id, f.dday(first.first_date), { class: 'strong' }), ' — ', FL.daysBetween(FL.TODAY, first.first_date) + ' days away.');
      } else take.append(h('span', { class: 'dash-skeleton', style: 'display:inline-block;width:320px' }, '—'));
      const actions = acc === 'edit' ? [FL.btn("Enter today's sales", { variant: 'outline', icon: 'sales', onClick: () => FL.go('daily-sales') }), FL.btn('Add cheque payment', { icon: 'plus', onClick: () => { FL.go('cheques'); FL.openAddCheque && FL.openAddCheque(); } })] : null;
      FL.$('#db-head', el).replaceChildren(h('div', { class: 'fl-page-head' }, h('div', null, h('h1', null, 'Good evening, Nadeesha'), take), actions ? h('div', { class: 'actions' }, actions) : null));

      // shortage alerts (red only here)
      const sbox = FL.$('#db-short', el);
      if (sh.length) {
        sbox.replaceChildren(FL.shortageCard(sh, { id: 'db-shortages', footer: true }));
      } else sbox.replaceChildren(FL.status('shortages') === 'ok' ? FL.card(null, null, h('div', { class: 'muted' }, FL.icon('checkc', 16), ' No cash shortages expected in the next 13 weeks.')) : '');

      // KPIs
      const kpi = (lbl, valNode, sub, opts = {}) => h('div', { class: 'fl-card fl-kpi' + (opts.forecast ? ' forecast' : ''), id: opts.id || null },
        h('div', { class: 'lbl' }, opts.icon ? FL.icon(opts.icon, 14) : null, lbl), h('div', { class: 'val' + (opts.neg ? ' neg' : '') }, valNode), h('div', { class: 'sub' }, sub));
      const cur = (field, v, opt) => [h('span', { class: 'cur' }, 'LKR'), kmark(field, f.amt(v), opt)];
      const A = FL.$('#db-actual', el), F = FL.$('#db-forecast', el);
      if (!k) { A.replaceChildren(FL.stateLine(['accounts', 'transactions'], 92)); F.replaceChildren(FL.stateLine(['forecast_weekly'], 92)); }
      else {
        const errAcc = FL.rows('accounts').find((a) => a.sync_status !== 'Synced');
        A.replaceChildren(
          kpi("Today's confirmed bank balance", cur('confirmed_balance', k.confirmed_balance), ['All ', kmark('accounts_total', k.accounts_total), ' accounts', errAcc ? ' · NDB balance as of ' + f.dt(errAcc.balance_as_of) : ''], { icon: 'wallet', id: 'kpi-balance' }),
          kpi('Bank sync', [kmark('accounts_synced', k.accounts_synced), h('span', { class: 'cur', style: 'margin-left:4px' }, 'of ' + k.accounts_total + ' synced')],
            h('span', { class: 'fl-status ' + (k.accounts_error ? 'err' : 'ok') }, h('span', { class: 'fl-dot' }), k.accounts_error ? [errAcc ? errAcc.bank + ' failed · ' : '', 'last ', kmark('last_sync', f.dt(k.last_sync))] : ['Last sync ', kmark('last_sync', f.dt(k.last_sync))]), { icon: 'refresh', id: 'kpi-sync' }),
          kpi("Today's confirmed receipts", cur('receipts_today', k.receipts_today), [kmark('receipts_count', k.receipts_count), ' credits · excl. own-account transfers'], { icon: 'download', id: 'kpi-receipts' }),
          kpi("Today's confirmed payments", cur('payments_today', k.payments_today), [kmark('payments_count', k.payments_count), ' debits · excl. own-account transfers'], { icon: 'upload', id: 'kpi-payments' }));
        F.replaceChildren(
          kpi('Cheques clearing next 7 days', cur('cheques_7d_amount', k.cheques_7d_amount), [kmark('cheques_7d_count', k.cheques_7d_count), ' cheques · to ', f.dshort(FL.addDays(FL.TODAY, 7))], { forecast: true, icon: 'cheque', id: 'kpi-chq7' }),
          kpi('Expected sales inflows', cur('forecast_inflow', k.forecast_inflow), 'Cash + card sales, 13 weeks', { forecast: true, icon: 'forecast', id: 'kpi-fin' }),
          kpi('Scheduled cheque outflows', cur('forecast_outflow', k.forecast_outflow), 'Entered by Finance Controller', { forecast: true, icon: 'cheque', id: 'kpi-fout' }),
          kpi('Lowest projected balance', [h('span', { class: 'cur' }, 'LKR'), kmark('lowest_balance', f.amt(k.lowest_balance), { class: k.lowest_balance < 0 ? 'neg' : null })], ['On ', kmark('lowest_date', f.dday(k.lowest_date))], { forecast: true, icon: 'alert', id: 'kpi-low' }));
      }

      // weekly chart
      const wk = FL.rows('forecast_weekly');
      const chartBox = h('div', { class: 'fl-chart', 'data-source': 'forecast_weekly', id: 'db-weekly-chart' });
      FL.$('#db-chart', el).replaceChildren(FL.card('13-week expected cash flow', 'Expected closing balance each Sunday; the line marks the lowest balance during the week', [
        h('div', { class: 'fl-legend', style: 'margin-bottom:8px' }, h('span', null, h('i', { style: 'background:var(--chart-3)' }), 'Week closing balance'), h('span', null, h('i', { class: 'line', style: 'background:var(--foreground)' }), 'Lowest point in week'), h('span', null, h('i', { class: 'line', style: 'background:var(--destructive)' }), 'Below zero')),
        chartBox], { actions: FL.btn('Open forecast', { variant: 'outline', size: 'sm', onClick: () => FL.go('forecast') }), id: 'card-weekly' }));
      if (wk.length) FL.barChart(chartBox, wk.map((w) => 'W' + w.week), [{ name: 'Closing balance', values: wk.map((w) => +w.closing), colorFn: (v) => (v < 0 ? 'var(--destructive)' : 'var(--chart-3)') }],
        { height: 230, markers: { name: 'Lowest in week', values: wk.map((w) => +w.lowest_balance) }, tipHead: (i) => `Week ${wk[i].week} · ${f.range(wk[i].week_start, wk[i].week_end)}`, aria: 'Weekly expected closing balance',
          extraTip: (i) => [[null, 'Inflows', f.lkr0(wk[i].inflow)], [null, 'Cheque outflows', f.lkr0(wk[i].cheque_out)]] });
      else chartBox.replaceChildren(FL.stateLine(['forecast_weekly'], 230));

      // upcoming cheques
      const chq = FL.rows('cheques').filter((c) => (c.status === 'Issued' || c.status === 'Presented')).sort((a, b) => a.expected_clear_date.localeCompare(b.expected_clear_date)).slice(0, 7);
      const t1 = FL.table({ source: 'cheques', key: 'cheque_no', cap: false, compact: true, sort: null, cols: [
        { field: 'expected_clear_date', label: 'Clears', render: (r) => h('span', { class: 'nowrap' }, f.dday(r.expected_clear_date)) },
        { field: 'payee', label: 'Payee', render: (r) => h('span', null, r.payee, h('span', { class: 'sub' }, r.category + ' · #' + r.cheque_no)) },
        { field: 'status', label: 'Status', render: (r) => FL.badge(r.status) },
        { field: 'amount', label: 'Amount (LKR)', num: true, fmt: f.amt },
      ] });
      t1.draw(chq);
      FL.$('#db-cheques', el).replaceChildren(FL.card('Upcoming cheque payments', 'Outstanding cheques by expected clearing date', chq.length || FL.status('cheques') === 'ok' ? t1 : FL.stateLine(['cheques']), { bodyCls: 'flush-b', actions: FL.btn('View all', { variant: 'outline', size: 'sm', onClick: () => FL.go('cheques') }), id: 'card-upcoming' }));

      // recent transactions + unmatched
      const tx = FL.rows('transactions');
      const recent = tx.slice(0, 7);
      const t2 = FL.table({ source: 'transactions', key: 'txn_id', cap: false, compact: true, cols: [
        { field: 'txn_date', label: 'Date', render: (r) => h('span', { class: 'nowrap' }, f.dshort(r.txn_date)) },
        { field: 'description', label: 'Description', render: (r) => h('span', null, r.description, h('span', { class: 'sub' }, r.account)) },
        { field: 'match_status', label: 'Match', render: (r) => FL.badge(r.match_status) },
        { field: 'amount', label: 'Amount (LKR)', num: true, fmt: f.signed },
      ] });
      t2.draw(recent);
      FL.$('#db-recent', el).replaceChildren(FL.card('Recent bank transactions', 'Latest bank-confirmed and pending items', tx.length ? t2 : FL.stateLine(['transactions']), { actions: FL.btn('Bank accounts', { variant: 'outline', size: 'sm', onClick: () => FL.go('bank-accounts') }), id: 'card-recent' }));
      const un = tx.filter((t) => t.match_status === 'Unmatched' || t.match_status === 'Review');
      const t3 = FL.table({ source: 'transactions', key: 'txn_id', cap: false, compact: true, cols: [
        { field: 'txn_date', label: 'Date', render: (r) => h('span', { class: 'nowrap' }, f.dshort(r.txn_date)) },
        { field: 'description', label: 'Description', render: (r) => h('span', null, r.description, h('span', { class: 'sub' }, r.note || r.account)) },
        { field: 'match_status', label: 'Status', render: (r) => h('span', { style: 'display:inline-flex;gap:4px;flex-wrap:wrap' }, FL.badge(r.match_status), r.duplicate ? FL.badge('Possible duplicate', 'Duplicate?') : null) },
        { field: 'amount', label: 'Amount (LKR)', num: true, fmt: f.signed },
      ] });
      t3.draw(un);
      FL.$('#db-unmatched', el).replaceChildren(FL.card('Unmatched & review required', k ? [kmark('review_count', k.review_count), ' need review · ', kmark('unmatched_count', k.unmatched_count), ' unmatched'] : '—', tx.length ? t3 : FL.stateLine(['transactions']),
        { actions: acc === 'edit' ? FL.btn('Reconcile', { size: 'sm', onClick: () => FL.go('reconciliation') }) : null, id: 'card-unmatched' }));
    },
  });

  FL.shortageCard = (sh, opts = {}) => {
    const list = h('div', { 'data-source': 'shortages' });
    for (const s of sh) {
      const w = 'shortage_id=' + s.shortage_id;
      list.append(h('div', { class: 'fl-shortage', id: 'shortage-' + s.shortage_id },
        h('div', { class: 'date' }, mark('shortages', 'first_date', w, f.dday(s.first_date)), h('small', null, 'Week ', mark('shortages', 'week', w, s.week), ' · ', mark('shortages', 'days_negative', w, s.days_negative), s.days_negative > 1 ? ' days negative' : ' day negative')),
        h('div', { class: 'cause' }, h('span', null, 'Main cause: ', mark('shortages', 'main_cause', w, s.main_cause)),
          h('small', null, 'Plus ', mark('shortages', 'cheques_in_window', w, s.cheques_in_window - 1), ' other cheques worth ', mark('shortages', 'other_outflows', w, f.lkr0(s.other_outflows)), ' in the same days. Week still ends at ',
            mark('shortages', 'week_end_balance', w, f.lkr0(s.week_end_balance)), +s.week_end_balance >= 0 ? ' — positive, but the account goes negative mid-week.' : '.')),
        h('div', { class: 'amt' }, h('small', null, 'Shortage'), mark('shortages', 'shortage_amount', w, f.lkr(s.shortage_amount)), h('small', null, 'lowest ', f.dday(s.lowest_date)))));
    }
    return FL.card(`Upcoming cash-shortage alerts (${sh.length})`, 'Days when the expected balance across all accounts falls below zero', list,
      { cls: 'crit', icon: 'alert', id: opts.id || null, bodyCls: 'flush-b', actions: opts.footer ? FL.btn('View in forecast', { variant: 'outline', size: 'sm', onClick: () => FL.go('forecast') }) : null });
  };

  // ================================================================ DAILY SALES
  const salesState = { q: '', month: 'all', type: 'all', session: [] };
  FL.register('daily-sales', {
    init(el) {
      el.append(FL.pageHead('Daily Sales', 'Enter each day\'s cash sales and card payments. The sales date is the day the sales happened; the entry time is recorded automatically.'),
        h('div', { id: 'ds-alert' }),
        h('div', { class: 'fl-grid side-l' }, h('div', { id: 'ds-form', style: 'display:grid;gap:16px;align-content:start' }), h('div', { id: 'ds-table', style: 'display:grid;gap:16px;align-content:start' })));
      // ---- form (built once so typing survives redraws)
      const date = FL.dateInput(FL.TODAY, () => check(), 'Sales date'); date.max = FL.TODAY;
      const cash = FL.amountInput({ 'aria-label': 'Cash sales' }), card = FL.amountInput({ 'aria-label': 'Card payments' });
      const note = h('textarea', { class: 'fl-textarea', rows: 2, placeholder: 'Optional — e.g. till report delayed' });
      const total = h('div', { class: 'fl-diff', id: 'ds-total' }, h('span', null, 'Total sales'), h('b', { class: 'tnum' }, 'LKR 0.00'));
      const fDate = FL.field('Sales date', date, { help: 'Backdated entries are allowed and flagged in the audit log.' });
      const fCash = FL.field('Cash sales', cash), fCard = FL.field('Card payments', card), fNote = FL.field('Note', note);
      fCash.querySelector('label').htmlFor = cash.input.id = 'ds-cash'; fCard.querySelector('label').htmlFor = card.input.id = 'ds-card';
      const exists = h('div', { class: 'fl-alert info', hidden: true }, FL.icon('info', 18), h('div', { class: 't' }, ''), h('div', { class: 'd' }, ''));
      const back = h('div', { class: 'fl-badge outline', hidden: true, style: 'justify-self:start' }, FL.icon('clock', 12), 'Backdated entry');
      const stamp = h('div', { class: 'muted', style: 'font-size:12.5px;display:flex;gap:6px;align-items:center' }, FL.icon('clock', 13), 'Entry timestamp: recorded on save (now ', FL.NOW_LABEL, ')');
      const save = FL.btn('Save entry', { icon: 'check' }), clear = FL.btn('Clear', { variant: 'outline' });
      function check() {
        const v = (FL.parseAmt(cash.input.value) || 0) + (FL.parseAmt(card.input.value) || 0);
        total.querySelector('b').textContent = f.lkr(isNaN(v) ? 0 : v);
        const d = date.value; const ex = FL.rows('daily_sales').find((s) => s.sales_date === d) || salesState.session.find((s) => s.sales_date === d);
        exists.hidden = !ex;
        if (ex) { exists.querySelector('.t').textContent = `An entry for ${f.date(d)} already exists`; exists.querySelector('.d').textContent = `Recorded ${f.lkr(ex.total)} on ${f.dtFull(ex.entered_at)} by ${ex.entered_by}. Saving will replace it and record the change in Audit History.`; save.lastChild.textContent = 'Update entry'; }
        else save.lastChild.textContent = 'Save entry';
        back.hidden = !(d && FL.daysBetween(d, FL.TODAY) >= 2);
      }
      [cash.input, card.input].forEach((i) => i.addEventListener('input', check));
      [date, cash.input, card.input].forEach((i, n, arr) => i.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); (arr[n + 1] || save).focus(); } }));
      clear.addEventListener('click', () => { cash.input.value = ''; card.input.value = ''; note.value = ''; [fCash, fCard, fDate].forEach((x) => FL.fieldErr(x)); check(); });
      save.addEventListener('click', () => {
        const c = FL.parseAmt(cash.input.value), k = FL.parseAmt(card.input.value);
        let ok = true;
        FL.fieldErr(fDate, !date.value ? (ok = false, 'Choose the sales date.') : date.value > FL.TODAY ? (ok = false, 'Sales date can\'t be in the future.') : null);
        FL.fieldErr(fCash, c == null ? (ok = false, 'Enter cash sales (0 if none).') : isNaN(c) || c < 0 ? (ok = false, 'Enter a positive amount.') : null);
        FL.fieldErr(fCard, k == null ? (ok = false, 'Enter card payments (0 if none).') : isNaN(k) || k < 0 ? (ok = false, 'Enter a positive amount.') : null);
        if (!ok) return;
        const backd = FL.daysBetween(date.value, FL.TODAY) >= 2;
        salesState.session.unshift({ sales_date: date.value, cash_sales: c, card_sales: k, total: c + k, entered_at: '2026-10-10T17:24', entered_by: 'Nadeesha Perera', entry_type: backd ? 'Backdated' : 'Standard', note: note.value });
        FL.toast(`Sales for ${f.date(date.value)} saved`, `${f.lkr(c + k)} · forecast and dashboard updated (prototype — not stored)`);
        clear.click(); FL.pages['daily-sales'].draw(el, FL.access('daily-sales'));
      });
      const formCard = FL.card('New sales entry', 'For the Finance Controller · takes under a minute', h('div', { style: 'display:grid;gap:14px' }, exists, fDate, back, h('div', { class: 'fl-form-grid' }, fCash, fCard), total, fNote, stamp, h('div', { style: 'display:flex;gap:8px' }, save, clear)), { id: 'ds-entry' });
      formCard.dataset.role = 'form';
      FL.$('#ds-form', el).append(formCard, h('div', { id: 'ds-week' }));
      el._check = check;
      // ---- table toolbar (built once)
      const month = FL.select([['all', 'All months'], ['2026-10', 'October 2026'], ['2026-09', 'September 2026'], ['2026-08', 'August 2026'], ['2026-07', 'July 2026'], ['2026-06', 'June 2026']], 'all', { 'aria-label': 'Month' });
      month.addEventListener('change', () => { salesState.month = month.value; FL.pages['daily-sales'].draw(el, FL.access('daily-sales')); });
      const type = FL.select([['all', 'All entries'], ['Backdated', 'Backdated only']], 'all', { 'aria-label': 'Entry type' });
      type.addEventListener('change', () => { salesState.type = type.value; FL.pages['daily-sales'].draw(el, FL.access('daily-sales')); });
      el._toolbar = h('div', { class: 'fl-toolbar' }, FL.search('Search date, amount, person or note', (q) => { salesState.q = q; FL.pages['daily-sales'].draw(el, FL.access('daily-sales')); }), month, type);
      el._table = FL.table({ source: 'daily_sales', key: 'sales_date', sort: 'sales_date', dir: 'desc', id: 'ds-entries', cols: [
        { field: 'sales_date', label: 'Sales date', render: (r) => h('span', { class: 'nowrap strong' }, f.dday(r.sales_date)) },
        { field: 'cash_sales', label: 'Cash (LKR)', num: true, fmt: f.amt },
        { field: 'card_sales', label: 'Card (LKR)', num: true, fmt: f.amt },
        { field: 'total', label: 'Total (LKR)', num: true, fmt: f.amt, cls: () => 'strong' },
        { field: 'entered_at', label: 'Entered at', render: (r) => h('span', { class: 'nowrap muted' }, f.dt(r.entered_at)) },
        { field: 'entered_by', label: 'Entered by' },
        { field: 'entry_type', label: 'Type', render: (r) => r.entry_type === 'Backdated' ? FL.badge('Backdated') : h('span', { class: 'muted' }, 'On time') },
        { field: 'note', label: 'Note', render: (r) => h('span', { class: 'muted' }, r.note || '') },
      ], foot: (rs) => [h('span', null, rs.length + ' entries'), h('span', { class: 'tnum' }, 'Total ' + f.lkr(d3.sum(rs, (r) => +r.total)))] });
    },
    draw(el, acc) {
      const rows = FL.rows('daily_sales');
      const latest = rows.map((r) => r.sales_date).concat(salesState.session.map((s) => s.sales_date)).sort().slice(-1)[0];
      FL.$('#ds-alert', el).replaceChildren(latest && latest < FL.TODAY ? h('div', { class: 'fl-alert info', id: 'ds-missing' }, FL.icon('calendar', 18), h('div', { class: 't' }, `Sales for today (${f.date(FL.TODAY)}) haven't been entered yet`),
        h('div', { class: 'd' }, ['Last entry is for ', mark('daily_sales', 'sales_date', 'sales_date=' + latest, f.dday(latest)), '. Expected sales are used in the forecast until actual sales are entered.'])) : '');
      // form state by role
      const form = FL.$('#ds-entry', el);
      FL.$$('input,textarea,button', form).forEach((x) => (x.disabled = acc !== 'edit'));
      let ro = FL.$('#ds-ro', el);
      if (acc !== 'edit' && !ro) form.querySelector('.fl-card-b').prepend(h('div', { class: 'fl-alert', id: 'ds-ro' }, FL.icon('lock', 18), h('div', { class: 't' }, 'View only'), h('div', { class: 'd' }, 'Daily sales are entered by the Finance Controller.')));
      if (acc === 'edit' && ro) ro.remove();
      el._check && el._check();
      // last 7 days summary
      const last7 = rows.filter((r) => r.sales_date > FL.addDays(FL.TODAY, -8));
      FL.$('#ds-week', el).replaceChildren(FL.card('Last 7 entered days', 'Sales by sales date', last7.length ? h('div', { class: 'fl-stat-row', 'data-source': 'daily_sales' },
        h('div', null, h('span', null, 'Cash'), h('b', null, f.m(d3.sum(last7, (r) => +r.cash_sales)))),
        h('div', null, h('span', null, 'Card'), h('b', null, f.m(d3.sum(last7, (r) => +r.card_sales)))),
        h('div', null, h('span', null, 'Daily avg'), h('b', null, f.m(d3.mean(last7, (r) => +r.total))))) : FL.stateLine(['daily_sales'], 60), { id: 'ds-summary' }));
      // table
      const q = salesState.q;
      const filt = rows.filter((r) => (salesState.month === 'all' || r.sales_date.startsWith(salesState.month)) && (salesState.type === 'all' || r.entry_type === salesState.type) &&
        (!q || [r.sales_date, f.dday(r.sales_date), r.entered_by, r.note, f.amt(r.total), r.total].join(' ').toLowerCase().includes(q)));
      el._table.draw(filt);
      const box = FL.$('#ds-table', el);
      const sess = salesState.session.length ? FL.card('Added in this session', 'Prototype: these entries are not stored', h('div', { class: 'fl-table-wrap' }, h('table', { class: 'fl-table compact' },
        h('thead', null, h('tr', null, ['Sales date', 'Cash (LKR)', 'Card (LKR)', 'Total (LKR)', 'Entered at', 'Type'].map((t, i) => h('th', { class: i && i < 4 ? 'num' : null }, t)))),
        h('tbody', null, salesState.session.map((s) => h('tr', null, h('td', { class: 'strong' }, f.dday(s.sales_date)), h('td', { class: 'num' }, f.amt(s.cash_sales)), h('td', { class: 'num' }, f.amt(s.card_sales)), h('td', { class: 'num strong' }, f.amt(s.total)), h('td', { class: 'muted' }, f.dt(s.entered_at)), h('td', null, s.entry_type === 'Backdated' ? FL.badge('Backdated') : h('span', { class: 'muted' }, 'On time'))))))), { id: 'ds-session' }) : null;
      if (!box.dataset.built) { box.dataset.built = '1'; box.append(h('div', { id: 'ds-sess-slot' }), FL.card('Sales entries', 'Sales date and entry time are shown separately', h('div', { style: 'display:grid;gap:12px' }, el._toolbar, h('div', { id: 'ds-table-slot' })), { id: 'ds-list' })); }
      FL.$('#ds-table-slot', el).replaceChildren(rows.length || FL.status('daily_sales') === 'ok' ? el._table : FL.stateLine(['daily_sales'], 300));
      FL.$('#ds-sess-slot', el).replaceChildren(sess || '');
    },
  });

  // ================================================================ HISTORICAL IMPORT
  const impState = { view: 'issues', approved: false, rejected: false };
  FL.register('historical-import', {
    init(el) {
      el.append(FL.pageHead('Historical Sales Import', 'One-time upload of past daily sales so the forecast has history to learn from.'),
        h('div', { class: 'fl-alert info', id: 'imp-scope' }, FL.icon('info', 18), h('div', { class: 't' }, 'For initial historical data only — not for daily sales'), h('div', { class: 'd' }, 'New days are entered on the Daily Sales screen by the Finance Controller. Imported history is used to calculate expected sales; it never changes bank balances.'),
          h('div', { class: 'a' }, FL.btn('Go to Daily Sales', { variant: 'outline', size: 'sm', onClick: () => FL.go('daily-sales') }))),
        h('div', { id: 'imp-steps' }), h('div', { class: 'fl-grid side-l' }, h('div', { id: 'imp-left', style: 'display:grid;gap:16px;align-content:start' }), h('div', { id: 'imp-right', style: 'display:grid;gap:16px;align-content:start' })),
        h('div', { id: 'imp-history' }));
      el._issues = FL.table({ source: 'import_rows', key: 'row', sort: 'row', dir: 'asc', id: 'imp-preview', cols: [
        { field: 'row', label: 'Row', num: true, render: (r) => h('span', { class: 'muted' }, r.row) },
        { field: 'date', label: 'date', render: (r) => h('span', { class: 'mono' }, r.date) },
        { field: 'cash_sales', label: 'cash_sales', num: true, fmt: (v) => (v == null ? 'empty' : f.amt(v)), cls: (r) => (r.cash_sales == null ? 'neg' : '') },
        { field: 'card_payments', label: 'card_payments', num: true, fmt: f.amt, cls: (r) => (+r.card_payments < 0 ? 'neg' : '') },
        { field: 'status', label: 'Check', render: (r) => FL.badge(r.status) },
        { field: 'issue', label: 'Issue', wrap: true, render: (r) => h('span', { class: r.status === 'Error' ? 'neg' : 'muted' }, r.issue || '') },
      ], foot: (rs) => [h('span', null, rs.length.toLocaleString() + ' rows shown'), h('span', null, 'Values in LKR')] });
    },
    draw(el, acc) {
      const s = FL.one('import_summary');
      const hist = FL.rows('import_history');
      const cur = hist.find((r) => r.status === 'Awaiting approval');
      const done = impState.approved, rej = impState.rejected;
      const step = (n, t, sub, state) => h('div', { class: 'fl-step ' + state }, h('span', { class: 'n' }, state === 'done' ? FL.icon('check', 13) : n), h('div', { style: 'min-width:0' }, h('b', null, t), h('span', null, sub)));
      FL.$('#imp-steps', el).replaceChildren(h('div', { class: 'fl-steps', id: 'imp-stepper' },
        step(1, 'Upload file', 'Excel or CSV', 'done'), step(2, 'Validate', s ? `${s.errors} errors · ${s.warnings} warnings` : '—', 'done'),
        step(3, 'Preview data', 'Check rows before submitting', done || rej ? 'done' : 'done'),
        step(4, 'Finance Controller approval', done ? 'Approved' : rej ? 'Rejected' : 'Waiting', done || rej ? 'done' : 'cur')));
      // left: file + columns + summary
      const w = cur ? 'import_id=' + cur.import_id : '';
      const file = cur ? h('div', { class: 'fl-file', id: 'imp-file', 'data-source': 'import_history' }, h('div', { class: 'ic-box' }, FL.icon('sheet', 18)),
        h('div', { style: 'min-width:0;flex:1' }, h('div', { class: 'strong', style: 'overflow-wrap:anywhere' }, mark('import_history', 'file_name', w, cur.file_name)),
          h('div', { class: 'muted', style: 'font-size:12px' }, mark('import_history', 'rows', w, f.int(cur.rows)), ' rows · uploaded ', mark('import_history', 'uploaded_at', w, f.dt(cur.uploaded_at)), ' by ', mark('import_history', 'uploaded_by', w, cur.uploaded_by))),
        FL.badge(done ? 'Approved' : rej ? 'Rejected' : cur.status)) : FL.stateLine(['import_history'], 60);
      const left = FL.$('#imp-left', el);
      left.replaceChildren(
        FL.card('Upload', 'One file covering the full historical period', h('div', { style: 'display:grid;gap:12px' }, file,
          h('div', { class: 'fl-drop' }, FL.icon('upload', 20), h('div', null, h('b', { style: 'color:var(--foreground)' }, 'Replace file'), ' — drag an .xlsx, .xls or .csv here'), h('div', { style: 'font-size:12px' }, 'Max 10 MB · one row per day'),
            FL.btn('Choose file', { variant: 'outline', size: 'sm', disabled: acc !== 'edit' }))), { id: 'imp-upload' }),
        FL.card('Required columns', 'Column names must match exactly (first row)', h('div', { class: 'fl-cols', id: 'imp-columns' },
          h('div', null, h('span', null, 'Column'), h('span', null, 'Format')),
          h('div', null, h('span', { class: 'mono' }, 'date'), h('span', null, 'YYYY-MM-DD · one row per date, no duplicates')),
          h('div', null, h('span', { class: 'mono' }, 'cash_sales'), h('span', null, 'Number ≥ 0, LKR, no currency symbol')),
          h('div', null, h('span', { class: 'mono' }, 'card_payments'), h('span', null, 'Number ≥ 0, LKR, no currency symbol'))),
          { actions: FL.btn('Template', { variant: 'outline', size: 'sm', icon: 'download' }) }));
      // right: validation + preview + approval
      const right = FL.$('#imp-right', el);
      const k = (field, v, cls) => h('b', { class: cls || null }, h('span', { 'data-source': 'import_summary', 'data-field': field, 'data-row': '0' }, v));
      const summary = s ? h('div', { class: 'fl-stat-row', id: 'imp-summary' },
        h('div', null, h('span', null, 'Rows'), k('rows', f.int(s.rows))), h('div', null, h('span', null, 'Valid'), k('valid', f.int(s.valid))),
        h('div', null, h('span', null, 'Errors'), k('errors', s.errors, s.errors ? 'neg' : '')), h('div', null, h('span', null, 'Warnings'), k('warnings', s.warnings)),
        h('div', null, h('span', null, 'Duplicate-date rows'), k('duplicate_rows', s.duplicate_rows, s.duplicate_rows ? 'neg' : ''))) : FL.stateLine(['import_rows'], 64);
      const rows = FL.rows('import_rows');
      const view = impState.view;
      const shown = view === 'issues' ? rows.filter((r) => r.status !== 'Valid') : rows;
      el._issues.draw(shown);
      const seg = FL.seg([['issues', 'Issues', s ? s.errors + s.warnings : ''], ['all', 'All rows', s ? f.int(s.rows) : '']], view, (v) => { impState.view = v; FL.pages['historical-import'].draw(el, acc); });
      const isFC = FL.state.role === 'Finance Controller';
      const excl = h('input', { type: 'checkbox', id: 'imp-excl' });
      const approve = FL.btn('Approve import', { icon: 'check', disabled: !isFC || done || rej });
      const reject = FL.btn('Reject', { variant: 'outline', disabled: !isFC || done || rej });
      const syncApprove = () => { approve.disabled = !isFC || done || rej || (s && s.errors > 0 && !excl.checked); };
      excl.addEventListener('change', syncApprove); syncApprove();
      approve.addEventListener('click', () => {
        const kept = s.valid + s.warnings;
        const box = FL.dialog({ title: 'Approve historical import?', desc: `${f.int(kept)} rows from ${f.date(s.first_date)} to ${f.date(s.last_date)} will be added as historical sales. ${s.errors} rows with errors will be skipped.`,
          body: [h('div', { class: 'fl-alert' }, FL.icon('info', 18), h('div', { class: 't' }, 'This can\'t be merged with daily entries'), h('div', { class: 'd' }, 'Dates that already have a Daily Sales entry are never overwritten. Approval is recorded in Audit History.')),
            FL.field('Approval note (optional)', h('textarea', { class: 'fl-textarea', placeholder: 'e.g. Checked totals against audited 2023–2025 accounts' }))],
          footer: [FL.btn('Cancel', { variant: 'outline', onClick: () => FL.closeDialog() }), FL.btn('Approve and import', { onClick: () => { impState.approved = true; FL.closeDialog(); FL.toast('Historical import approved', `${f.int(kept)} rows added · forecast recalculated (prototype)`); FL.pages['historical-import'].draw(el, acc); } })], anchor: approve });
        return box;
      });
      reject.addEventListener('click', () => {
        const reason = h('textarea', { class: 'fl-textarea', placeholder: 'Tell the uploader what to fix' });
        const fr = FL.field('Reason for rejection', reason);
        FL.dialog({ title: 'Reject this import?', desc: 'The file will not be imported. The uploader will see your reason.', body: fr,
          footer: [FL.btn('Cancel', { variant: 'outline', onClick: () => FL.closeDialog() }), FL.btn('Reject import', { variant: 'destructive', onClick: () => { if (!reason.value.trim()) return FL.fieldErr(fr, 'A reason is required.'); impState.rejected = true; FL.closeDialog(); FL.toast('Import rejected', 'Uploader notified (prototype)'); FL.pages['historical-import'].draw(el, acc); } })], anchor: reject });
      });
      const approval = FL.card('Finance Controller approval', done ? 'Approved in this session' : rej ? 'Rejected in this session' : 'Only a Finance Controller can approve. Nothing is imported until then.', h('div', { style: 'display:grid;gap:12px' },
        done ? h('div', { class: 'fl-alert info' }, FL.icon('checkc', 18), h('div', { class: 't' }, 'Import approved'), h('div', { class: 'd' }, 'Valid and warning rows were added; error rows were skipped. (Prototype — not stored.)')) :
          rej ? h('div', { class: 'fl-alert' }, FL.icon('xc', 18), h('div', { class: 't' }, 'Import rejected'), h('div', { class: 'd' }, 'Kasun can upload a corrected file.')) :
            [s && s.errors ? h('div', { class: 'fl-alert destructive', id: 'imp-errors' }, FL.icon('alert', 18), h('div', { class: 't' }, [k('errors', s.errors), ' rows have errors']), h('div', { class: 'd' }, 'Including duplicate dates. Fix the file and re-upload, or approve without those rows.')) : null,
              h('label', { class: 'fl-check' }, excl, `Skip the ${s ? s.errors : ''} error rows and import the rest`),
              !isFC ? h('div', { class: 'muted', style: 'font-size:12.5px' }, FL.icon('lock', 12), ' Waiting for a Finance Controller. You are previewing as ' + FL.state.role + '.') : null,
              h('div', { style: 'display:flex;gap:8px' }, approve, reject)]), { id: 'imp-approval' });
      right.replaceChildren(
        FL.card('Validation results', s ? ['Covers ', h('span', { 'data-source': 'import_summary', 'data-field': 'first_date', 'data-row': '0' }, f.date(s.first_date)), ' to ', h('span', { 'data-source': 'import_summary', 'data-field': 'last_date', 'data-row': '0' }, f.date(s.last_date))] : '—', summary, { id: 'imp-validation' }),
        approval,
        FL.card('Data preview', 'Review flagged rows before submitting', h('div', { style: 'display:grid;gap:12px' }, seg, rows.length ? el._issues : FL.stateLine(['import_rows'], 240)), { id: 'imp-preview-card' }));
      const ht = FL.table({ source: 'import_history', key: 'import_id', cap: false, compact: true, cols: [
        { field: 'import_id', label: 'Import', render: (r) => h('span', { class: 'mono' }, r.import_id) }, { field: 'file_name', label: 'File' },
        { field: 'period', label: 'Period' }, { field: 'rows', label: 'Rows', num: true, fmt: f.int },
        { field: 'uploaded_at', label: 'Uploaded', render: (r) => h('span', null, f.dt(r.uploaded_at), h('span', { class: 'sub' }, r.uploaded_by)) },
        { field: 'status', label: 'Status', render: (r) => FL.badge(r.status) }, { field: 'approved_by', label: 'Reviewed by', fmt: (v) => v || '—' },
      ] });
      ht.draw(hist);
      FL.$('#imp-history', el).replaceChildren(FL.card('Import history', 'Every upload and decision is kept in Audit History', hist.length ? ht : FL.stateLine(['import_history'], 80), { id: 'imp-hist' }));
    },
  });

  // ================================================================ BANK ACCOUNTS
  const bankState = { sel: 'ACC-COM', q: '' };
  FL.register('bank-accounts', {
    init(el) {
      el.append(FL.pageHead('Bank Accounts', 'Company accounts connected read-only. Balances shown are the latest confirmed by each bank.', [FL.btn('Sync all', { variant: 'outline', icon: 'refresh', onClick: () => FL.toast('Sync requested', '3 accounts queued; NDB needs renewed access first (prototype)') }), FL.btn('Connect account', { icon: 'plus', onClick: () => FL.go('users-settings') })]),
        h('div', { id: 'ba-error' }), h('div', { class: 'fl-grid kpis', id: 'ba-cards' }), h('div', { id: 'ba-detail' }));
      el._tx = FL.table({ source: 'transactions', key: 'txn_id', id: 'ba-tx', cols: [
        { field: 'txn_date', label: 'Date', render: (r) => h('span', { class: 'nowrap' }, f.dshort(r.txn_date)) },
        { field: 'value_date', label: 'Value date', render: (r) => h('span', { class: 'nowrap muted' }, r.value_date ? f.dshort(r.value_date) : 'Not provided') },
        { field: 'description', label: 'Description', render: (r) => h('span', null, r.description, h('span', { class: 'sub mono' }, r.reference)) },
        { field: 'txn_type', label: 'Type' },
        { field: 'amount', label: 'Amount (LKR)', num: true, fmt: f.signed },
        { field: 'balance_after', label: 'Balance (LKR)', num: true, fmt: (v) => (v == null ? 'Pending' : f.amt(v)), cls: (r) => (r.balance_after == null ? 'muted' : '') },
        { field: 'match_status', label: 'Match', render: (r) => FL.badge(r.match_status) },
      ], foot: (rs) => [h('span', null, rs.length + ' transactions'), h('span', null, 'Pending items are not part of the confirmed balance')] });
    },
    draw(el, acc) {
      const accts = FL.rows('accounts');
      const err = accts.filter((a) => a.sync_status !== 'Synced');
      FL.$('#ba-error', el).replaceChildren(...err.map((a) => h('div', { class: 'fl-alert destructive', id: 'ba-err-' + a.account_id, 'data-source': 'accounts' }, FL.icon('alert', 18),
        h('div', { class: 't' }, [mark('accounts', 'bank', 'account_id=' + a.account_id, a.bank), ' ', mark('accounts', 'account_masked', 'account_id=' + a.account_id, a.account_masked), ' is not syncing']),
        h('div', { class: 'd' }, [mark('accounts', 'error', 'account_id=' + a.account_id, a.error), ' Balance shown is from ', mark('accounts', 'last_sync', 'account_id=' + a.account_id, f.dtFull(a.last_sync)), ' and may be out of date.']),
        h('div', { class: 'a' }, FL.btn('Renew access', { variant: 'destructive', size: 'sm', disabled: acc !== 'edit', onClick: (e) => renew(a, e.currentTarget) })))));
      const cards = FL.$('#ba-cards', el);
      cards.replaceChildren(...(accts.length ? accts.map((a) => {
        const w = 'account_id=' + a.account_id, isErr = a.sync_status !== 'Synced';
        return h('button', { type: 'button', class: 'fl-acct' + (isErr ? ' err' : ''), 'aria-pressed': bankState.sel === a.account_id ? 'true' : 'false', id: 'acct-' + a.account_id, on: { click: () => { bankState.sel = a.account_id; FL.pages['bank-accounts'].draw(el, acc); } } },
          h('div', { class: 'top' }, h('div', { style: 'display:flex;gap:10px;min-width:0' }, h('span', { class: 'fl-bankmark' }, a.bank.split(' ').map((p) => p[0]).join('').slice(0, 3)),
            h('div', { style: 'min-width:0' }, h('div', { class: 'bank' }, mark('accounts', 'bank', w, a.bank)), h('div', { class: 'muted', style: 'font-size:12px' }, mark('accounts', 'account_name', w, a.account_name), ' · ', mark('accounts', 'account_masked', w, a.account_masked, { class: 'mono' })))),
            isErr ? h('span', { class: 'fl-badge destructive-soft' }, FL.icon('alert', 12), 'Sync error') : FL.badge('Synced')),
          h('div', null, h('div', { class: 'muted', style: 'font-size:12px' }, isErr ? 'Last confirmed balance' : 'Confirmed balance'), h('div', { class: 'bal' }, h('span', { class: 'cur' }, 'LKR'), mark('accounts', 'balance', w, f.amt(a.balance)))),
          h('div', { class: 'meta' }, h('span', { class: 'fl-status ' + (isErr ? 'err' : 'ok') }, h('span', { class: 'fl-dot' }), isErr ? 'Failed · ' : 'Synced · ', mark('accounts', 'last_sync', w, f.dt(a.last_sync))), h('span', null, mark('accounts', 'account_type', w, a.account_type))));
      }) : [FL.stateLine(['accounts'], 140)]));
      const a = accts.find((x) => x.account_id === bankState.sel);
      if (!a) { FL.$('#ba-detail', el).replaceChildren(); return; }
      const w = 'account_id=' + a.account_id;
      const tx = FL.rows('transactions').filter((t) => t.account_id === a.account_id && (!bankState.q || (t.description + ' ' + t.reference + ' ' + t.amount).toLowerCase().includes(bankState.q)));
      el._tx.draw(tx);
      if (!el._q) el._q = FL.search('Search description, reference or amount', (q) => { bankState.q = q; FL.pages['bank-accounts'].draw(el, acc); });
      FL.$('#ba-detail', el).replaceChildren(h('div', { class: 'fl-grid side' },
        FL.card([a.bank, ' ', a.account_masked, ' — transactions'].join(''), 'Bank-confirmed transactions, last 2 weeks', h('div', { style: 'display:grid;gap:12px' }, h('div', { class: 'fl-toolbar' }, el._q), el._tx), { id: 'ba-history' }),
        FL.card('Account details', null, h('div', { style: 'display:grid;gap:16px' }, h('dl', { class: 'fl-dl', id: 'ba-details', 'data-source': 'accounts' },
          h('dt', null, 'Bank'), h('dd', null, mark('accounts', 'bank', w, a.bank)), h('dt', null, 'Account name'), h('dd', null, mark('accounts', 'account_name', w, a.account_name)),
          h('dt', null, 'Account number'), h('dd', { class: 'mono' }, mark('accounts', 'account_masked', w, a.account_masked)), h('dt', null, 'Type'), h('dd', null, mark('accounts', 'account_type', w, a.account_type)),
          h('dt', null, 'Branch'), h('dd', null, mark('accounts', 'branch', w, a.branch)), h('dt', null, 'Currency'), h('dd', null, 'LKR'),
          h('dt', null, 'Balance as of'), h('dd', null, mark('accounts', 'balance_as_of', w, f.dtFull(a.balance_as_of))),
          h('dt', null, 'Connection'), h('dd', null, mark('accounts', 'connection', w, a.connection)), h('dt', null, 'Sync frequency'), h('dd', null, mark('accounts', 'sync_frequency', w, a.sync_frequency)),
          h('dt', null, 'Access expires'), h('dd', { class: a.sync_status !== 'Synced' ? 'neg' : null }, a.consent_expires ? mark('accounts', 'consent_expires', w, f.date(a.consent_expires)) : 'Not applicable')),
          h('div', { style: 'display:flex;gap:8px;flex-wrap:wrap' }, a.sync_status !== 'Synced' ? FL.btn('Renew access', { variant: 'destructive', size: 'sm', disabled: acc !== 'edit', onClick: (e) => renew(a, e.currentTarget) }) : FL.btn('Sync now', { variant: 'outline', size: 'sm', icon: 'refresh', onClick: () => FL.toast('Sync requested', a.bank + ' ' + a.account_masked + ' (prototype)') }),
            FL.btn('Reconcile', { variant: 'outline', size: 'sm', onClick: () => FL.go('reconciliation') }))), { id: 'ba-info' })));
    },
  });
  function renew(a, anchor) {
    FL.dialog({ title: `Renew access to ${a.bank} ${a.account_masked}`, desc: 'You\'ll be sent to the bank to approve read-only access again. FlowLedger can never move money.', anchor,
      body: h('dl', { class: 'fl-dl' }, h('dt', null, 'Access type'), h('dd', null, 'Read balances and transactions'), h('dt', null, 'Valid for'), h('dd', null, '180 days'), h('dt', null, 'Missed days'), h('dd', null, 'Transactions since the last sync will be fetched')),
      footer: [FL.btn('Cancel', { variant: 'outline', onClick: () => FL.closeDialog() }), FL.btn('Continue to bank', { onClick: () => { FL.closeDialog(); FL.toast('Bank approval started', 'Complete it in the bank window (prototype)'); } })] });
  }

  // ================================================================ RECONCILIATION
  const recState = { tab: 'attention', acct: 'all', q: '', over: {} };
  const recStatus = (t) => recState.over[t.txn_id] || t.match_status;
  FL.register('reconciliation', {
    init(el) {
      el.append(FL.pageHead('Bank Reconciliation', 'Match bank transactions to FlowLedger records. Differences are shown as they are — an account is never marked reconciled automatically.'),
        h('div', { id: 'rc-summary' }), h('div', { id: 'rc-list' }));
      el._sum = FL.table({ source: 'reconciliation', key: 'account_id', cap: false, id: 'rc-accounts', cols: [
        { field: 'account', label: 'Account', render: (r) => h('span', { class: 'strong' }, r.account, h('span', { class: 'sub', style: 'font-weight:400' }, 'Statement ' + f.date(r.statement_date))) },
        { field: 'statement_balance', label: 'Bank statement', num: true, fmt: f.amt },
        { field: 'flowledger_balance', label: 'FlowLedger records', num: true, fmt: f.amt },
        { field: 'difference', label: 'Difference', num: true, render: (r) => h('span', { class: +r.difference ? 'strong' : 'muted' }, +r.difference ? f.signed(r.difference) : '0.00') },
        { field: 'review', label: 'Review', num: true }, { field: 'unmatched', label: 'Unmatched', num: true }, { field: 'pending', label: 'Pending', num: true },
        { field: 'status', label: 'Status', render: (r) => FL.badge(r.status) },
      ] });
      el._tx = FL.table({ source: 'transactions', key: 'txn_id', id: 'rc-tx', sort: 'txn_date', dir: 'desc', rowCls: (r) => (recStatus(r) === 'Review' ? 'sel' : ''), cols: [
        { field: 'txn_date', label: 'Txn date', render: (r) => h('span', { class: 'nowrap' }, f.dshort(r.txn_date)) },
        { field: 'value_date', label: 'Value date', render: (r) => h('span', { class: 'nowrap ' + (r.value_date ? '' : 'muted') }, r.value_date ? f.dshort(r.value_date) : 'Not provided') },
        { field: 'description', label: 'Bank description', wrap: true, render: (r) => h('span', null, r.description, h('span', { class: 'sub' }, [r.account, ' · ', h('span', { class: 'mono' }, r.reference)])) },
        { field: 'amount', label: 'Amount (LKR)', num: true, fmt: f.signed },
        { field: 'matched_record', label: 'FlowLedger record', wrap: true, render: (r) => h('span', null, r.matched_record || h('span', { class: 'muted' }, 'No record'), r.note ? h('span', { class: 'sub' }, r.note) : null) },
        { field: 'match_status', label: 'Status', render: (r) => h('span', { style: 'display:inline-flex;gap:4px;flex-wrap:wrap' }, FL.badge(recStatus(r)), r.duplicate && !recState.over[r.txn_id] ? FL.badge('Possible duplicate', 'Duplicate?') : null) },
        { field: 'confidence', label: 'Match conf.', num: true, fmt: (v) => (v == null ? '—' : Math.round(v * 100) + '%') },
        { label: '', render: (r) => (['Review', 'Unmatched'].includes(recStatus(r)) && FL.access('reconciliation') === 'edit' ? FL.btn('Review', { variant: 'outline', size: 'sm', onClick: (e) => review(r, e.currentTarget, el) }) : null) },
      ], foot: (rs) => [h('span', null, rs.length + ' transactions'), h('span', null, 'Confirming matches does not mark an account reconciled')] });
    },
    draw(el, acc) {
      const rec = FL.rows('reconciliation');
      el._sum.draw(rec);
      const diffTotal = d3.sum(rec, (r) => +r.difference);
      FL.$('#rc-summary', el).replaceChildren(FL.card('Balance check by account', 'Bank statement balance vs. the balance implied by FlowLedger records', rec.length ? el._sum : FL.stateLine(['reconciliation'], 160),
        { id: 'rc-balances', footer: rec.length ? [h('span', null, FL.icon('info', 13), ' Differences come from unmatched bank items, amount mismatches and possible duplicates.'), h('span', { class: 'strong tnum', style: 'color:var(--foreground)' }, 'Total difference ' + f.signed(diffTotal))] : null }));
      const tx = FL.rows('transactions');
      const cnt = (fn) => tx.filter(fn).length;
      const tabs = [['attention', 'Needs attention', cnt((t) => ['Review', 'Unmatched'].includes(recStatus(t)))], ['Review', 'Review required', cnt((t) => recStatus(t) === 'Review')], ['Unmatched', 'Unmatched', cnt((t) => recStatus(t) === 'Unmatched')],
        ['Pending', 'Pending', cnt((t) => recStatus(t) === 'Pending')], ['dup', 'Possible duplicates', cnt((t) => t.duplicate && !recState.over[t.txn_id])], ['Matched', 'Matched', cnt((t) => recStatus(t) === 'Matched')], ['all', 'All', tx.length]];
      const filt = tx.filter((t) => {
        const st = recStatus(t);
        const tabOk = recState.tab === 'all' || (recState.tab === 'attention' ? ['Review', 'Unmatched'].includes(st) : recState.tab === 'dup' ? t.duplicate && !recState.over[t.txn_id] : st === recState.tab);
        return tabOk && (recState.acct === 'all' || t.account_id === recState.acct) && (!recState.q || [t.description, t.reference, t.amount, t.matched_record, t.note].join(' ').toLowerCase().includes(recState.q));
      });
      el._tx.draw(filt);
      if (!el._tools) {
        const acctSel = FL.select([['all', 'All accounts']].concat(FL.rows('accounts').map((a) => [a.account_id, a.bank + ' ' + a.account_masked])), 'all', { 'aria-label': 'Account' });
        acctSel.addEventListener('change', () => { recState.acct = acctSel.value; FL.pages.reconciliation.draw(el, acc); });
        el._acctSel = acctSel;
        el._tools = h('div', { class: 'fl-toolbar' }, FL.search('Search description, reference, amount', (q) => { recState.q = q; FL.pages.reconciliation.draw(el, acc); }), acctSel);
      }
      if (el._acctSel.options.length < 2 && FL.rows('accounts').length) FL.rows('accounts').forEach((a) => el._acctSel.append(new Option(a.bank + ' ' + a.account_masked, a.account_id)));
      const seg = FL.seg(tabs, recState.tab, (v) => { recState.tab = v; FL.pages.reconciliation.draw(el, acc); });
      seg.style.flexWrap = 'wrap';
      FL.$('#rc-list', el).replaceChildren(FL.card('Bank transactions', 'Bank items beside the FlowLedger record they were matched to', h('div', { style: 'display:grid;gap:12px' }, h('div', { style: 'overflow-x:auto' }, seg), el._tools, tx.length ? el._tx : FL.stateLine(['transactions'], 300)), { id: 'rc-transactions' }));
    },
  });
  function recordAmount(t) {
    const m = String(t.matched_record || '');
    let mm = m.match(/^(Card|Cash) sales (\d{2}) (\w{3})/);
    if (mm) {
      const months = { Sep: '09', Oct: '10', Aug: '08' };
      const d = `2026-${months[mm[3]]}-${mm[2]}`;
      const s = FL.rows('daily_sales').find((x) => x.sales_date === d);
      if (s) return { label: `${mm[1]} sales entered for ${f.dday(d)}`, amount: mm[1] === 'Card' ? +s.card_sales : +s.cash_sales, date: d };
    }
    mm = m.match(/^Cheque (\d+)/);
    if (mm) { const c = FL.rows('cheques').find((x) => x.cheque_no === mm[1]); if (c) return { label: `Cheque ${c.cheque_no} · ${c.payee}`, amount: -c.amount, date: c.expected_clear_date }; }
    return null;
  }
  function review(t, anchor, el) {
    const rec = recordAmount(t);
    const diff = rec ? +t.amount - rec.amount : null;
    const reason = h('textarea', { class: 'fl-textarea', placeholder: 'Reason (recorded in Audit History)' });
    const dup = t.duplicate ? FL.rows('transactions').filter((x) => x.reference === t.reference && x.txn_id !== t.txn_id) : [];
    const side = (title, rows) => h('div', null, h('h4', null, title), ...rows);
    const line = (k, v) => h('div', { style: 'display:flex;justify-content:space-between;gap:12px;font-size:13px' }, h('span', { class: 'muted' }, k), h('span', { class: 'strong tnum', style: 'text-align:right' }, v));
    const set = (st, msg) => { recState.over[t.txn_id] = st; FL.closeDialog(); FL.toast(msg, 'Account stays "Not reconciled" until every item is resolved (prototype)'); FL.pages.reconciliation.draw(el, 'edit'); };
    const actsUnmatched = t.match_status === 'Unmatched';
    FL.dialog({ wide: true, anchor, title: actsUnmatched ? 'Resolve unmatched transaction' : 'Review suggested match', desc: actsUnmatched ? 'No FlowLedger record matches this bank item.' : `Suggested with ${Math.round((t.confidence || 0) * 100)}% confidence. Confirm only if they are the same money movement.`,
      body: [
        h('div', { class: 'fl-compare' },
          side('Bank transaction', [line('Description', t.description), line('Reference', t.reference), line('Transaction date', f.date(t.txn_date)), line('Value date', t.value_date ? f.date(t.value_date) : 'Not provided'), line('Account', t.account), h('div', { class: 'amt' }, f.lkr(t.amount))]),
          side('FlowLedger record', rec ? [line('Record', rec.label), line('Date', f.date(rec.date)), line('Source', 'Entered by Finance Controller'), h('div', { class: 'amt' }, f.lkr(rec.amount))] : [h('p', { class: 'muted', style: 'font-size:13px' }, t.matched_record ? t.matched_record + ' (no amount on record)' : 'No matching record found.')])),
        rec ? h('div', { class: 'fl-diff' }, h('span', null, 'Difference (bank − record)'), h('b', { class: 'tnum' + (Math.abs(diff) > 0.005 ? ' neg' : '') }, f.lkr(diff))) : null,
        dup.length ? h('div', { class: 'fl-alert' }, FL.icon('copy', 18), h('div', { class: 't' }, 'Possible duplicate'), h('div', { class: 'd' }, `${dup.length} other transaction with the same reference, amount and date (${dup.map((d) => d.txn_id).join(', ')}). The bank may have sent it twice.`)) : null,
        actsUnmatched ? FL.field('Treat as', FL.select(['Bank charge — no record needed', 'Other receipt — not sales income', 'Link to a cheque…', 'Leave unmatched for now'])) : null,
        FL.field('Note', reason)],
      footer: actsUnmatched ? [FL.btn('Cancel', { variant: 'outline', onClick: () => FL.closeDialog() }), FL.btn('Save', { onClick: () => set('Matched', 'Transaction resolved') })]
        : [FL.btn('Reject match', { variant: 'outline', onClick: () => set('Unmatched', 'Match rejected') }), dup.length ? FL.btn('Mark as duplicate', { variant: 'outline', icon: 'copy', onClick: () => set('Unmatched', 'Marked as duplicate — excluded from balance') }) : null, FL.btn('Confirm match', { icon: 'check', onClick: () => set('Matched', 'Match confirmed') })] });
  }

  // ================================================================ CHEQUE PAYMENTS
  const chqState = { tab: 'outstanding', cat: 'all', q: '', from: null, to: null, session: [] };
  FL.register('cheques', {
    init(el) {
      el.append(FL.pageHead('Cheque Payments', 'Future cheque payments entered by the Finance Controller. Only cheques entered here are used as forecast outflows.', h('span', { id: 'chq-add-slot' })),
        h('div', { id: 'chq-stats' }), h('div', { id: 'chq-sess' }), h('div', { id: 'chq-list' }));
      const add = FL.btn('Add cheque payment', { icon: 'plus', onClick: (e) => openAdd(e.currentTarget, el) });
      FL.$('#chq-add-slot', el).append(add); el._add = add;
      FL.openAddCheque = () => setTimeout(() => openAdd(add, el), 30);
      const status = FL.select([['outstanding', 'Outstanding (issued + presented)'], ['Issued', 'Issued'], ['Presented', 'Presented'], ['Cleared', 'Cleared'], ['Bounced', 'Bounced'], ['Cancelled', 'Cancelled'], ['all', 'All statuses']], chqState.tab, { 'aria-label': 'Status' });
      status.addEventListener('change', () => { chqState.tab = status.value; FL.pages.cheques.draw(el, FL.access('cheques')); });
      const cat = FL.select([['all', 'All categories'], 'Supplier Payment', 'Salary', 'Tax', 'Other Expenses'], 'all', { 'aria-label': 'Category' });
      cat.addEventListener('change', () => { chqState.cat = cat.value; FL.pages.cheques.draw(el, FL.access('cheques')); });
      const from = FL.dateInput(null, (v) => { chqState.from = v; FL.pages.cheques.draw(el, FL.access('cheques')); }, 'Cheque date from');
      const to = FL.dateInput(null, (v) => { chqState.to = v; FL.pages.cheques.draw(el, FL.access('cheques')); }, 'Cheque date to');
      el._status = status;
      el._tools = h('div', { class: 'fl-toolbar' }, FL.search('Search payee, cheque no. or note', (q) => { chqState.q = q; FL.pages.cheques.draw(el, FL.access('cheques')); }), status, cat,
        h('span', { class: 'muted', style: 'font-size:12.5px' }, 'Cheque date'), from, h('span', { class: 'muted' }, '–'), to,
        FL.btn('Reset', { variant: 'ghost', size: 'sm', onClick: () => { Object.assign(chqState, { tab: 'outstanding', cat: 'all', from: null, to: null }); status.value = 'outstanding'; cat.value = 'all'; from.value = ''; to.value = ''; FL.pages.cheques.draw(el, FL.access('cheques')); } }));
      el._table = FL.table({ source: 'cheques', key: 'cheque_no', sort: 'expected_clear_date', dir: 'asc', id: 'chq-table', rowCls: (r) => (r.status === 'Bounced' ? 'crit' : ''), cols: [
        { field: 'cheque_no', label: 'Cheque no.', render: (r) => h('span', { class: 'mono' }, r.cheque_no) },
        { field: 'payee', label: 'Payee', wrap: true, render: (r) => h('span', null, r.payee, r.notes ? h('span', { class: 'sub' }, r.notes) : null) },
        { field: 'category', label: 'Category' },
        { field: 'cheque_date', label: 'Cheque date', render: (r) => h('span', { class: 'nowrap' }, f.dshort(r.cheque_date), r.status === 'Issued' && r.cheque_date < FL.TODAY ? h('span', { class: 'sub' }, 'Not presented · ' + FL.daysBetween(r.cheque_date, FL.TODAY) + ' days') : null) },
        { field: 'expected_clear_date', label: 'Expected clearing', render: (r) => h('span', { class: 'nowrap' }, f.dday(r.expected_clear_date), h('span', { class: 'sub' }, r.clearing)) },
        { field: 'amount', label: 'Amount (LKR)', num: true, fmt: f.amt, cls: () => 'strong' },
        { field: 'status', label: 'Status', render: (r) => FL.badge(r.status) },
        { field: 'account', label: 'Paid from', render: (r) => h('span', { class: 'muted nowrap' }, r.account) },
      ], foot: (rs) => [h('span', null, rs.length + ' cheques'), h('span', { class: 'tnum' }, 'Total ' + f.lkr(d3.sum(rs, (r) => +r.amount)))] });
    },
    draw(el, acc) {
      el._add.disabled = acc !== 'edit';
      const all = FL.rows('cheques');
      const out = all.filter((c) => c.status === 'Issued' || c.status === 'Presented');
      const k = FL.one('kpi');
      const bounced = all.filter((c) => c.status === 'Bounced');
      const stale = all.filter((c) => c.status === 'Issued' && c.cheque_date < FL.TODAY);
      FL.$('#chq-stats', el).replaceChildren(k ? h('div', { class: 'fl-stat-row', id: 'chq-kpis' },
        h('div', null, h('span', null, 'Outstanding cheques'), h('b', null, kmark('outstanding_count', k.outstanding_count)), h('span', null, kmark('outstanding_amount', f.lkr0(k.outstanding_amount)))),
        h('div', null, h('span', null, 'Clearing in next 7 days'), h('b', null, kmark('cheques_7d_count', k.cheques_7d_count)), h('span', null, kmark('cheques_7d_amount', f.lkr0(k.cheques_7d_amount)))),
        h('div', null, h('span', null, 'Issued, not yet presented'), h('b', null, stale.length), h('span', null, f.lkr0(d3.sum(stale, (c) => +c.amount)))),
        h('div', null, h('span', null, 'Bounced'), h('b', { class: bounced.length ? 'neg' : '' }, kmark('bounced_count', k.bounced_count)), h('span', null, bounced.length ? 'Action needed: re-issue or cancel' : 'None'))) : FL.stateLine(['cheques'], 72));
      if (bounced.length && !FL.$('#chq-bounced-alert', el)) {
        const b = bounced[0];
        FL.$('#chq-stats', el).append(h('div', { class: 'fl-alert destructive', id: 'chq-bounced-alert', style: 'margin-top:16px', 'data-source': 'cheques' }, FL.icon('xc', 18),
          h('div', { class: 't' }, ['Cheque ', mark('cheques', 'cheque_no', 'cheque_no=' + b.cheque_no, b.cheque_no), ' to ', mark('cheques', 'payee', 'cheque_no=' + b.cheque_no, b.payee), ' bounced']),
          h('div', { class: 'd' }, [mark('cheques', 'amount', 'cheque_no=' + b.cheque_no, f.lkr(b.amount)), ' · ', mark('cheques', 'notes', 'cheque_no=' + b.cheque_no, b.notes)]),
          h('div', { class: 'a' }, FL.btn('Show bounced', { variant: 'outline', size: 'sm', onClick: () => { chqState.tab = 'Bounced'; el._status.value = 'Bounced'; FL.pages.cheques.draw(el, acc); } }))));
      }
      const q = chqState.q;
      const filt = all.filter((c) => (chqState.tab === 'all' || (chqState.tab === 'outstanding' ? c.status === 'Issued' || c.status === 'Presented' : c.status === chqState.tab)) &&
        (chqState.cat === 'all' || c.category === chqState.cat) && (!chqState.from || c.cheque_date >= chqState.from) && (!chqState.to || c.cheque_date <= chqState.to) &&
        (!q || [c.cheque_no, c.payee, c.notes, c.amount, f.amt(c.amount)].join(' ').toLowerCase().includes(q)));
      el._table.draw(filt);
      FL.$('#chq-sess', el).replaceChildren(chqState.session.length ? FL.card('Added in this session', 'Prototype: not stored, but shown here as they would appear', h('div', { class: 'fl-table-wrap' }, h('table', { class: 'fl-table compact' },
        h('thead', null, h('tr', null, ['Cheque no.', 'Payee', 'Category', 'Cheque date', 'Expected clearing', 'Amount (LKR)', 'Status'].map((t, i) => h('th', { class: i === 5 ? 'num' : null }, t)))),
        h('tbody', null, chqState.session.map((c) => h('tr', null, h('td', { class: 'mono' }, c.cheque_no), h('td', null, c.payee), h('td', null, c.category), h('td', null, f.dshort(c.cheque_date)), h('td', null, f.dday(c.expected_clear_date), h('span', { class: 'sub' }, c.clearing)), h('td', { class: 'num strong' }, f.amt(c.amount)), h('td', null, FL.badge('Issued'))))))), { id: 'chq-session' }) : '');
      if (!el._listBuilt) { el._listBuilt = true; FL.$('#chq-list', el).append(FL.card('Cheques', 'Bounced cheques are marked in red; outstanding cheques show how long since they were written', h('div', { style: 'display:grid;gap:12px' }, el._tools, el._table), { id: 'chq-card' })); }
    },
  });
  function openAdd(anchor, el) {
    if (FL.access('cheques') !== 'edit') return;
    const cat = FL.select([['', 'Choose category'], 'Supplier Payment', 'Salary', 'Tax', 'Other Expenses'], '');
    const payee = h('input', { class: 'fl-input', placeholder: 'e.g. Kelani Valley Foods', list: 'fl-payees' });
    const dl = h('datalist', { id: 'fl-payees' }, [...new Set(FL.rows('cheques').map((c) => c.payee))].map((p) => h('option', { value: p })));
    const amt = FL.amountInput({ 'aria-label': 'Amount' });
    const no = h('input', { class: 'fl-input mono', placeholder: '000000', inputmode: 'numeric', maxlength: 6 });
    const cdate = h('input', { class: 'fl-input', type: 'date', min: '2026-01-01', max: '2027-12-31', value: FL.addDays(FL.TODAY, 3) });
    const clr = FL.select(['Same day', '1 business day', '2-3 business days'], '1 business day');
    const acct = FL.select(FL.rows('accounts').filter((a) => a.account_type === 'Current').map((a) => [a.bank + ' - ' + a.account_type, a.bank + ' ' + a.account_masked]));
    const notes = h('textarea', { class: 'fl-textarea', rows: 2, placeholder: 'Invoice number, terms, anything the team should know' });
    const fields = { cat: FL.field('Category', cat), payee: FL.field('Payee', payee), amt: FL.field('Amount', amt), no: FL.field('Cheque number', no), cdate: FL.field('Cheque date', cdate, { help: 'Date written on the cheque' }), clr: FL.field('Expected clearing', clr), acct: FL.field('Paid from account', acct, { cls: 'full' }), notes: FL.field('Notes', notes, { cls: 'full' }) };
    fields.amt.querySelector('label').htmlFor = amt.input.id = 'chq-amt';
    const clearOn = h('div', { class: 'fl-diff full' }, h('span', null, 'Expected to leave the account on'), h('b', { class: 'tnum' }, '—'));
    const calc = () => { if (!cdate.value) return; let d = cdate.value; const add = { 'Same day': 0, '1 business day': 1, '2-3 business days': 2 }[clr.value]; let n = 0; const wd = (s) => FL.pd(s).getUTCDay(); while (n < add || wd(d) === 0 || wd(d) === 6) { d = FL.addDays(d, 1); if (wd(d) !== 0 && wd(d) !== 6) n++; } clearOn.lastChild.textContent = f.dday(d) + ' ' + d.slice(0, 4); clearOn.dataset.d = d; };
    cdate.addEventListener('change', calc); clr.addEventListener('change', calc); calc();
    const save = FL.btn('Add cheque', { icon: 'check', onClick: () => {
      let ok = true;
      const v = FL.parseAmt(amt.input.value);
      FL.fieldErr(fields.cat, cat.value ? null : (ok = false, 'Choose a category.'));
      FL.fieldErr(fields.payee, payee.value.trim() ? null : (ok = false, 'Enter the payee.'));
      FL.fieldErr(fields.amt, v > 0 ? null : (ok = false, 'Enter an amount greater than 0.'));
      const dupNo = FL.rows('cheques').some((c) => c.cheque_no === no.value);
      FL.fieldErr(fields.no, /^\d{6}$/.test(no.value) ? (dupNo ? (ok = false, 'This cheque number is already recorded.') : null) : (ok = false, 'Enter the 6-digit cheque number.'));
      FL.fieldErr(fields.cdate, cdate.value ? null : (ok = false, 'Choose the cheque date.'));
      if (!ok) return;
      chqState.session.unshift({ cheque_no: no.value, payee: payee.value.trim(), category: cat.value, amount: v, cheque_date: cdate.value, clearing: clr.value, expected_clear_date: clearOn.dataset.d });
      FL.closeDialog(); FL.toast(`Cheque ${no.value} added`, `${f.lkr(v)} · included in the forecast for ${f.dday(clearOn.dataset.d)} (prototype)`);
      FL.pages.cheques.draw(el, 'edit');
    } });
    FL.dialog({ title: 'Add cheque payment', desc: 'Record a cheque you have written or will write. It becomes a forecast outflow on its expected clearing date.', anchor, wide: false,
      body: [dl, h('div', { class: 'fl-form-grid' }, fields.cat, fields.payee, fields.amt, fields.no, fields.cdate, fields.clr, clearOn, fields.acct, fields.notes)],
      footer: [FL.btn('Cancel', { variant: 'outline', onClick: () => FL.closeDialog() }), save] });
  }
})();
