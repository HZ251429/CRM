import { useEffect, useState } from 'react';
import { productsApi, tasksApi } from '../api.js';
import { fmtDateTime, useOwners } from '../format.js';
import Modal, { Field, ConfirmDialog } from '../Modal.jsx';

const STATUS = {
  NOT_STARTED: { label: 'Not started', cls: 'badge todo' },
  IN_PROGRESS: { label: 'In progress', cls: 'badge busy' },
  COMPLETED: { label: 'Completed', cls: 'badge on' },
};

const fmt = (n) => Number(n || 0).toLocaleString('en-IN');

/**
 * Sales tasks. A leader assigns units of a product to someone below them; that person can split the units among
 * their own team. Progress comes from units sold in activities by the assignee and everyone below them.
 */
export default function Tasks({ user }) {
  const [tasks, setTasks] = useState([]);
  const [products, setProducts] = useState([]);
  const [error, setError] = useState('');
  const { team } = useOwners();
  const [view, setView] = useState('all');
  const [statusFilter, setStatusFilter] = useState('');
  const [collapsed, setCollapsed] = useState(new Set());

  // modal: { mode: 'new' | 'split' | 'edit', task?: TaskView }
  const [modal, setModal] = useState(null);
  const [form, setForm] = useState({});
  const [formError, setFormError] = useState('');
  const [toDelete, setToDelete] = useState(null);

  const [loaded, setLoaded] = useState(false);
  const load = () => tasksApi.list().then((t) => { setTasks(t); setLoaded(true); }).catch((e) => setError(e.message));
  useEffect(() => {
    load();
    productsApi.list().then(setProducts).catch((e) => setError(e.message));
  }, []);

  const me = user.id;
  const assignees = team.filter((t) => t.userId !== me && t.role === 'MEMBER' && t.active);
  const isLeader = assignees.length > 0;
  const isMember = user.role === 'MEMBER';

  // ----- tree -----
  const byId = Object.fromEntries(tasks.map((t) => [t.id, t]));
  const children = {};
  tasks.forEach((t) => { if (t.parentId && byId[t.parentId]) (children[t.parentId] = children[t.parentId] || []).push(t); });

  const matches = (t) => (!statusFilter || t.status === statusFilter) &&
    (view === 'all' || (view === 'mine' && t.assignedToId === me) || (view === 'byme' && t.assignedById === me));

  let rows = [];
  if (view === 'all' && !statusFilter) {
    const walk = (t, depth) => {
      rows.push({ t, depth });
      if (!collapsed.has(t.id)) (children[t.id] || []).forEach((c) => walk(c, depth + 1));
    };
    tasks.filter((t) => !t.parentId || !byId[t.parentId]).forEach((t) => walk(t, 0));
  } else {
    rows = tasks.filter(matches).map((t) => ({ t, depth: 0 }));
  }

  const toggle = (id) => {
    const next = new Set(collapsed);
    if (next.has(id)) next.delete(id); else next.add(id);
    setCollapsed(next);
  };

  const mine = tasks.filter((t) => t.assignedToId === me);
  const summary = {
    mine: mine.length,
    open: mine.filter((t) => t.status !== 'COMPLETED').length,
    team: tasks.filter((t) => t.assignedToId !== me).length,
    teamDone: tasks.filter((t) => t.assignedToId !== me && t.status === 'COMPLETED').length,
  };

  // ----- actions -----
  const openNew = () => {
    setForm({ assigneeId: '', productId: '', units: '', title: '' });
    setFormError('');
    setModal({ mode: 'new' });
  };
  const openSplit = (task) => {
    setForm({ assigneeId: '', units: '', title: task.title || '' });
    setFormError('');
    setModal({ mode: 'split', task });
  };
  const openEdit = (task) => {
    setForm({ units: String(task.units), title: task.title || '' });
    setFormError('');
    setModal({ mode: 'edit', task });
  };
  const onChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });

  const submit = async (e) => {
    e.preventDefault();
    setFormError('');
    try {
      if (modal.mode === 'edit') {
        await tasksApi.update(modal.task.id, { units: Number(form.units), title: form.title });
      } else {
        await tasksApi.create({
          parentId: modal.mode === 'split' ? modal.task.id : null,
          productId: modal.mode === 'new' ? Number(form.productId) : null,
          assigneeId: Number(form.assigneeId),
          units: Number(form.units),
          title: form.title,
        });
        if (modal.mode === 'split') setCollapsed((s) => { const n = new Set(s); n.delete(modal.task.id); return n; });
      }
      setModal(null);
      load();
    } catch (err) {
      setFormError(err.message);
    }
  };

  const remove = async () => {
    const t = toDelete;
    setToDelete(null);
    setError('');
    try {
      await tasksApi.remove(t.id);
      load();
    } catch (err) {
      setError(err.message);
    }
  };

  const left = (t) => t.units - t.splitUnits;
  const activeProducts = products.filter((p) => p.active);

  return (
    <div>
      {error && <p className="error">{error}</p>}

      <div className="cards task-cards">
        {isMember && <div className="card"><h3>Assigned to me</h3><p>{summary.mine}</p></div>}
        {isMember && <div className="card"><h3>My open tasks</h3><p>{summary.open}</p></div>}
        {isLeader && <div className="card"><h3>Team tasks</h3><p>{summary.team}</p></div>}
        {isLeader && <div className="card"><h3>Team tasks completed</h3><p>{summary.teamDone}</p></div>}
      </div>

      <div className="toolbar">
        <div className="seg" role="tablist">
          {[['all', 'All'], ...(isMember ? [['mine', 'Assigned to me']] : []), ...(isLeader ? [['byme', 'Assigned by me']] : [])].map(([k, label]) => (
            <button key={k} type="button" role="tab" aria-selected={view === k}
              className={view === k ? 'seg-btn active' : 'seg-btn'} onClick={() => setView(k)}>{label}</button>
          ))}
        </div>
        <select className="filter-select" value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} title="Filter by status">
          <option value="">Any status</option>
          {Object.entries(STATUS).map(([k, s]) => <option key={k} value={k}>{s.label}</option>)}
        </select>
        <span className="spacer" />
        {isLeader && (
          <button type="button" className="primary" onClick={openNew} disabled={activeProducts.length === 0}
            title={activeProducts.length === 0 ? 'Add a product first' : ''}>+ Assign task</button>
        )}
      </div>

      {rows.map(({ t, depth }) => {
        const pct = Math.min(100, t.units ? Math.round((t.soldUnits / t.units) * 100) : 0);
        const kids = children[t.id] || [];
        const canSplit = t.assignedToId === me && isLeader && left(t) > 0;
        return (
          <div key={t.id} className="task-row" style={{ marginLeft: `${depth * 1.8}rem` }}>
            <div className="task-main">
              <div className="task-title">
                {view === 'all' && !statusFilter && kids.length > 0 ? (
                  <button type="button" className="expander" onClick={() => toggle(t.id)}
                    aria-label={collapsed.has(t.id) ? 'Expand' : 'Collapse'}>{collapsed.has(t.id) ? '▸' : '▾'}</button>
                ) : (depth > 0 && <span className="task-branch" aria-hidden="true">↳</span>)}
                <div>
                  <strong>{t.productName}</strong>
                  {t.title && <span className="muted"> · {t.title}</span>}
                  <p className="muted task-meta">
                    {t.assignedToId === me ? 'Assigned to you' : `Assigned to ${t.assignedToName}`}
                    {' by '}{t.assignedById === me ? 'you' : t.assignedByName} · {fmtDateTime(t.createdAt)}
                  </p>
                </div>
              </div>
              <span className={STATUS[t.status].cls}>{STATUS[t.status].label}</span>
            </div>

            <div className="task-progress">
              <div className={t.status === 'COMPLETED' ? 'progress done' : 'progress'}
                role="progressbar" aria-valuenow={pct} aria-valuemin="0" aria-valuemax="100">
                <span style={{ width: `${pct}%` }} />
              </div>
              <span className="task-numbers">
                <strong>{fmt(t.soldUnits)}</strong> / {fmt(t.units)} {t.unit} sold ({pct}%)
              </span>
            </div>

            <div className="task-foot">
              <span className="muted">
                {t.subTaskCount > 0
                  ? `Split to team: ${fmt(t.splitUnits)} of ${fmt(t.units)} ${t.unit} in ${t.subTaskCount} sub-task${t.subTaskCount > 1 ? 's' : ''}`
                  : 'Not split'}
              </span>
              <span className="task-actions">
                {canSplit && <button className="plain" onClick={() => openSplit(t)}>Split to team</button>}
                {t.assignedById === me && <button className="plain" onClick={() => openEdit(t)}>Edit</button>}
                {t.assignedById === me && <button className="plain" onClick={() => setToDelete(t)}>Delete</button>}
              </span>
            </div>
          </div>
        );
      })}

      {!loaded && !error && <p className="muted">Loading tasks...</p>}
      {loaded && rows.length === 0 && (
        <div className="log-empty">
          <p className="muted">
            {tasks.length > 0 ? 'No tasks match this view.'
              : isLeader ? 'No tasks yet. Assign units of a product to someone in your team.'
                : 'No tasks assigned to you yet.'}
          </p>
          {tasks.length === 0 && isLeader && activeProducts.length > 0 && (
            <button type="button" className="primary" onClick={openNew}>+ Assign task</button>
          )}
          {isLeader && activeProducts.length === 0 && (
            <p className="muted">{user.role === 'TENANT_ADMIN' ? 'Add products on the Products page first.' : 'Your admin has not added any products yet.'}</p>
          )}
        </div>
      )}
      <p className="muted hint">
        Units sold are counted from activities logged by the assignee and everyone below them, on or after the day the task was assigned.
        If someone has several tasks for the same product, each sale counts once and fills their oldest task first.
        A task is completed automatically when its units are sold.
      </p>

      {toDelete && (
        <ConfirmDialog message={`Delete the task "${toDelete.productName}: ${fmt(toDelete.units)} ${toDelete.unit}" assigned to ${toDelete.assignedToName}?`}
          onConfirm={remove} onCancel={() => setToDelete(null)} />
      )}

      {modal && (
        <Modal
          title={modal.mode === 'new' ? 'Assign task' : modal.mode === 'split' ? 'Split task to team' : 'Edit task'}
          onClose={() => setModal(null)}>
          <form onSubmit={submit} autoComplete="off">
            {formError && <p className="error">{formError}</p>}
            {modal.mode !== 'new' && (
              <p className="muted task-context">
                <strong>{modal.task.productName}</strong>: {fmt(modal.task.units)} {modal.task.unit}
                {modal.mode === 'split' && ` · ${fmt(left(modal.task))} ${modal.task.unit} left to split`}
                {modal.mode === 'edit' && ` · assigned to ${modal.task.assignedToName}`}
              </p>
            )}
            <div className="form-grid">
              {modal.mode !== 'edit' && (
                <Field label="Assign to" wide={modal.mode === 'split'}>
                  <select name="assigneeId" value={form.assigneeId} onChange={onChange} required autoFocus>
                    <option value="">Select team member...</option>
                    {assignees.map((a) => <option key={a.userId} value={a.userId}>{a.fullName}</option>)}
                  </select>
                </Field>
              )}
              {modal.mode === 'new' && (
                <Field label="Product">
                  <select name="productId" value={form.productId} onChange={onChange} required>
                    <option value="">Select product...</option>
                    {activeProducts.map((p) => <option key={p.id} value={p.id}>{p.name} ({p.unit})</option>)}
                  </select>
                </Field>
              )}
              <Field label="Units">
                <input name="units" type="number" min="1" step="1" required value={form.units} onChange={onChange}
                  max={modal.mode === 'split' ? left(modal.task) : 1000000} autoFocus={modal.mode === 'edit'} />
              </Field>
              <Field label="Title (optional)" wide={modal.mode !== 'edit'}>
                <input name="title" value={form.title} onChange={onChange} maxLength={200} placeholder="e.g. November push" />
              </Field>
            </div>
            <div className="modal-actions">
              <button type="button" onClick={() => setModal(null)}>Cancel</button>
              <button type="submit" className="primary">
                {modal.mode === 'edit' ? 'Save' : modal.mode === 'split' ? 'Assign part' : 'Assign task'}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
