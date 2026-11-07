import { useEffect, useState } from 'react';
import { tenantsApi } from '../api.js';
import { fmtDateTime } from '../format.js';
import Icon from '../Icon.jsx';
import { TenantDetail, lastSeen } from './Tenants.jsx';

const fmt = (n) => Number(n || 0).toLocaleString('en-IN');

/**
 * Super admin dashboard: the platform at a glance (tenants, people, activity). No sales or pipeline figures here;
 * those belong to each tenant and are shown in the tenant's details.
 */
export default function PlatformDashboard({ user }) {
  const [tenants, setTenants] = useState(null);
  const [error, setError] = useState('');
  const [openId, setOpenId] = useState(null);

  const load = () => tenantsApi.list().then(setTenants).catch((e) => setError(e.message));
  useEffect(() => { load(); }, []);

  if (openId) return <TenantDetail id={openId} onBack={() => { setOpenId(null); load(); }} />;
  if (error) return <p className="error">{error}</p>;
  if (!tenants) return <p className="muted">Loading...</p>;

  const sum = (k) => tenants.reduce((n, t) => n + Number(t[k] || 0), 0);
  const active = tenants.filter((t) => t.active).length;
  const monthStart = new Date(); monthStart.setDate(1); monthStart.setHours(0, 0, 0, 0);
  const newThisMonth = tenants.filter((t) => new Date(t.createdAt) >= monthStart).length;
  const quiet = tenants.filter((t) => t.active && t.activitiesLast7Days === 0);
  const newest = [...tenants].sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt))).slice(0, 5);
  const busiest = [...tenants].filter((t) => t.activitiesLast7Days > 0).sort((a, b) => b.activitiesLast7Days - a.activitiesLast7Days).slice(0, 5);
  const hour = new Date().getHours();
  const greeting = hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening';

  const TenantRow = ({ t, right }) => (
    <li>
      <button type="button" onClick={() => setOpenId(t.id)}>
        <span className="recent-main">
          <strong>{t.name} <span className="muted">· {t.code}</span></strong>
          <span className="muted">{fmt(t.userCount)} users · {fmt(t.customerCount)} customers</span>
        </span>
        <span className="recent-amt">{right}</span>
      </button>
    </li>
  );

  return (
    <div className="dash">
      <section className="dash-hero">
        <div className="hero-who">
          <span className="avatar" aria-hidden="true"><Icon name="chart" size={20} /></span>
          <div>
            <h2>{greeting}, {user.fullName || 'Super Admin'}</h2>
            <p className="muted"><Icon name="calendar" size={15} /> {new Date().toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })} · platform overview</p>
          </div>
        </div>
      </section>

      <div className="dash-cards platform-cards">
        <section className="dcard">
          <h3 className="dcard-title"><span className="icon-chip small tone-indigo"><Icon name="target" size={16} /></span> Tenants</h3>
          <strong className="big-num">{fmt(tenants.length)}</strong>
          <span className="fig-sub">{fmt(active)} active · {fmt(tenants.length - active)} disabled · {fmt(newThisMonth)} new this month</span>
        </section>
        <section className="dcard">
          <h3 className="dcard-title"><span className="icon-chip small tone-sky"><Icon name="users" size={16} /></span> People</h3>
          <strong className="big-num">{fmt(sum('userCount'))}</strong>
          <span className="fig-sub">{fmt(sum('adminCount'))} admins · {fmt(sum('userCount') - sum('adminCount'))} members</span>
        </section>
        <section className="dcard">
          <h3 className="dcard-title"><span className="icon-chip small tone-emerald"><Icon name="user" size={16} /></span> Customers</h3>
          <strong className="big-num">{fmt(sum('customerCount'))}</strong>
          <span className="fig-sub">across all tenants</span>
        </section>
        <section className="dcard">
          <h3 className="dcard-title"><span className="icon-chip small tone-amber"><Icon name="note" size={16} /></span> Activity</h3>
          <strong className="big-num">{fmt(sum('activitiesLast7Days'))}</strong>
          <span className="fig-sub">activities logged in the last 7 days</span>
        </section>
      </div>

      <div className="dash-layout platform-layout">
        <section className="dcard">
          <div className="panel-head">
            <h3 className="dcard-title"><span className="icon-chip small tone-indigo"><Icon name="box" size={16} /></span> All tenants</h3>
          </div>
          <div className="table-scroll">
            <table className="act-table">
              <thead><tr><th>Tenant</th><th className="num">Users</th><th className="num">Customers</th><th>Last activity</th><th>Status</th></tr></thead>
              <tbody>
                {tenants.map((t) => (
                  <tr key={t.id} className="clickable" tabIndex={0} onClick={() => setOpenId(t.id)}
                    onKeyDown={(e) => { if (e.key === 'Enter') setOpenId(t.id); }}>
                    <td className="two-line"><strong>{t.name}</strong><span className="muted">{t.code}</span></td>
                    <td className="num">{fmt(t.userCount)}</td>
                    <td className="num">{fmt(t.customerCount)}</td>
                    <td>{lastSeen(t.lastActivityAt)}</td>
                    <td><span className={t.active ? 'badge on' : 'badge off'}>{t.active ? 'Active' : 'Disabled'}</span></td>
                  </tr>
                ))}
                {tenants.length === 0 && <tr><td colSpan="5" className="muted">No tenants yet. Create one on the Tenants page.</td></tr>}
              </tbody>
            </table>
          </div>
          <p className="muted hint">Click a tenant to see its users, customers, tasks and sales.</p>
        </section>

        <aside className="dash-rail">
          <section className="dcard rail-card">
            <h3 className="dcard-title"><span className="icon-chip small tone-emerald"><Icon name="trend" size={16} /></span> Most active (7 days)</h3>
            {busiest.length === 0 ? <p className="muted">No activity in the last 7 days.</p> : (
              <ul className="recent-list">{busiest.map((t) => <TenantRow key={t.id} t={t} right={<><strong>{fmt(t.activitiesLast7Days)}</strong><span className="muted">activities</span></>} />)}</ul>
            )}
          </section>
          <section className="dcard rail-card">
            <h3 className="dcard-title"><span className="icon-chip small tone-sky"><Icon name="calendar" size={16} /></span> Newest tenants</h3>
            {newest.length === 0 ? <p className="muted">None yet.</p> : (
              <ul className="recent-list">{newest.map((t) => <TenantRow key={t.id} t={t} right={<span className="muted">{fmtDateTime(t.createdAt)}</span>} />)}</ul>
            )}
          </section>
          {quiet.length > 0 && (
            <section className="dcard rail-card">
              <h3 className="dcard-title"><span className="icon-chip small tone-amber"><Icon name="clock" size={16} /></span> Quiet this week</h3>
              <ul className="recent-list">{quiet.slice(0, 5).map((t) => <TenantRow key={t.id} t={t} right={<span className="muted">{lastSeen(t.lastActivityAt)}</span>} />)}</ul>
            </section>
          )}
        </aside>
      </div>
    </div>
  );
}
