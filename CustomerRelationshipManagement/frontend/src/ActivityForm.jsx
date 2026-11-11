import { useEffect, useState } from 'react';
import { activitiesApi, tasksApi } from './api.js';
import { nowLocalInput, toLocalInput } from './format.js';
import Modal, { Field } from './Modal.jsx';
import CustomerPicker from './CustomerPicker.jsx';
import { ACTIVITY_TYPES, MEETING_MODES, TYPES } from './ActivityDetail.jsx';
import { taskName } from './TaskBits.jsx';

/**
 * Log or edit an activity: a meeting (in person or online), a call or something else, against a customer or a
 * task. Sales are recorded separately (SaleForm).
 *
 * @param entry   the activity being edited (a row from the log), or null for a new one
 * @param taskId   start a new activity against this task (e.g. opened from the Tasks page)
 * @param customer start a new activity with this customer chosen: { id, name } (e.g. from an enquiry)
 */
/** default follow-up time: tomorrow at 10:00, as a datetime-local value */
function tomorrowAt10() {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  d.setHours(10, 0, 0, 0);
  return toLocalInput(new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString());
}

export const REMIND_OPTIONS = [['0', 'At the time'], ['15', '15 minutes before'], ['60', '1 hour before'], ['1440', '1 day before']];

export default function ActivityForm({ entry, taskId, customer, onSaved, onClose }) {
  const editing = !!entry;
  const [form, setForm] = useState(() => (entry ? {
    type: entry.type, meetingMode: entry.meetingMode || 'IN_PERSON',
    against: entry.taskId && !entry.customerId ? 'task' : 'customer',
    customerId: entry.customerId ? String(entry.customerId) : '', customerName: entry.customerName || '',
    taskId: entry.taskId ? String(entry.taskId) : '',
    subject: entry.subject || '', attendees: entry.attendees || '', notes: entry.notes || '', outcome: entry.outcome || '',
    activityTime: toLocalInput(entry.activityTime),
    followUp: !!entry.followUpAt, followUpAt: entry.followUpAt ? toLocalInput(entry.followUpAt) : tomorrowAt10(),
    followUpNote: entry.followUpNote || '', followUpRemindMinutes: String(entry.followUpRemindMinutes ?? 15),
  } : {
    type: 'MEETING', meetingMode: 'IN_PERSON', against: taskId ? 'task' : 'customer',
    customerId: customer ? String(customer.id) : '', customerName: customer ? customer.name : '', taskId: taskId ? String(taskId) : '',
    subject: '', attendees: '', notes: '', outcome: '', activityTime: nowLocalInput(),
    followUp: false, followUpAt: tomorrowAt10(), followUpNote: '', followUpRemindMinutes: '15',
  }));
  const [tasks, setTasks] = useState(null);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => { tasksApi.list().then(setTasks).catch(() => setTasks([])); }, []);

  const set = (k, v) => setForm((f) => ({ ...f, [k]: v }));
  const onChange = (e) => set(e.target.name, e.target.value);
  // the task list: open tasks first, newest first; always include the chosen one
  const taskOptions = (tasks || [])
    .filter((t) => t.status !== 'COMPLETED' || String(t.id) === form.taskId)
    .sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)));
  const types = TYPES[form.type]?.legacy ? [...ACTIVITY_TYPES, form.type] : ACTIVITY_TYPES;

  const submit = async (e) => {
    e.preventDefault();
    setError('');
    const body = {
      type: form.type,
      meetingMode: form.type === 'MEETING' ? form.meetingMode : null,
      customerId: form.against === 'customer' && form.customerId ? Number(form.customerId) : null,
      taskId: form.against === 'task' && form.taskId ? Number(form.taskId) : null,
      subject: form.subject, attendees: form.attendees, notes: form.notes, outcome: form.outcome,
      activityTime: form.activityTime || null,
      followUpAt: form.followUp ? form.followUpAt : null,
      followUpNote: form.followUp ? form.followUpNote : null,
      followUpRemindMinutes: form.followUp ? Number(form.followUpRemindMinutes) : null,
    };
    if (!body.customerId && !body.taskId) {
      setError(form.against === 'customer' ? 'Choose the customer.' : 'Choose the task.');
      return;
    }
    const unchangedFollowUp = editing && entry.followUpAt && toLocalInput(entry.followUpAt) === form.followUpAt;
    if (form.followUp && !unchangedFollowUp && new Date(form.followUpAt) < new Date()) {
      setError('Pick a follow-up date and time in the future.');
      return;
    }
    setSaving(true);
    try {
      const saved = editing ? await activitiesApi.update(entry.id, body) : await activitiesApi.create(body);
      onSaved(saved);
    } catch (err) {
      setError(err.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal title={editing ? 'Edit activity' : 'Log activity'} onClose={onClose}>
      <form onSubmit={submit} autoComplete="off">
        {error && <p className="error" role="alert">{error}</p>}
        <div className="type-picker" role="radiogroup" aria-label="Type">
          {types.map((k) => (
            <button key={k} type="button" role="radio" aria-checked={form.type === k}
              className={form.type === k ? 'type-option active' : 'type-option'} onClick={() => set('type', k)}>
              <span aria-hidden="true">{TYPES[k].icon}</span> {TYPES[k].label}
            </button>
          ))}
        </div>
        {form.type === 'MEETING' && (
          <div className="seg mode-seg" role="radiogroup" aria-label="Meeting">
            {Object.entries(MEETING_MODES).map(([k, m]) => (
              <button key={k} type="button" role="radio" aria-checked={form.meetingMode === k}
                className={form.meetingMode === k ? 'seg-btn active' : 'seg-btn'} onClick={() => set('meetingMode', k)}>
                <span aria-hidden="true">{m.icon}</span> {m.label}
              </button>
            ))}
          </div>
        )}

        <div className="field against">
          <span>About</span>
          <div className="seg" role="radiogroup" aria-label="About">
            {[['customer', '👤 A customer'], ['task', '🎯 A task']].map(([k, label]) => (
              <button key={k} type="button" role="radio" aria-checked={form.against === k}
                className={form.against === k ? 'seg-btn active' : 'seg-btn'} onClick={() => set('against', k)}>{label}</button>
            ))}
          </div>
        </div>

        <div className="form-grid">
          {form.against === 'customer' ? (
            <Field label="Customer">
              <CustomerPicker key={`c-${entry?.id || 'new'}`} value={form.customerId} initial={form.customerName}
                onChange={(id, name) => setForm((f) => ({ ...f, customerId: id, customerName: name }))} />
            </Field>
          ) : (
            <Field label="Task">
              <select name="taskId" value={form.taskId} onChange={onChange} required>
                <option value="">{tasks ? (taskOptions.length ? 'Select task...' : 'No open tasks') : 'Loading tasks...'}</option>
                {taskOptions.map((t) => (
                  <option key={t.id} value={t.id}>{taskName(t)} · {t.productName} · {t.assignedToName}</option>
                ))}
              </select>
            </Field>
          )}
          <Field label="Date and time">
            <input name="activityTime" type="datetime-local" value={form.activityTime || ''} onChange={onChange} required />
          </Field>
          <Field label="Title" wide>
            <input name="subject" value={form.subject} onChange={onChange} required maxLength={200}
              placeholder={form.type === 'CALL' ? 'e.g. Follow-up call about the quote' : 'e.g. Quarterly review with finance team'} />
          </Field>
          {form.type !== 'OTHER' && (
            <Field label={form.type === 'CALL' ? 'Spoke with' : 'Attendees'} wide>
              <input name="attendees" value={form.attendees} onChange={onChange} maxLength={500}
                placeholder="e.g. Ravi (CFO), Priya, me" />
            </Field>
          )}
          <Field label="Notes" wide>
            <textarea name="notes" rows={5} maxLength={2000} value={form.notes} onChange={onChange}
              placeholder="What was discussed..." />
            <small className="muted count">{form.notes.length} / 2000</small>
          </Field>
          <Field label="Outcome / next steps" wide>
            <textarea name="outcome" rows={3} maxLength={2000} value={form.outcome} onChange={onChange}
              className="short" placeholder="Decisions made, follow-ups, who does what by when..." />
          </Field>
        </div>
        <div className={form.followUp ? 'followup-box on' : 'followup-box'}>
          <label className="check">
            <input type="checkbox" checked={form.followUp} onChange={(e) => set('followUp', e.target.checked)} />
            <span>⏰ Follow-up needed</span>
          </label>
          {form.followUp && (
            <div className="form-grid">
              <Field label="Follow up on">
                <input name="followUpAt" type="datetime-local" value={form.followUpAt} onChange={onChange} required />
              </Field>
              <Field label="Remind me">
                <select name="followUpRemindMinutes" value={form.followUpRemindMinutes} onChange={onChange}>
                  {REMIND_OPTIONS.map(([v, label]) => <option key={v} value={v}>{label}</option>)}
                </select>
              </Field>
              <Field label="What to do" wide>
                <input name="followUpNote" value={form.followUpNote} onChange={onChange} maxLength={500}
                  placeholder="e.g. Call back with the revised quote" />
              </Field>
            </div>
          )}
        </div>
        <div className="modal-actions">
          <button type="button" onClick={onClose}>Cancel</button>
          <button type="submit" className="primary" disabled={saving}>{editing ? 'Save changes' : 'Save activity'}</button>
        </div>
      </form>
    </Modal>
  );
}
