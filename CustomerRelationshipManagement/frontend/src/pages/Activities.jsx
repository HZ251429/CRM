import { useEffect, useState } from 'react';
import { customersApi, activitiesApi, productsApi } from '../api.js';
import { money, nowLocalInput, toLocalInput, useOwners } from '../format.js';
import Modal, { Field, ConfirmDialog } from '../Modal.jsx';
import ActivityDetail, { TYPES, typeOf, PayBadge, fmtWhen, fmtNum } from '../ActivityDetail.jsx';

const PICKABLE = Object.keys(TYPES).filter((t) => !TYPES[t].legacy);
const PAGE = 50;

const empty = {
  customerId: '', type: 'MEETING', subject: '', attendees: '', notes: '', outcome: '', activityTime: '',
  isSale: false, productId: '', unitsSold: '', saleAmount: '', fullyPaid: true, amountPaid: '',
};

const dayKey = (t) => (t ? String(t).slice(0, 10) : '');

/** Activity log as a compact list; click a row for the full details. */
export default function Activities({ user }) {
  const [items, setItems] = useState([]);
  const [loaded, setLoaded] = useState(false);
  const [customers, setCustomers] = useState([]);
  const [products, setProducts] = useState([]);
  const [form, setForm] = useState(empty);
  const [editingId, setEditingId] = useState(null);
  const [open, setOpen] = useState(false);
  const [viewing, setViewing] = useState(null);
  const [error, setError] = useState('');
  const [formError, setFormError] = useState('');
  const [toDelete, setToDelete] = useState(null);
  const { names, multi, team } = useOwners();
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState('');
  const [ownerFilter, setOwnerFilter] = useState('');
  const [salesOnly, setSalesOnly] = useState(false);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [limit, setLimit] = useState(PAGE);

  const load = () => activitiesApi.list().then((r) => { setItems(r); setLoaded(true); }).catch((e) => setError(e.message));
  useEffect(() => {
    load();
    customersApi.all().then(setCustomers).catch((e) => setError(e.message));
    productsApi.list().then(setProducts).catch((e) => setError(e.message));
  }, []);
  useEffect(() => { setLimit(PAGE); }, [search, typeFilter, ownerFilter, salesOnly, from, to]);

  const productOf = (id) => products.find((p) => p.id === Number(id));
  const customerName = (id) => {
    const c = customers.find((x) => x.id === id);
    return c ? `${c.firstName} ${c.lastName}${c.company ? ` · ${c.company}` : ''}` : `Customer #${id}`;
  };
  const memberName = (id) => (id === user.id ? `${names[id] || 'Me'} (me)` : names[id] || 'Unknown');
  const onChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });

  // date/time defaults to now but can be changed, e.g. when logging a meeting afterwards
  const openAdd = () => { setEditingId(null); setForm({ ...empty, activityTime: nowLocalInput() }); setFormError(''); setOpen(true); };
  const openEdit = (a) => {
    setViewing(null);
    setEditingId(a.id);
    const fullyPaid = a.saleAmount == null || a.amountPaid == null || Number(a.amountPaid) >= Number(a.saleAmount);
    setForm({
      ...empty, customerId: a.customerId, type: a.type, subject: a.subject,
      attendees: a.attendees || '', notes: a.notes || '', outcome: a.outcome || '',
      activityTime: toLocalInput(a.activityTime),
      isSale: !!a.productId, productId: a.productId ? String(a.productId) : '',
      unitsSold: a.unitsSold ? String(a.unitsSold) : '', saleAmount: a.saleAmount != null ? String(a.saleAmount) : '',
      fullyPaid, amountPaid: !fullyPaid && a.amountPaid != null ? String(a.amountPaid) : '',
    });
    setFormError('');
    setOpen(true);
  };
  const close = () => setOpen(false);

  const credit = form.isSale && !form.fullyPaid && form.saleAmount !== ''
    ? Math.max(0, Number(form.saleAmount) - Number(form.amountPaid || 0)) : 0;

  const submit = async (e) => {
    e.preventDefault();
    setFormError('');
    const sale = form.isSale;
    if (sale && !form.fullyPaid && Number(form.amountPaid || 0) > Number(form.saleAmount)) {
      setFormError('Amount paid cannot be more than the total amount');
      return;
    }
    const body = {
      customerId: Number(form.customerId), type: form.type, subject: form.subject, attendees: form.attendees,
      notes: form.notes, outcome: form.outcome, activityTime: form.activityTime || null,
      productId: sale ? Number(form.productId) : null,
      unitsSold: sale ? Number(form.unitsSold) : null,
      saleAmount: sale ? Number(form.saleAmount) : null,
      amountPaid: sale ? Number(form.fullyPaid ? form.saleAmount : (form.amountPaid || 0)) : null,
    };
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
    setViewing(null);
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
      const haystack = [customerName(a.customerId), a.subject, a.attendees, a.notes, a.outcome, names[a.ownerId],
        productOf(a.productId)?.name].join(' ').toLowerCase();
      return (!q || haystack.includes(q)) &&
        (!typeFilter || a.type === typeFilter) &&
        (!ownerFilter || String(a.ownerId) === ownerFilter) &&
        (!salesOnly || a.productId) &&
        (!from || (day && day >= from)) &&
        (!to || (day && day <= to));
    })
    .sort((a, b) => String(b.activityTime || '').localeCompare(String(a.activityTime || '')) || b.id - a.id);
  const shown = filtered.slice(0, limit);

  const anyFilter = search || typeFilter || ownerFilter || salesOnly || from || to;
  const deals = filtered.filter((a) => a.productId);
  const dealTotal = deals.reduce((n, a) => n + Number(a.saleAmount || 0), 0);
  const creditTotal = deals.reduce((n, a) => n + Number(a.creditAmount || 0), 0);
  const cols = multi ? 5 : 4;

  return (
    <div>
      {error && <p className="error">{error}</p>}

      <div className="toolbar">
        <input className="search" placeholder="Search customer, title, notes, member, product..." value={search}
          onChange={(e) => setSearch(e.target.value)} />
        <button type="button" className="primary" onClick={openAdd}>+ Log activity</button>
      </div>
      <div className="toolbar filters">
        <select className="filter-select" value={typeFilter} onChange={(e) => setTypeFilter(e.target.value)} title="Filter by type">
          <option value="">All types</option>
          {Object.keys(TYPES).map((t) => <option key={t} value={t}>{TYPES[t].icon} {TYPES[t].label}</option>)}
        </select>
        {multi && (
          <select className="filter-select" value={ownerFilter} onChange={(e) => setOwnerFilter(e.target.value)} title="Filter by member">
            <option value="">All members</option>
            {team.map((t) => <option key={t.userId} value={t.userId}>{t.userId === user.id ? `${t.fullName} (me)` : t.fullName}</option>)}
          </select>
        )}
        <label className="filter-time">From
          <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className="filter-time">To
          <input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
        <label className="check-inline">
          <input type="checkbox" checked={salesOnly} onChange={(e) => setSalesOnly(e.target.checked)} /> Sales only
        </label>
        {anyFilter && (
          <button type="button" onClick={() => { setSearch(''); setTypeFilter(''); setOwnerFilter(''); setSalesOnly(false); setFrom(''); setTo(''); }}>Clear filters</button>
        )}
      </div>

      {filtered.length > 0 && (
        <p className="muted log-summary">
          {fmtNum(filtered.length)} {filtered.length === 1 ? 'entry' : 'entries'}
          {deals.length > 0 && ` · ${deals.length} sale${deals.length > 1 ? 's' : ''} worth ${money(dealTotal)}`}
          {creditTotal > 0 && ` · ${money(creditTotal)} on credit`}
        </p>
      )}

      <div className="table-scroll">
        <table className="act-table">
          <thead>
            <tr>
              <th>When</th>{multi && <th>Member</th>}<th>Activity</th><th>Customer</th><th className="num">Sale</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((a) => {
              const t = typeOf(a.type);
              return (
                <tr key={a.id} className="clickable" tabIndex={0} onClick={() => setViewing(a)}
                  onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setViewing(a); } }}>
                  <td className="nowrap">{fmtWhen(a.activityTime)}</td>
                  {multi && <td><span className={a.ownerId === user.id ? 'member-chip me' : 'member-chip'}>{memberName(a.ownerId)}</span></td>}
                  <td className="act-title">
                    <span><span aria-hidden="true">{t.icon}</span> {a.subject}</span>
                    <span className="muted act-preview">{t.label}{(a.notes || a.outcome) ? ` · ${a.notes || a.outcome}` : ''}</span>
                  </td>
                  <td>{customerName(a.customerId)}</td>
                  <td className="num">
                    {a.productId ? (
                      <div className="act-sale">
                        <strong>{a.saleAmount != null ? money(a.saleAmount) : `${fmtNum(a.unitsSold)} units`}</strong>
                        <PayBadge a={a} />
                      </div>
                    ) : <span className="muted">—</span>}
                  </td>
                </tr>
              );
            })}
            {loaded && filtered.length === 0 && (
              <tr><td colSpan={cols} className="muted">
                {items.length === 0 ? 'Nothing logged yet. Use "+ Log activity" to record a meeting, call or sale.' : 'No entries match the filters.'}
              </td></tr>
            )}
            {!loaded && !error && <tr><td colSpan={cols} className="muted">Loading...</td></tr>}
          </tbody>
        </table>
      </div>
      {filtered.length > shown.length && (
        <div className="more-row">
          <span className="muted">Showing {shown.length} of {fmtNum(filtered.length)}</span>
          <button type="button" onClick={() => setLimit(limit + PAGE)}>Show more</button>
        </div>
      )}

      {viewing && (
        <Modal title="Activity details" onClose={() => setViewing(null)}>
          <ActivityDetail a={viewing} memberName={memberName(viewing.ownerId)}
            customerName={customerName(viewing.customerId)} product={productOf(viewing.productId)} />
          <div className="modal-actions">
            <button type="button" className="danger-outline" onClick={() => setToDelete(viewing)}>Delete</button>
            <span className="spacer" />
            <button type="button" onClick={() => setViewing(null)}>Close</button>
            <button type="button" className="primary" onClick={() => openEdit(viewing)}>Edit</button>
          </div>
        </Modal>
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
            </div>

            <div className={form.isSale ? 'sale-box on' : 'sale-box'}>
              <label className="check-inline sale-toggle">
                <input type="checkbox" checked={form.isSale}
                  onChange={(e) => setForm({ ...form, isSale: e.target.checked })} />
                <span><strong>This includes a sale</strong> <span className="muted">— record product, units and payment</span></span>
              </label>
              {form.isSale && (
                <div className="form-grid">
                  <Field label="Product">
                    <select name="productId" value={form.productId} onChange={onChange} required>
                      <option value="">Select product...</option>
                      {products.filter((p) => p.active || String(p.id) === form.productId).map((p) => (
                        <option key={p.id} value={p.id}>{p.name} ({p.unit})</option>
                      ))}
                    </select>
                  </Field>
                  <Field label={`Units sold${productOf(form.productId) ? ` (${productOf(form.productId).unit})` : ''}`}>
                    <input name="unitsSold" type="number" min="1" step="1" max="1000000" value={form.unitsSold}
                      onChange={onChange} required placeholder="e.g. 10" />
                  </Field>
                  <Field label="Total deal amount (₹)">
                    <input name="saleAmount" type="number" min="0" step="0.01" value={form.saleAmount}
                      onChange={onChange} required placeholder="e.g. 50000" />
                  </Field>
                  <div className="field">
                    <span>Payment</span>
                    <div className="seg pay-seg" role="radiogroup" aria-label="Payment">
                      {[[true, 'Fully paid'], [false, 'Part on credit']].map(([v, label]) => (
                        <button key={label} type="button" role="radio" aria-checked={form.fullyPaid === v}
                          className={form.fullyPaid === v ? 'seg-btn active' : 'seg-btn'}
                          onClick={() => setForm({ ...form, fullyPaid: v })}>{label}</button>
                      ))}
                    </div>
                  </div>
                  {!form.fullyPaid && (
                    <>
                      <Field label="Amount paid now (₹)">
                        <input name="amountPaid" type="number" min="0" step="0.01" max={form.saleAmount || undefined}
                          value={form.amountPaid} onChange={onChange} required placeholder="0 if nothing paid yet" />
                      </Field>
                      <div className="credit-note">
                        <span className="muted">On credit</span>
                        <strong>{money(credit)}</strong>
                      </div>
                    </>
                  )}
                </div>
              )}
            </div>

            <div className="form-grid">
              <Field label="Notes" wide>
                <textarea name="notes" rows={6} maxLength={2000} value={form.notes} onChange={onChange}
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
