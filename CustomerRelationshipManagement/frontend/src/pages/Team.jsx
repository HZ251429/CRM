import { useEffect, useMemo, useState } from 'react';
import { usersApi, rolesApi } from '../api.js';
import Modal, { Field } from '../Modal.jsx';

const emptyMember = { username: '', fullName: '', password: '', managerId: '', teamRoleId: '' };

export default function Team({ user }) {
  const code = user?.tenantCode || 'CODE';
  const [users, setUsers] = useState([]);
  const [roles, setRoles] = useState([]);
  const [expanded, setExpanded] = useState(new Set());
  const [initialised, setInitialised] = useState(false);
  const [error, setError] = useState('');

  // popups
  const [memberModal, setMemberModal] = useState(null); // null | { editing: user|null }
  const [form, setForm] = useState(emptyMember);
  const [formError, setFormError] = useState('');
  const [rolesOpen, setRolesOpen] = useState(false);
  const [newRole, setNewRole] = useState('');
  const [roleError, setRoleError] = useState('');

  const loadUsers = () => usersApi.list().then(setUsers).catch((e) => setError(e.message));
  const loadRoles = () => rolesApi.list().then(setRoles).catch((e) => setError(e.message));
  useEffect(() => { loadUsers(); loadRoles(); }, []);

  const admin = users.find((u) => u.role === 'TENANT_ADMIN');

  // children lookup: members without a manager report directly to the admin
  const children = useMemo(() => {
    const map = {};
    if (!admin) return map;
    users.forEach((u) => {
      if (u.id === admin.id) return;
      const parent = u.managerId && users.some((x) => x.id === u.managerId) ? u.managerId : admin.id;
      (map[parent] = map[parent] || []).push(u);
    });
    Object.values(map).forEach((list) => list.sort((a, b) => (a.fullName || '').localeCompare(b.fullName || '')));
    return map;
  }, [users, admin]);

  // the admin row starts open so the first level is visible; everything else is collapsed
  useEffect(() => {
    if (admin && !initialised) {
      setExpanded(new Set([admin.id]));
      setInitialised(true);
    }
  }, [admin, initialised]);

  const toggle = (id) => {
    const next = new Set(expanded);
    if (next.has(id)) next.delete(id); else next.add(id);
    setExpanded(next);
  };
  const expandAll = () => setExpanded(new Set(users.map((u) => u.id)));
  const collapseAll = () => setExpanded(new Set(admin ? [admin.id] : []));

  const countAll = (id) => (children[id] || []).reduce((n, c) => n + 1 + countAll(c.id), 0);

  const rows = [];
  const walk = (u, depth) => {
    rows.push({ u, depth });
    if (expanded.has(u.id)) (children[u.id] || []).forEach((c) => walk(c, depth + 1));
  };
  if (admin) walk(admin, 0);

  const nameOf = (id) => users.find((u) => u.id === id)?.fullName;
  const managerLabel = (u) => {
    if (u.role === 'TENANT_ADMIN') return '-';
    return u.managerId && nameOf(u.managerId) ? nameOf(u.managerId) : 'Admin';
  };

  // ----- members -----
  const subtreeIds = (id) => {
    const ids = new Set([id]);
    (children[id] || []).forEach((c) => subtreeIds(c.id).forEach((x) => ids.add(x)));
    return ids;
  };

  const openAdd = (managerId = '') => {
    setForm({ ...emptyMember, managerId: managerId ? String(managerId) : '' });
    setFormError('');
    setMemberModal({ editing: null });
  };
  const openEdit = (u) => {
    setForm({ ...emptyMember, fullName: u.fullName || '', managerId: u.managerId ? String(u.managerId) : '',
      teamRoleId: u.teamRoleId ? String(u.teamRoleId) : '' });
    setFormError('');
    setMemberModal({ editing: u });
  };
  const closeMember = () => setMemberModal(null);
  const onChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });

  const submitMember = async (e) => {
    e.preventDefault();
    setFormError('');
    const managerId = form.managerId ? Number(form.managerId) : null;
    const teamRoleId = form.teamRoleId ? Number(form.teamRoleId) : null;
    try {
      const editing = memberModal.editing;
      if (editing) {
        await usersApi.update(editing.id, { fullName: form.fullName, managerId, teamRoleId, active: editing.active });
      } else {
        await usersApi.create({ username: form.username, password: form.password, fullName: form.fullName, managerId, teamRoleId });
        if (managerId) setExpanded(new Set([...expanded, managerId]));
      }
      closeMember();
      loadUsers();
      loadRoles();
    } catch (err) {
      setFormError(err.message);
    }
  };

  const setActive = async (u, active) => {
    setError('');
    try {
      await usersApi.update(u.id, { fullName: u.fullName, managerId: u.managerId, teamRoleId: u.teamRoleId, active });
      loadUsers();
    } catch (err) {
      setError(err.message);
    }
  };

  const resetPassword = async (u) => {
    const password = window.prompt(`New password for ${u.username} (8-72 characters):`);
    if (!password) return;
    setError('');
    try {
      await usersApi.update(u.id, { fullName: u.fullName, managerId: u.managerId, teamRoleId: u.teamRoleId, active: u.active, password });
    } catch (err) {
      setError(err.message);
    }
  };

  // ----- roles -----
  const addRole = async (e) => {
    e.preventDefault();
    setRoleError('');
    try {
      await rolesApi.create({ name: newRole });
      setNewRole('');
      loadRoles();
    } catch (err) {
      setRoleError(err.message);
    }
  };
  const renameRole = async (r) => {
    const name = window.prompt('Rename role', r.name);
    if (!name || name.trim() === r.name) return;
    setRoleError('');
    try {
      await rolesApi.update(r.id, { name });
      loadRoles();
      loadUsers();
    } catch (err) {
      setRoleError(err.message);
    }
  };
  const deleteRole = async (r) => {
    if (!window.confirm(`Delete role "${r.name}"?`)) return;
    setRoleError('');
    try {
      await rolesApi.remove(r.id);
      loadRoles();
    } catch (err) {
      setRoleError(err.message);
    }
  };

  const editing = memberModal?.editing;
  const blocked = editing ? subtreeIds(editing.id) : new Set();
  const managerOptions = users.filter((u) => u.role === 'MEMBER' && u.active && !blocked.has(u.id));

  return (
    <div>
      {error && <p className="error">{error}</p>}

      <div className="toolbar">
        <button type="button" onClick={expandAll}>Expand all</button>
        <button type="button" onClick={collapseAll}>Collapse all</button>
        <span className="spacer" />
        <button type="button" onClick={() => { setRoleError(''); setRolesOpen(true); }}>Manage roles</button>
        <button type="button" className="primary" onClick={() => openAdd()}>+ Add member</button>
      </div>

      <table className="tree">
        <thead><tr><th>Name</th><th>Username</th><th>Role</th><th>Reports to</th><th>Status</th><th></th></tr></thead>
        <tbody>
          {rows.map(({ u, depth }) => {
            const kids = children[u.id] || [];
            const isOpen = expanded.has(u.id);
            return (
              <tr key={u.id} className={u.active ? '' : 'inactive'}>
                <td>
                  <div className="tree-cell" style={{ paddingLeft: `${depth * 1.6}rem` }}>
                    {kids.length > 0 ? (
                      <button type="button" className="expander" onClick={() => toggle(u.id)}
                        aria-label={isOpen ? 'Collapse' : 'Expand'} title={isOpen ? 'Collapse' : 'Expand'}>
                        {isOpen ? '▾' : '▸'}
                      </button>
                    ) : <span className="expander-gap" />}
                    <span className="tree-name">{u.fullName}</span>
                    {kids.length > 0 && <span className="count-pill" title="Members under this person">{countAll(u.id)}</span>}
                  </div>
                </td>
                <td>{u.username}</td>
                <td>{u.role === 'TENANT_ADMIN' ? <span className="badge on">Admin</span> : (u.teamRoleName || <span className="muted">-</span>)}</td>
                <td>{managerLabel(u)}</td>
                <td><span className={u.active ? 'badge on' : 'badge off'}>{u.active ? 'Active' : 'Inactive'}</span></td>
                <td>
                  {u.role === 'MEMBER' && u.active && (
                    <button className="plain" onClick={() => openAdd(u.id)} title="Add a member under this person">+ Add under</button>
                  )}
                  {u.role === 'MEMBER' && <button className="plain" onClick={() => openEdit(u)}>Edit</button>}
                  <button className="plain" onClick={() => resetPassword(u)}>Reset password</button>
                  {u.role === 'MEMBER' && (
                    <button className="plain" onClick={() => setActive(u, !u.active)}>{u.active ? 'Deactivate' : 'Activate'}</button>
                  )}
                </td>
              </tr>
            );
          })}
          {rows.length === 0 && <tr><td colSpan="6" className="muted">Loading...</td></tr>}
        </tbody>
      </table>
      <p className="muted hint">
        Click ▸ to show the members under a person. Members can see their own data and the data of everyone below them.
      </p>

      {memberModal && (
        <Modal title={editing ? `Edit ${editing.fullName}` : 'Add team member'} onClose={closeMember}>
          <form onSubmit={submitMember} autoComplete="off">
            {formError && <p className="error">{formError}</p>}
            <div className="form-grid">
              <Field label="Full name" wide>
                <input name="fullName" value={form.fullName} onChange={onChange} required autoFocus autoComplete="off" />
              </Field>
              {!editing && (
                <>
                  <Field label="Username">
                    <span className="suffix-group">
                      <input name="username" value={form.username} onChange={onChange} required autoComplete="new-password"
                        pattern="[A-Za-z0-9._\-]{1,30}" title="Letters, digits, . _ - only" />
                      <span className="suffix">@{code}</span>
                    </span>
                  </Field>
                  <Field label="Password">
                    <input name="password" type="password" minLength={8} maxLength={72} value={form.password} onChange={onChange}
                      required autoComplete="new-password" />
                  </Field>
                </>
              )}
              <Field label="Role">
                <select name="teamRoleId" value={form.teamRoleId} onChange={onChange}>
                  <option value="">No role</option>
                  {roles.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
                </select>
              </Field>
              <Field label="Reports to">
                <select name="managerId" value={form.managerId} onChange={onChange}>
                  <option value="">Admin</option>
                  {managerOptions.map((m) => <option key={m.id} value={m.id}>{m.fullName}</option>)}
                </select>
              </Field>
            </div>
            {roles.length === 0 && (
              <p className="muted hint">No roles yet. Use "Manage roles" to create roles such as Sales Manager or Executive.</p>
            )}
            <div className="modal-actions">
              <button type="button" onClick={closeMember}>Cancel</button>
              <button type="submit" className="primary">{editing ? 'Update' : 'Add member'}</button>
            </div>
          </form>
        </Modal>
      )}

      {rolesOpen && (
        <Modal title="Manage roles" onClose={() => setRolesOpen(false)}>
          {roleError && <p className="error">{roleError}</p>}
          <form onSubmit={addRole} className="role-add" autoComplete="off">
            <input placeholder="New role name (e.g. Sales Manager)" value={newRole} maxLength={50}
              onChange={(e) => setNewRole(e.target.value)} required autoFocus autoComplete="off" />
            <button type="submit" className="primary">Add role</button>
          </form>
          <table>
            <thead><tr><th>Role</th><th>Members</th><th></th></tr></thead>
            <tbody>
              {roles.map((r) => (
                <tr key={r.id}>
                  <td>{r.name}</td><td>{r.memberCount}</td>
                  <td>
                    <button className="plain" onClick={() => renameRole(r)}>Rename</button>
                    <button className="plain" onClick={() => deleteRole(r)}>Delete</button>
                  </td>
                </tr>
              ))}
              {roles.length === 0 && <tr><td colSpan="3" className="muted">No roles yet</td></tr>}
            </tbody>
          </table>
          <div className="modal-actions">
            <button type="button" onClick={() => setRolesOpen(false)}>Done</button>
          </div>
        </Modal>
      )}
    </div>
  );
}