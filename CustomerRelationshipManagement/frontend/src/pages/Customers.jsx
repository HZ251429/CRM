import { useEffect, useState } from 'react';
import { customersApi } from '../api.js';
import { useOwners } from '../format.js';
import Modal, { Field, ConfirmDialog } from '../Modal.jsx';

const empty = { firstName: '', lastName: '', email: '', phone: '', company: '', status: 'LEAD' };
const STATUSES = ['LEAD', 'PROSPECT', 'CUSTOMER', 'INACTIVE'];

export default function Customers() {
  const [data, setData] = useState({ content: [], page: 0, totalPages: 0, totalElements: 0 });
  const [q, setQ] = useState('');
  const [ownerFilter, setOwnerFilter] = useState('');
  const [page, setPage] = useState(0);
  const [form, setForm] = useState(empty);
  const [editingId, setEditingId] = useState(null);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState('');
  const [formError, setFormError] = useState('');
  const [toDelete, setToDelete] = useState(null);
  const { names, multi, team } = useOwners();

  const load = () => customersApi.search(q, page, 10, ownerFilter).then(setData).catch((e) => setError(e.message));
  useEffect(() => { load(); }, [q, page, ownerFilter]);

  const onChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });

  const openAdd = () => { setEditingId(null); setForm(empty); setFormError(''); setOpen(true); };
  const openEdit = (c) => { setEditingId(c.id); setForm({ ...empty, ...c }); setFormError(''); setOpen(true); };
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
    try {
      await customersApi.remove(c.id);
      load();
    } catch (err) {
      setError(err.message);
    }
  };

  return (
    <div>
      {error && <p className="error">{error}</p>}

      <div className="toolbar">
        <input className="search" placeholder="Filter by customer name, email, company..." value={q}
          onChange={(e) => { setPage(0); setQ(e.target.value); }} />
        {multi && (
          <select className="filter-select" value={ownerFilter} onChange={(e) => { setPage(0); setOwnerFilter(e.target.value); }}
            title="Filter by owner">
            <option value="">All owners</option>
            {team.map((t) => <option key={t.userId} value={t.userId}>{t.fullName}</option>)}
          </select>
        )}
        {(q || ownerFilter) && (
          <button type="button" onClick={() => { setPage(0); setQ(''); setOwnerFilter(''); }}>Clear</button>
        )}
        <button type="button" className="primary" onClick={openAdd}>+ Add customer</button>
      </div>

      <table>
        <thead><tr><th>Name</th><th>Email</th><th>Phone</th><th>Company</th><th>Status</th>{multi && <th>Owner</th>}<th></th></tr></thead>
        <tbody>
          {data.content.map((c) => (
            <tr key={c.id}>
              <td>{c.firstName} {c.lastName}</td><td>{c.email}</td><td>{c.phone}</td>
              <td>{c.company}</td><td>{c.status}</td>
              {multi && <td>{names[c.ownerId]}</td>}
              <td>
                <button onClick={() => openEdit(c)}>Edit</button>
                <button onClick={() => setToDelete(c)}>Delete</button>
              </td>
            </tr>
          ))}
          {data.content.length === 0 && <tr><td colSpan="7" className="muted">No customers found</td></tr>}
        </tbody>
      </table>

      <div className="pager">
        <button disabled={page === 0} onClick={() => setPage(page - 1)}>Prev</button>
        <span>Page {data.totalPages === 0 ? 0 : data.page + 1} of {data.totalPages} ({data.totalElements} total)</span>
        <button disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
      </div>

      {toDelete && (
        <ConfirmDialog
          message={`Delete ${toDelete.firstName} ${toDelete.lastName}? All of this customer's deals and activities will be deleted too. This cannot be undone.`}
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
              <Field label="Phone"><input name="phone" value={form.phone || ''} onChange={onChange} /></Field>
              <Field label="Company"><input name="company" value={form.company || ''} onChange={onChange} /></Field>
              <Field label="Status">
                <select name="status" value={form.status} onChange={onChange}>
                  {STATUSES.map((s) => <option key={s}>{s}</option>)}
                </select>
              </Field>
            </div>
            <div className="modal-actions">
              <button type="button" onClick={close}>Cancel</button>
              <button type="submit" className="primary">{editingId ? 'Update' : 'Add customer'}</button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}