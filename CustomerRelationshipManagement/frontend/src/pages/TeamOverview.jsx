import { useEffect, useState } from 'react';
import { getTeam } from '../api.js';
import { money } from '../format.js';

const fmt = (n) => Number(n || 0).toLocaleString('en-IN');

const PERIODS = [['all', 'All time'], ['day', 'Today'], ['week', 'This week'], ['month', 'This month'], ['year', 'This year'], ['range', 'Dates']];
const PERIOD_NAMES = { all: 'all time', day: 'today', week: 'this week', month: 'this month', year: 'this year' };
const isoDay = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const niceDay = (s) => (s ? new Date(`${s}T00:00:00`).toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' }) : '');

/** First day of the current day / week (Monday) / month / year, as yyyy-mm-dd. */
function periodStart(period) {
  const d = new Date();
  if (period === 'week') d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  if (period === 'month') d.setDate(1);
  if (period === 'year') { d.setMonth(0); d.setDate(1); }
  return isoDay(d);
}

/** Each visible person's own numbers, laid out along the reporting tree, for a chosen period. */
export default function TeamOverview({ user }) {
  const [team, setTeam] = useState(null);
  const [error, setError] = useState('');
  const [period, setPeriod] = useState('all');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [loading, setLoading] = useState(false);

  // the period becomes a from/to range of days; the server counts only what falls in it
  const range = period === 'all' ? ['', ''] : period === 'range' ? [from, to] : [periodStart(period), isoDay(new Date())];
  useEffect(() => {
    setLoading(true);
    getTeam(range[0], range[1]).then(setTeam).catch((e) => setError(e.message)).finally(() => setLoading(false));
  }, [range[0], range[1]]);

  const periodName = period === 'range'
    ? (from && to && from === to ? `on ${niceDay(from)}` : `from ${niceDay(from) || 'the start'} to ${niceDay(to) || 'today'}`)
    : PERIOD_NAMES[period];

  if (error) return <p className="error">{error}</p>;
  if (!team) return <p className="muted">Loading team...</p>;

  const byId = Object.fromEntries(team.map((t) => [t.userId, t]));
  // the top of the tree is the current user; people whose manager is not visible hang off it
  const parentOf = (t) => (t.userId === user.id ? null : byId[t.managerId] ? t.managerId : user.id);
  const kids = {};
  team.forEach((t) => { const p = parentOf(t); if (p != null) (kids[p] = kids[p] || []).push(t); });
  Object.values(kids).forEach((l) => l.sort((a, b) => a.fullName.localeCompare(b.fullName)));
  const rows = [];
  const walk = (t, depth) => { rows.push({ t, depth }); (kids[t.userId] || []).forEach((k) => walk(k, depth + 1)); };
  if (byId[user.id]) walk(byId[user.id], 0);

  const sum = (k) => team.reduce((n, t) => n + Number(t[k] || 0), 0);

  return (
    <div>
      <div className="team-filter" aria-label="Period">
        <div className="seg" role="tablist" aria-label="Period">
          {PERIODS.map(([k, label]) => (
            <button key={k} type="button" role="tab" aria-selected={period === k}
              className={period === k ? 'seg-btn active' : 'seg-btn'} onClick={() => setPeriod(k)}>{label}</button>
          ))}
        </div>
        {period === 'range' && (
          <span className="date-range">
            <input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} aria-label="From" />
            <span className="muted">to</span>
            <input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} aria-label="To" />
          </span>
        )}
        <span className="muted team-filter-note">{loading ? 'Updating...' : `Showing ${periodName}`}</span>
      </div>
      <div className={loading ? 'table-scroll is-loading' : 'table-scroll'}>
        <table>
          <thead>
            <tr>
              <th>Member</th><th>Role</th><th className="num">Tasks</th><th className="num">Open tasks</th><th className="num">Units balance</th><th className="num">Deals</th>
              <th className="num">Units sold</th><th className="num">Sales</th><th className="num">On credit</th>
              <th className="num">Activities</th><th className="num">New customers</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(({ t, depth }) => (
              <tr key={t.userId} className={t.active ? '' : 'inactive'}>
                <td>
                  <div className="tree-cell" style={{ paddingLeft: `${depth * 1.4}rem` }}>
                    {depth > 0 && <span className="task-branch" aria-hidden="true">↳</span>}
                    <span className="tree-name">{t.fullName}{t.userId === user.id ? ' (me)' : ''}</span>
                    {!t.active && <span className="badge off">inactive</span>}
                  </div>
                </td>
                <td>{t.role === 'TENANT_ADMIN' ? <span className="badge on">Admin</span>
                  : t.teamRoleName ? <span className="role-chip">{t.teamRoleName}</span> : <span className="muted">—</span>}</td>
                <td className="num">{fmt(t.totalTasks)}</td>
                <td className="num">{fmt(t.openTasks)}</td>
                <td className="num">{fmt(t.unitsBalance)}</td>
                <td className="num">{fmt(t.deals)}</td>
                <td className="num">{fmt(t.unitsSold)}</td>
                <td className="num">{money(t.salesAmount)}</td>
                <td className="num">{Number(t.creditOutstanding) > 0 ? money(t.creditOutstanding) : '-'}</td>
                <td className="num">{fmt(t.activities)}</td>
                <td className="num">{fmt(t.totalCustomers)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <th>Team total</th><th /><th className="num">{fmt(sum('totalTasks'))}</th><th className="num">{fmt(sum('openTasks'))}</th><th className="num">{fmt(team.filter((x) => x.role !== 'TENANT_ADMIN').reduce((n, x) => n + Number(x.unitsBalance || 0), 0))}</th>
              <th className="num">{fmt(sum('deals'))}</th><th className="num">{fmt(sum('unitsSold'))}</th>
              <th className="num">{money(sum('salesAmount'))}</th><th className="num">{money(sum('creditOutstanding'))}</th>
              <th className="num">{fmt(sum('activities'))}</th><th className="num">{fmt(sum('totalCustomers'))}</th>
            </tr>
          </tfoot>
        </table>
      </div>
      {team.length <= 1 && (
        <p className="muted hint">No one reports to you yet. Use "My members" to add people under you.</p>
      )}
      <p className="muted hint">
        Each row shows that person's own numbers for the chosen period: tasks assigned in it (for the admin: the tasks they assigned), sales and activities in it, and customers added in it. Units balance is what is still unsold on those tasks today.
        A deal is an activity where a sale was recorded.
      </p>
    </div>
  );
}
