import { useEffect, useState } from 'react';
import { stockApi } from '../api.js';
import Modal, { Field, ConfirmDialog } from '../Modal.jsx';
import { fmtDateTime } from '../format.js';
import Icon from '../Icon.jsx';
import StockTasks from '../StockTasks.jsx';

const fmt = (n) => Number(n || 0).toLocaleString('en-IN');

/**
 * Admin: stock of each product. Units go out of stock when they are assigned in a task, so tasks can never assign
 * more than is left. Stock can be added (goods received) or removed (damaged, returned), and each change is logged.
 */
export default function Stock() {
  const [rows, setRows] = useState(null);
  const [error, setError] = useState('');
  const [change, setChange] = useState(null); // { row, sign: 1 | -1 }
  const [form, setForm] = useState({ quantity: '', note: '' });
  const [formError, setFormError] = useState('');
  const [history, setHistory] = useState(null); // { row, entries }
  const [toUndo, setToUndo] = useState(null);
  const [tasksFor, setTasksFor] = useState(null); // stock row, or { all: true }

  const load = () => stockApi.list().then(setRows).catch((e) => setError(e.message));
  useEffect(() => { load(); }, []);

  const openChange = (row, sign) => { setChange({ row, sign }); setForm({ quantity: '', note: '' }); setFormError(''); };
  const openHistory = (row) => {
    setError('');
    stockApi.history(row.productId).then((entries) => setHistory({ row, entries })).catch((e) => setError(e.message));
  };

  const submit = async (e) => {
    e.preventDefault();
    setFormError('');
    try {
      await stockApi.add({ productId: change.row.productId, quantity: change.sign * Number(form.quantity), note: form.note });
      setChange(null);
      load();
    } catch (err) {
      setFormError(err.message);
    }
  };

  const undo = async () => {
    const entry = toUndo;
    setToUndo(null);
    try {
      await stockApi.removeEntry(entry.id);
      const entries = await stockApi.history(history.row.productId);
      setHistory({ ...history, entries });
      load();
    } catch (err) {
      setHistory({ ...history, error: err.message });
    }
  };

  if (!rows) return error ? <p className="error">{error}</p> : <p className="muted">Loading...</p>;

  const active = rows.filter((r) => r.active || r.total > 0 || r.assigned > 0);
  const sum = (k) => active.reduce((n, r) => n + r[k], 0);
  const low = active.filter((r) => r.active && r.left <= 0);

  return (
    <div>
      {error && <p className="error">{error}</p>}
      <div className="dash-cards platform-cards stock-cards">
        <section className="dcard">
          <h3 className="dcard-title"><span className="icon-chip small tone-emerald"><Icon name="box" size={16} /></span> In stock</h3>
          <strong className="big-num">{fmt(active.reduce((n, r) => n + Math.max(0, r.left), 0))}</strong>
          <span className="fig-sub">{low.length ? `${low.length} product${low.length > 1 ? 's' : ''} out of stock` : 'units available for new tasks'}</span>
        </section>
        <section className="dcard">
          <h3 className="dcard-title"><span className="icon-chip small tone-indigo"><Icon name="trend" size={16} /></span> Received</h3>
          <strong className="big-num">{fmt(sum('total'))}</strong>
          <span className="fig-sub">units added across {fmt(active.length)} products</span>
        </section>
        <button type="button" className="dcard dcard-btn" onClick={() => setTasksFor({ all: true })}>
          <h3 className="dcard-title"><span className="icon-chip small tone-sky"><Icon name="target" size={16} /></span> Assigned in tasks</h3>
          <strong className="big-num">{fmt(sum('assigned'))}</strong>
          <span className="fig-sub">units taken from stock · view tasks ›</span>
        </button>
        <section className="dcard">
          <h3 className="dcard-title"><span className="icon-chip small tone-amber"><Icon name="cart" size={16} /></span> Sold</h3>
          <strong className="big-num">{fmt(sum('sold'))}</strong>
          <span className="fig-sub">units sold in activities</span>
        </section>
      </div>

      <div className="table-scroll">
        <table className="stock-table">
          <thead>
            <tr><th>Product</th><th className="num">In stock</th><th className="num">Received</th>
              <th className="num">Assigned in tasks</th><th className="num">Sold</th><th>Last change</th><th></th></tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.productId} className={r.active ? '' : 'inactive'}>
                <td className="two-line"><strong>{r.productName}</strong>
                  <span className="muted">{r.unit}{r.active ? '' : ' · inactive'}</span></td>
                <td className="num">
                  <span className={r.left > 0 ? 'badge on' : 'badge off'}>{fmt(Math.max(0, r.left))}</span>
                </td>
                <td className="num">{fmt(r.total)}</td>
                <td className="num">
                  <button type="button" className="link-num" onClick={() => setTasksFor(r)} title="View assigned tasks">{fmt(r.assigned)}</button>
                </td>
                <td className="num">{fmt(r.sold)}</td>
                <td className="muted">{r.lastChangedAt ? fmtDateTime(r.lastChangedAt) : 'No stock yet'}</td>
                <td className="row-actions">
                  <button type="button" className="plain" onClick={() => openChange(r, 1)}>+ Add</button>
                  <button type="button" className="plain" onClick={() => openChange(r, -1)} disabled={r.left <= 0}>− Remove</button>
                  <button type="button" className="plain" onClick={() => openHistory(r)}>History</button>
                </td>
              </tr>
            ))}
            {rows.length === 0 && <tr><td colSpan="7" className="muted">No products yet. Add products first, then their stock.</td></tr>}
          </tbody>
        </table>
      </div>
      <p className="muted hint">
        In stock = units you can still assign. Units leave the stock when you assign them in a task, so new tasks can
        only use what is in stock. Click an Assigned number to see those tasks. Team leads hand out units only from their own balance.
      </p>

      {change && (
        <Modal title={`${change.sign > 0 ? 'Add stock' : 'Remove stock'}: ${change.row.productName}`} onClose={() => setChange(null)}>
          <form onSubmit={submit} autoComplete="off">
            {formError && <p className="error">{formError}</p>}
            <p className="muted task-context">
              Received {fmt(change.row.total)} · assigned in tasks {fmt(change.row.assigned)} · <strong>In stock: {fmt(Math.max(0, change.row.left))} {change.row.unit}</strong>
            </p>
            <div className="form-grid">
              <Field label={`Units to ${change.sign > 0 ? 'add' : 'remove'} (${change.row.unit})`}>
                <input name="quantity" type="number" min="1" step="1" required autoFocus value={form.quantity}
                  max={change.sign < 0 ? Math.max(0, change.row.left) : 1000000}
                  onChange={(e) => setForm({ ...form, quantity: e.target.value })} />
              </Field>
              <Field label="Note (optional)">
                <input name="note" maxLength={200} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })}
                  placeholder={change.sign > 0 ? 'e.g. Invoice 1043 from supplier' : 'e.g. Damaged in transit'} />
              </Field>
            </div>
            {change.sign < 0 && <p className="muted hint">Only units not yet assigned in tasks can be removed.</p>}
            <div className="modal-actions">
              <button type="button" onClick={() => setChange(null)}>Cancel</button>
              <button type="submit" className="primary">{change.sign > 0 ? 'Add stock' : 'Remove stock'}</button>
            </div>
          </form>
        </Modal>
      )}

      {history && (
        <Modal title={`Stock history: ${history.row.productName}`} onClose={() => setHistory(null)} wide>
          {history.error && <p className="error">{history.error}</p>}
          <div className="table-scroll">
            <table>
              <thead><tr><th>When</th><th className="num">Change</th><th>Note</th><th>By</th><th></th></tr></thead>
              <tbody>
                {history.entries.map((e) => (
                  <tr key={e.id}>
                    <td>{fmtDateTime(e.createdAt)}</td>
                    <td className="num"><strong className={e.quantity > 0 ? 'qty-in' : 'qty-out'}>{e.quantity > 0 ? '+' : '−'}{fmt(Math.abs(e.quantity))}</strong></td>
                    <td className="muted">{e.note || '-'}</td>
                    <td>{e.createdByName}</td>
                    <td><button type="button" className="plain" onClick={() => setToUndo(e)}>Undo</button></td>
                  </tr>
                ))}
                {history.entries.length === 0 && <tr><td colSpan="5" className="muted">No stock changes yet.</td></tr>}
              </tbody>
            </table>
          </div>
          <div className="modal-actions">
            <button type="button" onClick={() => setHistory(null)}>Close</button>
          </div>
        </Modal>
      )}

      {tasksFor && <StockTasks product={tasksFor.all ? null : tasksFor} onClose={() => setTasksFor(null)} />}

      {toUndo && (
        <ConfirmDialog title="Undo stock change" confirmLabel="Undo"
          message={`Undo the change of ${toUndo.quantity > 0 ? '+' : '−'}${fmt(Math.abs(toUndo.quantity))} ${history.row.unit} made on ${fmtDateTime(toUndo.createdAt)}?`}
          onConfirm={undo} onCancel={() => setToUndo(null)} />
      )}
    </div>
  );
}
