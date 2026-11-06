import { useEffect, useState } from 'react';
import { getTeam } from '../api.js';
import { money } from '../format.js';

const fmt = (n) => Number(n || 0).toLocaleString('en-IN');

/** Each visible person's own numbers, laid out along the reporting tree. */
export default function TeamOverview({ user }) {
  const [team, setTeam] = useState(null);
  const [error, setError] = useState('');
  useEffect(() => { getTeam().then(setTeam).catch((e) => setError(e.message)); }, []);

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
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Member</th><th className="num">Tasks</th><th className="num">Open tasks</th><th className="num">Units balance</th><th className="num">Deals</th>
              <th className="num">Units sold</th><th className="num">Sales</th><th className="num">On credit</th>
              <th className="num">Activities (7 days)</th><th className="num">Customers</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(({ t, depth }) => (
              <tr key={t.userId} className={t.active ? '' : 'inactive'}>
                <td>
                  <div className="tree-cell" style={{ paddingLeft: `${depth * 1.4}rem` }}>
                    {depth > 0 && <span className="task-branch" aria-hidden="true">↳</span>}
                    <span className="tree-name">{t.fullName}{t.userId === user.id ? ' (me)' : ''}</span>
                    {t.role === 'TENANT_ADMIN' && <span className="badge on">Admin</span>}
                    {!t.active && <span className="badge off">inactive</span>}
                  </div>
                </td>
                <td className="num">{fmt(t.totalTasks)}</td>
                <td className="num">{fmt(t.openTasks)}</td>
                <td className="num">{fmt(t.unitsBalance)}</td>
                <td className="num">{fmt(t.deals)}</td>
                <td className="num">{fmt(t.unitsSold)}</td>
                <td className="num">{money(t.salesAmount)}</td>
                <td className="num">{Number(t.creditOutstanding) > 0 ? money(t.creditOutstanding) : '-'}</td>
                <td className="num">{fmt(t.activitiesThisWeek)}</td>
                <td className="num">{fmt(t.totalCustomers)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <th>Team total</th><th className="num">{fmt(sum('totalTasks'))}</th><th className="num">{fmt(sum('openTasks'))}</th><th className="num">{fmt(team.filter((x) => x.role !== 'TENANT_ADMIN').reduce((n, x) => n + Number(x.unitsBalance || 0), 0))}</th>
              <th className="num">{fmt(sum('deals'))}</th><th className="num">{fmt(sum('unitsSold'))}</th>
              <th className="num">{money(sum('salesAmount'))}</th><th className="num">{money(sum('creditOutstanding'))}</th>
              <th className="num">{fmt(sum('activitiesThisWeek'))}</th><th className="num">{fmt(sum('totalCustomers'))}</th>
            </tr>
          </tfoot>
        </table>
      </div>
      {team.length <= 1 && (
        <p className="muted hint">No one reports to you yet. Use "My members" to add people under you.</p>
      )}
      <p className="muted hint">
        Each row shows that person's own numbers. Tasks are the tasks assigned to them (for the admin: the tasks they assigned).
        A deal is an activity where a sale was recorded.
      </p>
    </div>
  );
}
