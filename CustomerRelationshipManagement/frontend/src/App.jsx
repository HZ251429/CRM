import { useEffect, useState } from 'react';
import { isLoggedIn, getUser, logout } from './api.js';
import Logo from './Logo.jsx';
import NotificationBell from './NotificationBell.jsx';
import Modal from './Modal.jsx';
import Login from './pages/Login.jsx';
import Dashboard from './pages/Dashboard.jsx';
import Customers from './pages/Customers.jsx';
import Activities from './pages/Activities.jsx';
import Tenants from './pages/Tenants.jsx';
import Team from './pages/Team.jsx';
import Products from './pages/Products.jsx';
import Tasks from './pages/Tasks.jsx';
import Stock from './pages/Stock.jsx';
import WhatsApp from './pages/WhatsApp.jsx';
import PlatformDashboard from './pages/PlatformDashboard.jsx';

const ALL_TABS = {
  Dashboard: { page: Dashboard, icon: '📊' },
  Customers: { page: Customers, icon: '👥' },
  Activities: { page: Activities, icon: '✅' },
  Team: { page: Team, icon: '🧑‍🤝‍🧑' },
  Products: { page: Products, icon: '📦' },
  Stock: { page: Stock, icon: '🏬' },
  WhatsApp: { page: WhatsApp, icon: '💬' },
  Tasks: { page: Tasks, icon: '🎯' },
  Tenants: { page: Tenants, icon: '🏢' },
};

const TABS_BY_ROLE = {
  SUPER_ADMIN: ['Dashboard', 'Tenants'],
  TENANT_ADMIN: ['Dashboard', 'Team', 'Products', 'Stock', 'Tasks', 'Customers', 'Activities', 'WhatsApp'],
  MEMBER: ['Dashboard', 'Team', 'Tasks', 'Customers', 'Activities', 'WhatsApp'],
};

const ROLE_LABEL = { SUPER_ADMIN: 'Super Admin', TENANT_ADMIN: 'Admin', MEMBER: 'Member' };

/** Colour themes; each works in light and dark mode (see styles.css, :root[data-palette=...]). */
// side = sidebar colour, accent = primary, accent2 = second gradient colour, bg = page background [light, dark]
const PALETTES = [
  { id: 'sunset', name: 'Sunset', side: '#3b1e3f', accent: '#ea580c', accent2: '#e11d48', bg: ['#fdf8f3', '#1a1216'] },
  { id: 'indigo', name: 'Indigo', side: '#1e1b4b', accent: '#6366f1', accent2: '#a855f7', bg: ['#f4f5fb', '#0f1020'] },
  { id: 'ocean', name: 'Ocean', side: '#0f2a3d', accent: '#0d9488', accent2: '#0284c7', bg: ['#f3f8fa', '#0b1820'] },
  { id: 'slate', name: 'Slate', side: '#1f2937', accent: '#059669', accent2: '#0d9488', bg: ['#f3f4f6', '#0b0f14'] },
  { id: 'rose', name: 'Rose', side: '#4a1d2f', accent: '#e11d48', accent2: '#db2777', bg: ['#fdf6f8', '#1a1014'] },
  { id: 'forest', name: 'Forest', side: '#14301f', accent: '#16a34a', accent2: '#65a30d', bg: ['#f4f8f4', '#0d1611'] },
  { id: 'plum', name: 'Plum', side: '#2e1065', accent: '#9333ea', accent2: '#c026d3', bg: ['#f8f5fd', '#130c22'] },
  { id: 'amber', name: 'Amber', side: '#3a2a10', accent: '#d97706', accent2: '#ca8a04', bg: ['#fcf9f2', '#17120a'] },
  { id: 'midnight', name: 'Midnight', side: '#0b1b3a', accent: '#2563eb', accent2: '#0ea5e9', bg: ['#f3f6fc', '#0a1020'] },
  { id: 'aurora', name: 'Aurora', side: '#0c1a2b', accent: '#0891b2', accent2: '#7c3aed', bg: ['#f2f8fb', '#0a141d'] },
];
const MODES = [['light', '☀️', 'Light'], ['dark', '🌙', 'Dark'], ['auto', '🖥️', 'Auto']];
const darkQuery = () => window.matchMedia('(prefers-color-scheme: dark)');

// theme choices are a per-browser convenience; storage can be unavailable (private mode), so never fail on it
const readPref = (k) => { try { return localStorage.getItem(k); } catch { return null; } };
const savePref = (k, v) => { try { localStorage.setItem(k, v); } catch { /* ignore */ } };

export default function App() {
  const [user, setUser] = useState(isLoggedIn() ? getUser() : null);
  const [tab, setTab] = useState(null);
  const [pageKey, setPageKey] = useState(0); // bumped to reload a page, e.g. when opened from a notification
  // mode: light | dark | auto (follows the device setting)
  const [theme, setTheme] = useState(['light', 'dark', 'auto'].includes(readPref('theme')) ? readPref('theme') : 'auto');
  const [systemDark, setSystemDark] = useState(darkQuery().matches);
  const [palette, setPalette] = useState(PALETTES.some((p) => p.id === readPref('palette')) ? readPref('palette') : 'sunset');
  const [themeOpen, setThemeOpen] = useState(false);
  const isDark = theme === 'dark' || (theme === 'auto' && systemDark);

  useEffect(() => {
    const q = darkQuery();
    const onChange = (e) => setSystemDark(e.matches);
    q.addEventListener('change', onChange);
    return () => q.removeEventListener('change', onChange);
  }, []);
  useEffect(() => {
    document.documentElement.dataset.theme = isDark ? 'dark' : 'light';
    savePref('theme', theme);
  }, [theme, isDark]);
  useEffect(() => {
    document.documentElement.dataset.palette = palette;
    savePref('palette', palette);
  }, [palette]);

  if (!user) return <Login onSuccess={(u) => { setTab(null); setUser(u); }} />;

  const activePalette = PALETTES.find((p) => p.id === palette) || PALETTES[0];
  const names = TABS_BY_ROLE[user.role] || [];
  const current = names.includes(tab) ? tab : names[0];
  // the super admin's dashboard is a platform overview, not a sales dashboard
  const Page = user.role === 'SUPER_ADMIN' && current === 'Dashboard' ? PlatformDashboard : ALL_TABS[current].page;

  return (
    <div className="shell">
      <aside className="sidebar">
        <Logo light />
        <div className="whoami">
          <strong>{user.fullName || user.username}</strong>
          <span>{ROLE_LABEL[user.role]}{user.tenantName ? ` · ${user.tenantName}` : ''}</span>
        </div>
        <nav>
          {names.map((name) => (
            <button key={name} className={name === current ? 'nav active' : 'nav'} onClick={() => setTab(name)}>
              <span>{ALL_TABS[name].icon}</span> {name}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="theme-bar">
            <button type="button" className="theme-open" onClick={() => setThemeOpen(true)} title="Change theme" aria-haspopup="dialog">
              <span className="theme-icon" aria-hidden="true" style={{ '--a': activePalette.accent, '--b': activePalette.accent2 }}>🎨</span>
              <span className="theme-open-text"><strong>Theme</strong><small>{activePalette.name} · {MODES.find(([m]) => m === theme)[2]}</small></span>
            </button>
            <button type="button" className="mode-quick" onClick={() => setTheme(isDark ? 'light' : 'dark')}
              title={isDark ? 'Switch to light mode' : 'Switch to dark mode'} aria-label={isDark ? 'Switch to light mode' : 'Switch to dark mode'}>
              {isDark ? '☀️' : '🌙'}
            </button>
          </div>
          <button className="nav" onClick={async () => { await logout(); setUser(null); }}>
            <span>🚪</span> Logout
          </button>
        </div>
      </aside>
      <main className="content">
        <div className="page-top">
          <h1 className="page-title">{current === 'Tenants' ? 'Tenant management' : current}</h1>
          {user.role !== 'SUPER_ADMIN' && (
            <NotificationBell onOpenTab={(t) => { setTab(t); setPageKey((k) => k + 1); }} />
          )}
        </div>
        <Page key={`${current}-${pageKey}`} user={user} />
      </main>
      {themeOpen && (
        <Modal title="Appearance" onClose={() => setThemeOpen(false)}>
          <h4 className="detail-label">Mode</h4>
          <div className="mode-seg" role="radiogroup" aria-label="Light or dark mode">
            {MODES.map(([m, icon, label]) => (
              <button key={m} type="button" role="radio" aria-checked={theme === m}
                className={theme === m ? 'active' : ''} onClick={() => setTheme(m)}>
                <span aria-hidden="true">{icon}</span> {label}
              </button>
            ))}
          </div>
          {theme === 'auto' && <p className="muted hint">Auto follows your device setting (now {systemDark ? 'dark' : 'light'}).</p>}
          <h4 className="detail-label sub-head">Colour theme</h4>
          <div className="theme-grid" role="radiogroup" aria-label="Colour theme">
            {PALETTES.map((p) => (
              <button key={p.id} type="button" role="radio" aria-checked={palette === p.id}
                className={palette === p.id ? 'theme-card active' : 'theme-card'} onClick={() => setPalette(p.id)}>
                <span className="theme-preview" aria-hidden="true" style={{ '--side': p.side, '--acc': p.accent, '--acc2': p.accent2, '--pbg': p.bg[isDark ? 1 : 0] }}>
                  <span className="tp-side"><i /><i /><i /></span>
                  <span className="tp-main"><span className="tp-bar" /><span className="tp-line" /><span className="tp-line short" /></span>
                </span>
                <span className="theme-card-name">{palette === p.id && <span className="theme-check">✓</span>}{p.name}</span>
              </button>
            ))}
          </div>
          <div className="modal-actions">
            <button type="button" className="primary" onClick={() => setThemeOpen(false)}>Done</button>
          </div>
        </Modal>
      )}
    </div>
  );
}