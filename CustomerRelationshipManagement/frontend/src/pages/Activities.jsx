import { useEffect, useState } from 'react';
import { customersApi, activitiesApi } from '../api.js';
import { fmtDateTime, nowLocalInput, toLocalInput, useOwners } from '../format.js';
import Modal, { Field, ConfirmDialog } from '../Modal.jsx';

const TYPES = ['TASK', 'CALL', 'EMAIL', 'MEETING', 'NOTE'];
const empty = { customerId: '', type: 'TASK', subject: '', notes: '', activityTime: '', completed: false };

export default function Activities() {
  const [items, setItems] = useState([]);
  const [customers, setCustomers] = useState([]);
  const [form, setForm] = useState(empty);
  const [editingId, setEditingId] = useState(null);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState('');
  const [formError, setFormError] = useState('');
  const [toDelete, setToDelete] = useState(null);
  const { names, multi, team } = useOwners();
  const [nameFilter, setNameFilter] = useState('');
  const [ownerFilter, setOwnerFilter] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  const load = () => activitiesApi.list().then(setItems).catch((e) => setError(e.message));
  useEffect(() => { load(); customersApi.all().then(setCustomers); }, []);

  const name = (id) => {
    const c = customers.find((x) => x.id === id);
    return c ? `${c.firstName} ${c.lastName}` : id;
  };
  const onChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });

  // the time is generated automatically (current date and time) but can still be adjusted
  const openAdd = () => { setEditingId(null); setForm({ ...empty, activityTime: nowLocalInput() }); setFormError(''); setOpen(true); };
  const openEdit = (a) => {
    setEditingId(a.id);
    setForm({ ...empty, ...a, activityTime: toLocalInput(a.activityTime) });
    setFormError('');
    setOpen(true);
  };
  const close = () => setOpen(false);

  const submit = async (e) => {
    e.preventDefault();
    setFormError('');
    const body = { ...form, customerId: Number(form.customerId), activityTime: form.activityTime || null };
    try {
      if (editingId) await activitiesApi.update(editingId, body);
      else await activitiesApi.create(body);
      close();
      load();
    } catch (err) {
      setFormError(err.message);
    }
  };

  const remove = async () => {
    const a = toDelete;
    setToDelete(null);
    try {
      await activitiesApi.remove(a.id);
      load();
    } catch (err) {
      setError(err.message);
    }
  };

  const nf = nameFilter.trim().toLowerCase();
  const filtered = items.filter((a) => {
    const t = toLocalInput(a.activityTime); // YYYY-MM-DDTHH:mm sorts as text
    return (!nf || String(name(a.customerId)).toLowerCase().includes(nf)) &&
      (!ownerFilter || String(a.ownerId) === ownerFilter) &&
      (!from || (t && t >= from)) &&
      (!to || (t && t <= to));
  });
  const anyFilter = nameFilter || ownerFilter || from || to;

  return (
    <div>
      {error && <p className="error">{error}</p>}

      <div className="toolbar">
        <input className="search" placeholder="Filter by customer name..." value={nameFilter}
          onChange={(e) => setNameFilter(e.target.value)} />
        {multi && (
          <select className="filter-select" value={ownerFilter} onChange={(e) => setOwnerFilter(e.target.value)} title="Filter by owner">
            <option value="">All owners</option>
            {team.map((t) => <option key={t.userId} value={t.userId}>{t.fullName}</option>)}
          </select>
        )}
        <label className="filter-time">From
          <input type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className="filter-time">To
          <input type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
        {anyFilter && (
          <button type="button" onClick={() => { setNameFilter(''); setOwnerFilter(''); setFrom(''); setTo(''); }}>Clear</button>
        )}
        <button type="button" className="primary" onClick={openAdd}>+ Add activity</button>
      </div>

      <table>
        <thead><tr><th>Type</th><th>Subject</th><th>Customer</th><th>Time</th>{multi && <th>Owner</th>}<th></th></tr></thead>
        <tbody>
          {filtered.map((a) => (
            <tr key={a.id}>
              <td>{a.type}</td>
              <td>
                {a.subject}
                {a.notes && <div className="note-preview" title={a.notes}>{a.notes}</div>}
              </td>
              <td>{name(a.customerId)}</td><td>{fmtDateTime(a.activityTime)}</td>
              {multi && <td>{names[a.ownerId]}</td>}
              <td>
                <button onClick={() => openEdit(a)}>Edit</button>
                <button onClick={() => setToDelete(a)}>Delete</button>
              </td>
            </tr>
          ))}
          {filtered.length === 0 && <tr><td colSpan="6" className="muted">{items.length === 0 ? 'No activities yet' : 'No activities match the filters'}</td></tr>}
        </tbody>
      </table>

      {toDelete && (
        <ConfirmDialog message={`Delete the activity "${toDelete.subject}"? This cannot be undone.`}
          onConfirm={remove} onCancel={() => setToDelete(null)} />
      )}

      {open && (
        <Modal title={editingId ? 'Edit activity' : 'Add activity'} onClose={close}>
          <form onSubmit={submit} autoComplete="off">
            {formError && <p className="error">{formError}</p>}
            <div className="form-grid">
              <Field label="Customer" wide>
                <select name="customerId" value={form.customerId} onChange={onChange} required autoFocus>
                  <option value="">Select customer...</option>
                  {customers.map((c) => <option key={c.id} value={c.id}>{c.firstName} {c.lastName}</option>)}
                </select>
              </Field>
              <Field label="Type">
                <select name="type" value={form.type} onChange={onChange}>
                  {TYPES.map((t) => <option key={t}>{t}</option>)}
                </select>
              </Field>
              <Field label="Time (auto-filled with the current time)">
                <input name="activityTime" type="datetime-local" value={form.activityTime || ''} onChange={onChange} />
              </Field>
              <Field label="Subject" wide><input name="subject" value={form.subject} onChange={onChange} required /></Field>
              <Field label="Notes" wide>
                <textarea name="notes" rows={6} maxLength={2000} value={form.notes || ''} onChange={onChange}
                  placeholder="Add details, call summary, next steps..." />
                <small className="muted count">{(form.notes || '').length} / 2000</small>
              </Field>
            </div>
            <div className="modal-actions">
              <button type="button" onClick={close}>Cancel</button>
              <button type="submit" className="primary">{editingId ? 'Update' : 'Add activity'}</button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}