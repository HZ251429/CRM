const BASE = 'http://localhost:8081/api';

let authHeader = sessionStorage.getItem('auth') || '';

export const isLoggedIn = () => !!authHeader;

export const getUser = () => {
  try { return JSON.parse(sessionStorage.getItem('user')); } catch { return null; }
};

export function logout() {
  authHeader = '';
  sessionStorage.removeItem('auth');
  sessionStorage.removeItem('user');
}

export async function login(username, password) {
  const header = 'Basic ' + btoa(`${username}:${password}`);
  const res = await fetch(`${BASE}/me`, { headers: { Authorization: header } });
  if (!res.ok) throw new Error('Invalid username or password');
  const user = await res.json();
  authHeader = header;
  sessionStorage.setItem('auth', header);
  sessionStorage.setItem('user', JSON.stringify(user));
  return user;
}

async function request(path, options = {}) {
  const res = await fetch(BASE + path, {
    ...options,
    headers: { 'Content-Type': 'application/json', Authorization: authHeader },
  });
  if (res.status === 401) {
    logout();
    window.location.reload();
    throw new Error('Session expired');
  }
  if (!res.ok) {
    let msg = `Request failed (${res.status})`;
    try {
      const body = await res.json();
      if (body.message) msg = body.message;
      else if (body.detail) msg = body.detail;
    } catch { /* ignore */ }
    throw new Error(msg);
  }
  return res.status === 204 ? null : res.json();
}

const crud = (name) => ({
  list: (query = '') => request(`/${name}${query}`),
  create: (body) => request(`/${name}`, { method: 'POST', body: JSON.stringify(body) }),
  update: (id, body) => request(`/${name}/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  remove: (id) => request(`/${name}/${id}`, { method: 'DELETE' }),
});

export const customersApi = {
  ...crud('customers'),
  all: () => request('/customers/all'),
  search: (q, page, size = 10, ownerId = '') =>
    request(`/customers?q=${encodeURIComponent(q)}&page=${page}&size=${size}${ownerId ? `&ownerId=${ownerId}` : ''}`),
};
export const dealsApi = crud('deals');
export const activitiesApi = crud('activities');
export const usersApi = crud('users');
export const rolesApi = crud('roles');
export const tenantsApi = {
  list: () => request('/admin/tenants'),
  details: (id) => request(`/admin/tenants/${id}/details`),
  create: (body) => request('/admin/tenants', { method: 'POST', body: JSON.stringify(body) }),
  setActive: (id, active) =>
    request(`/admin/tenants/${id}/active`, { method: 'PUT', body: JSON.stringify({ active }) }),
};
export const getDashboard = (userId) => request(`/dashboard${userId ? `?userId=${userId}` : ''}`);
export const getTeam = () => request('/dashboard/team');