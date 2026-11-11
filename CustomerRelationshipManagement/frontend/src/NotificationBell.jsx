import { useEffect, useRef, useState } from 'react';
import { notificationsApi } from './api.js';
import Icon from './Icon.jsx';

const KIND = {
  CUSTOMER_ADDED: { icon: 'user', tone: 'sky', tab: 'Customers', label: 'New customer' },
  MEMBER_ADDED: { icon: 'userPlus', tone: 'indigo', tab: 'Team', label: 'New team member' },
  ACTIVITY_ADDED: { icon: 'note', tone: 'emerald', tab: 'Activities', label: 'New activity' },
  TASK_ASSIGNED: { icon: 'target', tone: 'amber', tab: 'Tasks', label: 'Task assigned to you' },
  ENQUIRY_ASSIGNED: { icon: 'user', tone: 'sky', tab: 'Enquiries', label: 'New enquiry for you' },
  FOLLOW_UP_DUE: { icon: 'clock', tone: 'rose', tab: 'FollowUps', label: 'Follow-up reminder' },
};
const POLL_MS = 30000;

function ago(t) {
  const s = Math.max(0, (Date.now() - new Date(t).getTime()) / 1000);
  if (s < 60) return 'just now';
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`;
  if (s < 7 * 86400) return `${Math.floor(s / 86400)} d ago`;
  return new Date(t).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' });
}

/**
 * Bell with the number of unread notifications. Leads are told when someone below them adds a customer, a team
 * member or an activity. Clicking a notification marks it read and opens the matching section.
 */
export default function NotificationBell({ onOpenTab }) {
  const [data, setData] = useState({ unread: 0, items: [] });
  const [open, setOpen] = useState(false);
  const [onlyUnread, setOnlyUnread] = useState(false);
  const box = useRef(null);

  const load = () => notificationsApi.list().then(setData).catch(() => {});
  useEffect(() => {
    load();
    const t = setInterval(() => { if (!document.hidden) load(); }, POLL_MS);
    return () => clearInterval(t);
  }, []);
  useEffect(() => { if (open) load(); }, [open]);

  // close when clicking outside or pressing Esc
  useEffect(() => {
    if (!open) return undefined;
    const onDown = (e) => { if (box.current && !box.current.contains(e.target)) setOpen(false); };
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey); };
  }, [open]);

  const openItem = async (n) => {
    if (!n.read) {
      setData((d) => ({ unread: Math.max(0, d.unread - 1), items: d.items.map((x) => (x.id === n.id ? { ...x, read: true } : x)) }));
      notificationsApi.read(n.id).catch(() => {});
    }
    setOpen(false);
    const k = KIND[n.type];
    // a sale is an "activity added" notification, but sales have their own section
    const tab = n.type === 'ACTIVITY_ADDED' && / recorded a sale /.test(n.message) ? 'Sales' : k?.tab;
    if (tab && onOpenTab) onOpenTab(tab);
  };

  const readAll = async () => {
    setData((d) => ({ unread: 0, items: d.items.map((x) => ({ ...x, read: true })) }));
    notificationsApi.readAll().catch(() => {});
  };

  const items = onlyUnread ? data.items.filter((n) => !n.read) : data.items;

  return (
    <div className="bell-wrap" ref={box}>
      <button type="button" className={open ? 'bell open' : 'bell'} onClick={() => setOpen(!open)}
        aria-label={`Notifications${data.unread ? `, ${data.unread} unread` : ''}`} aria-expanded={open}>
        <Icon name="bell" size={22} />
        {data.unread > 0 && <span className="bell-badge">{data.unread > 99 ? '99+' : data.unread}</span>}
      </button>

      {open && (
        <div className="bell-panel" role="dialog" aria-label="Notifications">
          <div className="bell-head">
            <strong>Notifications</strong>
            <div className="seg small-seg" role="tablist">
              {[[false, 'All'], [true, `Unread${data.unread ? ` (${data.unread})` : ''}`]].map(([v, label]) => (
                <button key={label} type="button" role="tab" aria-selected={onlyUnread === v}
                  className={onlyUnread === v ? 'seg-btn active' : 'seg-btn'} onClick={() => setOnlyUnread(v)}>{label}</button>
              ))}
            </div>
          </div>
          {items.length === 0 ? (
            <p className="muted bell-empty">
              {onlyUnread ? 'You are all caught up.' : 'No notifications yet. You will be told here when someone in your team adds a customer, a member or an activity.'}
            </p>
          ) : (
            <ul className="bell-list">
              {items.map((n) => {
                const k = KIND[n.type] || KIND.ACTIVITY_ADDED;
                return (
                  <li key={n.id}>
                    <button type="button" className={n.read ? 'bell-item' : 'bell-item unread'} onClick={() => openItem(n)}>
                      <span className={`icon-chip small tone-${k.tone}`}><Icon name={k.icon} size={16} /></span>
                      <span className="bell-text">
                        <span className="bell-kind">{k.label}</span>
                        <span>{n.message}</span>
                        <span className="muted bell-time">{ago(n.createdAt)}</span>
                      </span>
                      {!n.read && <span className="unread-dot" aria-label="unread" />}
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
          {data.unread > 0 && (
            <div className="bell-foot">
              <button type="button" className="link" onClick={readAll}>Mark all as read</button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
