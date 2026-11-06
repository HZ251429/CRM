import { useEffect, useState } from 'react';
import { isLoggedIn, getUser, logout } from './api.js';
import Logo from './Logo.jsx';
import Login from './pages/Login.jsx';
import Dashboard from './pages/Dashboard.jsx';
import Customers from './pages/Customers.jsx';
import Deals from './pages/Deals.jsx';
import Activities from './pages/Activities.jsx';
import Tenants from './pages/Tenants.jsx';
import Team from './pages/Team.jsx';
import Products from './pages/Products.jsx';
import Tasks from './pages/Tasks.jsx';

const ALL_TABS = {
  Dashboard: { page: Dashboard, icon: '📊' },
  Customers: { page: Customers, icon: '👥' },
  Deals: { page: Deals, icon: '💼' },
  Activities: { page: Activities, icon: '✅' },
  Team: { page: Team, icon: '🧑‍🤝‍🧑' },
  Products: { page: Products, icon: '📦' },
  Tasks: { page: Tasks, icon: '🎯' },
  Tenants: { page: Tenants, icon: '🏢' },
};

const TABS_BY_ROLE = {
  SUPER_ADMIN: ['Tenants'],
  TENANT_ADMIN: ['Dashboard', 'Team', 'Products', 'Tasks', 'Customers', 'Deals', 'Activities'],
  MEMBER: ['Dashboard', 'Tasks', 'Customers', 'Deals', 'Activities'],
};

const ROLE_LABEL = { SUPER_ADMIN: 'Super Admin', TENANT_ADMIN: 'Admin', MEMBER: 'Member' };

export default function App() {
  const [user, setUser] = useState(isLoggedIn() ? getUser() : null);
  const [tab, setTab] = useState(null);
  const [theme, setTheme] = useState(
    localStorage.getItem('theme') ||
      (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'));

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem('theme', theme);
  }, [theme]);

  if (!user) return <Login onSuccess={(u) => { setTab(null); setUser(u); }} />;

  const names = TABS_BY_ROLE[user.role] || [];
  const current = names.includes(tab) ? tab : names[0];
  const Page = ALL_TABS[current].page;

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
          <button className="nav" onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}>
            <span>{theme === 'dark' ? '☀️' : '🌙'}</span> {theme === 'dark' ? 'Light mode' : 'Dark mode'}
          </button>
          <button className="nav" onClick={async () => { await logout(); setUser(null); }}>
            <span>🚪</span> Logout
          </button>
        </div>
      </aside>
      <main className="content">
        <h1 className="page-title">{current === 'Tenants' ? 'Tenant management' : current}</h1>
        <Page key={current} user={user} />
      </main>
    </div>
  );
}