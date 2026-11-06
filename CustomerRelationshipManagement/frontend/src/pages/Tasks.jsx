import { useEffect, useState } from 'react';
import { productsApi, tasksApi } from '../api.js';
import { fmtDateTime, useOwners } from '../format.js';
import Modal, { Field, ConfirmDialog } from '../Modal.jsx';
import { StatusBadge, StatusChips, balanceOf, teamBalanceOf, taskName } from '../TaskBits.jsx';

const fmt = (n) => Number(n || 0).toLocaleString('en-IN');

/** true when there is room for the list and the details panel side by side */
function useWide(min = 1200) {
  const [wide, setWide] = useState(() => window.innerWidth >= min);
  useEffect(() => {
    const onResize = () => setWide(window.innerWidth >= min);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, [min]);
  return wide;
}

/** Bar of units sold against the target, with what is still pending for the assignee. */
function PendingCell({ t }) {
  const pct = Math.min(100, t.units ? Math.round((t.soldUnits / t.units) * 100) : 0);
  const pending = balanceOf(t);
  const withTeam = t.splitUnits > 0 ? teamBalanceOf(t) - pending : 0;
  return (
    <div className="pending-cell">
      <div className={t.status === 'COMPLETED' ? 'progress done' : 'progress'} role="progressbar"
        aria-valuenow={pct} aria-valuemin="0" aria-valuemax="100" title={`${pct}% of the target sold`}>
        <span style={{ width: `${pct}%` }} />
      </div>
      <div className="pending-text">
        {pending > 0
          ? <span><strong className="pending-num">{fmt(pending)}</strong> {t.unit} pending</span>
          : <span className="done-text">✔ Nothing pending</span>}
        <span className="muted">{fmt(t.soldUnits)} of {fmt(t.units)} sold{withTeam > 0 ? ` · ${fmt(withTeam)} pending with team` : ''}</span>
      </div>
    </div>
  );
}

/**
 * Sales tasks. A leader assigns units of a product to someone below them; that person can split the units among
 * their own team. Select a task to see its details and actions.
 */
export default function Tasks({ user }) {
  const [tasks, setTasks] = useState([]);
  const [products, setProducts] = useState([]);
  const [error, setError] = useState('');
  const { team } = useOwners();
  const [view, setView] = useState('all');
  const [statusFilter, setStatusFilter] = useState('');
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState(null);
  const [popup, setPopup] = useState(false);
  const wide = useWide();

  // form modal: { mode: 'new' | 'split' | 'edit', task?: TaskView }
  const [modal, setModal] = useState(null);
  const [form, setForm] = useState({});
  const [formError, setFormError] = useState('');
  const [toDelete, setToDelete] = useState(null);

  const [loaded, setLoaded] = useState(false);
  const load = () => tasksApi.list().then((t) => { setTasks(t); setLoaded(true); return t; }).catch((e) => setError(e.message));
  useEffect(() => {
    load();
    productsApi.list().then(setProducts).catch((e) => setError(e.message));
  }, []);

  const me = user.id;
  const assignees = team.filter((t) => t.userId !== me && t.role === 'MEMBER' && t.active);
  const isLeader = assignees.length > 0;
  const isMember = user.role === 'MEMBER';
  const activeProducts = products.filter((p) => p.active);
  const person = (id, name) => (id === me ? 'You' : name);

  const byId = Object.fromEntries(tasks.map((t) => [t.id, t]));
  const children = {};
  tasks.forEach((t) => { if (t.parentId && byId[t.parentId]) (children[t.parentId] = children[t.parentId] || []).push(t); });

  // tasks in the chosen view; shown as a tree (sub-tasks under their task) unless a filter or search is on
  const inView = (t) => view === 'all' || (view === 'mine' && t.assignedToId === me) || (view === 'byme' && t.assignedById === me);
  const q = search.trim().toLowerCase();
  const matchesSearch = (t) => !q || [t.productName, t.title, t.assignedToName, t.assignedByName, fmtDateTime(t.createdAt)]
    .join(' ').toLowerCase().includes(q);
  const viewTasks = tasks.filter(inView);
  let rows = [];
  if (!statusFilter && !q) {
    const ids = new Set(viewTasks.map((t) => t.id));
    const walk = (t, depth) => {
      rows.push({ t, depth });
      (children[t.id] || []).filter((c) => ids.has(c.id)).forEach((c) => walk(c, depth + 1));
    };
    viewTasks.filter((t) => !t.parentId || !ids.has(t.parentId)).forEach((t) => walk(t, 0));
  } else {
    rows = viewTasks.filter((t) => (!statusFilter || t.status === statusFilter) && matchesSearch(t)).map((t) => ({ t, depth: 0 }));
  }

  // on wide screens the first task is selected so its details are always visible
  const current = selected && byId[selected] && rows.some((r) => r.t.id === selected) ? byId[selected]
    : (wide && rows.length > 0 ? rows[0].t : null);
  const choose = (id) => { setSelected(id); if (!wide) setPopup(true); };

  const mine = tasks.filter((t) => t.assignedToId === me);
  const myPending = mine.reduce((n, t) => n + balanceOf(t), 0);

  // ----- actions -----
  const openNew = () => {
    setForm({ assigneeId: '', productId: '', units: '', title: '', assignedOn: new Date() });
    setFormError('');
    setModal({ mode: 'new' });
  };
  const openSplit = (task) => {
    setPopup(false);
    setForm({ assigneeId: '', units: '', title: task.title || '', assignedOn: new Date() });
    setFormError('');
    setModal({ mode: 'split', task });
  };
  const openEdit = (task) => {
    setPopup(false);
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
        const created = await tasksApi.create({
          parentId: modal.mode === 'split' ? modal.task.id : null,
          productId: modal.mode === 'new' ? Number(form.productId) : null,
          assigneeId: Number(form.assigneeId),
          units: Number(form.units),
          title: form.title,
        });
        if (modal.mode === 'new') setSelected(created.id);
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
    setPopup(false);
    setSelected(null);
    setError('');
    try {
      await tasksApi.remove(t.id);
      load();
    } catch (err) {
      setError(err.message);
    }
  };

  const left = (t) => t.units - t.splitUnits;

  // ----- details of one task (side panel or popup) -----
  const details = (v) => (
    <div className="task-details">
      <dl className="detail-grid">
        <dt>Assigned to</dt><dd>{person(v.assignedToId, v.assignedToName)}</dd>
        <dt>Assigned by</dt><dd>{person(v.assignedById, v.assignedByName)}</dd>
        <dt>Assigned on</dt><dd>{fmtDateTime(v.createdAt)}</dd>
        {v.parentId && byId[v.parentId] && (<><dt>Split from</dt><dd>
          <button type="button" className="link" onClick={() => setSelected(v.parentId)}>
            {taskName(byId[v.parentId])} ({person(byId[v.parentId].assignedToId, byId[v.parentId].assignedToName)})
          </button></dd></>)}
        <dt>Status</dt><dd><StatusBadge status={v.status} /></dd>
      </dl>

      <div className="pending-hero">
        <div>
          <span className="muted">{v.assignedToId === me ? 'Pending for you' : `Pending for ${v.assignedToName}`}</span>
          <strong>{fmt(balanceOf(v))} <small>{v.unit}</small></strong>
        </div>
        <PendingCell t={v} />
      </div>

      <div className="detail-sale">
        <div><span className="muted">Target</span><strong>{fmt(v.units)} {v.unit}</strong></div>
        <div><span className="muted">Sold</span><strong>{fmt(v.soldUnits)}</strong></div>
        <div><span className="muted">Split to team</span><strong>{fmt(v.splitUnits)}</strong></div>
        <div><span className="muted">Pending (whole task)</span><strong>{fmt(teamBalanceOf(v))}</strong></div>
        {v.assignedToId === me && isLeader && <div><span className="muted">Left to split</span><strong>{fmt(left(v))}</strong></div>}
      </div>

      {(children[v.id] || []).length > 0 && (
        <>
          <h4 className="detail-label sub-head">Split to team</h4>
          <ul className="sub-list">
            {children[v.id].map((c) => (
              <li key={c.id}>
                <button type="button" className="sub-item" onClick={() => setSelected(c.id)}>
                  <span className="sub-who">{person(c.assignedToId, c.assignedToName)}<small className="muted">{fmtDateTime(c.createdAt)}</small></span>
                  <PendingCell t={c} />
                  <StatusBadge status={c.status} />
                </button>
              </li>
            ))}
          </ul>
        </>
      )}

      <div className="modal-actions">
        {v.assignedById === me && <button type="button" className="danger-outline" onClick={() => setToDelete(v)}>Delete</button>}
        <span className="spacer" />
        {v.assignedById === me && <button type="button" onClick={() => openEdit(v)}>Edit</button>}
        {v.assignedToId === me && isLeader && left(v) > 0 && (
          <button type="button" className="primary" onClick={() => openSplit(v)}>Split to team</button>
        )}
      </div>
    </div>
  );

  const list = (
    <div className="table-scroll">
      <table className="act-table task-table">
        <thead>
          <tr><th>Task</th><th>Assigned to</th><th>Pending</th><th>Status</th></tr>
        </thead>
        <tbody>
          {rows.map(({ t, depth }) => (
            <tr key={t.id} className={current && current.id === t.id ? 'clickable selected' : 'clickable'} tabIndex={0}
              aria-selected={current && current.id === t.id} onClick={() => choose(t.id)}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); choose(t.id); } }}>
              <td>
                <div className="tree-cell" style={{ paddingLeft: `${depth * 1.3}rem` }}>
                  {depth > 0 && <span className="task-branch" aria-hidden="true">↳</span>}
                  <div className="two-line">
                    <strong>{taskName(t)}</strong>
                    <span className="muted">{t.productName} · {fmt(t.units)} {t.unit}</span>
                    <span className="muted nowrap">Assigned {fmtDateTime(t.createdAt)}</span>
                  </div>
                </div>
              </td>
              <td className="two-line">
                <span>{t.assignedToId === me ? <span className="member-chip me">You</span> : t.assignedToName}</span>
                <span className="muted">by {person(t.assignedById, t.assignedByName).replace(/^You$/, 'you')}</span>
              </td>
              <td><PendingCell t={t} /></td>
              <td><StatusBadge status={t.status} /></td>
            </tr>
          ))}
          {loaded && rows.length === 0 && (
            <tr><td colSpan="4" className="muted">
              {tasks.length > 0 ? 'No tasks match.'
                : isLeader ? (activeProducts.length > 0 ? 'No tasks yet. Use "+ Create task".' : (user.role === 'TENANT_ADMIN' ? 'Add products on the Products page first.' : 'Your admin has not added any products yet.'))
                  : 'No tasks assigned to you yet.'}
            </td></tr>
          )}
          {!loaded && !error && <tr><td colSpan="4" className="muted">Loading...</td></tr>}
        </tbody>
      </table>
    </div>
  );

  return (
    <div>
      {error && <p className="error">{error}</p>}

      <div className="stat-strip">
        {isMember && <div><span className="muted">Assigned to me</span><strong>{fmt(mine.length)}</strong></div>}
        {isMember && <div><span className="muted">My open tasks</span><strong>{fmt(mine.filter((t) => t.status !== 'COMPLETED').length)}</strong></div>}
        {isMember && <div><span className="muted">Units pending for me</span><strong>{fmt(myPending)}</strong></div>}
        {isLeader && <div><span className="muted">Team tasks</span><strong>{fmt(tasks.filter((t) => t.assignedToId !== me).length)}</strong></div>}
        {isLeader && <div><span className="muted">Team completed</span><strong>{fmt(tasks.filter((t) => t.assignedToId !== me && t.status === 'COMPLETED').length)}</strong></div>}
      </div>

      <div className="toolbar">
        <input className="search" placeholder="Search product, title, person or date..." value={search}
          onChange={(e) => setSearch(e.target.value)} />
        {isLeader && (
          <button type="button" className="primary" onClick={openNew} disabled={activeProducts.length === 0}
            title={activeProducts.length === 0 ? 'Add a product first' : ''}>+ Create task</button>
        )}
      </div>
      <div className="toolbar filters">
        {(isMember && isLeader) && (
          <div className="seg" role="tablist">
            {[['all', 'All'], ['mine', 'Assigned to me'], ['byme', 'Assigned by me']].map(([k, label]) => (
              <button key={k} type="button" role="tab" aria-selected={view === k}
                className={view === k ? 'seg-btn active' : 'seg-btn'} onClick={() => setView(k)}>{label}</button>
            ))}
          </div>
        )}
        <StatusChips tasks={viewTasks.filter(matchesSearch)} value={statusFilter} onChange={setStatusFilter} />
      </div>

      {wide ? (
        <div className="tasks-layout">
          {list}
          <aside className="panel task-panel" aria-label="Task details">
            {current ? (
              <>
                <h3 className="panel-title">{taskName(current)}<span className="muted panel-sub">{current.productName} · {fmt(current.units)} {current.unit}</span></h3>
                {details(current)}
              </>
            ) : <p className="muted">Select a task to see its details.</p>}
          </aside>
        </div>
      ) : list}

      <p className="muted hint">
        Select a task to see its details. Pending = units the assignee still has to sell themselves; units split to their team are pending with the team.
        Sales count from the day the task was assigned, and each sale fills a person's oldest task first.
      </p>

      {!wide && popup && current && (
        <Modal wide title={taskName(current)} onClose={() => setPopup(false)}>
          <p className="muted task-context">{current.productName} · {fmt(current.units)} {current.unit}</p>
          {details(current)}
        </Modal>
      )}

      {toDelete && (
        <ConfirmDialog message={`Delete the task "${taskName(toDelete)}" (${toDelete.productName}, ${fmt(toDelete.units)} ${toDelete.unit}) assigned to ${toDelete.assignedToName}?`}
          onConfirm={remove} onCancel={() => setToDelete(null)} />
      )}

      {modal && (
        <Modal
          title={modal.mode === 'new' ? 'Create task' : modal.mode === 'split' ? 'Split task to team' : 'Edit task'}
          onClose={() => setModal(null)}>
          <form onSubmit={submit} autoComplete="off">
            {formError && <p className="error">{formError}</p>}
            {modal.mode !== 'new' && (
              <p className="muted task-context">
                <strong>{taskName(modal.task)}</strong> · {modal.task.productName}: {fmt(modal.task.units)} {modal.task.unit}
                {modal.mode === 'split' && ` · ${fmt(left(modal.task))} ${modal.task.unit} left to split`}
                {modal.mode === 'edit' && ` · assigned to ${modal.task.assignedToName} on ${fmtDateTime(modal.task.createdAt)}`}
              </p>
            )}
            <div className="form-grid">
              <Field label={modal.mode === 'split' ? 'Title (leave empty to keep the same title)' : 'Task title'} wide>
                <input name="title" value={form.title} onChange={onChange} maxLength={200}
                  required={modal.mode === 'new' || (modal.mode === 'edit' && !modal.task.parentId)}
                  autoFocus={modal.mode !== 'split'} placeholder="e.g. November purifier push - Kochi region" />
              </Field>
              {modal.mode !== 'edit' && (
                <Field label="Assign to">
                  <select name="assigneeId" value={form.assigneeId} onChange={onChange} required autoFocus={modal.mode === 'split'}>
                    <option value="">Select team member...</option>
                    {assignees.map((a) => <option key={a.userId} value={a.userId}>{a.fullName}</option>)}
                  </select>
                </Field>
              )}
              {modal.mode !== 'edit' && (
                <Field label="Assigned on (automatic)">
                  <input value={fmtDateTime(form.assignedOn)} readOnly tabIndex={-1} className="readonly" />
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
                  max={modal.mode === 'split' ? left(modal.task) : 1000000} />
              </Field>
            </div>
            <div className="modal-actions">
              <button type="button" onClick={() => setModal(null)}>Cancel</button>
              <button type="submit" className="primary">
                {modal.mode === 'edit' ? 'Save' : modal.mode === 'split' ? 'Assign part' : 'Create task'}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
