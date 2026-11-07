import { useEffect, useState } from 'react';
import { tasksApi } from './api.js';
import Modal from './Modal.jsx';
import { fmtDateTime } from './format.js';
import { StatusBadge, StatusChips, matchesStatus, taskName, teamBalanceOf } from './TaskBits.jsx';

const fmt = (n) => Number(n || 0).toLocaleString('en-IN');
const dayOf = (t) => String(t).slice(0, 10);

/**
 * Popup: the tasks the admin assigned for a product (or all products), with filters.
 * These are the tasks that take units out of stock; tasks created before stock was tracked are marked.
 */
export default function StockTasks({ product, onClose }) {
  const [tasks, setTasks] = useState(null);
  const [error, setError] = useState('');
  const [f, setF] = useState({ q: '', status: '', person: '', productId: '', from: '', to: '' });
  const set = (k, v) => setF({ ...f, [k]: v });

  useEffect(() => {
    tasksApi.list().then((all) => setTasks(all.filter((t) => !t.parentId))).catch((e) => setError(e.message));
  }, []);

  const base = (tasks || []).filter((t) => !product || t.productId === product.productId);
  const q = f.q.trim().toLowerCase();
  const before = base.filter((t) => (!q || [t.title, t.productName, t.assignedToName].join(' ').toLowerCase().includes(q))
    && (!f.person || String(t.assignedToId) === f.person)
    && (!f.productId || String(t.productId) === f.productId)
    && (!f.from || dayOf(t.createdAt) >= f.from)
    && (!f.to || dayOf(t.createdAt) <= f.to));
  const shown = before.filter((t) => matchesStatus(t, f.status));
  const people = [...new Map(base.map((t) => [t.assignedToId, t.assignedToName])).entries()].sort((a, b) => a[1].localeCompare(b[1]));
  const products = [...new Map(base.map((t) => [t.productId, t.productName])).entries()];
  const sum = (k) => shown.reduce((n, t) => n + Number(t[k] || 0), 0);
  const unit = product ? product.unit : '';
  const anyFilter = f.q || f.status || f.person || f.productId || f.from || f.to;

  return (
    <Modal title={product ? `Assigned tasks: ${product.productName}` : 'Assigned tasks'} onClose={onClose} wide>
      {error && <p className="error">{error}</p>}
      {!tasks ? <p className="muted">Loading...</p> : (
        <>
          <StatusChips tasks={before} value={f.status} onChange={(v) => set('status', v)} withOpen />
          <div className="filter-row stock-filters">
            <input placeholder="Search title or person..." value={f.q} onChange={(e) => set('q', e.target.value)} aria-label="Search" />
            <select value={f.person} onChange={(e) => set('person', e.target.value)} aria-label="Assigned to">
              <option value="">Anyone</option>
              {people.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
            </select>
            {!product && (
              <select value={f.productId} onChange={(e) => set('productId', e.target.value)} aria-label="Product">
                <option value="">All products</option>
                {products.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
              </select>
            )}
            <label className="date-filter">From <input type="date" value={f.from} onChange={(e) => set('from', e.target.value)} /></label>
            <label className="date-filter">To <input type="date" value={f.to} onChange={(e) => set('to', e.target.value)} /></label>
            {anyFilter && <button type="button" className="plain" onClick={() => setF({ q: '', status: '', person: '', productId: '', from: '', to: '' })}>Clear</button>}
          </div>
          <p className="muted stock-sum">
            {fmt(shown.length)} task{shown.length === 1 ? '' : 's'} · {fmt(sum('units'))} {unit} assigned · {fmt(sum('soldUnits'))} sold · {fmt(shown.reduce((n, t) => n + teamBalanceOf(t), 0))} pending
          </p>
          <div className="table-scroll">
            <table>
              <thead>
                <tr><th>Task</th>{!product && <th>Product</th>}<th>Assigned to</th><th className="num">Units</th>
                  <th className="num">Sold</th><th className="num">Pending</th><th>Status</th></tr>
              </thead>
              <tbody>
                {shown.map((t) => (
                  <tr key={t.id}>
                    <td className="two-line"><strong>{taskName(t)}</strong>
                      <span className="muted">{fmtDateTime(t.createdAt)}{t.fromStock ? '' : ' · before stock tracking'}</span></td>
                    {!product && <td>{t.productName}</td>}
                    <td>{t.assignedToName}</td>
                    <td className="num">{fmt(t.units)}</td>
                    <td className="num">{fmt(t.soldUnits)}</td>
                    <td className="num">{fmt(teamBalanceOf(t))}</td>
                    <td><StatusBadge status={t.status} /></td>
                  </tr>
                ))}
                {shown.length === 0 && <tr><td colSpan={product ? 6 : 7} className="muted">{base.length ? 'No tasks match the filter.' : 'No tasks assigned yet.'}</td></tr>}
              </tbody>
            </table>
          </div>
        </>
      )}
      <div className="modal-actions">
        <button type="button" onClick={onClose}>Close</button>
      </div>
    </Modal>
  );
}
