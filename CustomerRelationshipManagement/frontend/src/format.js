import { useEffect, useState } from 'react';
import { getTeam } from './api.js';

/** Indian Rupee formatting, e.g. ₹12,34,567.00 */
export const money = (n) =>
  Number(n || 0).toLocaleString('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 });

const pad = (n) => String(n).padStart(2, '0');

/** Current local date/time in the format a datetime-local input expects (YYYY-MM-DDTHH:mm). */
export const nowLocalInput = () => {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

/** Trim server timestamps (2026-11-04T14:35:00) to a datetime-local value. */
export const toLocalInput = (t) => (t ? String(t).slice(0, 16) : '');

/** Friendly date and time for tables, e.g. 04 Nov 2026, 02:35 PM */
export const fmtDateTime = (t) => {
  if (!t) return '';
  const d = new Date(t);
  if (Number.isNaN(d.getTime())) return String(t);
  return d.toLocaleString('en-IN', { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: true });
};

/** Everyone the current user can see (self + team), so lists can show and filter by owner. */
export function useOwners() {
  const [team, setTeam] = useState([]);
  useEffect(() => { getTeam().then(setTeam).catch(() => {}); }, []);
  const names = Object.fromEntries(team.map((t) => [t.userId, t.fullName]));
  return { names, multi: team.length > 1, team };
}