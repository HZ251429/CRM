import { useEffect, useState } from 'react';
import { activitiesApi, productsApi, tasksApi } from './api.js';
import { money, nowLocalInput, toLocalInput } from './format.js';
import Modal, { Field, AlertDialog } from './Modal.jsx';
import CustomerPicker from './CustomerPicker.jsx';
import { balanceOf } from './TaskBits.jsx';

/**
 * Record or edit a sale: a customer, a product, units and the payment. A sale is not against a task: a member may
 * sell up to the balance left on their tasks for that product (used up oldest task first); the admin is not limited.
 *
 * @param entry     the sale being edited (a row from the log), or null for a new one
 * @param productId start a new sale with this product chosen (e.g. opened from a task); the sale is still not
 *                  tied to that task: it uses the seller's balance for the product, oldest task first
 */
export default function SaleForm({ entry, productId, customer, user, onSaved, onClose }) {
  const editing = !!entry;
  const limited = user.role === 'MEMBER';
  const [form, setForm] = useState(() => {
    const fullyPaid = !entry || entry.saleAmount == null || entry.amountPaid == null || Number(entry.amountPaid) >= Number(entry.saleAmount);
    return entry ? {
      customerId: String(entry.customerId || ''), customerName: entry.customerName || '',
      productId: entry.productId ? String(entry.productId) : '', unitsSold: entry.unitsSold ? String(entry.unitsSold) : '',
      saleAmount: entry.saleAmount != null ? String(entry.saleAmount) : '', fullyPaid,
      amountPaid: !fullyPaid && entry.amountPaid != null ? String(entry.amountPaid) : '',
      activityTime: toLocalInput(entry.activityTime), notes: entry.notes || '',
    } : {
      customerId: customer ? String(customer.id) : '', customerName: customer ? customer.name : '', productId: productId ? String(productId) : '', unitsSold: '', saleAmount: '', fullyPaid: true, amountPaid: '',
      activityTime: nowLocalInput(), notes: '',
    };
  });
  const [products, setProducts] = useState([]);
  const [myTasks, setMyTasks] = useState([]);
  const [error, setError] = useState('');
  const [alert, setAlert] = useState(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    productsApi.list().then(setProducts).catch((e) => setError(e.message));
    if (limited) tasksApi.list().then((t) => setMyTasks(t.filter((x) => x.assignedToId === user.id))).catch(() => {});
  }, []);

  const set = (k, v) => setForm((f) => ({ ...f, [k]: v }));
  const productOf = (id) => products.find((p) => p.id === Number(id));

  // a member may sell what is left in their own balance; when editing, this sale's own units count as available
  const balanceFor = (productId) => {
    const pid = Number(productId);
    const tasks = myTasks.filter((t) => t.productId === pid);
    let left = tasks.reduce((n, t) => n + balanceOf(t), 0);
    if (entry && entry.productId === pid) left += Number(entry.unitsSold || 0);
    return { left, hasTask: tasks.length > 0 };
  };
  const limit = limited && form.productId ? balanceFor(form.productId) : null;
  const overLimit = limit && form.unitsSold !== '' && Number(form.unitsSold) > limit.left;
  const limitMessage = (productId, lim) => {
    const p = productOf(productId);
    return lim.hasTask
      ? `You can sell at most ${lim.left} ${p?.unit || 'units'} of ${p?.name || 'this product'}. That is all that is left in your balance.`
      : `You have no units of ${p?.name || 'this product'} to sell. Ask your lead to assign you a task for it.`;
  };
  const onUnits = (e) => {
    const v = e.target.value;
    if (limited && form.productId && v !== '') {
      const lim = balanceFor(form.productId);
      if (Number(v) > lim.left) {
        set('unitsSold', lim.left > 0 ? String(lim.left) : '');
        setAlert({ title: 'Not enough balance', message: limitMessage(form.productId, lim) });
        return;
      }
    }
    set('unitsSold', v);
  };
  const onProduct = (e) => {
    const pid = e.target.value;
    const next = { ...form, productId: pid };
    if (limited && pid && form.unitsSold !== '') {
      const lim = balanceFor(pid);
      if (Number(form.unitsSold) > lim.left) {
        next.unitsSold = lim.left > 0 ? String(lim.left) : '';
        setAlert({ title: 'Not enough balance', message: limitMessage(pid, lim) });
      }
    }
    setForm(next);
  };
  const credit = !form.fullyPaid && form.saleAmount !== '' ? Math.max(0, Number(form.saleAmount) - Number(form.amountPaid || 0)) : 0;
  // members only see products they can still sell (plus the one already on the sale when editing)
  const choices = products.filter((p) => (p.active || String(p.id) === form.productId)
    && (!limited || balanceFor(p.id).left > 0 || String(p.id) === form.productId));

  const submit = async (e) => {
    e.preventDefault();
    setError('');
    const fail = (message, title = 'Could not save') => { setError(message); setAlert({ title, message }); };
    if (!form.customerId) { fail('Choose the customer.'); return; }
    if (limit && limit.left <= 0) { fail(limitMessage(form.productId, limit), 'Not enough balance'); return; }
    if (overLimit) { fail(limitMessage(form.productId, limit), 'Not enough balance'); return; }
    if (!form.fullyPaid && Number(form.amountPaid || 0) > Number(form.saleAmount)) { fail('Amount paid cannot be more than the total amount'); return; }
    const body = {
      type: entry ? entry.type : 'SALE',
      customerId: Number(form.customerId), productId: Number(form.productId), unitsSold: Number(form.unitsSold),
      saleAmount: Number(form.saleAmount), amountPaid: Number(form.fullyPaid ? form.saleAmount : (form.amountPaid || 0)),
      activityTime: form.activityTime || null, notes: form.notes,
      subject: entry && entry.type !== 'SALE' ? entry.subject : null, // the server names new sales ("Sale of 10 pcs ...")
    };
    setSaving(true);
    try {
      const saved = editing ? await activitiesApi.update(entry.id, body) : await activitiesApi.create(body);
      onSaved(saved);
    } catch (err) {
      fail(err.message, /balance|to sell/i.test(err.message) ? 'Not enough balance' : 'Could not save');
    } finally {
      setSaving(false);
    }
  };

  const p = productOf(form.productId);
  return (
    <Modal title={editing ? 'Edit sale' : 'Record sale'} onClose={onClose}>
      <form onSubmit={submit} autoComplete="off">
        {error && <p className="error" role="alert">{error}</p>}
        <div className="form-grid">
          <Field label="Customer">
            <CustomerPicker key={`s-${entry?.id || 'new'}`} value={form.customerId} initial={form.customerName} autoFocus
              onChange={(id, name) => setForm((f) => ({ ...f, customerId: id, customerName: name }))} />
          </Field>
          <Field label="Date and time">
            <input name="activityTime" type="datetime-local" value={form.activityTime || ''} required
              onChange={(e) => set('activityTime', e.target.value)} />
          </Field>
          <Field label="Product">
            <select value={form.productId} onChange={onProduct} required>
              <option value="">{limited && products.length && !choices.length ? 'Nothing left to sell' : 'Select product...'}</option>
              {choices.map((x) => (
                <option key={x.id} value={x.id}>{x.name} ({x.unit}){limited ? ` · ${balanceFor(x.id).left} left` : ''}</option>
              ))}
            </select>
          </Field>
          <Field label={`Units sold${p ? ` (${p.unit})` : ''}`}>
            <input type="number" min="1" step="1" value={form.unitsSold} onChange={onUnits} required
              max={limit ? Math.max(limit.left, 1) : 1000000} disabled={!!limit && limit.left <= 0}
              placeholder={limit && limit.left <= 0 ? 'Nothing left to sell' : 'e.g. 10'}
              className={overLimit ? 'invalid' : ''} aria-invalid={overLimit || undefined} />
            {limit && (
              <small className={overLimit || limit.left === 0 ? 'balance-note over' : 'balance-note'}>
                {!limit.hasTask ? 'No task for this product, so nothing to sell yet'
                  : `Your balance: ${limit.left} ${p?.unit || 'units'}`}
              </small>
            )}
          </Field>
          <Field label="Total amount (₹)">
            <input type="number" min="0" step="0.01" value={form.saleAmount} required placeholder="e.g. 50000"
              onChange={(e) => set('saleAmount', e.target.value)} />
          </Field>
          <div className="field">
            <span>Payment</span>
            <div className="seg pay-seg" role="radiogroup" aria-label="Payment">
              {[[true, 'Fully paid'], [false, 'Part on credit']].map(([v, label]) => (
                <button key={label} type="button" role="radio" aria-checked={form.fullyPaid === v}
                  className={form.fullyPaid === v ? 'seg-btn active' : 'seg-btn'} onClick={() => set('fullyPaid', v)}>{label}</button>
              ))}
            </div>
          </div>
          {!form.fullyPaid && (
            <>
              <Field label="Amount paid now (₹)">
                <input type="number" min="0" step="0.01" max={form.saleAmount || undefined} value={form.amountPaid} required
                  placeholder="0 if nothing paid yet" onChange={(e) => set('amountPaid', e.target.value)} />
              </Field>
              <div className="credit-note"><span className="muted">On credit</span><strong>{money(credit)}</strong></div>
            </>
          )}
          <Field label="Notes (optional)" wide>
            <textarea rows={3} maxLength={2000} value={form.notes} onChange={(e) => set('notes', e.target.value)}
              className="short" placeholder="Invoice number, delivery date, ..." />
          </Field>
        </div>
        <div className="modal-actions">
          <button type="button" onClick={onClose}>Cancel</button>
          <button type="submit" className="primary" disabled={saving}>{editing ? 'Save changes' : 'Save sale'}</button>
        </div>
      </form>
      {alert && <AlertDialog title={alert.title} message={alert.message} onClose={() => setAlert(null)} />}
    </Modal>
  );
}
