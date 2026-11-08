import { useEffect, useState } from 'react';
import { activitiesApi, getDashboard, getTeam, productsApi, stockApi, tasksApi } from '../api.js';
import StockTasks from '../StockTasks.jsx';
import { money } from '../format.js';
import Modal from '../Modal.jsx';
import ActivityDetail, { PayBadge, fmtNum, fmtWhen } from '../ActivityDetail.jsx';
import SalesChart from './SalesChart.jsx';
import Icon from '../Icon.jsx';
import { StatusChips, StatusBadge, matchesStatus, balanceOf, teamBalanceOf, taskName } from '../TaskBits.jsx';

/** whole rupees for the big dashboard figures (popups keep exact amounts) */
const rupees = (n) => Number(n || 0).toLocaleString('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });

const STATUS = {
  NOT_STARTED: { label: 'Not started', cls: 'badge todo' },
  IN_PROGRESS: { label: 'In progress', cls: 'badge busy' },
  COMPLETED: { label: 'Completed', cls: 'badge on' },
};
const PERIOD_OPTIONS = [['all', 'All time'], ['day', 'Today'], ['week', 'This week'], ['month', 'This month'], ['year', 'This year'], ['range', 'Dates']];
const PERIOD_NAMES = { all: 'all time', day: 'today', week: 'this week', month: 'this month', year: 'this year' };

function Seg({ value, options, onChange, label }) {
  return (
    <div className="seg" role="tablist" aria-label={label}>
      {options.map(([k, text]) => (
        <button key={k} type="button" role="tab" aria-selected={value === k}
          className={value === k ? 'seg-btn active' : 'seg-btn'} onClick={() => onChange(k)}>{text}</button>
      ))}
    </div>
  );
}

/**
 * Units as a compact table: a one-line header (assigned, handed to team) and a Me / Team × Sold / Balance grid.
 * items are the unit figures ({ label, value, sub, onClick }); rows without figures (e.g. Me for the admin) are left out.
 */
function UnitsMatrix({ items }) {
  const by = Object.fromEntries(items.map((it) => [it.label, it]));
  const cell = (it) => (it ? (
    <button type="button" className="um-cell" onClick={it.onClick} title={it.sub}>{it.value}</button>
  ) : <span className="muted">—</span>);
  const rows = [
    ['me', 'user', 'Me', by['Sold by me'], by['My balance']],
    ['team', 'users', 'Team', by['Sold by team'], by['Team balance']],
  ].filter(([, , , sold, bal]) => sold || bal);
  const head = [by.Assigned, by['Handed to team']].filter(Boolean);
  return (
    <div className="units-matrix">
      <div className="um-head">
        {head.map((it) => (
          <button key={it.label} type="button" className="um-chip" onClick={it.onClick} title={it.sub}>
            {it.label} <strong>{it.value}</strong>
          </button>
        ))}
      </div>
      <table className="um-table">
        <thead><tr><th aria-label="Who" /><th className="num">Sold</th><th className="num">Balance</th></tr></thead>
        <tbody>
          {rows.map(([key, icon, label, sold, bal]) => (
            <tr key={key} className={`um-${key}`}>
              <th scope="row"><Icon name={icon} size={14} /> {label}</th>
              <td className="num">{cell(sold)}</td>
              <td className="num">{cell(bal)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** One clickable money figure: { icon, label, value, sub, onClick } */
function Figure({ it }) {
  return (
    <button type="button" className="fig" onClick={it.onClick}>
      <span className="fig-label"><Icon name={it.icon} size={14} /> {it.label}</span>
      <strong className="fig-value">{it.value}</strong>
      {it.sub && <span className="fig-sub">{it.sub}</span>}
    </button>
  );
}

/** Start of the current day / week (Monday) / month / year. */
function periodStart(period) {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  if (period === 'week') d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  if (period === 'month') d.setDate(1);
  if (period === 'year') { d.setMonth(0); d.setDate(1); }
  return d;
}

const isoDay = (dt) => `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, '0')}-${String(dt.getDate()).padStart(2, '0')}`;
const isoMonth = (dt) => isoDay(dt).slice(0, 7);
const niceDay = (s) => (s ? new Date(`${s}T00:00:00`).toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' }) : '');

/**
 * Chart buckets for the sales that count toward the shown tasks: daily for up to two months, monthly beyond.
 * shares: [{ a: activity, units, amount, mine }]
 */
function buildSeries(shares, startDay) {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  let start = startDay ? new Date(`${startDay}T00:00:00`) : null;
  if (!start || start > today) {
    start = new Date(today);
    start.setDate(start.getDate() - 29);
  }
  const days = Math.round((today - start) / 86400000) + 1;
  const monthly = days > 62;
  const mode = monthly ? 'year' : days <= 7 ? 'week' : 'month';
  const keys = [];
  if (monthly) {
    const m = new Date(start.getFullYear(), start.getMonth(), 1);
    const last = new Date(today.getFullYear(), today.getMonth(), 1);
    while (m <= last) { keys.push(isoMonth(m)); m.setMonth(m.getMonth() + 1); }
    while (keys.length > 24) keys.shift();
  } else {
    const dd = new Date(days < 7 ? today : start);
    if (days < 7) dd.setDate(dd.getDate() - 6); // always show at least a week
    while (dd <= today) { keys.push(isoDay(dd)); dd.setDate(dd.getDate() + 1); }
  }
  const points = keys.map((key) => ({ key, deals: 0, units: 0, amount: 0, dealsMine: 0, unitsMine: 0, amountMine: 0, ids: [] }));
  const byKey = Object.fromEntries(points.map((p) => [p.key, p]));
  shares.forEach((s) => {
    const key = String(s.a.activityTime).slice(0, monthly ? 7 : 10);
    const p = byKey[key];
    if (!p) return;
    p.deals += 1; p.units += s.units; p.amount += s.amount;
    if (s.mine) { p.dealsMine += 1; p.unitsMine += s.units; p.amountMine += s.amount; }
    p.ids.push(s.a.id);
  });
  return { points, mode };
}

export default function Dashboard({ user }) {
  const [selected, setSelected] = useState(user.id);
  const [d, setD] = useState(null);
  const [team, setTeam] = useState([]);
  const [error, setError] = useState('');
  const [metric, setMetric] = useState('amount');
  const [detail, setDetail] = useState(null);
  const [data, setData] = useState(null); // tasks, activities, customers, products
  // the one dashboard filter: a single task, or the tasks assigned in a period
  const [taskSel, setTaskSel] = useState('');
  const [taskPeriod, setTaskPeriod] = useState('all');
  const [rangeFrom, setRangeFrom] = useState('');
  const [rangeTo, setRangeTo] = useState('');

  // admin: stock per product (only active products, or ones that still have stock or tasks)
  const [stock, setStock] = useState(null);
  const [stockTasks, setStockTasks] = useState(null);
  useEffect(() => {
    getTeam().then(setTeam).catch(() => {});
    if (user.role === 'TENANT_ADMIN') {
      stockApi.list().then((rows) => setStock(rows.filter((r) => r.active || r.total > 0 || r.assigned > 0))).catch(() => {});
    }
  }, []);
  useEffect(() => {
    setD(null);
    setError('');
    setTaskSel('');
    getDashboard(selected, 'team').then(setD).catch((e) => setError(e.message));
    // sales in short form only (no notes, no customer list); full details are fetched for what a popup shows
    Promise.all([tasksApi.list(), activitiesApi.sales(), productsApi.list()])
      .then(([tasks, activities, products]) => setData({ tasks, activities, products }))
      .catch((e) => setError(e.message));
  }, [selected]);

  const openDetail = (spec) => setDetail(spec);

  // full activity rows (with customer names) for the deals a popup shows, fetched on demand and kept
  const [rowsById, setRowsById] = useState({});
  const DEAL_ROWS = 200;
  useEffect(() => {
    if (!detail || !data) return;
    const want = detail.kind === 'activityId' ? [detail.id]
      : detail.kind === 'deals' ? dealList(detail).slice(0, DEAL_ROWS).map((a) => a.id) : [];
    const missing = want.filter((id) => !rowsById[id]);
    if (missing.length === 0) return;
    activitiesApi.rows(missing)
      .then((rows) => setRowsById((old) => ({ ...old, ...Object.fromEntries(rows.map((r) => [r.id, r])) })))
      .catch(() => {});
  }, [detail, data]);

  const names = Object.fromEntries(team.map((t) => [t.userId, t.fullName]));
  const hasPeople = team.length > 1;
  const isAdminView = d?.role === 'TENANT_ADMIN';
  const viewingSelf = selected === user.id;
  const memberName = (id) => (id === user.id ? `${names[id] || 'Me'} (me)` : names[id] || 'Unknown');

  // the person and everyone below them; 'me' = only the person, 'team' = only the people below
  const ownerIds = (who = 'all') => {
    if (who === 'me') return new Set([selected]);
    if (who === 'team') { const s = ownerIds('all'); s.delete(selected); return s; }
    if (isAdminView) return new Set(team.map((t) => t.userId));
    const ids = new Set([selected]);
    let grew = true;
    while (grew) {
      grew = false;
      team.forEach((t) => { if (t.managerId && ids.has(t.managerId) && !ids.has(t.userId)) { ids.add(t.userId); grew = true; } });
    }
    return ids;
  };

  // ----- the filter: which tasks every area adds up -----
  const tasksTitle = isAdminView ? 'Tasks assigned' : 'My tasks';
  const myTasks = (data?.tasks || [])
    .filter((t) => (isAdminView ? t.assignedById === selected : t.assignedToId === selected))
    .sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)));
  const dayOfT = (t) => String(t.createdAt || '').slice(0, 10);
  const inPeriod = (t) => {
    if (taskPeriod === 'all') return true;
    const day = dayOfT(t);
    if (taskPeriod === 'range') return (!rangeFrom || day >= rangeFrom) && (!rangeTo || day <= rangeTo);
    return day >= isoDay(periodStart(taskPeriod));
  };
  const periodTasks = myTasks.filter(inPeriod);
  const pickedTask = taskSel ? myTasks.find((t) => String(t.id) === taskSel) : null;
  const shownTasks = pickedTask ? [pickedTask] : periodTasks;
  const periodName = taskPeriod === 'range'
    ? (rangeFrom && rangeTo && rangeFrom === rangeTo ? `on ${niceDay(rangeFrom)}`
      : `between ${niceDay(rangeFrom) || 'the start'} and ${niceDay(rangeTo) || 'today'}`)
    : PERIOD_NAMES[taskPeriod];
  const shownTitle = pickedTask ? taskName(pickedTask) : `${tasksTitle} (assigned ${periodName})`;

  // ----- totals over the shown tasks -----
  const sum = (fn) => shownTasks.reduce((n, t) => n + Number(fn(t) || 0), 0);
  const agg = {
    count: shownTasks.length,
    open: shownTasks.filter((t) => t.status !== 'COMPLETED').length,
    done: shownTasks.filter((t) => t.status === 'COMPLETED').length,
    units: sum((t) => t.units),
    split: sum((t) => t.splitUnits),
    sold: sum((t) => t.soldUnits),
    soldOnTarget: sum((t) => Math.min(t.units, t.soldUnits)),
    soldMine: sum((t) => (t.soldByAssignee ?? t.soldUnits)),
    myBal: sum((t) => balanceOf(t)),
    teamBal: isAdminView ? sum((t) => teamBalanceOf(t)) : sum((t) => Math.max(0, teamBalanceOf(t) - balanceOf(t))),
    sales: sum((t) => t.salesAmount),
    credit: sum((t) => t.creditAmount),
    dealIds: [...new Set(shownTasks.flatMap((t) => t.dealIds || []))],
    taskIds: shownTasks.map((t) => t.id),
  };
  agg.soldTeam = Math.max(0, agg.sold - agg.soldMine);
  const taskPct = agg.units > 0 ? Math.min(100, Math.round((agg.soldOnTarget / agg.units) * 100)) : 0;
  const hasTeamPart = !isAdminView && (agg.split > 0 || (d && d.teamSize > 0));

  // ----- the sales chart: each task's share of each sale, by sale date -----
  const actById = Object.fromEntries((data?.activities || []).map((a) => [a.id, a]));
  const shareByAct = {};
  shownTasks.forEach((t) => Object.entries(t.dealUnits || {}).forEach(([id, units]) => {
    shareByAct[id] = (shareByAct[id] || 0) + Number(units);
  }));
  const shares = Object.entries(shareByAct).map(([id, units]) => {
    const a = actById[id];
    if (!a || !a.activityTime) return null;
    const part = a.unitsSold ? units / a.unitsSold : 0;
    return { a, units, amount: Number(a.saleAmount || 0) * part, mine: a.ownerId === selected };
  }).filter(Boolean);
  const chartStart = pickedTask ? dayOfT(pickedTask)
    : taskPeriod === 'all' ? (shares.map((s) => String(s.a.activityTime).slice(0, 10)).sort()[0] || null)
      : taskPeriod === 'range' ? rangeFrom || (periodTasks.map(dayOfT).sort()[0] || null)
        : isoDay(periodStart(taskPeriod === 'day' ? 'week' : taskPeriod));
  const series = buildSeries(shares, chartStart);

  // ----- popups -----
  const tasksPopup = (status = '', filter = 'all') => openDetail({ kind: 'tasks', filter, status, taskIds: agg.taskIds, title: shownTitle });
  const dealsPopup = (extra, title) => openDetail({ kind: 'deals', dealIds: agg.dealIds, ...extra, title });

  const unitFigures = isAdminView ? [
    { label: 'Assigned', value: fmtNum(agg.units), sub: `on ${agg.count} task${agg.count === 1 ? '' : 's'}`, onClick: () => tasksPopup() },
    { label: 'Sold by team', value: fmtNum(agg.sold), sub: 'sold by everyone', onClick: () => dealsPopup({}, `Deals for ${shownTitle}`) },
    { label: 'Team balance', value: fmtNum(agg.teamBal), sub: 'not sold yet', onClick: () => tasksPopup() },
  ] : [
    { label: 'Assigned', value: fmtNum(agg.units), sub: `on ${agg.count} task${agg.count === 1 ? '' : 's'}`, onClick: () => tasksPopup() },
    { label: 'Handed to team', value: fmtNum(agg.split), sub: agg.split > 0 ? 'split into sub-tasks' : 'not split', onClick: () => tasksPopup() },
    { label: 'Sold by me', value: fmtNum(agg.soldMine), sub: 'your own sales', onClick: () => dealsPopup({ who: 'me' }, `My deals for ${shownTitle}`) },
    { label: 'My balance', value: fmtNum(agg.myBal), sub: 'left for you to sell', onClick: () => tasksPopup('', 'balance') },
    ...(hasTeamPart ? [
      { label: 'Sold by team', value: fmtNum(agg.soldTeam), sub: 'by your teammates', onClick: () => dealsPopup({ who: 'team' }, `Team deals for ${shownTitle}`) },
      { label: 'Team balance', value: fmtNum(agg.teamBal), sub: agg.split > 0 ? `left with your team (of ${fmtNum(agg.split)})` : 'nothing handed to team', onClick: () => tasksPopup() },
    ] : []),
  ];
  const payFigures = [
    { icon: 'rupee', label: 'Sales', value: rupees(agg.sales), sub: pickedTask ? 'share of deal value' : `across ${agg.count} task${agg.count === 1 ? '' : 's'}`,
      onClick: () => dealsPopup({}, `Deals for ${shownTitle}`) },
    { icon: 'card', label: 'On credit', value: rupees(agg.credit), sub: 'still to collect',
      onClick: () => dealsPopup({ creditOnly: true }, `Credit for ${shownTitle}`) },
    { icon: 'handshake', label: 'Deals', value: fmtNum(agg.dealIds.length), sub: 'sales counted',
      onClick: () => dealsPopup({}, `Deals for ${shownTitle}`) },
  ];

  // the sales behind a deals popup, newest first
  function dealList(spec) {
    const owners = ownerIds(spec.who);
    const ids = spec.dealIds ? new Set(spec.dealIds) : null;
    return (data?.activities || [])
      .filter((a) => a.productId && a.activityTime && (ids ? ids.has(a.id) && (!spec.who || owners.has(a.ownerId)) : owners.has(a.ownerId)))
      .filter((a) => !spec.creditOnly || Number(a.creditAmount || 0) > 0)
      .sort((a, b) => String(b.activityTime).localeCompare(String(a.activityTime)));
  }

  const renderDetail = () => {
    if (!data) return <p className="muted">Loading details...</p>;
    const productOf = (id) => data.products.find((p) => p.id === id);

    if (detail.kind === 'activityId') {
      const a = rowsById[detail.id];
      if (!a) return <p className="muted">Loading details...</p>;
      return <ActivityDetail a={a} memberName={memberName(a.ownerId)} customerName={a.customerName} product={productOf(a.productId)} />;
    }

    if (detail.kind === 'activity') {
      const a = rowsById[detail.a.id] || detail.a;
      return (
        <>
          <ActivityDetail a={a} memberName={memberName(a.ownerId)}
            customerName={a.customerName || '…'} product={productOf(a.productId)} />
          <div className="modal-actions">
            <button type="button" onClick={() => setDetail(detail.back)}>← Back to list</button>
          </div>
        </>
      );
    }

    if (detail.kind === 'tasks') {
      const balance = (t) => (isAdminView ? teamBalanceOf(t) : balanceOf(t));
      const teamLeft = (t) => (isAdminView ? teamBalanceOf(t) : Math.max(0, teamBalanceOf(t) - balanceOf(t)));
      const list = myTasks
        .filter((t) => detail.filter !== 'balance' || balance(t) > 0)
        .filter((t) => !detail.taskIds || detail.taskIds.includes(t.id));
      const rows = list.filter((t) => matchesStatus(t, detail.status || ''));
      return (
        <>
          <StatusChips tasks={list} withOpen value={detail.status || ''} onChange={(s) => setDetail({ ...detail, status: s })} />
          <div className="table-scroll">
            <table>
              <thead>
                <tr><th>Task</th><th>{isAdminView ? 'Assigned to' : 'Assigned by'}</th><th className="num">Units</th>
                  <th className="num">Sold</th><th className="num">Split to team</th>{!isAdminView && <th className="num">My balance</th>}
                  <th className="num">Team balance</th><th>Status</th></tr>
              </thead>
              <tbody>
                {rows.map((t) => (
                  <tr key={t.id}>
                    <td className="two-line"><strong>{taskName(t)}</strong><span className="muted">{t.productName} · assigned {fmtWhen(t.createdAt)}</span></td>
                    <td>{isAdminView ? t.assignedToName : t.assignedByName}</td>
                    <td className="num">{fmtNum(t.units)} {t.unit}</td>
                    <td className="num">{fmtNum(t.soldUnits)}</td>
                    <td className="num">{fmtNum(t.splitUnits)}</td>
                    {!isAdminView && <td className="num"><strong>{fmtNum(balance(t))}</strong></td>}
                    <td className="num">{fmtNum(teamLeft(t))}</td>
                    <td><span className={STATUS[t.status].cls}>{STATUS[t.status].label}</span></td>
                  </tr>
                ))}
                {rows.length === 0 && <tr><td colSpan={isAdminView ? 7 : 8} className="muted">No tasks with this status.</td></tr>}
              </tbody>
              {rows.length > 0 && (
                <tfoot>
                  <tr><th colSpan="2">Total</th><th className="num">{fmtNum(rows.reduce((n, t) => n + t.units, 0))}</th>
                    <th className="num">{fmtNum(rows.reduce((n, t) => n + t.soldUnits, 0))}</th>
                    <th className="num">{fmtNum(rows.reduce((n, t) => n + t.splitUnits, 0))}</th>
                    {!isAdminView && <th className="num">{fmtNum(rows.reduce((n, t) => n + balance(t), 0))}</th>}
                    <th className="num">{fmtNum(rows.reduce((n, t) => n + teamLeft(t), 0))}</th><th /></tr>
                </tfoot>
              )}
            </table>
          </div>
          <p className="muted hint">{isAdminView ? 'Team balance = units still unsold on the tasks you assigned.' : 'My balance = units still for you to sell yourself. Team balance = units you handed to your team that they have not sold yet.'}</p>
        </>
      );
    }

    // deals (sales): totals over all of them, the newest 200 listed
    const rows = dealList(detail);
    const listed = rows.slice(0, DEAL_ROWS);
    const showMember = detail.who !== 'me';
    return (
      <>
        <p className="muted log-summary">
          {rows.length} deal{rows.length === 1 ? '' : 's'} · {fmtNum(rows.reduce((n, a) => n + Number(a.unitsSold || 0), 0))} units ·{' '}
          {money(rows.reduce((n, a) => n + Number(a.saleAmount || 0), 0))} · {money(rows.reduce((n, a) => n + Number(a.creditAmount || 0), 0))} on credit
        </p>
        <div className="table-scroll">
          <table className="act-table">
            <thead>
              <tr><th>When</th>{showMember && <th>Member</th>}<th>Customer</th><th>Product</th>
                <th className="num">Units</th><th className="num">Amount</th><th className="num">Payment</th></tr>
            </thead>
            <tbody>
              {listed.map((a) => (
                <tr key={a.id} className="clickable" tabIndex={0}
                  onClick={() => setDetail({ kind: 'activity', a, title: 'Deal details', back: detail })}
                  onKeyDown={(e) => { if (e.key === 'Enter') setDetail({ kind: 'activity', a, title: 'Deal details', back: detail }); }}>
                  <td className="nowrap">{fmtWhen(a.activityTime)}</td>
                  {showMember && <td><span className="member-chip">{memberName(a.ownerId)}</span></td>}
                  <td>{rowsById[a.id]?.customerName || <span className="muted">…</span>}</td>
                  <td>{productOf(a.productId)?.name}</td>
                  <td className="num">{fmtNum(a.unitsSold)}</td>
                  <td className="num">{a.saleAmount != null ? money(a.saleAmount) : '—'}</td>
                  <td className="num"><PayBadge a={a} /></td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={showMember ? 7 : 6} className="muted">No deals here.</td></tr>}
            </tbody>
          </table>
        </div>
        {rows.length > listed.length && <p className="muted hint">Showing the newest {listed.length} of {rows.length} deals. Use the Activities page (Sales only) to see them all.</p>}
        <p className="muted hint">Click a deal to see its full details. Amounts are whole deals; the dashboard counts each task's share.</p>
      </>
    );
  };

  const hour = new Date().getHours();
  const greeting = hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening';
  const firstName = (user.fullName || user.username || '').split(/[ @]/)[0];
  const initials = (d?.userName || user.fullName || '?').split(' ').map((w) => w[0]).join('').slice(0, 2).toUpperCase();
  const loading = !d || !data;

  return (
    <div className="dash">
      {error && <p className="error">{error}</p>}

      <section className="dash-hero">
        <div className="hero-who">
          <span className="avatar" aria-hidden="true">{initials}</span>
          <div>
            <h2>{viewingSelf ? `${greeting}, ${firstName}` : `${d?.userName || ''}'s dashboard`}</h2>
            <p className="muted">
              <Icon name="calendar" size={15} /> {new Date().toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })}
              {d && d.teamSize > 0 && <> · <Icon name="users" size={15} /> team of {d.teamSize}</>}
            </p>
          </div>
        </div>
        {hasPeople && (
          <div className="hero-controls">
            <label className="viewing-inline">
              <Icon name="user" size={16} />
              <select value={selected} onChange={(e) => setSelected(Number(e.target.value))} aria-label="Dashboard of">
                {team.map((t) => <option key={t.userId} value={t.userId}>{t.fullName}{t.userId === user.id ? ' (me)' : ''}</option>)}
              </select>
            </label>
            {!viewingSelf && <button type="button" onClick={() => setSelected(user.id)}>Back to mine</button>}
          </div>
        )}
      </section>

      <section className="dash-filter" aria-label="Dashboard filter">
        <span className="filter-label"><Icon name="target" size={16} /> Tasks</span>
        <select value={taskSel} onChange={(e) => setTaskSel(e.target.value)} aria-label="Show one task">
          <option value="">All tasks</option>
          {(pickedTask && !periodTasks.includes(pickedTask) ? [pickedTask, ...periodTasks] : periodTasks).map((t) => (
            <option key={t.id} value={t.id}>{taskName(t)} · {t.productName}</option>
          ))}
        </select>
        {!pickedTask && <Seg label="Assigned" value={taskPeriod} onChange={setTaskPeriod} options={PERIOD_OPTIONS} />}
        {!pickedTask && taskPeriod === 'range' && (
          <span className="date-range">
            <input type="date" value={rangeFrom} onChange={(e) => setRangeFrom(e.target.value)} aria-label="Assigned from" />
            <span className="muted">to</span>
            <input type="date" value={rangeTo} onChange={(e) => setRangeTo(e.target.value)} aria-label="Assigned to" />
          </span>
        )}
        <span className="filter-note muted">
          {pickedTask ? (
            <>{pickedTask.productName} · assigned {fmtWhen(pickedTask.createdAt)} · <StatusBadge status={pickedTask.status} />
              <button type="button" className="link" onClick={() => setTaskSel('')}>All tasks</button></>
          ) : (
            <>{agg.count} task{agg.count === 1 ? '' : 's'} assigned {periodName}
              {taskPeriod !== 'all' && <button type="button" className="link" onClick={() => setTaskPeriod('all')}>Clear</button>}</>
          )}
        </span>
      </section>

      {loading ? (!error && <p className="muted">Loading...</p>) : (
        <div className="dash-layout">
          <div className="dash-main">
            <div className="dash-cards">
              <section className="dcard">
                <h3 className="dcard-title"><span className="icon-chip small tone-indigo"><Icon name="trend" size={16} /></span> Progress</h3>
                <button type="button" className="progress-box" onClick={() => tasksPopup()}>
                  <span className="progress-hero"><strong>{taskPct}%</strong><span className="muted">of {fmtNum(agg.units)} {pickedTask ? pickedTask.unit : 'units'} sold</span></span>
                  <span className={taskPct >= 100 ? 'progress done big' : 'progress big'} role="progressbar"
                    aria-valuenow={taskPct} aria-valuemin="0" aria-valuemax="100"><span style={{ width: `${taskPct}%` }} /></span>
                </button>
                <div className="status-mini">
                  {[['target', 'Total', agg.count, ''], ['clock', 'Open', agg.open, 'OPEN'], ['check', 'Done', agg.done, 'COMPLETED']].map(([icon, label, value, st]) => (
                    <button key={label} type="button" className="status-mini-btn" onClick={() => tasksPopup(st)}>
                      <Icon name={icon} size={14} /> {label} <strong>{fmtNum(value)}</strong>
                    </button>
                  ))}
                </div>
              </section>

              <section className="dcard">
                <h3 className="dcard-title"><span className="icon-chip small tone-sky"><Icon name="box" size={16} /></span> Units</h3>
                <UnitsMatrix items={unitFigures} />
              </section>

              <section className="dcard">
                <h3 className="dcard-title"><span className="icon-chip small tone-emerald"><Icon name="rupee" size={16} /></span> Payments</h3>
                <div className="fig-grid">
                  {payFigures.map((it) => <Figure key={it.label} it={it} />)}
                </div>
              </section>
            </div>

            <section className="dcard chart-card">
              <div className="panel-head">
                <h3 className="dcard-title"><span className="icon-chip small tone-indigo"><Icon name="chart" size={16} /></span> Sales
                  <span className="muted chart-sub">for {pickedTask ? taskName(pickedTask) : `tasks assigned ${periodName}`}</span></h3>
                <Seg label="Measure" value={metric} onChange={setMetric} options={[['amount', '₹ Amount'], ['units', 'Units']]} />
              </div>
              <SalesChart points={series.points} metric={metric} period={series.mode}
                showTeam={isAdminView || (d && d.teamSize > 0)}
                emptyText={agg.count === 0 ? 'No tasks match the filter.' : 'No sales on these tasks yet.'}
                onBarClick={(pt, label) => openDetail({ kind: 'deals', dealIds: pt.ids, title: `Deals on ${label}` })} />
            </section>
          </div>

          <aside className="dash-rail">
            {stock && (
              <section className="dcard rail-card">
                <div className="panel-head">
                  <h3 className="dcard-title"><span className="icon-chip small tone-sky"><Icon name="box" size={16} /></span> Stock</h3>
                  {stock.length > 0 && <button type="button" className="link" onClick={() => setStockTasks({ all: true })}>Assigned tasks</button>}
                </div>
                {stock.length === 0 ? <p className="muted">No stock yet. Add it in the Stock section.</p> : (
                  <ul className="recent-list">
                    {stock.map((r) => (
                      <li key={r.productId}>
                        <button type="button" onClick={() => setStockTasks(r)} title="View assigned tasks">
                          <span className="recent-main">
                            <strong>{r.productName}</strong>
                            <span className="muted">{fmtNum(r.total)} received · {fmtNum(r.assigned)} assigned · {fmtNum(r.sold)} sold</span>
                          </span>
                          <span className="recent-amt">
                            <strong>{fmtNum(Math.max(0, r.left))}</strong>
                            <span className={r.left > 0 ? 'pay-badge paid' : 'pay-badge credit'}>{r.left > 0 ? `${r.unit} in stock` : 'Out of stock'}</span>
                          </span>
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            )}
            <section className="dcard rail-card">
              <div className="panel-head">
                <h3 className="dcard-title"><span className="icon-chip small tone-emerald"><Icon name="cart" size={16} /></span> Recent sales</h3>
                {d.recentDeals?.length > 0 && <button type="button" className="link" onClick={() => openDetail({ kind: 'deals', title: 'All sales by you and your team' })}>View all</button>}
              </div>
              {!d.recentDeals?.length ? <p className="muted">No sales yet.</p> : (
                <ul className="recent-list">
                  {d.recentDeals.map((r) => (
                    <li key={r.id}>
                      <button type="button" onClick={() => openDetail({ kind: 'activityId', id: r.id, title: 'Sale details' })}>
                        <span className="recent-main">
                          <strong>{r.customer}</strong>
                          <span className="muted">{r.product} · {fmtNum(r.units)} units</span>
                          <span className="muted">{r.member} · {fmtWhen(r.when)}</span>
                        </span>
                        <span className="recent-amt">
                          <strong>{r.amount != null ? rupees(r.amount) : '—'}</strong>
                          {Number(r.credit) > 0 ? <span className="pay-badge credit">{rupees(r.credit)} credit</span> : r.amount != null && <span className="pay-badge paid">Paid</span>}
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </aside>
        </div>
      )}

      {stockTasks && <StockTasks product={stockTasks.all ? null : stockTasks} onClose={() => setStockTasks(null)} />}

      {detail && (
        <Modal wide title={detail.title} onClose={() => setDetail(null)}>
          {renderDetail()}
        </Modal>
      )}
    </div>
  );
}
