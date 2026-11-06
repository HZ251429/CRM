/** Small shared pieces for showing sales tasks. */

export const STATUS = {
  NOT_STARTED: { label: 'Not started', cls: 'badge todo' },
  IN_PROGRESS: { label: 'In progress', cls: 'badge busy' },
  COMPLETED: { label: 'Completed', cls: 'badge on' },
};

const fmt = (n) => Number(n || 0).toLocaleString('en-IN');

/** What a task is called in lists: its title (older tasks without one fall back to the product name). */
export const taskName = (t) => t.title || t.productName;

/** Units still to be sold by the assignee themselves: units split to their team are no longer theirs. */
export const balanceOf = (t) => (t.ownBalance != null ? t.ownBalance : Math.max(0, t.units - t.soldUnits));
/** Units of the whole task still unsold, team included. */
export const teamBalanceOf = (t) => (t.teamBalance != null ? t.teamBalance : Math.max(0, t.units - t.soldUnits));

export function StatusBadge({ status }) {
  return <span className={STATUS[status].cls}>{STATUS[status].label}</span>;
}

/** OPEN = anything not completed */
export const matchesStatus = (t, s) => !s || (s === 'OPEN' ? t.status !== 'COMPLETED' : t.status === s);

/** "All · (Open) · Not started · In progress · Completed" filter chips with counts. */
export function StatusChips({ tasks, value, onChange, withOpen = false }) {
  const count = (s) => tasks.filter((t) => matchesStatus(t, s)).length;
  const options = [['', 'All'], ...(withOpen ? [['OPEN', 'Open']] : []), ...Object.entries(STATUS).map(([k, s]) => [k, s.label])];
  return (
    <div className="chips" role="tablist" aria-label="Filter by status">
      {options.map(([k, label]) => (
        <button key={k || 'all'} type="button" role="tab" aria-selected={value === k}
          className={value === k ? 'chip active' : 'chip'} onClick={() => onChange(k)}>
          {label} <span className="chip-count">{count(k)}</span>
        </button>
      ))}
    </div>
  );
}

/** Thin progress bar with "sold / units" next to it. */
export function Progress({ t }) {
  const pct = Math.min(100, t.units ? Math.round((t.soldUnits / t.units) * 100) : 0);
  return (
    <div className="mini-progress" title={`${pct}% sold`}>
      <div className={t.status === 'COMPLETED' ? 'progress done' : 'progress'} role="progressbar"
        aria-valuenow={pct} aria-valuemin="0" aria-valuemax="100">
        <span style={{ width: `${pct}%` }} />
      </div>
      <span className="nowrap">{fmt(t.soldUnits)} / {fmt(t.units)}</span>
    </div>
  );
}
