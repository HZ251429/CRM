import { useEffect, useState } from 'react';
import { productsApi } from '../api.js';
import Modal, { Field } from '../Modal.jsx';

const empty = { name: '', unit: 'pcs', description: '' };

/** Admin: the products this company sells. Products are deactivated rather than deleted, so old records keep them. */
export default function Products() {
  const [items, setItems] = useState([]);
  const [error, setError] = useState('');
  const [form, setForm] = useState(empty);
  const [editing, setEditing] = useState(null);
  const [open, setOpen] = useState(false);
  const [formError, setFormError] = useState('');

  const load = () => productsApi.list().then(setItems).catch((e) => setError(e.message));
  useEffect(() => { load(); }, []);

  const onChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });
  const openAdd = () => { setEditing(null); setForm(empty); setFormError(''); setOpen(true); };
  const openEdit = (p) => {
    setEditing(p);
    setForm({ name: p.name, unit: p.unit, description: p.description || '' });
    setFormError('');
    setOpen(true);
  };

  const submit = async (e) => {
    e.preventDefault();
    setFormError('');
    try {
      if (editing) await productsApi.update(editing.id, { ...form, active: editing.active });
      else await productsApi.create(form);
      setOpen(false);
      load();
    } catch (err) {
      setFormError(err.message);
    }
  };

  const setActive = async (p, active) => {
    setError('');
    try {
      await productsApi.update(p.id, { name: p.name, unit: p.unit, description: p.description, active });
      load();
    } catch (err) {
      setError(err.message);
    }
  };

  return (
    <div>
      {error && <p className="error">{error}</p>}
      <div className="toolbar end">
        <button type="button" className="primary" onClick={openAdd}>+ Add product</button>
      </div>

      <table>
        <thead><tr><th>Product</th><th>Unit</th><th>Description</th><th>Status</th><th></th></tr></thead>
        <tbody>
          {items.map((p) => (
            <tr key={p.id} className={p.active ? '' : 'inactive'}>
              <td><strong>{p.name}</strong></td>
              <td>{p.unit}</td>
              <td className="muted">{p.description}</td>
              <td><span className={p.active ? 'badge on' : 'badge off'}>{p.active ? 'Active' : 'Inactive'}</span></td>
              <td>
                <button className="plain" onClick={() => openEdit(p)}>Edit</button>
                <button className="plain" onClick={() => setActive(p, !p.active)}>{p.active ? 'Deactivate' : 'Activate'}</button>
              </td>
            </tr>
          ))}
          {items.length === 0 && <tr><td colSpan="5" className="muted">No products yet. Add the products your team sells.</td></tr>}
        </tbody>
      </table>
      <p className="muted hint">
        Products are used when assigning sales tasks and when logging units sold in an activity.
        Deactivated products can no longer be picked for new tasks or sales.
      </p>

      {open && (
        <Modal title={editing ? `Edit ${editing.name}` : 'Add product'} onClose={() => setOpen(false)}>
          <form onSubmit={submit} autoComplete="off">
            {formError && <p className="error">{formError}</p>}
            <div className="form-grid">
              <Field label="Product name">
                <input name="name" value={form.name} onChange={onChange} required maxLength={100} autoFocus
                  placeholder="e.g. Water purifier X200" />
              </Field>
              <Field label="Unit">
                <input name="unit" value={form.unit} onChange={onChange} required maxLength={20}
                  placeholder="e.g. pcs, boxes, licences" />
              </Field>
              <Field label="Description (optional)" wide>
                <input name="description" value={form.description} onChange={onChange} maxLength={500} />
              </Field>
            </div>
            <div className="modal-actions">
              <button type="button" onClick={() => setOpen(false)}>Cancel</button>
              <button type="submit" className="primary">{editing ? 'Save' : 'Add product'}</button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
