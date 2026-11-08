import { useEffect, useState } from 'react';
import { tenantsApi } from '../api.js';
import { fmtDateTime, money } from '../format.js';
import Icon from '../Icon.jsx';
import { StatusBadge } from '../TaskBits.jsx';

const empty = { name: '', code: '', adminFullName: '', adminUsername: '', adminPassword: '', adminEmail: '', adminMobile: '' };
const fmt = (n) => Number(n || 0).toLocaleString('en-IN');
const rupees = (n) => Number(n || 0).toLocaleString('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });
export const lastSeen = (t) => (t ? fmtDateTime(t) : 'No activity yet');

/** Everything about one tenant, including its sales figures (read-only for the super admin). */
export function TenantDetail({ id, onBack }) {
  const [data, setData] = useState(null);
  const [section, setSection] = useState('Overview');
  const [error, setError] = useState('');

  useEffect(() => { tenantsApi.details(id).then(setData).catch((e) => setError(e.message)); }, [id]);

  if (error) return <p className="error">{error}</p>;
  if (!data) return <p className="muted">Loading...</p>;

  const { tenant: t, sales: s, users, customers, activities, products, tasks } = data;
  const userName = Object.fromEntries(users.map((u) => [u.id, u.fullName || u.username]));
  const productName = Object.fromEntries(products.map((p) => [p.id, p.name]));
  // customers and activities lists hold the latest 200; the counts are the real totals
  const count = { Users: users.length, Customers: t.customerCount, Activities: t.activityCount, Products: products.length, Tasks: tasks.length };
  const pct = s.unitsAssigned > 0 ? Math.min(100, Math.round((s.unitsSoldOnTasks / s.unitsAssigned) * 100)) : 0;

  return (
    <div>
      <div className="tenant-head">
        <button type="button" onClick={onBack}>← Back</button>
        <h2>{t.name}</h2>
        <span className="badge on">{t.code}</span>
        <span className={t.active ? 'badge on' : 'badge off'}>{t.active ? 'Active' : 'Disabled'}</span>
        <span className="muted small left">Created {fmtDateTime(t.createdAt)} · last activity {lastSeen(t.lastActivityAt)}</span>
      </div>

      <div className="tabs">
        {['Overview', 'Users', 'Customers', 'Activities', 'Products', 'Tasks'].map((name) => (
          <button key={name} className={name === section ? 'tab active' : 'tab'} onClick={() => setSection(name)}>
            {name}{count[name] !== undefined ? ` (${count[name]})` : ''}
          </button>
        ))}
      </div>

      {section === 'Overview' && (
        <>
          <h3 className="section-title">Sales</h3>
          <div className="dash-cards tenant-cards">
            <div className="dcard"><span className="fig-label"><Icon name="rupee" size={14} /> Sales</span><strong className="big-num">{rupees(s.salesAmount)}</strong><span className="fig-sub">{fmt(s.deals)} deals</span></div>
            <div className="dcard"><span className="fig-label"><Icon name="card" size={14} /> On credit</span><strong className="big-num">{rupees(s.creditAmount)}</strong><span className="fig-sub">still to collect</span></div>
            <div className="dcard"><span className="fig-label"><Icon name="cart" size={14} /> Units sold</span><strong className="big-num">{fmt(s.unitsSold)}</strong><span className="fig-sub">across all products</span></div>
            <div className="dcard">
              <span className="fig-label"><Icon name="target" size={14} /> Task progress</span>
              <strong className="big-num">{pct}%</strong>
              <span className="progress"><span style={{ width: `${pct}%` }} /></span>
              <span className="fig-sub">{fmt(s.unitsSoldOnTasks)} of {fmt(s.unitsAssigned)} units · {fmt(s.openTasks)} open, {fmt(s.completedTasks)} done</span>
            </div>
          </div>
          <h3 className="section-title">Size</h3>
          <div className="stat-strip">
            <div><span className="muted">Users</span><strong>{fmt(t.userCount)}</strong></div>
            <div><span className="muted">Admins</span><strong>{fmt(t.adminCount)}</strong></div>
            <div><span className="muted">Customers</span><strong>{fmt(t.customerCount)}</strong></div>
            <div><span className="muted">Products</span><strong>{fmt(t.productCount)}</strong></div>
            <div><span className="muted">Tasks</span><strong>{fmt(t.taskCount)}</strong></div>
            <div><span className="muted">Activities (7 days)</span><strong>{fmt(t.activitiesLast7Days)}</strong></div>
          </div>
        </>
      )}

      {section === 'Users' && (
        <table>
          <thead><tr><th>Name</th><th>Username</th><th>Access</th><th>Role</th><th>Reports to</th><th>Status</th></tr></thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id}>
                <td>{u.fullName}</td><td>{u.username}</td>
                <td>{u.role === 'TENANT_ADMIN' ? 'Admin' : 'Member'}</td>
                <td>{u.teamRoleName || '-'}</td>
                <td>{u.role === 'TENANT_ADMIN' ? '-' : (u.managerId ? userName[u.managerId] : 'Admin')}</td>
                <td><span className={u.active ? 'badge on' : 'badge off'}>{u.active ? 'Active' : 'Inactive'}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {section === 'Customers' && (
        <table>
          <thead><tr><th>Name</th><th>Email</th><th>Phone</th><th>Company</th><th>Owner</th></tr></thead>
          <tbody>
            {customers.map((c) => (
              <tr key={c.id}>
                <td>{c.firstName} {c.lastName}</td><td>{c.email}</td><td>{c.phone}</td>
                <td>{c.company}</td><td>{userName[c.ownerId]}</td>
              </tr>
            ))}
            {customers.length === 0 && <tr><td colSpan="5" className="muted">No customers</td></tr>}
            {t.customerCount > customers.length && <tr><td colSpan="5" className="muted">Showing the newest {fmt(customers.length)} of {fmt(t.customerCount)} customers.</td></tr>}
          </tbody>
        </table>
      )}

      {section === 'Activities' && (
        <table>
          <thead><tr><th>When</th><th>Type</th><th>Title</th><th>Customer</th><th>Member</th><th className="num">Sale</th></tr></thead>
          <tbody>
            {[...activities].sort((a, b) => String(b.activityTime).localeCompare(String(a.activityTime))).map((a) => (
              <tr key={a.id}>
                <td className="nowrap">{fmtDateTime(a.activityTime)}</td><td>{a.type}</td><td>{a.subject}</td>
                <td>{a.customerName}</td><td>{userName[a.ownerId]}</td>
                <td className="num">{a.productId ? `${fmt(a.unitsSold)} × ${productName[a.productId] || ''}${a.saleAmount != null ? ` · ${money(a.saleAmount)}` : ''}` : '—'}</td>
              </tr>
            ))}
            {activities.length === 0 && <tr><td colSpan="6" className="muted">No activities</td></tr>}
            {t.activityCount > activities.length && <tr><td colSpan="6" className="muted">Showing the latest {fmt(activities.length)} of {fmt(t.activityCount)} activities.</td></tr>}
          </tbody>
        </table>
      )}

      {section === 'Products' && (
        <table>
          <thead><tr><th>Product</th><th>Unit</th><th>Status</th></tr></thead>
          <tbody>
            {products.map((p) => (
              <tr key={p.id}><td>{p.name}</td><td>{p.unit}</td>
                <td><span className={p.active ? 'badge on' : 'badge off'}>{p.active ? 'Active' : 'Inactive'}</span></td></tr>
            ))}
            {products.length === 0 && <tr><td colSpan="3" className="muted">No products</td></tr>}
          </tbody>
        </table>
      )}

      {section === 'Tasks' && (
        <table>
          <thead><tr><th>Task</th><th>Assigned to</th><th>Assigned by</th><th className="num">Sold</th><th>Status</th></tr></thead>
          <tbody>
            {tasks.map((k) => (
              <tr key={k.id}>
                <td className="two-line"><strong>{k.parentId ? '↳ ' : ''}{k.title || k.productName}</strong><span className="muted">{k.productName} · {fmtDateTime(k.createdAt)}</span></td>
                <td>{k.assignedToName}</td><td>{k.assignedByName}</td>
                <td className="num">{fmt(k.soldUnits)} / {fmt(k.units)} {k.unit}</td>
                <td><StatusBadge status={k.status} /></td>
              </tr>
            ))}
            {tasks.length === 0 && <tr><td colSpan="5" className="muted">No tasks</td></tr>}
          </tbody>
        </table>
      )}
    </div>
  );
}

/** Super admin: list of tenants (size and activity only), create and enable/disable tenants. */
export default function Tenants() {
  const [tenants, setTenants] = useState([]);
  const [form, setForm] = useState(empty);
  const [error, setError] = useState('');
  const [openId, setOpenId] = useState(null);
  const [showForm, setShowForm] = useState(false);
  const [q, setQ] = useState('');

  const load = () => tenantsApi.list().then(setTenants).catch((e) => setError(e.message));
  useEffect(() => { load(); }, []);

  if (openId) return <TenantDetail id={openId} onBack={() => { setOpenId(null); load(); }} />;

  const onCode = (e) => setForm({ ...form, code: e.target.value.replace(/[^A-Za-z0-9]/g, '').toUpperCase().slice(0, 5) });

  const submit = async (e) => {
    e.preventDefault();
    setError('');
    try {
      await tenantsApi.create(form);
      setForm(empty);
      setShowForm(false);
      load();
    } catch (err) {
      setError(err.message);
    }
  };

  const toggle = async (t) => { await tenantsApi.setActive(t.id, !t.active); load(); };
  const query = q.trim().toLowerCase();
  const shown = tenants.filter((t) => !query || `${t.name} ${t.code}`.toLowerCase().includes(query));

  return (
    <div>
      {error && <p className="error">{error}</p>}

      <div className="toolbar">
        <input className="search" placeholder="Search tenant name or code..." value={q} onChange={(e) => setQ(e.target.value)} />
        <button type="button" className="primary" onClick={() => { setForm(empty); setShowForm(!showForm); }}>
          {showForm ? 'Close' : '+ Create tenant'}
        </button>
      </div>

      {showForm && (
        <form onSubmit={submit} className="form" autoComplete="off">
          <input name="tenantName" placeholder="Tenant / company name" value={form.name} autoComplete="off"
            onChange={(e) => setForm({ ...form, name: e.target.value })} required />
          <input name="tenantCode" placeholder="Code (max 5)" maxLength={5} value={form.code} autoComplete="off"
            onChange={onCode} title="Unique tenant code, up to 5 letters/digits" required style={{ width: '8rem' }} />
          <input name="adminDisplayName" placeholder="Admin full name" value={form.adminFullName} autoComplete="off"
            onChange={(e) => setForm({ ...form, adminFullName: e.target.value })} />
          <span className="suffix-group">
            <input name="adminLoginName" placeholder="Admin username" value={form.adminUsername}
              autoComplete="new-password" pattern="[A-Za-z0-9._\-]{1,30}" title="Letters, digits, . _ - only"
              onChange={(e) => setForm({ ...form, adminUsername: e.target.value })} required />
            <span className="suffix">@{form.code || 'CODE'}</span>
          </span>
          <input name="adminSecret" type="password" minLength={8} maxLength={72} placeholder="Admin password (min 8)"
            value={form.adminPassword} autoComplete="new-password"
            onChange={(e) => setForm({ ...form, adminPassword: e.target.value })} required />
          <input name="adminEmail" type="email" placeholder="Admin email (optional)" maxLength={254} value={form.adminEmail}
            autoComplete="off" onChange={(e) => setForm({ ...form, adminEmail: e.target.value })} />
          <input name="adminMobile" type="tel" placeholder="Admin mobile (optional)" maxLength={25} value={form.adminMobile}
            autoComplete="off" pattern="[+0-9 ()\-.]{7,25}" title="Digits, with or without country code"
            onChange={(e) => setForm({ ...form, adminMobile: e.target.value })} />
          <button type="submit">Create tenant</button>
        </form>
      )}

      <div className="table-scroll">
        <table className="act-table">
          <thead>
            <tr><th>Tenant</th><th className="num">Users</th><th className="num">Customers</th><th className="num">Products</th>
              <th className="num">Tasks</th><th>Last activity</th><th>Status</th><th /></tr>
          </thead>
          <tbody>
            {shown.map((t) => (
              <tr key={t.id} className="clickable" onClick={() => setOpenId(t.id)}>
                <td className="two-line"><strong>{t.name}</strong><span className="muted">{t.code} · since {fmtDateTime(t.createdAt)}</span></td>
                <td className="num">{fmt(t.userCount)}</td><td className="num">{fmt(t.customerCount)}</td>
                <td className="num">{fmt(t.productCount)}</td><td className="num">{fmt(t.taskCount)}</td>
                <td className="two-line"><span>{lastSeen(t.lastActivityAt)}</span><span className="muted">{fmt(t.activitiesLast7Days)} in last 7 days</span></td>
                <td><span className={t.active ? 'badge on' : 'badge off'}>{t.active ? 'Active' : 'Disabled'}</span></td>
                <td onClick={(e) => e.stopPropagation()}>
                  <button className="plain" onClick={() => setOpenId(t.id)}>Details</button>
                  <button className="plain" onClick={() => toggle(t)}>{t.active ? 'Disable' : 'Enable'}</button>
                </td>
              </tr>
            ))}
            {shown.length === 0 && <tr><td colSpan="8" className="muted">{tenants.length ? 'No tenants match.' : 'No tenants yet. Use "+ Create tenant".'}</td></tr>}
          </tbody>
        </table>
      </div>
      <p className="muted hint">Sales figures for each tenant are in its details.</p>
    </div>
  );
}
