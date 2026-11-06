import { money } from './format.js';

/** Kinds of log entries. TASK only exists on older entries and is not offered for new ones. */
export const TYPES = {
  MEETING: { label: 'Meeting', icon: '🤝' },
  CALL: { label: 'Call', icon: '📞' },
  VISIT: { label: 'Visit', icon: '📍' },
  EMAIL: { label: 'Email', icon: '✉️' },
  NOTE: { label: 'Note', icon: '📝' },
  TASK: { label: 'Task', icon: '✅', legacy: true },
};
export const typeOf = (t) => TYPES[t] || { label: t, icon: '•' };

export const fmtNum = (n) => Number(n || 0).toLocaleString('en-IN');

export const fmtWhen = (t, withYear = false) => {
  const d = new Date(t);
  if (Number.isNaN(d.getTime())) return '';
  return d.toLocaleString('en-IN', {
    day: '2-digit', month: 'short', ...(withYear ? { year: 'numeric' } : {}), hour: '2-digit', minute: '2-digit', hour12: true,
  });
};

/** Payment badge for a sale: fully paid or how much is on credit. */
export function PayBadge({ a }) {
  if (!a.productId) return <span className="muted">—</span>;
  if (a.saleAmount == null) return <span className="muted">no amount</span>;
  const owed = Number(a.creditAmount || 0);
  return owed > 0
    ? <span className="pay-badge credit" title={`Paid ${money(a.amountPaid)}`}>⏳ {money(owed)} credit</span>
    : <span className="pay-badge paid">✔ Paid</span>;
}

/** Full details of one activity, for a popup. */
export default function ActivityDetail({ a, memberName, customerName, product }) {
  const t = typeOf(a.type);
  const owed = Number(a.creditAmount || 0);
  return (
    <div className="detail">
      <div className="detail-head">
        <span className="log-icon" aria-hidden="true">{t.icon}</span>
        <div>
          <span className="log-type">{t.label}{a.productId ? ' · Sale' : ''}</span>
          <h3 className="detail-title">{a.subject}</h3>
          <p className="muted detail-meta">{fmtWhen(a.activityTime, true)}</p>
        </div>
      </div>

      <dl className="detail-grid">
        <dt>Member</dt><dd><span className="member-chip">{memberName}</span></dd>
        <dt>Customer</dt><dd>{customerName}</dd>
        {a.attendees && (<><dt>Attendees</dt><dd>{a.attendees}</dd></>)}
      </dl>

      {a.productId && (
        <div className="detail-sale">
          <div><span className="muted">Product</span><strong>{product?.name || 'Unknown product'}</strong></div>
          <div><span className="muted">Units sold</span><strong>{fmtNum(a.unitsSold)} {product?.unit || 'units'}</strong></div>
          <div><span className="muted">Total amount</span><strong>{a.saleAmount != null ? money(a.saleAmount) : '—'}</strong></div>
          <div><span className="muted">Paid</span><strong>{a.amountPaid != null ? money(a.amountPaid) : '—'}</strong></div>
          <div><span className="muted">On credit</span>
            <strong className={owed > 0 ? 'credit-text' : ''}>{a.saleAmount != null ? money(owed) : '—'}</strong></div>
        </div>
      )}

      {a.notes && (<><h4 className="detail-label">Notes</h4><p className="log-text">{a.notes}</p></>)}
      {a.outcome && (
        <div className="log-outcome">
          <strong>Outcome / next steps</strong>
          <p className="log-text">{a.outcome}</p>
        </div>
      )}
      {!a.notes && !a.outcome && !a.productId && <p className="muted">No notes recorded.</p>}
    </div>
  );
}
