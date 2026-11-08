// Same-origin '/api' in development (Vite proxies it to the backend). Set VITE_API_URL when the API lives elsewhere.
const BASE = import.meta.env.VITE_API_URL || '/api';

// Authentication is an HttpOnly session cookie set by the server; no password or token is kept in the browser.
// Only the (non-secret) profile of the signed-in user is cached for display.
export const getUser = () => {
  try { return JSON.parse(sessionStorage.getItem('user')); } catch { return null; }
};
export const isLoggedIn = () => !!getUser();

const csrfToken = () => {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
  return m ? decodeURIComponent(m[1]) : '';
};

async function send(path, options = {}) {
  const method = (options.method || 'GET').toUpperCase();
  const headers = { 'Content-Type': 'application/json' };
  if (method !== 'GET') headers['X-XSRF-TOKEN'] = csrfToken();
  return fetch(BASE + path, { ...options, method, headers, credentials: 'include' });
}

async function errorMessage(res) {
  let msg = `Request failed (${res.status})`;
  try {
    const body = await res.json();
    if (body.message) msg = body.message;
    else if (body.detail) msg = body.detail;
  } catch { /* ignore */ }
  return msg;
}

const clearLocal = () => sessionStorage.removeItem('user');

export async function logout() {
  clearLocal();
  try { await send('/auth/logout', { method: 'POST' }); } catch { /* already signed out */ }
}

export async function login(username, password) {
  if (!csrfToken()) await send('/auth/csrf');
  const res = await send('/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) });
  if (!res.ok) throw new Error(res.status === 403 ? 'Please reload the page and try again' : await errorMessage(res));
  const user = await res.json();
  sessionStorage.setItem('user', JSON.stringify(user));
  return user;
}

async function request(path, options = {}) {
  const res = await send(path, options);
  if (res.status === 401) {
    clearLocal();
    window.location.reload();
    throw new Error('Session expired');
  }
  if (!res.ok) throw new Error(await errorMessage(res));
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
const qs = (params) => Object.entries(params)
  .filter(([, v]) => v !== '' && v != null && v !== false)
  .map(([k, v]) => `${k}=${encodeURIComponent(Array.isArray(v) ? v.join(',') : v)}`).join('&');
export const activitiesApi = {
  ...crud('activities'),
  /** one page of the log with totals: { content, page, totalPages, totalElements, sales, saleTotal, creditTotal } */
  page: (params) => request(`/activities/page?${qs(params)}`),
  get: (id) => request(`/activities/${id}`),
  rows: (ids) => request(`/activities/rows?${qs({ ids })}`),
  forCustomer: (customerId) => request(`/activities?customerId=${customerId}`),
  sales: () => request('/activities/sales'),
  stats: (customerIds) => request(`/activities/stats?${qs({ customerIds })}`),
};
export const usersApi = crud('users');
export const rolesApi = crud('roles');
export const productsApi = crud('products');
export const tasksApi = crud('tasks');
export const stockApi = {
  list: () => request('/stock'),
  history: (productId) => request(`/stock/${productId}/entries`),
  add: (body) => request('/stock', { method: 'POST', body: JSON.stringify(body) }),
  removeEntry: (id) => request(`/stock/entries/${id}`, { method: 'DELETE' }),
};
export const notificationsApi = {
  list: (limit = 30) => request(`/notifications?limit=${limit}`),
  read: (id) => request(`/notifications/${id}/read`, { method: 'POST' }),
  readAll: () => request('/notifications/read-all', { method: 'POST' }),
};
export const tenantsApi = {
  list: () => request('/admin/tenants'),
  details: (id) => request(`/admin/tenants/${id}/details`),
  create: (body) => request('/admin/tenants', { method: 'POST', body: JSON.stringify(body) }),
  setActive: (id, active) =>
    request(`/admin/tenants/${id}/active`, { method: 'PUT', body: JSON.stringify({ active }) }),
};
export const getDashboard = (userId, scope = 'me') =>
  request(`/dashboard?scope=${scope}${userId ? `&userId=${userId}` : ''}`);
export const getTeam = () => request('/dashboard/team');