import { useEffect, useState } from 'react';
import { customersApi, activitiesApi, productsApi } from '../api.js';
import { nowLocalInput, toLocalInput, useOwners } from '../format.js';
import Modal, { Field, ConfirmDialog } from '../Modal.jsx';

/** Kinds of log entries. TASK only exists on older entries and is not offered for new ones. */
const TYPES = {
  MEETING: { label: 'Meeting', icon: '🤝' },
  CALL: { label: 'Call', icon: '📞' },
  VISIT: { label: 'Visit', icon: '📍' },
  EMAIL: { label: 'Email', icon: '✉️' },
  NOTE: { label: 'Note', icon: '📝' },
  TASK: { label: 'Task', icon: '✅', legacy: true },
};
const PICKABLE = Object.keys(TYPES).filter((t) => !TYPES[t].legacy);
const typeOf = (t) => TYPES[t] || { label: t, icon: '•' };

const empty = { customerId: '', type: 'MEETING', subject: '', attendees: '', notes: '', outcome: '', activityTime: '', productId: '', unitsSold: '' };

const dayKey = (t) => (t ? String(t).slice(0, 10) : '');
const pad = (n) => String(n).padStart(2, '0');
const isoDay = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

function dayLabel(key) {
  if (!key) return 'No date';
  const today = new Date();
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  if (key === isoDay(today)) return 'Today';
  if (key === isoDay(yesterday)) return 'Yesterday';
  const [y, m, d] = key.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('en-IN', { weekday: 'long', day: '2-digit', month: 'short', year: 'numeric' });
}

const timeLabel = (t) => {
  const d = new Date(t);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: true });
};

/** Notes can be long: show the first few lines, with a toggle for the rest. */
function LongText({ text }) {
  const [more, setMore] = useState(false);
  const long = text.length > 320 || text.split('\n').length > 5;
  return (
    <>
      <p className={long && !more ? 'log-text clamped' : 'log-text'}>{text}</p>
      {long && <button type="button" className="link" onClick={() => setMore(!more)}>{more ? 'Show less' : 'Show more'}</button>}
    </>
  );
}

export default function Activities() {
  const [items, setItems] = useState([]);
  const [customers, setCustomers] = useState([]);
  const [products, setProducts] = useState([]);
  const [form, setForm] = useState(empty);
  const [editingId, setEditingId] = useState(null);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState('');
  const [formError, setFormError] = useState('');
  const [toDelete, setToDelete] = useState(null);
  const { names, multi, team } = useOwners();
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState('');
  const [ownerFilter, setOwnerFilter] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  const load = () => activitiesApi.list().then(setItems).catch((e) => setError(e.message));
  useEffect(() => {
    load();
    customersApi.all().then(setCustomers).catch((e) => setError(e.message));
    productsApi.list().then(setProducts).catch((e) => setError(e.message));
  }, []);
  const productOf = (id) => products.find((p) => p.id === Number(id));

  const customerName = (id) => {
    const c = customers.find((x) => x.id === id);
    return c ? `${c.firstName} ${c.lastName}${c.company ? ` · ${c.company}` : ''}` : `Customer #${id}`;
  };
  const onChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });

  // date/time defaults to now but can be changed, e.g. when logging a meeting afterwards
  const openAdd = () => { setEditingId(null); setForm({ ...empty, activityTime: nowLocalInput() }); setFormError(''); setOpen(true); };
  const openEdit = (a) => {
    setEditingId(a.id);
    setForm({ ...empty, ...a, attendees: a.attendees || '', notes: a.notes || '', outcome: a.outcome || '',
      activityTime: toLocalInput(a.activityTime), productId: a.productId ? String(a.productId) : '',
      unitsSold: a.unitsSold ? String(a.unitsSold) : '' });
    setFormError('');
    setOpen(true);
  };
  const close = () => setOpen(false);

  const submit = async (e) => {
    e.preventDefault();
    setFormError('');
    const body = { ...form, customerId: Number(form.customerId), activityTime: form.activityTime || null,
      productId: form.productId ? Number(form.productId) : null,
      unitsSold: form.productId && form.unitsSold ? Number(form.unitsSold) : null };
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

  const q = search.trim().toLowerCase();
  const filtered = items
    .filter((a) => {
      const day = dayKey(a.activityTime);
      const haystack = [customerName(a.customerId), a.subject, a.attendees, a.notes, a.outcome].join(' ').toLowerCase();
      return (!q || haystack.includes(q)) &&
        (!typeFilter || a.type === typeFilter) &&
        (!ownerFilter || String(a.ownerId) === ownerFilter) &&
        (!from || (day && day >= from)) &&
        (!to || (day && day <= to));
    })
    .sort((a, b) => String(b.activityTime || '').localeCompare(String(a.activityTime || '')) || b.id - a.id);

  const groups = [];
  for (const a of filtered) {
    const key = dayKey(a.activityTime);
    if (!groups.length || groups[groups.length - 1].key !== key) groups.push({ key, entries: [] });
    groups[groups.length - 1].entries.push(a);
  }
  const anyFilter = search || typeFilter || ownerFilter || from || to;
  const counts = PICKABLE.map((t) => [t, filtered.filter((a) => a.type === t).length]).filter(([, n]) => n > 0);

  return (
    <div>
      {error && <p className="error">{error}</p>}

      <div className="toolbar">
        <input className="search" placeholder="Search customer, title, attendees, notes..." value={search}
          onChange={(e) => setSearch(e.target.value)} />
        <select className="filter-select" value={typeFilter} onChange={(e) => setTypeFilter(e.target.value)} title="Filter by type">
          <option value="">All types</option>
          {Object.keys(TYPES).map((t) => <option key={t} value={t}>{TYPES[t].icon} {TYPES[t].label}</option>)}
        </select>
        {multi && (
          <select className="filter-select" value={ownerFilter} onChange={(e) => setOwnerFilter(e.target.value)} title="Filter by owner">
            <option value="">All owners</option>
            {team.map((t) => <option key={t.userId} value={t.userId}>{t.fullName}</option>)}
          </select>
        )}
        <label className="filter-time">From
          <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className="filter-time">To
          <input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
        {anyFilter && (
          <button type="button" onClick={() => { setSearch(''); setTypeFilter(''); setOwnerFilter(''); setFrom(''); setTo(''); }}>Clear</button>
        )}
        <button type="button" className="primary" onClick={openAdd}>+ Log activity</button>
      </div>

      {filtered.length > 0 && (
        <p className="muted log-summary">
          {filtered.length} {filtered.length === 1 ? 'entry' : 'entries'}
          {counts.length > 0 && ' · '}
          {counts.map(([t, n]) => `${n} ${TYPES[t].label.toLowerCase()}${n > 1 ? 's' : ''}`).join(', ')}
        </p>
      )}

      {groups.map((g) => (
        <section key={g.key || 'none'} className="log-day">
          <h3 className="log-day-head">{dayLabel(g.key)} <span className="count-pill">{g.entries.length}</span></h3>
          {g.entries.map((a) => {
            const t = typeOf(a.type);
            return (
              <article key={a.id} className="log-entry">
                <div className="log-when">
                  <span className="log-icon" aria-hidden="true">{t.icon}</span>
                  <span className="log-time">{timeLabel(a.activityTime)}</span>
                </div>
                <div className="log-body">
                  <div className="log-head">
                    <div>
                      <span className="log-type">{t.label}</span>
                      <h4 className="log-title">{a.subject}</h4>
                      <p className="muted log-meta">
                        {customerName(a.customerId)}{multi && names[a.ownerId] ? ` · logged by ${names[a.ownerId]}` : ''}
                      </p>
                    </div>
                    <div className="log-actions">
                      <button className="plain" onClick={() => openEdit(a)}>Edit</button>
                      <button className="plain" onClick={() => setToDelete(a)}>Delete</button>
                    </div>
                  </div>
                  {a.productId && (
                    <p className="log-sale"><span aria-hidden="true">🛒</span>
                      Sold <strong>{Number(a.unitsSold || 0).toLocaleString('en-IN')} {productOf(a.productId)?.unit || 'units'}</strong>
                      {' '}of {productOf(a.productId)?.name || 'a product'}</p>
                  )}
                  {a.attendees && <p className="log-attendees"><strong>Attendees:</strong> {a.attendees}</p>}
                  {a.notes && <LongText text={a.notes} />}
                  {a.outcome && (
                    <div className="log-outcome">
                      <strong>Outcome / next steps</strong>
                      <p className="log-text">{a.outcome}</p>
                    </div>
                  )}
                </div>
              </article>
            );
          })}
        </section>
      ))}

      {filtered.length === 0 && (
        <div className="log-empty">
          <p className="muted">{items.length === 0 ? 'Nothing logged yet. Record your first meeting, call or note.' : 'No entries match the filters.'}</p>
          {items.length === 0 && <button type="button" className="primary" onClick={openAdd}>+ Log activity</button>}
        </div>
      )}

      {toDelete && (
        <ConfirmDialog message={`Delete the entry "${toDelete.subject}"? This cannot be undone.`}
          onConfirm={remove} onCancel={() => setToDelete(null)} />
      )}

      {open && (
        <Modal title={editingId ? 'Edit log entry' : 'Log activity'} onClose={close}>
          <form onSubmit={submit} autoComplete="off">
            {formError && <p className="error">{formError}</p>}
            <div className="type-picker" role="radiogroup" aria-label="Type">
              {(TYPES[form.type]?.legacy ? [...PICKABLE, form.type] : PICKABLE).map((k) => (
                <button key={k} type="button" role="radio" aria-checked={form.type === k}
                  className={form.type === k ? 'type-option active' : 'type-option'}
                  onClick={() => setForm({ ...form, type: k })}>
                  <span aria-hidden="true">{TYPES[k].icon}</span> {TYPES[k].label}
                </button>
              ))}
            </div>
            <div className="form-grid">
              <Field label="Customer">
                <select name="customerId" value={form.customerId} onChange={onChange} required autoFocus>
                  <option value="">Select customer...</option>
                  {customers.map((c) => <option key={c.id} value={c.id}>{c.firstName} {c.lastName}{c.company ? ` (${c.company})` : ''}</option>)}
                </select>
              </Field>
              <Field label="Date and time">
                <input name="activityTime" type="datetime-local" value={form.activityTime || ''} onChange={onChange} required />
              </Field>
              <Field label="Title" wide>
                <input name="subject" value={form.subject} onChange={onChange} required maxLength={200}
                  placeholder="e.g. Quarterly review with finance team" />
              </Field>
              <Field label="Attendees" wide>
                <input name="attendees" value={form.attendees} onChange={onChange} maxLength={500}
                  placeholder="e.g. Ravi (CFO), Priya, me" />
              </Field>
              <Field label="Product sold (optional)">
                <select name="productId" value={form.productId}
                  onChange={(e) => setForm({ ...form, productId: e.target.value, unitsSold: e.target.value ? form.unitsSold : '' })}>
                  <option value="">No sale</option>
                  {products.filter((p) => p.active || String(p.id) === form.productId).map((p) => (
                    <option key={p.id} value={p.id}>{p.name} ({p.unit})</option>
                  ))}
                </select>
              </Field>
              <Field label={`Units sold${productOf(form.productId) ? ` (${productOf(form.productId).unit})` : ''}`}>
                <input name="unitsSold" type="number" min="1" step="1" max="1000000" value={form.unitsSold} onChange={onChange}
                  disabled={!form.productId} required={!!form.productId}
                  placeholder={form.productId ? 'e.g. 10' : 'Pick a product first'} />
              </Field>
              <Field label="Notes" wide>
                <textarea name="notes" rows={7} maxLength={2000} value={form.notes} onChange={onChange}
                  placeholder="What was discussed..." />
                <small className="muted count">{form.notes.length} / 2000</small>
              </Field>
              <Field label="Outcome / next steps" wide>
                <textarea name="outcome" rows={3} maxLength={2000} value={form.outcome} onChange={onChange}
                  className="short" placeholder="Decisions made, follow-ups, who does what by when..." />
              </Field>
            </div>
            <div className="modal-actions">
              <button type="button" onClick={close}>Cancel</button>
              <button type="submit" className="primary">{editingId ? 'Save changes' : 'Save entry'}</button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
