import { useEffect, useState } from 'react';
import { activitiesApi, productsApi, tasksApi, stockApi } from '../api.js';
import ActivityForm from '../ActivityForm.jsx';
import SaleForm from '../SaleForm.jsx';
import Enquiries from './Enquiries.jsx';
import { fmtWhen, typeLabel, typeOf } from '../ActivityDetail.jsx';
import { fmtDateTime, money, useOwners } from '../format.js';
import Modal, { Field, ConfirmDialog } from '../Modal.jsx';
import { STATUS, StatusBadge, balanceOf, teamBalanceOf, taskName } from '../TaskBits.jsx';

const fmt = (n) => Number(n || 0).toLocaleString('en-IN');
const PAGE = 20;
const dayOf = (t) => String(t || '').slice(0, 10);

const SORTS = {
  newest: { label: 'Newest first', fn: (a, b) => String(b.createdAt).localeCompare(String(a.createdAt)) },
  oldest: { label: 'Oldest first', fn: (a, b) => String(a.createdAt).localeCompare(String(b.createdAt)) },
  pending: { label: 'Most pending', fn: (a, b) => balanceOf(b) - balanceOf(a) },
  progress: { label: 'Least progress', fn: (a, b) => (a.soldUnits / a.units) - (b.soldUnits / b.units) },
};

const NO_FILTERS = { search: '', status: '', productId: '', personId: '', from: '', to: '', pendingOnly: false, sort: 'newest' };

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
        <span className="muted">{fmt(t.soldUnits)} of {fmt(t.units)} sold{withTeam > 0 ? ` · ${fmt(withTeam)} with team` : ''}</span>
      </div>
    </div>
  );
}

/**
 * Sales tasks: a filterable list that stays usable with many tasks. A leader creates tasks (units of a product) for
 * people below them; those people can split the units among their own team. Click a task for details and actions.
 */
/**
 * Tasks section with two views: sales tasks, and enquiries from new customers.
 * view = 'enquiries' opens the Enquiries view (e.g. from a notification).
 */
export default function Tasks({ user, view: startView }) {
  const [view, setView] = useState(startView === 'enquiries' ? 'enquiries' : 'tasks');
  return (
    <div>
      <div className="toolbar">
        <div className="seg" role="tablist" aria-label="Tasks or enquiries">
          {[['tasks', '🎯 Tasks'], ['enquiries', '📥 Enquiries']].map(([k, label]) => (
            <button key={k} type="button" role="tab" aria-selected={view === k}
              className={view === k ? 'seg-btn active' : 'seg-btn'} onClick={() => setView(k)}>{label}</button>
          ))}
        </div>
      </div>
      {view === 'enquiries' ? <Enquiries user={user} /> : <TaskList user={user} />}
    </div>
  );
}

function TaskList({ user }) {
  const [tasks, setTasks] = useState([]);
  const [products, setProducts] = useState([]);
  const [error, setError] = useState('');
  const { team, names: ownerNames } = useOwners();
  const me = user.id;
  const isMember = user.role === 'MEMBER';
  const [view, setView] = useState(isMember ? 'mine' : 'byme');
  const [f, setF] = useState(NO_FILTERS);
  const [showFilters, setShowFilters] = useState(false);
  const [limit, setLimit] = useState(PAGE);
  const [selected, setSelected] = useState(null);
  // activities logged against the open task, and the task an activity is being logged for
  const [taskActs, setTaskActs] = useState(null);
  const [logFor, setLogFor] = useState(null);
  const loadTaskActs = (taskId) => {
    if (!taskId) { setTaskActs(null); return; }
    setTaskActs(null);
    activitiesApi.page({ taskId, activitiesOnly: true, size: 20 }).then((r) => setTaskActs(r.content)).catch(() => setTaskActs([]));
  };
  // the sales that count toward the open task (sales are not tied to a task: they fill the oldest task first)
  const [taskSales, setTaskSales] = useState(null);
  const [saleFor, setSaleFor] = useState(null); // the task a sale is being recorded from
  const loadTaskSales = (task) => {
    const ids = task?.dealIds || [];
    if (!ids.length) { setTaskSales([]); return; }
    setTaskSales(null);
    activitiesApi.rows(ids.slice(0, 50)).then(setTaskSales).catch(() => setTaskSales([]));
  };
  useEffect(() => { loadTaskActs(selected); loadTaskSales(tasks.find((x) => x.id === selected)); }, [selected]);

  // form modal: { mode: 'new' | 'split' | 'edit', task?: TaskView }
  const [modal, setModal] = useState(null);
  const [form, setForm] = useState({});
  const [formError, setFormError] = useState('');
  const [toDelete, setToDelete] = useState(null);

  const [loaded, setLoaded] = useState(false);
  const [stock, setStock] = useState({}); // admin only: productId -> stock row
  const loadStock = () => {
    if (!isMember) stockApi.list().then((rows) => setStock(Object.fromEntries(rows.map((r) => [r.productId, r])))).catch(() => {});
  };
  const load = () => {
    loadStock();
    return tasksApi.list().then((t) => { setTasks(t); setLoaded(true); return t; }).catch((e) => setError(e.message));
  };
  useEffect(() => {
    load();
    productsApi.list().then(setProducts).catch((e) => setError(e.message));
  }, []);
  useEffect(() => { setLimit(PAGE); }, [view, f]);

  const assignees = team.filter((t) => t.userId !== me && t.role === 'MEMBER' && t.active);
  const isLeader = assignees.length > 0;
  // assignee picker: people in reporting order (each person followed by those under them), role in brackets
  const assigneeTree = (() => {
    const ids = new Set(assignees.map((a) => a.userId));
    const kids = {};
    assignees.forEach((a) => { const p = ids.has(a.managerId) ? a.managerId : 'root'; (kids[p] ||= []).push(a); });
    Object.values(kids).forEach((l) => l.sort((x, y) => x.fullName.localeCompare(y.fullName)));
    const out = [];
    const walk = (key, depth) => (kids[key] || []).forEach((a) => { out.push({ ...a, depth }); walk(a.userId, depth + 1); });
    walk('root', 0);
    return out;
  })();
  const assigneeLabel = (a) => '    '.repeat(a.depth) + (a.depth ? '└ ' : '')
    + a.fullName + (a.teamRoleName ? ` (${a.teamRoleName})` : '');
  const activeProducts = products.filter((p) => p.active);
  const person = (id, name) => (id === me ? 'You' : name);
  const set = (k, v) => setF({ ...f, [k]: v });

  const byId = Object.fromEntries(tasks.map((t) => [t.id, t]));
  const children = {};
  tasks.forEach((t) => { if (t.parentId && byId[t.parentId]) (children[t.parentId] = children[t.parentId] || []).push(t); });

  // ----- views and filters -----
  const VIEWS = [
    ...(isMember ? [['mine', 'Assigned to me', (t) => t.assignedToId === me]] : []),
    ...(isLeader || !isMember ? [['byme', 'Assigned by me', (t) => t.assignedById === me]] : []),
    ...(isMember && isLeader ? [['team', 'My team', (t) => t.assignedToId !== me]] : []),
    ...(isLeader || !isMember ? [['all', 'All', () => true]] : []),
  ];
  const viewFn = (VIEWS.find(([k]) => k === view) || VIEWS[0])[2];
  const inView = tasks.filter(viewFn);

  const q = f.search.trim().toLowerCase();
  const filtered = inView.filter((t) => (!q || [t.title, t.productName, t.assignedToName, t.assignedByName].join(' ').toLowerCase().includes(q))
      && (!f.status || t.status === f.status)
      && (!f.productId || String(t.productId) === f.productId)
      && (!f.personId || String(t.assignedToId) === f.personId)
      && (!f.from || dayOf(t.createdAt) >= f.from)
      && (!f.to || dayOf(t.createdAt) <= f.to)
      && (!f.pendingOnly || balanceOf(t) > 0))
    .sort(SORTS[f.sort].fn);
  const shown = filtered.slice(0, limit);

  const people = [...new Map(inView.map((t) => [t.assignedToId, person(t.assignedToId, t.assignedToName)])).entries()]
    .sort((a, b) => a[1].localeCompare(b[1]));
  const usedProducts = [...new Map(inView.map((t) => [t.productId, t.productName])).entries()];
  const activeCount = ['status', 'productId', 'personId', 'from', 'to'].filter((k) => f[k]).length + (f.pendingOnly ? 1 : 0);
  const anyFilter = activeCount > 0 || q;

  const mine = tasks.filter((t) => t.assignedToId === me);
  const myPending = mine.reduce((n, t) => n + balanceOf(t), 0);
  const current = selected ? byId[selected] : null;

  // ----- actions -----
  const openNew = () => {
    loadStock();
    setForm({ assigneeId: '', productId: '', sourceId: '', units: '', title: '', assignedOn: new Date() });
    setFormError('');
    setModal({ mode: 'new' });
  };
  const openSplit = (task) => {
    setSelected(null);
    setForm({ assigneeId: '', units: '', title: task.title || '', assignedOn: new Date() });
    setFormError('');
    setModal({ mode: 'split', task });
  };
  const openEdit = (task) => {
    setSelected(null);
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
          // members create tasks out of one of their own tasks (their balance); the admin creates them from stock
          parentId: modal.mode === 'split' ? modal.task.id : isMember ? Number(form.sourceId) : null,
          productId: modal.mode === 'new' && !isMember ? Number(form.productId) : null,
          assigneeId: Number(form.assigneeId),
          units: Number(form.units),
          title: form.title,
        });
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
    setSelected(null);
    setError('');
    try {
      await tasksApi.remove(t.id);
      load();
    } catch (err) {
      setError(err.message);
    }
  };

  // units the assignee can still hand out: not handed to the team and not sold by them yet
  const left = (t) => t.ownBalance;
  const stockLeft = (productId) => Math.max(0, stock[productId]?.left ?? 0);
  const sources = mine.filter((t) => left(t) > 0); // a member's tasks they can hand units out of
  // most units the form may ask for, and a line saying why
  const cap = (() => {
    if (!modal) return null;
    if (modal.mode === 'split') return { max: left(modal.task), text: `${fmt(left(modal.task))} ${modal.task.unit} left with you to hand out` };
    if (modal.mode === 'edit') {
      const t = modal.task;
      const extra = t.parentId ? left(byId[t.parentId] || { ownBalance: 0 }) : stockLeft(t.productId);
      return { max: t.units + extra, text: `You can raise this by up to ${fmt(extra)} ${t.unit} (${t.parentId ? 'your balance on the task' : 'stock left'})` };
    }
    if (isMember) {
      const src = byId[form.sourceId];
      return src ? { max: left(src), text: `${fmt(left(src))} ${src.unit} left with you on this task` } : null;
    }
    const p = stock[form.productId];
    return form.productId ? { max: stockLeft(form.productId), text: `${fmt(stockLeft(form.productId))} ${p?.unit || 'units'} left in stock` } : null;
  })();
  const overLimit = cap && form.units !== '' && Number(form.units) > cap.max;

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
        {VIEWS.length > 1 && (
          <div className="seg" role="tablist" aria-label="Which tasks">
            {VIEWS.map(([k, label, fn]) => (
              <button key={k} type="button" role="tab" aria-selected={view === k}
                className={view === k ? 'seg-btn active' : 'seg-btn'} onClick={() => setView(k)}>
                {label} <span className="chip-count">{tasks.filter(fn).length}</span>
              </button>
            ))}
          </div>
        )}
        <span className="spacer" />
        {isLeader && (
          <button type="button" className="primary" onClick={openNew} disabled={activeProducts.length === 0}
            title={activeProducts.length === 0 ? 'Add a product first' : ''}>+ Create task</button>
        )}
      </div>

      <div className="toolbar">
        <input className="search" placeholder="Search title, product or person..." value={f.search}
          onChange={(e) => set('search', e.target.value)} />
        <select className="filter-select" value={f.status} onChange={(e) => set('status', e.target.value)} aria-label="Status">
          <option value="">Any status</option>
          <option value="NOT_STARTED">{STATUS.NOT_STARTED.label}</option>
          <option value="IN_PROGRESS">{STATUS.IN_PROGRESS.label}</option>
          <option value="COMPLETED">{STATUS.COMPLETED.label}</option>
        </select>
        <button type="button" className={showFilters || activeCount > (f.status ? 1 : 0) ? 'filter-toggle on' : 'filter-toggle'}
          onClick={() => setShowFilters(!showFilters)} aria-expanded={showFilters}>
          ⚙ More filters{activeCount - (f.status ? 1 : 0) > 0 ? ` (${activeCount - (f.status ? 1 : 0)})` : ''}
        </button>
        <select className="filter-select" value={f.sort} onChange={(e) => set('sort', e.target.value)} aria-label="Sort">
          {Object.entries(SORTS).map(([k, s]) => <option key={k} value={k}>{s.label}</option>)}
        </select>
      </div>

      {showFilters && (
        <div className="filter-panel">
          <label className="field">
            <span>Product</span>
            <select value={f.productId} onChange={(e) => set('productId', e.target.value)}>
              <option value="">All products</option>
              {usedProducts.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
            </select>
          </label>
          {people.length > 1 && (
            <label className="field">
              <span>Assigned to</span>
              <select value={f.personId} onChange={(e) => set('personId', e.target.value)}>
                <option value="">Anyone</option>
                {people.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
              </select>
            </label>
          )}
          <label className="field">
            <span>Assigned from</span>
            <input type="date" value={f.from} onChange={(e) => set('from', e.target.value)} />
          </label>
          <label className="field">
            <span>Assigned to date</span>
            <input type="date" value={f.to} onChange={(e) => set('to', e.target.value)} />
          </label>
          <label className="check-inline pending-only">
            <input type="checkbox" checked={f.pendingOnly} onChange={(e) => set('pendingOnly', e.target.checked)} />
            Only tasks with units pending
          </label>
        </div>
      )}

      <div className="result-line">
        <span className="muted">
          {loaded ? `${fmt(filtered.length)} task${filtered.length === 1 ? '' : 's'}${anyFilter ? ` match (of ${fmt(inView.length)})` : ''}` : 'Loading...'}
          {filtered.length > 0 && ` · ${fmt(filtered.reduce((n, t) => n + balanceOf(t), 0))} units pending`}
        </span>
        {anyFilter && <button type="button" className="link" onClick={() => setF({ ...NO_FILTERS, sort: f.sort })}>Clear filters</button>}
      </div>

      <div className="table-scroll">
        <table className="act-table task-table">
          <thead>
            <tr><th>Task</th><th>Assigned to</th><th>Pending</th><th>Status</th></tr>
          </thead>
          <tbody>
            {shown.map((t) => (
              <tr key={t.id} className="clickable" tabIndex={0} onClick={() => setSelected(t.id)}
                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setSelected(t.id); } }}>
                <td className="two-line">
                  <strong>{taskName(t)}</strong>
                  <span className="muted">{t.productName} · {fmt(t.units)} {t.unit} · {fmtDateTime(t.createdAt)}</span>
                  {(t.parentId || t.subTaskCount > 0) && (
                    <span className="task-tags">
                      {t.parentId && <span className="tag">part of a team task</span>}
                      {t.subTaskCount > 0 && <span className="tag">split to {t.subTaskCount}</span>}
                    </span>
                  )}
                </td>
                <td className="two-line">
                  <span>{t.assignedToId === me ? <span className="member-chip me">You</span> : t.assignedToName}</span>
                  <span className="muted">by {person(t.assignedById, t.assignedByName).replace(/^You$/, 'you')}</span>
                </td>
                <td><PendingCell t={t} /></td>
                <td><StatusBadge status={t.status} /></td>
              </tr>
            ))}
            {loaded && filtered.length === 0 && (
              <tr><td colSpan="4" className="muted">
                {inView.length > 0 ? 'No tasks match these filters.'
                  : tasks.length > 0 ? 'No tasks in this view.'
                    : isLeader ? (activeProducts.length > 0 ? 'No tasks yet. Use "+ Create task".' : (user.role === 'TENANT_ADMIN' ? 'Add products on the Products page first.' : 'Your admin has not added any products yet.'))
                      : 'No tasks assigned to you yet.'}
              </td></tr>
            )}
            {!loaded && !error && <tr><td colSpan="4" className="muted">Loading...</td></tr>}
          </tbody>
        </table>
      </div>
      {filtered.length > shown.length && (
        <div className="more-row">
          <span className="muted">Showing {fmt(shown.length)} of {fmt(filtered.length)}</span>
          <button type="button" onClick={() => setLimit(limit + PAGE)}>Show {Math.min(PAGE, filtered.length - shown.length)} more</button>
        </div>
      )}
      <p className="muted hint">
        Click a task for details. Pending = units the assignee still has to sell themselves; units split to their team are pending with the team.
      </p>

      {current && (
        <Modal wide title={taskName(current)} onClose={() => setSelected(null)}>
          <p className="muted task-context">{current.productName} · {fmt(current.units)} {current.unit}</p>
          <dl className="detail-grid">
            <dt>Assigned to</dt><dd>{person(current.assignedToId, current.assignedToName)}</dd>
            <dt>Assigned by</dt><dd>{person(current.assignedById, current.assignedByName)}</dd>
            <dt>Assigned on</dt><dd>{fmtDateTime(current.createdAt)}</dd>
            {current.parentId && byId[current.parentId] && (<><dt>Split from</dt><dd>
              <button type="button" className="link" onClick={() => setSelected(current.parentId)}>
                {taskName(byId[current.parentId])} ({person(byId[current.parentId].assignedToId, byId[current.parentId].assignedToName)})
              </button></dd></>)}
            <dt>Status</dt><dd><StatusBadge status={current.status} /></dd>
          </dl>

          <div className="pending-hero">
            <div>
              <span className="muted">{current.assignedToId === me ? 'Pending for you' : `Pending for ${current.assignedToName}`}</span>
              <strong>{fmt(balanceOf(current))} <small>{current.unit}</small></strong>
            </div>
            <PendingCell t={current} />
          </div>

          <div className="detail-sale">
            <div><span className="muted">Target</span><strong>{fmt(current.units)} {current.unit}</strong></div>
            <div><span className="muted">Sold</span><strong>{fmt(current.soldUnits)}</strong></div>
            <div><span className="muted">Split to team</span><strong>{fmt(current.splitUnits)}</strong></div>
            <div><span className="muted">Pending (whole task)</span><strong>{fmt(teamBalanceOf(current))}</strong></div>
            {current.assignedToId === me && isLeader && <div><span className="muted">Left to split</span><strong>{fmt(left(current))}</strong></div>}
          </div>

          {(children[current.id] || []).length > 0 && (
            <>
              <h4 className="detail-label sub-head">Split to team</h4>
              <ul className="sub-list">
                {children[current.id].map((c) => (
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

          <h4 className="detail-label sub-head">Activities on this task</h4>
          {taskActs === null ? <p className="muted">Loading...</p> : taskActs.length === 0 ? (
            <p className="muted">Nothing logged yet. Use <strong>Log activity</strong> to record a meeting or call about this task.</p>
          ) : (
            <ul className="mini-list">
              {taskActs.map((a) => (
                <li key={a.id}>
                  <span className="nowrap muted">{fmtWhen(a.activityTime)}</span>
                  <span className="mini-title"><span aria-hidden="true">{typeOf(a.type).icon}</span> {a.subject}
                    <small className="muted"> · {typeLabel(a)}</small></span>
                  <span className="member-chip">{person(a.ownerId, ownerNames[a.ownerId] || '')}</span>
                  <span />
                </li>
              ))}
            </ul>
          )}

          <h4 className="detail-label sub-head">Sales counted on this task</h4>
          {taskSales === null ? <p className="muted">Loading...</p> : taskSales.length === 0 ? (
            <p className="muted">No sales yet.{current.assignedToId === me ? ' Use Record sale when you sell this product.' : ''}</p>
          ) : (
            <ul className="mini-list">
              {taskSales.map((a) => (
                <li key={a.id}>
                  <span className="nowrap muted">{fmtWhen(a.activityTime)}</span>
                  <span className="mini-title">💰 {a.customerName}
                    <small className="muted"> · {fmt(current.dealUnits?.[a.id] ?? a.unitsSold)} {current.unit} counted here</small></span>
                  <span className="member-chip">{person(a.ownerId, ownerNames[a.ownerId] || '')}</span>
                  <span className="mini-sale">{a.saleAmount != null ? money(a.saleAmount) : ''}</span>
                </li>
              ))}
            </ul>
          )}

          <div className="modal-actions">
            {current.assignedById === me && <button type="button" className="danger-outline" onClick={() => setToDelete(current)}>Delete</button>}
            <span className="spacer" />
            <button type="button" onClick={() => setSelected(null)}>Close</button>
            <button type="button" onClick={() => setLogFor(current.id)}>📝 Log activity</button>
            {current.assignedToId === me && (
              <button type="button" onClick={() => setSaleFor(current)} disabled={balanceOf(current) <= 0}
                title={balanceOf(current) <= 0 ? 'Nothing left for you to sell on this task' : ''}>💰 Record sale</button>
            )}
            {current.assignedById === me && <button type="button" onClick={() => openEdit(current)}>Edit</button>}
            {current.assignedToId === me && isLeader && left(current) > 0 && (
              <button type="button" className="primary" onClick={() => openSplit(current)}>Split to team</button>
            )}
          </div>
        </Modal>
      )}
      {logFor && (
        <ActivityForm taskId={logFor} onClose={() => setLogFor(null)}
          onSaved={() => { setLogFor(null); loadTaskActs(selected); }} />
      )}
      {saleFor && (
        <SaleForm productId={saleFor.productId} user={user} onClose={() => setSaleFor(null)}
          onSaved={() => {
            setSaleFor(null);
            // the sale changes this task's progress: reload the tasks, then this task's sales
            load().then((ts) => loadTaskSales((ts || []).find((x) => x.id === selected)));
          }} />
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
                    {assigneeTree.map((a) => <option key={a.userId} value={a.userId}>{assigneeLabel(a)}</option>)}
                  </select>
                </Field>
              )}
              {modal.mode !== 'edit' && (
                <Field label="Assigned on (automatic)">
                  <input value={fmtDateTime(form.assignedOn)} readOnly tabIndex={-1} className="readonly" />
                </Field>
              )}
              {modal.mode === 'new' && !isMember && (
                <Field label="Product">
                  <select name="productId" value={form.productId} onChange={onChange} required>
                    <option value="">Select product...</option>
                    {activeProducts.map((p) => (
                      <option key={p.id} value={p.id} disabled={stockLeft(p.id) <= 0}>
                        {p.name} - {stockLeft(p.id) > 0 ? `${fmt(stockLeft(p.id))} ${p.unit} in stock` : 'no stock left'}
                      </option>
                    ))}
                  </select>
                </Field>
              )}
              {modal.mode === 'new' && isMember && (
                <Field label="Units come from my task">
                  <select name="sourceId" value={form.sourceId} onChange={onChange} required>
                    <option value="">Select one of your tasks...</option>
                    {sources.map((t) => (
                      <option key={t.id} value={t.id}>{taskName(t)} - {t.productName}, {fmt(left(t))} {t.unit} left</option>
                    ))}
                  </select>
                </Field>
              )}
              <Field label="Units">
                <input name="units" type="number" min="1" step="1" required value={form.units} onChange={onChange}
                  max={cap ? Math.max(1, cap.max) : 1000000} className={overLimit ? 'invalid' : ''}
                  disabled={!!cap && cap.max <= 0} />
                {cap && <span className={overLimit || cap.max <= 0 ? 'balance-note over' : 'balance-note'}>
                  {overLimit ? `Too many: only ${fmt(cap.max)} available. ` : ''}{cap.text}
                </span>}
              </Field>
            </div>
            {modal.mode === 'new' && !isMember && activeProducts.every((p) => stockLeft(p.id) <= 0) && (
              <p className="muted hint">No stock left for any product. Add stock in the Stock section first.</p>
            )}
            {modal.mode === 'new' && isMember && sources.length === 0 && (
              <p className="muted hint">You have no units left to hand out. Tasks you create come out of your own balance.</p>
            )}
            <div className="modal-actions">
              <button type="button" onClick={() => setModal(null)}>Cancel</button>
              <button type="submit" className="primary" disabled={overLimit || (!!cap && cap.max <= 0)}>
                {modal.mode === 'edit' ? 'Save' : modal.mode === 'split' ? 'Assign part' : 'Create task'}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
