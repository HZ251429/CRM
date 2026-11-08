import { useEffect, useState } from 'react';
import { activitiesApi, customersApi } from '../api.js';
import { money, useOwners } from '../format.js';
import Modal, { Field, ConfirmDialog } from '../Modal.jsx';
import { PayBadge, fmtNum, fmtWhen, typeOf } from '../ActivityDetail.jsx';

const empty = { firstName: '', lastName: '', email: '', phone: '', company: '' };

/** Customers as a short list; click a row for contact details and the customer's activity history. */
export default function Customers({ user }) {
  const [data, setData] = useState({ content: [], page: 0, totalPages: 0, totalElements: 0 });
  const [loaded, setLoaded] = useState(false);
  const [q, setQ] = useState('');
  const [ownerFilter, setOwnerFilter] = useState('');
  const [page, setPage] = useState(0);
  const [form, setForm] = useState(empty);
  const [editingId, setEditingId] = useState(null);
  const [open, setOpen] = useState(false);
  const [viewing, setViewing] = useState(null);
  const [activities, setActivities] = useState([]);
  const [error, setError] = useState('');
  const [formError, setFormError] = useState('');
  const [toDelete, setToDelete] = useState(null);
  const { names, multi, team } = useOwners();

  // activity summary (count, last contact, sales, credit) only for the customers on this page, worked out on the server
  const [stats, setStats] = useState({});
  const load = () => customersApi.search(q, page, 15, ownerFilter)
    .then((r) => {
      setData(r);
      setLoaded(true);
      const ids = r.content.map((c) => c.id);
      if (ids.length) activitiesApi.stats(ids).then(setStats).catch(() => {});
    }).catch((e) => setError(e.message));
  useEffect(() => { const t = setTimeout(load, 250); return () => clearTimeout(t); }, [q, page, ownerFilter]);
  // the open customer's own activities
  useEffect(() => {
    setActivities([]);
    if (viewing) activitiesApi.forCustomer(viewing.id).then(setActivities).catch(() => {});
  }, [viewing?.id]);

  const statsOf = (id) => {
    const s = stats[id] || {};
    return { count: Number(s.count || 0), last: s.last || null, sales: Number(s.sales || 0), credit: Number(s.credit || 0),
      deals: Number(s.deals || 0), list: viewing && viewing.id === id ? activities : [] };
  };
  const ownerName = (id) => (id === user.id ? `${names[id] || 'Me'} (me)` : names[id] || '');

  const onChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });
  const openAdd = () => { setEditingId(null); setForm(empty); setFormError(''); setOpen(true); };
  const openEdit = (c) => {
    setViewing(null);
    setEditingId(c.id);
    setForm({ firstName: c.firstName, lastName: c.lastName, email: c.email, phone: c.phone || '', company: c.company || '' });
    setFormError('');
    setOpen(true);
  };
  const close = () => setOpen(false);

  const submit = async (e) => {
    e.preventDefault();
    setFormError('');
    try {
      if (editingId) await customersApi.update(editingId, form);
      else await customersApi.create(form);
      close();
      load();
    } catch (err) {
      setFormError(err.message);
    }
  };

  const remove = async () => {
    const c = toDelete;
    setToDelete(null);
    setViewing(null);
    try {
      await customersApi.remove(c.id);
      load();
    } catch (err) {
      setError(err.message);
    }
  };

  const cols = multi ? 5 : 4;
  const v = viewing ? statsOf(viewing.id) : null;

  return (
    <div>
      {error && <p className="error">{error}</p>}

      <div className="toolbar">
        <input className="search" placeholder="Search name, email, company..." value={q}
          onChange={(e) => { setPage(0); setQ(e.target.value); }} />
        {multi && (
          <select className="filter-select" value={ownerFilter} onChange={(e) => { setPage(0); setOwnerFilter(e.target.value); }}
            title="Filter by owner">
            <option value="">All owners</option>
            {team.map((t) => <option key={t.userId} value={t.userId}>{t.userId === user.id ? `${t.fullName} (me)` : t.fullName}</option>)}
          </select>
        )}
        {(q || ownerFilter) && (
          <button type="button" onClick={() => { setPage(0); setQ(''); setOwnerFilter(''); }}>Clear</button>
        )}
        <button type="button" className="primary" onClick={openAdd}>+ Add customer</button>
      </div>

      <div className="table-scroll">
        <table className="act-table">
          <thead>
            <tr><th>Customer</th><th>Contact</th>{multi && <th>Owner</th>}<th>Last activity</th><th className="num">Sales</th></tr>
          </thead>
          <tbody>
            {data.content.map((c) => {
              const s = statsOf(c.id);
              return (
                <tr key={c.id} className="clickable" tabIndex={0} onClick={() => setViewing(c)}
                  onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setViewing(c); } }}>
                  <td className="two-line">
                    <strong>{c.firstName} {c.lastName}</strong>
                    <span className="muted">{c.company || '—'}</span>
                  </td>
                  <td className="two-line">
                    <span>{c.email}</span>
                    <span className="muted">{c.phone || '—'}</span>
                  </td>
                  {multi && <td><span className={c.ownerId === user.id ? 'member-chip me' : 'member-chip'}>{ownerName(c.ownerId)}</span></td>}
                  <td className="two-line">
                    <span>{s.last ? fmtWhen(s.last) : <span className="muted">No activity yet</span>}</span>
                    <span className="muted">{s.count > 0 ? `${s.count} activit${s.count === 1 ? 'y' : 'ies'}` : ''}</span>
                  </td>
                  <td className="num two-line">
                    <strong>{s.deals > 0 ? money(s.sales) : <span className="muted">—</span>}</strong>
                    {s.credit > 0 && <span className="pay-badge credit">⏳ {money(s.credit)} credit</span>}
                  </td>
                </tr>
              );
            })}
            {loaded && data.content.length === 0 && (
              <tr><td colSpan={cols} className="muted">{q || ownerFilter ? 'No customers match.' : 'No customers yet. Use "+ Add customer".'}</td></tr>
            )}
            {!loaded && !error && <tr><td colSpan={cols} className="muted">Loading...</td></tr>}
          </tbody>
        </table>
      </div>

      {data.totalPages > 1 && (
        <div className="pager">
          <button disabled={page === 0} onClick={() => setPage(page - 1)}>Prev</button>
          <span>Page {data.page + 1} of {data.totalPages} ({fmtNum(data.totalElements)} customers)</span>
          <button disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
        </div>
      )}
      {data.totalPages <= 1 && data.totalElements > 0 && <p className="muted hint">{fmtNum(data.totalElements)} customers</p>}

      {viewing && (
        <Modal wide title={`${viewing.firstName} ${viewing.lastName}`} onClose={() => setViewing(null)}>
          <dl className="detail-grid">
            <dt>Company</dt><dd>{viewing.company || '—'}</dd>
            <dt>Email</dt><dd><a href={`mailto:${viewing.email}`}>{viewing.email}</a></dd>
            <dt>Phone</dt><dd>{viewing.phone ? <a href={`tel:${viewing.phone}`}>{viewing.phone}</a> : '—'}</dd>
            {multi && (<><dt>Owner</dt><dd><span className="member-chip">{ownerName(viewing.ownerId)}</span></dd></>)}
            <dt>Added</dt><dd>{viewing.createdAt ? fmtWhen(viewing.createdAt, true) : '—'}</dd>
          </dl>
          <div className="detail-sale">
            <div><span className="muted">Activities</span><strong>{v.count}</strong></div>
            <div><span className="muted">Deals</span><strong>{v.deals}</strong></div>
            <div><span className="muted">Sales</span><strong>{money(v.sales)}</strong></div>
            <div><span className="muted">On credit</span><strong className={v.credit > 0 ? 'credit-text' : ''}>{money(v.credit)}</strong></div>
          </div>
          <h4 className="detail-label">Recent activities</h4>
          {v.list.length === 0 ? <p className="muted">Nothing logged with this customer yet.</p> : (
            <ul className="mini-list">
              {[...v.list].sort((a, b) => String(b.activityTime).localeCompare(String(a.activityTime))).slice(0, 8).map((a) => (
                <li key={a.id}>
                  <span className="nowrap muted">{fmtWhen(a.activityTime)}</span>
                  <span className="mini-title"><span aria-hidden="true">{typeOf(a.type).icon}</span> {a.subject}</span>
                  {multi ? <span className="member-chip">{ownerName(a.ownerId)}</span> : <span />}
                  {a.productId ? <span className="mini-sale">{a.saleAmount != null ? money(a.saleAmount) : ''} <PayBadge a={a} /></span> : <span />}
                </li>
              ))}
            </ul>
          )}
          {v.list.length > 8 && <p className="muted hint">Showing the latest 8 of {v.count}. See all on the Activities page.</p>}
          <div className="modal-actions">
            <button type="button" className="danger-outline" onClick={() => setToDelete(viewing)}>Delete</button>
            <span className="spacer" />
            <button type="button" onClick={() => setViewing(null)}>Close</button>
            <button type="button" className="primary" onClick={() => openEdit(viewing)}>Edit</button>
          </div>
        </Modal>
      )}

      {toDelete && (
        <ConfirmDialog
          message={`Delete ${toDelete.firstName} ${toDelete.lastName}? All of this customer's activities and recorded sales will be deleted too. This cannot be undone.`}
          onConfirm={remove} onCancel={() => setToDelete(null)} />
      )}

      {open && (
        <Modal title={editingId ? 'Edit customer' : 'Add customer'} onClose={close}>
          <form onSubmit={submit} autoComplete="off">
            {formError && <p className="error">{formError}</p>}
            <div className="form-grid">
              <Field label="First name"><input name="firstName" value={form.firstName} onChange={onChange} required autoFocus /></Field>
              <Field label="Last name"><input name="lastName" value={form.lastName} onChange={onChange} required /></Field>
              <Field label="Email"><input name="email" type="email" value={form.email} onChange={onChange} required /></Field>
              <Field label="Phone"><input name="phone" value={form.phone} onChange={onChange} /></Field>
              <Field label="Company" wide><input name="company" value={form.company} onChange={onChange} /></Field>
            </div>
            <div className="modal-actions">
              <button type="button" onClick={close}>Cancel</button>
              <button type="submit" className="primary">{editingId ? 'Save changes' : 'Add customer'}</button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
