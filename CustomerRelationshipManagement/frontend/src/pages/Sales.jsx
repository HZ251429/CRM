import { useEffect, useState } from 'react';
import { activitiesApi, productsApi } from '../api.js';
import { money, useOwners } from '../format.js';
import Modal, { ConfirmDialog } from '../Modal.jsx';
import ActivityDetail, { PayBadge, fmtWhen, fmtNum } from '../ActivityDetail.jsx';
import SaleForm from '../SaleForm.jsx';

const PAGE = 50;

/**
 * Sales: what was sold to which customer, for how much, and what is still on credit. A sale uses up the seller's
 * balance (oldest task first) and counts toward the tasks of everyone above them.
 */
export default function Sales({ user }) {
  const [items, setItems] = useState([]);
  const [result, setResult] = useState(null);
  const [loaded, setLoaded] = useState(false);
  const [hasAny, setHasAny] = useState(true);
  const [products, setProducts] = useState([]);
  const [error, setError] = useState('');
  const [formFor, setFormFor] = useState(null); // { entry } (null = new)
  const [viewing, setViewing] = useState(null);
  const [toDelete, setToDelete] = useState(null);
  const { names, multi, team } = useOwners();
  const [search, setSearch] = useState('');
  const [ownerFilter, setOwnerFilter] = useState('');
  const [creditOnly, setCreditOnly] = useState(false);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [loadingMore, setLoadingMore] = useState(false);

  useEffect(() => { productsApi.list().then(setProducts).catch(() => {}); }, []);
  const anyFilter = search || ownerFilter || creditOnly || from || to;
  const params = (page) => ({ q: search.trim(), ownerId: ownerFilter, from, to, salesOnly: true, creditOnly, page, size: PAGE });
  const load = (page = 0) => activitiesApi.page(params(page)).then((r) => {
    setResult(r);
    setItems((old) => (page === 0 ? r.content : [...old, ...r.content]));
    setLoaded(true);
    if (page === 0 && !anyFilter) setHasAny(r.totalElements > 0);
  }).catch((e) => setError(e.message));
  useEffect(() => { const t = setTimeout(() => load(0), search ? 300 : 0); return () => clearTimeout(t); }, [search, ownerFilter, creditOnly, from, to]);
  const loadMore = () => { setLoadingMore(true); load(result.page + 1).finally(() => setLoadingMore(false)); };

  const productOf = (id) => products.find((p) => p.id === id);
  const memberName = (id) => (id === user.id ? `${names[id] || 'Me'} (me)` : names[id] || 'Unknown');
  const remove = async () => {
    const a = toDelete;
    setToDelete(null);
    setViewing(null);
    try { await activitiesApi.remove(a.id); load(0); } catch (err) { setError(err.message); }
  };

  const total = result?.totalElements || 0;
  const cols = multi ? 6 : 5;

  return (
    <div>
      {error && <p className="error">{error}</p>}
      <div className="toolbar">
        <input className="search" placeholder="Search customer, product, member, notes..." value={search}
          onChange={(e) => setSearch(e.target.value)} />
        <button type="button" className="primary" onClick={() => setFormFor({ entry: null })}>+ Record sale</button>
      </div>
      <div className="toolbar filters">
        {multi && (
          <select className="filter-select" value={ownerFilter} onChange={(e) => setOwnerFilter(e.target.value)} title="Filter by member">
            <option value="">All members</option>
            {team.map((t) => <option key={t.userId} value={t.userId}>{t.userId === user.id ? `${t.fullName} (me)` : t.fullName}</option>)}
          </select>
        )}
        <label className="filter-time">From <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
        <label className="filter-time">To <input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></label>
        <label className="check-inline"><input type="checkbox" checked={creditOnly} onChange={(e) => setCreditOnly(e.target.checked)} /> On credit only</label>
        {anyFilter && (
          <button type="button" onClick={() => { setSearch(''); setOwnerFilter(''); setCreditOnly(false); setFrom(''); setTo(''); }}>Clear filters</button>
        )}
      </div>

      {total > 0 && (
        <div className="sales-summary">
          <div><span className="muted">Sales</span><strong>{fmtNum(total)}</strong></div>
          <div><span className="muted">Total value</span><strong>{money(result.saleTotal)}</strong></div>
          <div><span className="muted">On credit</span><strong className={Number(result.creditTotal) > 0 ? 'credit-text' : ''}>{money(result.creditTotal)}</strong></div>
        </div>
      )}

      <div className="table-scroll">
        <table className="act-table">
          <thead>
            <tr><th>When</th>{multi && <th>Member</th>}<th>Customer</th><th>Product</th><th className="num">Amount</th><th className="num">Payment</th></tr>
          </thead>
          <tbody>
            {items.map((a) => {
              const p = productOf(a.productId);
              return (
                <tr key={a.id} className="clickable" tabIndex={0} onClick={() => setViewing(a)}
                  onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setViewing(a); } }}>
                  <td className="nowrap">{fmtWhen(a.activityTime)}</td>
                  {multi && <td><span className={a.ownerId === user.id ? 'member-chip me' : 'member-chip'}>{memberName(a.ownerId)}</span></td>}
                  <td>{a.customerName}</td>
                  <td className="two-line"><strong>{p?.name || 'Product'}</strong><span className="muted">{fmtNum(a.unitsSold)} {p?.unit || 'units'}</span></td>
                  <td className="num"><strong>{a.saleAmount != null ? money(a.saleAmount) : '—'}</strong></td>
                  <td className="num"><PayBadge a={a} /></td>
                </tr>
              );
            })}
            {loaded && total === 0 && (
              <tr><td colSpan={cols} className="muted">
                {!hasAny && !anyFilter ? 'No sales yet. Use "+ Record sale" when you sell to a customer.' : 'No sales match the filters.'}
              </td></tr>
            )}
            {!loaded && !error && <tr><td colSpan={cols} className="muted">Loading...</td></tr>}
          </tbody>
        </table>
      </div>
      {total > items.length && (
        <div className="more-row">
          <span className="muted">Showing {fmtNum(items.length)} of {fmtNum(total)}</span>
          <button type="button" onClick={loadMore} disabled={loadingMore}>{loadingMore ? 'Loading...' : 'Show more'}</button>
        </div>
      )}
      <p className="muted hint">A sale uses up your balance for the product, oldest task first, and counts toward your lead's tasks too.</p>

      {viewing && (
        <Modal title="Sale details" onClose={() => setViewing(null)}>
          <ActivityDetail a={viewing} memberName={memberName(viewing.ownerId)} customerName={viewing.customerName}
            product={productOf(viewing.productId)} />
          <div className="modal-actions">
            <button type="button" className="danger-outline" onClick={() => setToDelete(viewing)}>Delete</button>
            <span className="spacer" />
            <button type="button" onClick={() => setViewing(null)}>Close</button>
            <button type="button" className="primary" onClick={() => { setFormFor({ entry: viewing }); setViewing(null); }}>Edit</button>
          </div>
        </Modal>
      )}
      {toDelete && (
        <ConfirmDialog message={`Delete this sale to ${toDelete.customerName}? Its units go back to the seller's balance. This cannot be undone.`}
          onConfirm={remove} onCancel={() => setToDelete(null)} />
      )}
      {formFor && (
        <SaleForm entry={formFor.entry} user={user} onClose={() => setFormFor(null)}
          onSaved={() => { setFormFor(null); load(0); }} />
      )}
    </div>
  );
}
