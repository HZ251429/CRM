import { useEffect, useState } from 'react';
import { followupsApi } from '../api.js';
import { toLocalInput, useOwners } from '../format.js';
import Modal, { Field } from '../Modal.jsx';
import ActivityDetail, { typeOf, typeLabel, fmtWhen } from '../ActivityDetail.jsx';
import ActivityForm, { REMIND_OPTIONS } from '../ActivityForm.jsx';

const dayStart = (offset) => {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() + offset);
  return d;
};

/** Which group a follow-up time falls in. */
function groupOf(t) {
  const d = new Date(t);
  if (d < new Date()) return 'overdue';
  if (d < dayStart(1)) return 'today';
  if (d < dayStart(2)) return 'tomorrow';
  if (d < dayStart(8)) return 'week';
  return 'later';
}
const GROUPS = [
  ['overdue', '🔴 Overdue'], ['today', '🟠 Today'], ['tomorrow', '🟡 Tomorrow'], ['week', 'Next 7 days'], ['later', 'Later'],
];

/**
 * Follow-ups: the activities a follow-up was set on. Open ones are grouped by when they are due (overdue first);
 * mark one done, move it, or log the next activity (which closes it). A lead can also look at the team's.
 */
export default function FollowUps({ user, onChanged }) {
  const { names, multi } = useOwners();
  const [who, setWho] = useState('me');
  const [open, setOpen] = useState(true);
  const [items, setItems] = useState(null);
  const [error, setError] = useState('');
  const [viewing, setViewing] = useState(null);
  const [moving, setMoving] = useState(null); // { row, followUpAt, remindMinutes, note }
  const [nextFor, setNextFor] = useState(null); // the follow-up the next activity is logged for

  const load = () => followupsApi.list(who, open).then((r) => { setItems(r); setError(''); }).catch((e) => setError(e.message));
  useEffect(() => { setItems(null); load(); }, [who, open]);

  const change = async (row, body) => {
    try {
      await followupsApi.change(row.id, body);
      setViewing(null);
      load();
      onChanged?.();
      return true;
    } catch (e) {
      setError(e.message);
      return false;
    }
  };

  const mine = (row) => row.ownerId === user.id;
  const memberName = (id) => (id === user.id ? `${names[id] || 'Me'} (me)` : names[id] || 'Unknown');
  const about = (a) => (a.customerId ? `👤 ${a.customerName}` : a.taskId ? `🎯 ${a.taskTitle || `Task #${a.taskId}`}` : '');

  const saveMove = async (e) => {
    e.preventDefault();
    if (new Date(moving.followUpAt) < new Date()) {
      setError('Pick a follow-up date and time in the future.');
      return;
    }
    const ok = await change(moving.row, {
      followUpAt: moving.followUpAt, remindMinutes: Number(moving.remindMinutes), note: moving.note,
    });
    if (ok) setMoving(null);
  };

  const actions = (row) => (open ? (
    <>
      <button type="button" className="primary" onClick={() => setNextFor(row)} title="Log what you did; this follow-up is then done">
        + Log next activity
      </button>
      <button type="button" onClick={() => setMoving({
        row, followUpAt: toLocalInput(row.followUpAt), remindMinutes: String(row.followUpRemindMinutes ?? 0), note: row.followUpNote || '',
      })}>Move</button>
      <button type="button" onClick={() => change(row, { done: true })}>✓ Done</button>
    </>
  ) : (
    <button type="button" onClick={() => change(row, { done: false })}>Reopen</button>
  ));

  const card = (row) => {
    const t = typeOf(row.type);
    return (
      <li key={row.id} className="fu-card">
        <button type="button" className="fu-main" onClick={() => setViewing(row)}>
          <span className="fu-when">⏰ {fmtWhen(row.followUpAt)}</span>
          <strong className="fu-what">{row.followUpNote || row.subject}</strong>
          <span className="muted fu-from">
            {about(row)} · after {t.icon} {typeLabel(row)} “{row.subject}” on {fmtWhen(row.activityTime)}
            {multi && who === 'team' ? ` · ${memberName(row.ownerId)}` : ''}
            {!open && row.followUpDoneAt ? ` · done ${fmtWhen(row.followUpDoneAt)}` : ''}
          </span>
        </button>
        {mine(row) && <div className="fu-actions">{actions(row)}</div>}
      </li>
    );
  };

  return (
    <div>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="toolbar filters">
        <div className="seg" role="radiogroup" aria-label="Open or done">
          {[[true, 'To do'], [false, 'Done']].map(([k, label]) => (
            <button key={label} type="button" role="radio" aria-checked={open === k}
              className={open === k ? 'seg-btn active' : 'seg-btn'} onClick={() => setOpen(k)}>{label}</button>
          ))}
        </div>
        {multi && (
          <select className="filter-select" value={who} onChange={(e) => setWho(e.target.value)} title="Whose follow-ups">
            <option value="me">My follow-ups</option>
            <option value="team">My team&apos;s follow-ups</option>
          </select>
        )}
      </div>

      {!items && !error && <p className="muted">Loading...</p>}
      {items && items.length === 0 && (
        <p className="muted empty-note">
          {open ? 'No follow-ups to do. Tick "Follow-up needed" when you log an activity to plan the next step.' : 'No finished follow-ups yet.'}
        </p>
      )}
      {items && items.length > 0 && (open ? GROUPS.map(([g, label]) => {
        const rows = items.filter((r) => groupOf(r.followUpAt) === g);
        return rows.length ? (
          <section key={g} className={`fu-group ${g}`}>
            <h3 className="fu-group-title">{label} <span className="muted">({rows.length})</span></h3>
            <ul className="fu-list">{rows.map(card)}</ul>
          </section>
        ) : null;
      }) : <ul className="fu-list">{items.map(card)}</ul>)}
      <p className="muted hint">You get a reminder in the 🔔 bell (and on WhatsApp, if linked) at the time you chose.</p>

      {viewing && (
        <Modal title="Follow-up" onClose={() => setViewing(null)}>
          <ActivityDetail a={viewing} memberName={memberName(viewing.ownerId)} customerName={viewing.customerName} />
          <div className="modal-actions">
            <button type="button" onClick={() => setViewing(null)}>Close</button>
            {mine(viewing) && actions(viewing)}
          </div>
        </Modal>
      )}

      {moving && (
        <Modal title="Move follow-up" onClose={() => setMoving(null)}>
          <form onSubmit={saveMove}>
            <div className="form-grid">
              <Field label="Follow up on">
                <input type="datetime-local" value={moving.followUpAt} required
                  onChange={(e) => setMoving({ ...moving, followUpAt: e.target.value })} />
              </Field>
              <Field label="Remind me">
                <select value={moving.remindMinutes} onChange={(e) => setMoving({ ...moving, remindMinutes: e.target.value })}>
                  {REMIND_OPTIONS.map(([v, label]) => <option key={v} value={v}>{label}</option>)}
                </select>
              </Field>
              <Field label="What to do" wide>
                <input value={moving.note} maxLength={500} onChange={(e) => setMoving({ ...moving, note: e.target.value })} />
              </Field>
            </div>
            <div className="modal-actions">
              <button type="button" onClick={() => setMoving(null)}>Cancel</button>
              <button type="submit" className="primary">Save</button>
            </div>
          </form>
        </Modal>
      )}

      {nextFor && (
        <ActivityForm entry={null}
          taskId={!nextFor.customerId ? nextFor.taskId : undefined}
          customer={nextFor.customerId ? { id: nextFor.customerId, name: nextFor.customerName } : undefined}
          onClose={() => setNextFor(null)}
          onSaved={() => { const row = nextFor; setNextFor(null); change(row, { done: true }); }} />
      )}
    </div>
  );
}
