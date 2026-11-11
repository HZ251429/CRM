import { useEffect, useState } from 'react';
import { activitiesApi, enquiriesApi, productsApi } from '../api.js';
import { fmtDateTime, money, useOwners } from '../format.js';
import Modal, { Field } from '../Modal.jsx';
import ActivityForm from '../ActivityForm.jsx';
import SaleForm from '../SaleForm.jsx';
import { fmtWhen, typeLabel, typeOf } from '../ActivityDetail.jsx';

const STATUS = {
  NEW: { label: 'New', cls: 'badge todo' },
  CONTACTED: { label: 'Contacted', cls: 'badge busy' },
  WON: { label: 'Won', cls: 'badge on' },
  LOST: { label: 'Lost', cls: 'badge off' },
};
const emptyForm = { name: '', phone: '', email: '', address: '', company: '', requirement: '', productId: '', assigneeId: '' };

/**
 * Enquiries from new customers. Take an enquiry with the customer's details and hand it to yourself or someone below
 * you: the customer goes into that person's customer list, and they follow it up with activities and sales.
 * Progress follows from those (New → Contacted → Won); an enquiry that goes nowhere can be closed as Lost.
 */
export default function Enquiries({ user }) {
  const me = user.id;
  const [items, setItems] = useState(null);
  const [error, setError] = useState('');
  const [status, setStatus] = useState('');
  const [search, setSearch] = useState('');
  const [products, setProducts] = useState([]);
  const { team } = useOwners();
  const [form, setForm] = useState(null); // the new-enquiry form, or null
  const [formError, setFormError] = useState('');
  const [openId, setOpenId] = useState(null);
  const [history, setHistory] = useState(null);
  const [logFor, setLogFor] = useState(null); // { kind: 'activity' | 'sale', enquiry }

  const load = () => enquiriesApi.list().then(setItems).catch((e) => setError(e.message));
  useEffect(() => { load(); productsApi.list().then(setProducts).catch(() => {}); }, []);
  const current = items?.find((e) => e.id === openId) || null;
  useEffect(() => {
    setHistory(null);
    if (current) activitiesApi.forCustomer(current.customerId).then(setHistory).catch(() => setHistory([]));
  }, [openId, current?.activities, current?.sales]);

  // who can follow up: yourself, then everyone below you, in reporting order
  const people = (() => {
    const below = team.filter((t) => t.userId !== me && t.role === 'MEMBER' && t.active);
    const ids = new Set(below.map((t) => t.userId));
    const kids = {};
    below.forEach((t) => { const p = ids.has(t.managerId) ? t.managerId : 'root'; (kids[p] ||= []).push(t); });
    Object.values(kids).forEach((l) => l.sort((a, b) => a.fullName.localeCompare(b.fullName)));
    const out = [{ userId: me, label: `${user.fullName || 'Me'} (me)` }];
    const walk = (k, d) => (kids[k] || []).forEach((t) => {
      out.push({ userId: t.userId, label: `${'    '.repeat(d)}${d ? '└ ' : ''}${t.fullName}${t.teamRoleName ? ` (${t.teamRoleName})` : ''}` });
      walk(t.userId, d + 1);
    });
    walk('root', 0);
    return out;
  })();

  const openNew = () => { setForm({ ...emptyForm, assigneeId: String(people[1]?.userId || me) }); setFormError(''); };
  const set = (k, v) => setForm((f) => ({ ...f, [k]: v }));
  const submit = async (e) => {
    e.preventDefault();
    setFormError('');
    if (![form.phone, form.email, form.address].some((v) => v.trim())) {
      setFormError('Add at least a mobile number, email or address for the customer.');
      return;
    }
    try {
      const saved = await enquiriesApi.create({
        customer: { name: form.name, phone: form.phone, email: form.email, address: form.address, company: form.company },
        requirement: form.requirement, productId: form.productId ? Number(form.productId) : null, assigneeId: Number(form.assigneeId),
      });
      setForm(null);
      await load();
      setOpenId(saved.id);
    } catch (err) {
      setFormError(err.message);
    }
  };
  const changeStatus = async (e, s) => {
    try { await enquiriesApi.setStatus(e.id, s); load(); } catch (err) { setError(err.message); }
  };

  if (!items) return error ? <p className="error">{error}</p> : <p className="muted">Loading...</p>;
  const q = search.trim().toLowerCase();
  const shown = items.filter((e) => (!status || e.status === status)
    && (!q || [e.customerName, e.phone, e.email, e.company, e.requirement, e.assignedToName, e.productName].join(' ').toLowerCase().includes(q)));
  const count = (s) => items.filter((e) => !s || e.status === s).length;
  const who = (id, name) => (id === me ? 'You' : name);

  return (
    <div>
      {error && <p className="error">{error}</p>}
      <div className="toolbar">
        <input className="search" placeholder="Search customer, requirement, person..." value={search} onChange={(e) => setSearch(e.target.value)} />
        <button type="button" className="primary" onClick={openNew}>+ New enquiry</button>
      </div>
      <div className="chips" role="tablist" aria-label="Filter by status">
        {[['', 'All'], ...Object.entries(STATUS).map(([k, s]) => [k, s.label])].map(([k, label]) => (
          <button key={k || 'all'} type="button" role="tab" aria-selected={status === k}
            className={status === k ? 'chip active' : 'chip'} onClick={() => setStatus(k)}>
            {label} <span className="chip-count">{count(k)}</span>
          </button>
        ))}
      </div>

      <div className="table-scroll">
        <table className="act-table">
          <thead><tr><th>Customer</th><th>Enquiry</th><th>Follow-up by</th><th>Received</th><th>Status</th></tr></thead>
          <tbody>
            {shown.map((e) => (
              <tr key={e.id} className="clickable" tabIndex={0} onClick={() => setOpenId(e.id)}
                onKeyDown={(ev) => { if (ev.key === 'Enter') setOpenId(e.id); }}>
                <td className="two-line"><strong>{e.customerName}</strong><span className="muted">{e.phone || e.email || e.address || ''}</span></td>
                <td className="two-line"><span>{e.requirement || <span className="muted">—</span>}</span>{e.productName && <span className="muted">📦 {e.productName}</span>}</td>
                <td className="two-line"><span className={e.assignedToId === me ? 'member-chip me' : 'member-chip'}>{who(e.assignedToId, e.assignedToName)}</span>
                  <span className="muted">from {who(e.assignedById, e.assignedByName)}</span></td>
                <td className="nowrap">{fmtDateTime(e.createdAt)}</td>
                <td><span className={STATUS[e.status].cls}>{STATUS[e.status].label}</span></td>
              </tr>
            ))}
            {shown.length === 0 && (
              <tr><td colSpan="5" className="muted">{items.length ? 'No enquiries match.' : 'No enquiries yet. Use "+ New enquiry" when a new customer asks about something.'}</td></tr>
            )}
          </tbody>
        </table>
      </div>
      <p className="muted hint">The customer is added to the follow-up person's customers. Logging an activity marks the enquiry Contacted; a sale marks it Won.</p>

      {form && (
        <Modal title="New enquiry" onClose={() => setForm(null)}>
          <form onSubmit={submit} autoComplete="off">
            {formError && <p className="error" role="alert">{formError}</p>}
            <h4 className="detail-label">Customer</h4>
            <div className="form-grid">
              <Field label="Name" wide><input value={form.name} onChange={(e) => set('name', e.target.value)} required autoFocus maxLength={250} placeholder="e.g. Meera Iyer" /></Field>
              <Field label="Mobile"><input type="tel" value={form.phone} onChange={(e) => set('phone', e.target.value)} maxLength={25}
                pattern="[+0-9 ()\-.]{7,25}" title="Digits, with or without country code" placeholder="98765 43210" /></Field>
              <Field label="Email"><input type="email" value={form.email} onChange={(e) => set('email', e.target.value)} maxLength={254} placeholder="name@example.com" /></Field>
              <Field label="Address" wide><textarea rows={2} className="short" value={form.address} onChange={(e) => set('address', e.target.value)} maxLength={500} placeholder="Street, area, city" /></Field>
              <Field label="Company (optional)" wide><input value={form.company} onChange={(e) => set('company', e.target.value)} maxLength={150} /></Field>
            </div>
            <p className="muted hint">Name and at least one of mobile, email or address.</p>
            <h4 className="detail-label sub-head">Enquiry</h4>
            <div className="form-grid">
              <Field label="What are they asking for?" wide>
                <textarea rows={3} className="short" value={form.requirement} onChange={(e) => set('requirement', e.target.value)} maxLength={1000}
                  placeholder="e.g. Wants 2 purifiers for the office, asked for a demo next week" />
              </Field>
              <Field label="Product (optional)">
                <select value={form.productId} onChange={(e) => set('productId', e.target.value)}>
                  <option value="">Not sure yet</option>
                  {products.filter((p) => p.active).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
                </select>
              </Field>
              <Field label="Follow-up by">
                <select value={form.assigneeId} onChange={(e) => set('assigneeId', e.target.value)} required>
                  {people.map((p) => <option key={p.userId} value={p.userId}>{p.label}</option>)}
                </select>
              </Field>
            </div>
            <div className="modal-actions">
              <button type="button" onClick={() => setForm(null)}>Cancel</button>
              <button type="submit" className="primary">Save enquiry</button>
            </div>
          </form>
        </Modal>
      )}

      {current && !logFor && (
        <Modal title={`Enquiry from ${current.customerName}`} onClose={() => setOpenId(null)} wide>
          <dl className="detail-grid">
            <dt>Status</dt><dd><span className={STATUS[current.status].cls}>{STATUS[current.status].label}</span></dd>
            <dt>Mobile</dt><dd>{current.phone ? <a href={`tel:${current.phone}`}>{current.phone}</a> : '—'}</dd>
            <dt>Email</dt><dd>{current.email ? <a href={`mailto:${current.email}`}>{current.email}</a> : '—'}</dd>
            <dt>Address</dt><dd className="pre-line">{current.address || '—'}</dd>
            {current.company && (<><dt>Company</dt><dd>{current.company}</dd></>)}
            <dt>Asking for</dt><dd className="pre-line">{current.requirement || '—'}{current.productName ? ` (📦 ${current.productName})` : ''}</dd>
            <dt>Follow-up by</dt><dd>{who(current.assignedToId, current.assignedToName)} · from {who(current.assignedById, current.assignedByName)} on {fmtDateTime(current.createdAt)}</dd>
          </dl>
          <div className="detail-sale">
            <div><span className="muted">Activities</span><strong>{current.activities}</strong></div>
            <div><span className="muted">Sales</span><strong>{current.sales}</strong></div>
            <div><span className="muted">Sales value</span><strong>{money(current.salesAmount)}</strong></div>
          </div>
          <h4 className="detail-label sub-head">History with this customer</h4>
          {history === null ? <p className="muted">Loading...</p> : history.length === 0 ? <p className="muted">Nothing logged yet.</p> : (
            <ul className="mini-list">
              {[...history].sort((a, b) => String(b.activityTime).localeCompare(String(a.activityTime))).slice(0, 10).map((a) => (
                <li key={a.id}>
                  <span className="nowrap muted">{fmtWhen(a.activityTime)}</span>
                  <span className="mini-title"><span aria-hidden="true">{typeOf(a.type).icon}</span> {a.subject} <small className="muted">· {typeLabel(a)}</small></span>
                  <span />
                  <span className="mini-sale">{a.saleAmount != null ? money(a.saleAmount) : ''}</span>
                </li>
              ))}
            </ul>
          )}
          <div className="modal-actions">
            {current.status === 'LOST'
              ? <button type="button" onClick={() => changeStatus(current, 'OPEN')}>Reopen</button>
              : current.status !== 'WON' && <button type="button" className="danger-outline" onClick={() => changeStatus(current, 'LOST')}>Close as lost</button>}
            <span className="spacer" />
            <button type="button" onClick={() => setOpenId(null)}>Close</button>
            <button type="button" onClick={() => setLogFor({ kind: 'activity', enquiry: current })}>📝 Log activity</button>
            <button type="button" className="primary" onClick={() => setLogFor({ kind: 'sale', enquiry: current })}>💰 Record sale</button>
          </div>
        </Modal>
      )}

      {logFor?.kind === 'activity' && (
        <ActivityForm customer={{ id: logFor.enquiry.customerId, name: logFor.enquiry.customerName }}
          onClose={() => setLogFor(null)} onSaved={() => { setLogFor(null); load(); }} />
      )}
      {logFor?.kind === 'sale' && (
        <SaleForm user={user} customer={{ id: logFor.enquiry.customerId, name: logFor.enquiry.customerName }}
          productId={logFor.enquiry.productId} onClose={() => setLogFor(null)} onSaved={() => { setLogFor(null); load(); }} />
      )}
    </div>
  );
}
