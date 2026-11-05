import { useEffect, useState } from 'react';
import { tenantsApi } from '../api.js';
import { fmtDateTime, money } from '../format.js';

const empty = { name: '', code: '', adminFullName: '', adminUsername: '', adminPassword: '' };
const SECTIONS = ['Overview', 'Users', 'Customers', 'Deals', 'Activities'];

function TenantDetail({ id, onBack }) {
  const [data, setData] = useState(null);
  const [section, setSection] = useState('Overview');
  const [error, setError] = useState('');

  useEffect(() => { tenantsApi.details(id).then(setData).catch((e) => setError(e.message)); }, [id]);

  if (error) return <p className="error">{error}</p>;
  if (!data) return <p>Loading...</p>;

  const { tenant: t, users, customers, deals, activities } = data;
  const userName = Object.fromEntries(users.map((u) => [u.id, u.fullName || u.username]));
  const custName = Object.fromEntries(customers.map((c) => [c.id, `${c.firstName} ${c.lastName}`]));
  const count = { Users: users.length, Customers: customers.length, Deals: deals.length, Activities: activities.length };

  return (
    <div>
      <div className="viewing">
        <button onClick={onBack}>← All tenants</button>
        <h2>{t.name} <span className="badge on">{t.code}</span> <span className={t.active ? 'badge on' : 'badge off'}>{t.active ? 'Active' : 'Disabled'}</span></h2>
      </div>

      <div className="tabs">
        {SECTIONS.map((s) => (
          <button key={s} className={s === section ? 'tab active' : 'tab'} onClick={() => setSection(s)}>
            {s}{count[s] !== undefined ? ` (${count[s]})` : ''}
          </button>
        ))}
      </div>

      {section === 'Overview' && (
        <div className="cards">
          <div className="card"><h3>Users</h3><p>{t.userCount}</p></div>
          <div className="card"><h3>Customers</h3><p>{t.customerCount}</p></div>
          <div className="card"><h3>Deals</h3><p>{t.dealCount}</p></div>
          <div className="card"><h3>Activities</h3><p>{t.activityCount}</p></div>
          <div className="card"><h3>Pipeline value</h3><p>{money(t.pipelineValue)}</p></div>
          <div className="card"><h3>Won value</h3><p>{money(t.wonValue)}</p></div>
        </div>
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
          <thead><tr><th>Name</th><th>Email</th><th>Phone</th><th>Company</th><th>Status</th><th>Owner</th></tr></thead>
          <tbody>
            {customers.map((c) => (
              <tr key={c.id}>
                <td>{c.firstName} {c.lastName}</td><td>{c.email}</td><td>{c.phone}</td>
                <td>{c.company}</td><td>{c.status}</td><td>{userName[c.ownerId]}</td>
              </tr>
            ))}
            {customers.length === 0 && <tr><td colSpan="6" className="muted">No customers</td></tr>}
          </tbody>
        </table>
      )}

      {section === 'Deals' && (
        <table>
          <thead><tr><th>Title</th><th>Customer</th><th>Amount</th><th>Stage</th><th>Close date</th><th>Owner</th></tr></thead>
          <tbody>
            {deals.map((d) => (
              <tr key={d.id}>
                <td>{d.title}</td><td>{custName[d.customerId]}</td><td>{money(d.amount)}</td>
                <td>{d.stage}</td><td>{d.expectedCloseDate}</td><td>{userName[d.ownerId]}</td>
              </tr>
            ))}
            {deals.length === 0 && <tr><td colSpan="6" className="muted">No deals</td></tr>}
          </tbody>
        </table>
      )}

      {section === 'Activities' && (
        <table>
          <thead><tr><th>Type</th><th>Subject</th><th>Customer</th><th>Time</th><th>Owner</th></tr></thead>
          <tbody>
            {activities.map((a) => (
              <tr key={a.id}>
                <td>{a.type}</td><td>{a.subject}</td>
                <td>{custName[a.customerId]}</td><td>{fmtDateTime(a.activityTime)}</td><td>{userName[a.ownerId]}</td>
              </tr>
            ))}
            {activities.length === 0 && <tr><td colSpan="5" className="muted">No activities</td></tr>}
          </tbody>
        </table>
      )}
    </div>
  );
}

export default function Tenants() {
  const [tenants, setTenants] = useState([]);
  const [form, setForm] = useState(empty);
  const [error, setError] = useState('');
  const [openId, setOpenId] = useState(null);
  const [showForm, setShowForm] = useState(false);

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

  const total = (key) => tenants.reduce((s, t) => s + Number(t[key] || 0), 0);

  return (
    <div>
      {error && <p className="error">{error}</p>}

      <div className="cards">
        <div className="card"><h3>Tenants</h3><p>{tenants.length}</p></div>
        <div className="card"><h3>Users</h3><p>{total('userCount')}</p></div>
        <div className="card"><h3>Customers</h3><p>{total('customerCount')}</p></div>
        <div className="card"><h3>Deals</h3><p>{total('dealCount')}</p></div>
        <div className="card"><h3>Pipeline (all)</h3><p>{money(total('pipelineValue'))}</p></div>
        <div className="card"><h3>Won (all)</h3><p>{money(total('wonValue'))}</p></div>
      </div>

      <div className="list-head">
        <h3>All tenants</h3>
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
          <input name="adminSecret" type="password" minLength={6} placeholder="Admin password (min 6)"
            value={form.adminPassword} autoComplete="new-password"
            onChange={(e) => setForm({ ...form, adminPassword: e.target.value })} required />
          <button type="submit">Create tenant</button>
        </form>
      )}

      <table>
        <thead>
          <tr><th>Tenant</th><th>Code</th><th>Users</th><th>Customers</th><th>Deals</th><th>Pipeline</th><th>Won</th>
            <th>Status</th><th></th></tr>
        </thead>
        <tbody>
          {tenants.map((t) => (
            <tr key={t.id}>
              <td>{t.name}</td>
              <td><strong>{t.code}</strong></td>
              <td>{t.userCount}</td><td>{t.customerCount}</td><td>{t.dealCount}</td>
              <td>{money(t.pipelineValue)}</td><td>{money(t.wonValue)}</td>
              <td><span className={t.active ? 'badge on' : 'badge off'}>{t.active ? 'Active' : 'Disabled'}</span></td>
              <td>
                <button className="plain" onClick={() => setOpenId(t.id)}>View details</button>
                <button className="plain" onClick={() => toggle(t)}>{t.active ? 'Disable' : 'Enable'}</button>
              </td>
            </tr>
          ))}
          {tenants.length === 0 && <tr><td colSpan="9" className="muted">No tenants yet</td></tr>}
        </tbody>
      </table>
    </div>
  );
}