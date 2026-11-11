import { useEffect, useState } from 'react';
import { activitiesApi, followupsApi } from '../api.js';
import { useOwners } from '../format.js';
import Modal, { ConfirmDialog } from '../Modal.jsx';
import ActivityDetail, { ACTIVITY_TYPES, TYPES, typeLabel, typeOf, fmtWhen, fmtNum } from '../ActivityDetail.jsx';
import ActivityForm from '../ActivityForm.jsx';
import Sales from './Sales.jsx';
import FollowUps from './FollowUps.jsx';

const PAGE = 50;

/**
 * Activities section with two views: the activity log (meetings, calls, other) and Sales.
 * view = 'sales' opens the Sales view (e.g. from a sale notification).
 */
export default function Activities({ user, view: startView }) {
  const [view, setView] = useState(['sales', 'followups'].includes(startView) ? startView : 'log');
  const [due, setDue] = useState(null); // { overdue, today }
  const refreshDue = () => followupsApi.summary().then(setDue).catch(() => {});
  useEffect(() => { refreshDue(); }, [view]);
  const dueCount = due ? due.overdue + due.today : 0;
  return (
    <div>
      <div className="toolbar">
        <div className="seg" role="tablist" aria-label="Activities, follow-ups or sales">
          {[['log', '📝 Activities'], ['followups', '⏰ Follow-ups'], ['sales', '💰 Sales']].map(([k, label]) => (
            <button key={k} type="button" role="tab" aria-selected={view === k}
              className={view === k ? 'seg-btn active' : 'seg-btn'} onClick={() => setView(k)}>
              {label}
              {k === 'followups' && dueCount > 0 && (
                <span className={due.overdue ? 'count-pill late' : 'count-pill'}
                  title={`${due.overdue} overdue, ${due.today} due today`}>{dueCount}</span>
              )}
            </button>
          ))}
        </div>
      </div>
      {view === 'sales' ? <Sales user={user} />
        : view === 'followups' ? <FollowUps user={user} onChanged={refreshDue} />
          : <ActivityLog user={user} />}
    </div>
  );
}

/**
 * Activity log: meetings (in person or online), calls and other activities, each about a customer or a task.
 * Searched and paged on the server; click a row for the full details.
 */
function ActivityLog({ user }) {
  const [items, setItems] = useState([]);
  const [result, setResult] = useState(null);
  const [loaded, setLoaded] = useState(false);
  const [hasAny, setHasAny] = useState(true);
  const [error, setError] = useState('');
  const [formFor, setFormFor] = useState(null); // { entry } (entry null = new)
  const [viewing, setViewing] = useState(null);
  const [toDelete, setToDelete] = useState(null);
  const { names, multi, team } = useOwners();
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState('');
  const [ownerFilter, setOwnerFilter] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [loadingMore, setLoadingMore] = useState(false);

  const anyFilter = search || typeFilter || ownerFilter || from || to;
  const params = (page) => ({ q: search.trim(), type: typeFilter, ownerId: ownerFilter, from, to, activitiesOnly: true, page, size: PAGE });
  const load = (page = 0) => activitiesApi.page(params(page)).then((r) => {
    setResult(r);
    setItems((old) => (page === 0 ? r.content : [...old, ...r.content]));
    setLoaded(true);
    if (page === 0 && !anyFilter) setHasAny(r.totalElements > 0);
  }).catch((e) => setError(e.message));
  useEffect(() => { const t = setTimeout(() => load(0), search ? 300 : 0); return () => clearTimeout(t); }, [search, typeFilter, ownerFilter, from, to]);
  const loadMore = () => { setLoadingMore(true); load(result.page + 1).finally(() => setLoadingMore(false)); };

  const memberName = (id) => (id === user.id ? `${names[id] || 'Me'} (me)` : names[id] || 'Unknown');
  const about = (a) => (a.customerId ? a.customerName : a.taskId ? `🎯 ${a.taskTitle || `Task #${a.taskId}`}` : '—');

  const remove = async () => {
    const a = toDelete;
    setToDelete(null);
    setViewing(null);
    try { await activitiesApi.remove(a.id); load(0); } catch (err) { setError(err.message); }
  };

  const total = result?.totalElements || 0;
  const cols = multi ? 4 : 3;

  return (
    <div>
      {error && <p className="error">{error}</p>}

      <div className="toolbar">
        <input className="search" placeholder="Search title, notes, customer, member..." value={search}
          onChange={(e) => setSearch(e.target.value)} />
        <button type="button" className="primary" onClick={() => setFormFor({ entry: null })}>+ Log activity</button>
      </div>
      <div className="toolbar filters">
        <select className="filter-select" value={typeFilter} onChange={(e) => setTypeFilter(e.target.value)} title="Filter by type">
          <option value="">All types</option>
          {[...ACTIVITY_TYPES, 'VISIT', 'EMAIL', 'NOTE'].map((t) => (
            <option key={t} value={t}>{TYPES[t].icon} {TYPES[t].label}{TYPES[t].legacy ? ' (older)' : ''}</option>
          ))}
        </select>
        {multi && (
          <select className="filter-select" value={ownerFilter} onChange={(e) => setOwnerFilter(e.target.value)} title="Filter by member">
            <option value="">All members</option>
            {team.map((t) => <option key={t.userId} value={t.userId}>{t.userId === user.id ? `${t.fullName} (me)` : t.fullName}</option>)}
          </select>
        )}
        <label className="filter-time">From <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
        <label className="filter-time">To <input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></label>
        {anyFilter && (
          <button type="button" onClick={() => { setSearch(''); setTypeFilter(''); setOwnerFilter(''); setFrom(''); setTo(''); }}>Clear filters</button>
        )}
      </div>

      {total > 0 && <p className="muted log-summary">{fmtNum(total)} {total === 1 ? 'activity' : 'activities'}</p>}

      <div className="table-scroll">
        <table className="act-table">
          <thead>
            <tr><th>When</th>{multi && <th>Member</th>}<th>Activity</th><th>About</th></tr>
          </thead>
          <tbody>
            {items.map((a) => {
              const t = typeOf(a.type);
              return (
                <tr key={a.id} className="clickable" tabIndex={0} onClick={() => setViewing(a)}
                  onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setViewing(a); } }}>
                  <td className="nowrap">{fmtWhen(a.activityTime)}</td>
                  {multi && <td><span className={a.ownerId === user.id ? 'member-chip me' : 'member-chip'}>{memberName(a.ownerId)}</span></td>}
                  <td className="act-title">
                    <span><span aria-hidden="true">{t.icon}</span> {a.subject}
                      {a.followUpAt && !a.followUpDoneAt && (
                        <span className="followup-tag" title="Follow-up">⏰ {fmtWhen(a.followUpAt)}</span>
                      )}
                    </span>
                    <span className="muted act-preview">{typeLabel(a)}{(a.notes || a.outcome) ? ` · ${a.notes || a.outcome}` : ''}</span>
                  </td>
                  <td>{about(a)}</td>
                </tr>
              );
            })}
            {loaded && total === 0 && (
              <tr><td colSpan={cols} className="muted">
                {!hasAny && !anyFilter ? 'Nothing logged yet. Use "+ Log activity" to record a meeting or call.' : 'No activities match the filters.'}
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
      <p className="muted hint">Activities can be about a customer or about a task. Sales are under the Sales tab above.</p>

      {viewing && (
        <Modal title="Activity details" onClose={() => setViewing(null)}>
          <ActivityDetail a={viewing} memberName={memberName(viewing.ownerId)} customerName={viewing.customerName} />
          <div className="modal-actions">
            <button type="button" className="danger-outline" onClick={() => setToDelete(viewing)}>Delete</button>
            <span className="spacer" />
            <button type="button" onClick={() => setViewing(null)}>Close</button>
            <button type="button" className="primary" onClick={() => { setFormFor({ entry: viewing }); setViewing(null); }}>Edit</button>
          </div>
        </Modal>
      )}
      {toDelete && (
        <ConfirmDialog message={`Delete the activity "${toDelete.subject}"? This cannot be undone.`}
          onConfirm={remove} onCancel={() => setToDelete(null)} />
      )}
      {formFor && (
        <ActivityForm entry={formFor.entry} onClose={() => setFormFor(null)}
          onSaved={() => { setFormFor(null); load(0); }} />
      )}
    </div>
  );
}
