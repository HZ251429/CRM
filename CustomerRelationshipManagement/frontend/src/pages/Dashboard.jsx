import { useEffect, useState } from 'react';
import { getDashboard, getTeam } from '../api.js';
import { money } from '../format.js';

export default function Dashboard({ user }) {
  const [selected, setSelected] = useState(user.id);
  const [d, setD] = useState(null);
  const [team, setTeam] = useState([]);
  const [error, setError] = useState('');

  useEffect(() => { getTeam().then(setTeam).catch((e) => setError(e.message)); }, []);
  useEffect(() => {
    setD(null);
    getDashboard(selected).then(setD).catch((e) => setError(e.message));
  }, [selected]);

  const names = Object.fromEntries(team.map((t) => [t.userId, t.fullName]));
  const hasTeam = team.length > 1;

  return (
    <div>
      {error && <p className="error">{error}</p>}

      {hasTeam && (
        <div className="viewing">
          <label>Viewing dashboard of
            <select value={selected} onChange={(e) => setSelected(Number(e.target.value))}>
              {team.map((t) => (
                <option key={t.userId} value={t.userId}>
                  {t.fullName}{t.userId === user.id ? ' (me)' : ''}
                </option>
              ))}
            </select>
          </label>
          {selected !== user.id && <button onClick={() => setSelected(user.id)}>Back to mine</button>}
        </div>
      )}

      {!d ? <p>Loading...</p> : (
        <>
          <p className="muted rollup">
            {d.userName}{d.teamSize > 0 ? ` + ${d.teamSize} team member${d.teamSize > 1 ? 's' : ''} (rolled up)` : ''}
          </p>
          <div className="cards">
            <div className="card"><h3>Customers</h3><p>{d.totalCustomers}</p></div>
            <div className="card"><h3>Deals</h3><p>{d.totalDeals}</p></div>
            <div className="card"><h3>Activities (last 7 days)</h3><p>{d.activitiesThisWeek}</p></div>
            <div className="card"><h3>Pipeline value</h3><p>{money(d.pipelineValue)}</p></div>
            <div className="card"><h3>Won value</h3><p>{money(d.wonValue)}</p></div>
          </div>
          <h3>Deals by stage</h3>
          <table>
            <thead><tr><th>Stage</th><th>Count</th></tr></thead>
            <tbody>
              {Object.entries(d.dealsByStage).map(([k, v]) => <tr key={k}><td>{k}</td><td>{v}</td></tr>)}
              {Object.keys(d.dealsByStage).length === 0 && <tr><td colSpan="2" className="muted">No deals yet</td></tr>}
            </tbody>
          </table>
        </>
      )}

      {hasTeam && (
        <>
          <h3 className="section">Team overview</h3>
          <table>
            <thead>
              <tr><th>Member</th><th>Reports to</th><th>Customers</th><th>Deals</th><th>Activities (7 days)</th>
                <th>Pipeline</th><th>Won</th><th></th></tr>
            </thead>
            <tbody>
              {team.map((t) => (
                <tr key={t.userId}>
                  <td>{t.fullName}{!t.active && <span className="badge off">inactive</span>}</td>
                  <td>{t.role === 'TENANT_ADMIN' ? '-' : (t.managerId && names[t.managerId] ? names[t.managerId] : 'Admin')}</td>
                  <td>{t.totalCustomers}</td><td>{t.totalDeals}</td><td>{t.activitiesThisWeek}</td>
                  <td>{money(t.pipelineValue)}</td><td>{money(t.wonValue)}</td>
                  <td><button onClick={() => { setSelected(t.userId); window.scrollTo({ top: 0, behavior: 'smooth' }); }}>View dashboard</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}