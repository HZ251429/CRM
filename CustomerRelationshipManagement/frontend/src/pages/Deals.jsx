import { useEffect, useState } from 'react';
import { customersApi, dealsApi } from '../api.js';
import { money, useOwners } from '../format.js';
import Modal, { Field, ConfirmDialog } from '../Modal.jsx';

const STAGES = ['NEW', 'QUALIFIED', 'PROPOSAL', 'NEGOTIATION', 'WON', 'LOST'];
const empty = { customerId: '', title: '', amount: 0, stage: 'NEW', expectedCloseDate: '' };

export default function Deals() {
  const [deals, setDeals] = useState([]);
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

  const load = () => dealsApi.list().then(setDeals).catch((e) => setError(e.message));
  useEffect(() => { load(); customersApi.all().then(setCustomers); }, []);

  const name = (id) => {
    const c = customers.find((x) => x.id === id);
    return c ? `${c.firstName} ${c.lastName}` : id;
  };
  const onChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });

  const openAdd = () => { setEditingId(null); setForm(empty); setFormError(''); setOpen(true); };
  const openEdit = (d) => { setEditingId(d.id); setForm({ ...empty, ...d }); setFormError(''); setOpen(true); };
  const close = () => setOpen(false);

  const submit = async (e) => {
    e.preventDefault();
    setFormError('');
    const body = { ...form, customerId: Number(form.customerId), amount: Number(form.amount),
      expectedCloseDate: form.expectedCloseDate || null };
    try {
      if (editingId) await dealsApi.update(editingId, body);
      else await dealsApi.create(body);
      close();
      load();
    } catch (err) {
      setFormError(err.message);
    }
  };

  const remove = async () => {
    const d = toDelete;
    setToDelete(null);
    try {
      await dealsApi.remove(d.id);
      load();
    } catch (err) {
      setError(err.message);
    }
  };

  const nf = nameFilter.trim().toLowerCase();
  const filtered = deals.filter((d) =>
    (!nf || String(name(d.customerId)).toLowerCase().includes(nf)) &&
    (!ownerFilter || String(d.ownerId) === ownerFilter));

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
        {(nameFilter || ownerFilter) && (
          <button type="button" onClick={() => { setNameFilter(''); setOwnerFilter(''); }}>Clear</button>
        )}
        <button type="button" className="primary" onClick={openAdd}>+ Add deal</button>
      </div>

      <table>
        <thead><tr><th>Title</th><th>Customer</th><th>Amount (₹)</th><th>Stage</th><th>Close date</th>{multi && <th>Owner</th>}<th></th></tr></thead>
        <tbody>
          {filtered.map((d) => (
            <tr key={d.id}>
              <td>{d.title}</td><td>{name(d.customerId)}</td><td>{money(d.amount)}</td>
              <td>{d.stage}</td><td>{d.expectedCloseDate}</td>
              {multi && <td>{names[d.ownerId]}</td>}
              <td>
                <button onClick={() => openEdit(d)}>Edit</button>
                <button onClick={() => setToDelete(d)}>Delete</button>
              </td>
            </tr>
          ))}
          {filtered.length === 0 && <tr><td colSpan="7" className="muted">{deals.length === 0 ? 'No deals yet' : 'No deals match the filters'}</td></tr>}
        </tbody>
      </table>

      {toDelete && (
        <ConfirmDialog message={`Delete the deal "${toDelete.title}"? This cannot be undone.`}
          onConfirm={remove} onCancel={() => setToDelete(null)} />
      )}

      {open && (
        <Modal title={editingId ? 'Edit deal' : 'Add deal'} onClose={close}>
          <form onSubmit={submit} autoComplete="off">
            {formError && <p className="error">{formError}</p>}
            <div className="form-grid">
              <Field label="Customer" wide>
                <select name="customerId" value={form.customerId} onChange={onChange} required autoFocus>
                  <option value="">Select customer...</option>
                  {customers.map((c) => <option key={c.id} value={c.id}>{c.firstName} {c.lastName}</option>)}
                </select>
              </Field>
              <Field label="Deal title" wide><input name="title" value={form.title} onChange={onChange} required /></Field>
              <Field label="Amount (₹ INR)">
                <input name="amount" type="number" min="0" step="0.01" value={form.amount} onChange={onChange} />
              </Field>
              <Field label="Stage">
                <select name="stage" value={form.stage} onChange={onChange}>
                  {STAGES.map((s) => <option key={s}>{s}</option>)}
                </select>
              </Field>
              <Field label="Expected close date">
                <input name="expectedCloseDate" type="date" value={form.expectedCloseDate || ''} onChange={onChange} />
              </Field>
            </div>
            <div className="modal-actions">
              <button type="button" onClick={close}>Cancel</button>
              <button type="submit" className="primary">{editingId ? 'Update' : 'Add deal'}</button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}