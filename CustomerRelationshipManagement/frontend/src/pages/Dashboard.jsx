import { useEffect, useState } from 'react';
import { activitiesApi, customersApi, getDashboard, getTeam, productsApi, tasksApi } from '../api.js';
import { money } from '../format.js';
import Modal from '../Modal.jsx';
import ActivityDetail, { PayBadge, fmtNum, fmtWhen } from '../ActivityDetail.jsx';
import SalesChart from './SalesChart.jsx';
import { StatusChips, matchesStatus, balanceOf, teamBalanceOf, taskName } from '../TaskBits.jsx';

const PERIODS = [['day', 'Today'], ['week', 'This week'], ['month', 'This month'], ['year', 'This year']];
const RANGES = [['week', 'Last 7 days'], ['month', 'Last 30 days'], ['year', 'Last 12 months']];
const STATUS = {
  NOT_STARTED: { label: 'Not started', cls: 'badge todo' },
  IN_PROGRESS: { label: 'In progress', cls: 'badge busy' },
  COMPLETED: { label: 'Completed', cls: 'badge on' },
};

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

/** A dashboard tile that opens a details popup when clicked. */
function Tile({ title, value, sub, onClick, small = false }) {
  return (
    <button type="button" className={small ? 'card tile small-card' : 'card tile'} onClick={onClick}>
      <h3>{title}</h3>
      <p>{value}</p>
      {sub && <span className="card-sub">{sub}</span>}
      <span className="tile-more">View details →</span>
    </button>
  );
}

/** Start of the current day / week (Monday) / month / year, matching the server's periods. */
function periodStart(period) {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  if (period === 'week') d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  if (period === 'month') d.setDate(1);
  if (period === 'year') { d.setMonth(0); d.setDate(1); }
  return d;
}

export default function Dashboard({ user }) {
  const [selected, setSelected] = useState(user.id);
  const [scope, setScope] = useState(user.role === 'TENANT_ADMIN' ? 'team' : 'me');
  const [d, setD] = useState(null);
  const [team, setTeam] = useState([]);
  const [error, setError] = useState('');
  const [period, setPeriod] = useState('month');
  const [range, setRange] = useState('month');
  const [metric, setMetric] = useState('amount');
  const [detail, setDetail] = useState(null);
  const [data, setData] = useState(null); // tasks, activities, customers, products for the popups

  useEffect(() => { getTeam().then(setTeam).catch(() => {}); }, []);
  useEffect(() => {
    setD(null);
    setError('');
    getDashboard(selected, scope).then(setD).catch((e) => setError(e.message));
  }, [selected, scope]);

  const openDetail = async (spec) => {
    setDetail(spec);
    if (!data) {
      try {
        const [tasks, activities, customers, products] = await Promise.all(
          [tasksApi.list(), activitiesApi.list(), customersApi.all(), productsApi.list()]);
        setData({ tasks, activities, customers, products });
      } catch (e) {
        setError(e.message);
        setDetail(null);
      }
    }
  };

  const names = Object.fromEntries(team.map((t) => [t.userId, t.fullName]));
  const hasTeam = team.length > 1;
  const isAdminView = d?.role === 'TENANT_ADMIN';
  const viewingSelf = selected === user.id;

  // people whose sales count in the current view
  const ownerIds = () => {
    if (scope !== 'team') return new Set([selected]);
    if (isAdminView) return new Set(team.map((t) => t.userId));
    const ids = new Set([selected]);
    let grew = true;
    while (grew) {
      grew = false;
      team.forEach((t) => { if (t.managerId && ids.has(t.managerId) && !ids.has(t.userId)) { ids.add(t.userId); grew = true; } });
    }
    return ids;
  };

  // ----- popup content -----
  const renderDetail = () => {
    if (!data) return <p className="muted">Loading details...</p>;
    const productOf = (id) => data.products.find((p) => p.id === id);
    const customerName = (id) => {
      const c = data.customers.find((x) => x.id === id);
      return c ? `${c.firstName} ${c.lastName}${c.company ? ` · ${c.company}` : ''}` : `Customer #${id}`;
    };
    const memberName = (id) => (id === user.id ? `${names[id] || 'Me'} (me)` : names[id] || 'Unknown');

    if (detail.kind === 'activity') {
      return (
        <>
          <ActivityDetail a={detail.a} memberName={memberName(detail.a.ownerId)}
            customerName={customerName(detail.a.customerId)} product={productOf(detail.a.productId)} />
          <div className="modal-actions">
            <button type="button" onClick={() => setDetail(detail.back)}>← Back to list</button>
          </div>
        </>
      );
    }

    if (detail.kind === 'tasks') {
      // members: what is still theirs to sell after splitting to the team; admin: everything unsold on tasks they assigned
      const balance = (t) => (isAdminView ? teamBalanceOf(t) : balanceOf(t));
      const mine = data.tasks.filter((t) => (isAdminView ? t.assignedById === selected : t.assignedToId === selected))
        .filter((t) => detail.filter !== 'balance' || balance(t) > 0);
      const rows = mine.filter((t) => matchesStatus(t, detail.status || ''));
      return (
        <>
          <StatusChips tasks={mine} withOpen value={detail.status || ''} onChange={(s) => setDetail({ ...detail, status: s })} />
          <div className="table-scroll">
            <table>
              <thead>
                <tr><th>Task</th><th>{isAdminView ? 'Assigned to' : 'Assigned by'}</th><th className="num">Units</th>
                  <th className="num">Sold</th><th className="num">Split to team</th><th className="num">Balance</th><th>Status</th></tr>
              </thead>
              <tbody>
                {rows.map((t) => (
                  <tr key={t.id}>
                    <td className="two-line">
                      <strong>{taskName(t)}</strong>
                      <span className="muted">{t.productName} · assigned {fmtWhen(t.createdAt)}</span>
                    </td>
                    <td>{isAdminView ? t.assignedToName : t.assignedByName}</td>
                    <td className="num">{fmtNum(t.units)} {t.unit}</td>
                    <td className="num">{fmtNum(t.soldUnits)}</td>
                    <td className="num">{fmtNum(t.splitUnits)}</td>
                    <td className="num"><strong>{fmtNum(balance(t))}</strong></td>
                    <td><span className={STATUS[t.status].cls}>{STATUS[t.status].label}</span></td>
                  </tr>
                ))}
                {rows.length === 0 && <tr><td colSpan="7" className="muted">No tasks with this status.</td></tr>}
              </tbody>
              {rows.length > 0 && (
                <tfoot>
                  <tr><th colSpan="2">Total</th><th className="num">{fmtNum(rows.reduce((n, t) => n + t.units, 0))}</th>
                    <th className="num">{fmtNum(rows.reduce((n, t) => n + t.soldUnits, 0))}</th>
                    <th className="num">{fmtNum(rows.reduce((n, t) => n + t.splitUnits, 0))}</th>
                    <th className="num">{fmtNum(rows.reduce((n, t) => n + balance(t), 0))}</th><th /></tr>
                </tfoot>
              )}
            </table>
          </div>
          <p className="muted hint">{isAdminView ? 'Balance = units still unsold on the tasks you assigned.' : 'Balance = units still to sell yourself. Units you split to your team come off your balance; their sales beyond their own share count toward yours.'}</p>
        </>
      );
    }

    // deals (sales)
    const owners = ownerIds();
    const rows = data.activities
      .filter((a) => a.productId && owners.has(a.ownerId) && a.activityTime)
      .filter((a) => !detail.from || new Date(a.activityTime) >= detail.from)
      .filter((a) => !detail.keyPrefix || String(a.activityTime).startsWith(detail.keyPrefix))
      .filter((a) => !detail.creditOnly || Number(a.creditAmount || 0) > 0)
      .sort((a, b) => String(b.activityTime).localeCompare(String(a.activityTime)));
    const showMember = scope === 'team';
    return (
      <>
        <p className="muted log-summary">
          {rows.length} deal{rows.length === 1 ? '' : 's'} · {fmtNum(rows.reduce((n, a) => n + Number(a.unitsSold || 0), 0))} units ·{' '}
          {money(rows.reduce((n, a) => n + Number(a.saleAmount || 0), 0))}
          {' '}· {money(rows.reduce((n, a) => n + Number(a.creditAmount || 0), 0))} on credit
        </p>
        <div className="table-scroll">
          <table className="act-table">
            <thead>
              <tr><th>When</th>{showMember && <th>Member</th>}<th>Customer</th><th>Product</th>
                <th className="num">Units</th><th className="num">Amount</th><th className="num">Payment</th></tr>
            </thead>
            <tbody>
              {rows.map((a) => (
                <tr key={a.id} className="clickable" tabIndex={0}
                  onClick={() => setDetail({ kind: 'activity', a, title: 'Deal details', back: detail })}
                  onKeyDown={(e) => { if (e.key === 'Enter') setDetail({ kind: 'activity', a, title: 'Deal details', back: detail }); }}>
                  <td className="nowrap">{fmtWhen(a.activityTime)}</td>
                  {showMember && <td><span className="member-chip">{memberName(a.ownerId)}</span></td>}
                  <td>{customerName(a.customerId)}</td>
                  <td>{productOf(a.productId)?.name}</td>
                  <td className="num">{fmtNum(a.unitsSold)}</td>
                  <td className="num">{a.saleAmount != null ? money(a.saleAmount) : '—'}</td>
                  <td className="num"><PayBadge a={a} /></td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={showMember ? 7 : 6} className="muted">No deals in this period.</td></tr>}
            </tbody>
          </table>
        </div>
        <p className="muted hint">Click a deal to see its full details.</p>
      </>
    );
  };

  const who = d ? (viewingSelf ? 'you' : d.userName) : '';
  const p = d?.deals[period];
  const periodLabel = PERIODS.find(([k]) => k === period)[1].toLowerCase();

  return (
    <div>
      {error && <p className="error">{error}</p>}

      <div className="toolbar dash-bar">
        {hasTeam && (
          <label className="viewing-inline">Dashboard of
            <select value={selected} onChange={(e) => setSelected(Number(e.target.value))}>
              {team.map((t) => (
                <option key={t.userId} value={t.userId}>{t.fullName}{t.userId === user.id ? ' (me)' : ''}</option>
              ))}
            </select>
          </label>
        )}
        {!viewingSelf && <button type="button" onClick={() => setSelected(user.id)}>Back to mine</button>}
        <span className="spacer" />
        {d && d.teamSize > 0 && (
          <Seg label="Whose sales" value={scope} onChange={setScope}
            options={[['me', viewingSelf ? 'Only me' : `Only ${d.userName}`], ['team', `With team (${d.teamSize})`]]} />
        )}
      </div>

      {!d ? (!error && <p className="muted">Loading...</p>) : (
        <>
          <p className="muted rollup">
            Tasks are {viewingSelf ? 'your' : `${d.userName}'s`} own.
            {' '}Sales show {d.scope === 'team' ? `${who} and ${d.teamSize} team member${d.teamSize > 1 ? 's' : ''}` : `${who} only`}.
            {' '}Click any tile for details.
          </p>

          <div className="cards">
            <Tile title={isAdminView ? 'Tasks assigned' : 'Total tasks'} value={fmtNum(d.totalTasks)}
              sub={`${fmtNum(d.completedTasks)} completed`}
              onClick={() => openDetail({ kind: 'tasks', filter: 'all', status: '', title: isAdminView ? 'Tasks assigned' : 'My tasks' })} />
            <Tile title="Open tasks" value={fmtNum(d.openTasks)} sub="not yet completed"
              onClick={() => openDetail({ kind: 'tasks', filter: 'all', status: 'OPEN', title: isAdminView ? 'Tasks assigned' : 'My tasks' })} />
            <Tile title="Units balance" value={fmtNum(d.unitsBalance)} sub={d.unitsSplit > 0 ? `of ${fmtNum(d.unitsAssigned)} assigned · ${fmtNum(d.unitsSplit)} handed to team` : `still to sell, of ${fmtNum(d.unitsAssigned)} assigned`}
              onClick={() => openDetail({ kind: 'tasks', filter: 'balance', title: 'Units balance by task' })} />
            <Tile title="Units sold" value={fmtNum(d.unitsSold)} sub={`${money(d.salesAmount)} in sales`}
              onClick={() => openDetail({ kind: 'deals', title: 'All deals' })} />
            <Tile title="On credit" value={money(d.creditOutstanding)} sub="still to be collected"
              onClick={() => openDetail({ kind: 'deals', creditOnly: true, title: 'Deals with credit outstanding' })} />
          </div>

          <div className="panel">
            <div className="panel-head">
              <h3>Deals closed</h3>
              <Seg label="Period" value={period} onChange={setPeriod} options={PERIODS} />
            </div>
            <div className="cards period-cards">
              <Tile small title="Deals" value={fmtNum(p.deals)} sub={periodLabel}
                onClick={() => openDetail({ kind: 'deals', from: periodStart(period), title: `Deals closed ${periodLabel}` })} />
              <Tile small title="Sales amount" value={money(p.amount)} sub={periodLabel}
                onClick={() => openDetail({ kind: 'deals', from: periodStart(period), title: `Deals closed ${periodLabel}` })} />
              <Tile small title="Units sold" value={fmtNum(p.units)} sub={periodLabel}
                onClick={() => openDetail({ kind: 'deals', from: periodStart(period), title: `Deals closed ${periodLabel}` })} />
            </div>
          </div>

          <div className="panel">
            <div className="panel-head">
              <h3>Sales</h3>
              <div className="panel-controls">
                <Seg label="Measure" value={metric} onChange={setMetric} options={[['amount', 'Amount'], ['units', 'Units']]} />
                <Seg label="Range" value={range} onChange={setRange} options={RANGES} />
              </div>
            </div>
            <SalesChart points={d.series[range]} metric={metric} period={range}
              onBarClick={(pt, label) => openDetail({ kind: 'deals', keyPrefix: pt.key, title: `Deals on ${label}` })} />
          </div>
        </>
      )}

      {detail && (
        <Modal wide title={detail.title} onClose={() => setDetail(null)}>
          {renderDetail()}
        </Modal>
      )}
    </div>
  );
}
