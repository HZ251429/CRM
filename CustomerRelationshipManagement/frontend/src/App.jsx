import { useEffect, useState } from 'react';
import { isLoggedIn, getUser, logout } from './api.js';
import Logo from './Logo.jsx';
import NotificationBell from './NotificationBell.jsx';
import Login from './pages/Login.jsx';
import Dashboard from './pages/Dashboard.jsx';
import Customers from './pages/Customers.jsx';
import Activities from './pages/Activities.jsx';
import Tenants from './pages/Tenants.jsx';
import Team from './pages/Team.jsx';
import Products from './pages/Products.jsx';
import Tasks from './pages/Tasks.jsx';
import Stock from './pages/Stock.jsx';
import PlatformDashboard from './pages/PlatformDashboard.jsx';

const ALL_TABS = {
  Dashboard: { page: Dashboard, icon: '📊' },
  Customers: { page: Customers, icon: '👥' },
  Activities: { page: Activities, icon: '✅' },
  Team: { page: Team, icon: '🧑‍🤝‍🧑' },
  Products: { page: Products, icon: '📦' },
  Stock: { page: Stock, icon: '🏬' },
  Tasks: { page: Tasks, icon: '🎯' },
  Tenants: { page: Tenants, icon: '🏢' },
};

const TABS_BY_ROLE = {
  SUPER_ADMIN: ['Dashboard', 'Tenants'],
  TENANT_ADMIN: ['Dashboard', 'Team', 'Products', 'Stock', 'Tasks', 'Customers', 'Activities'],
  MEMBER: ['Dashboard', 'Team', 'Tasks', 'Customers', 'Activities'],
};

const ROLE_LABEL = { SUPER_ADMIN: 'Super Admin', TENANT_ADMIN: 'Admin', MEMBER: 'Member' };

/** Colour themes; each works in light and dark mode (see styles.css, :root[data-palette=...]). */
const PALETTES = [
  { id: 'sunset', name: 'Sunset', colors: ['#3b1e3f', '#ea580c'] },
  { id: 'indigo', name: 'Indigo', colors: ['#1e1b4b', '#6366f1'] },
  { id: 'ocean', name: 'Ocean', colors: ['#0f2a3d', '#0d9488'] },
  { id: 'slate', name: 'Slate', colors: ['#1f2937', '#059669'] },
];

// theme choices are a per-browser convenience; storage can be unavailable (private mode), so never fail on it
const readPref = (k) => { try { return localStorage.getItem(k); } catch { return null; } };
const savePref = (k, v) => { try { localStorage.setItem(k, v); } catch { /* ignore */ } };

export default function App() {
  const [user, setUser] = useState(isLoggedIn() ? getUser() : null);
  const [tab, setTab] = useState(null);
  const [pageKey, setPageKey] = useState(0); // bumped to reload a page, e.g. when opened from a notification
  const [theme, setTheme] = useState(
    readPref('theme') || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'));
  const [palette, setPalette] = useState(PALETTES.some((p) => p.id === readPref('palette')) ? readPref('palette') : 'sunset');

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    savePref('theme', theme);
  }, [theme]);
  useEffect(() => {
    document.documentElement.dataset.palette = palette;
    savePref('palette', palette);
  }, [palette]);

  if (!user) return <Login onSuccess={(u) => { setTab(null); setUser(u); }} />;

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
          <div className="theme-picker">
            <span className="theme-picker-label">Theme · <span className="theme-name">{PALETTES.find((p) => p.id === palette)?.name}</span></span>
            <div className="theme-swatches" role="radiogroup" aria-label="Colour theme">
              {PALETTES.map((p) => (
                <button key={p.id} type="button" role="radio" aria-checked={palette === p.id} title={p.name} aria-label={p.name}
                  className={palette === p.id ? 'theme-swatch active' : 'theme-swatch'}
                  style={{ '--a': p.colors[0], '--b': p.colors[1] }} onClick={() => setPalette(p.id)} />
              ))}
            </div>
            <div className="mode-toggle" role="radiogroup" aria-label="Light or dark mode">
              {[['light', '☀️ Light'], ['dark', '🌙 Dark']].map(([m, label]) => (
                <button key={m} type="button" role="radio" aria-checked={theme === m}
                  className={theme === m ? 'active' : ''} onClick={() => setTheme(m)}>{label}</button>
              ))}
            </div>
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
    </div>
  );
}