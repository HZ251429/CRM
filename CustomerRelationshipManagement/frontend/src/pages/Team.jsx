import { useEffect, useMemo, useState } from 'react';
import { usersApi, rolesApi } from '../api.js';
import Modal, { Field } from '../Modal.jsx';
import TeamOverview from './TeamOverview.jsx';

const emptyMember = { username: '', fullName: '', password: '', managerId: '', teamRoleId: '', email: '', mobile: '' };
/** stored as country code + number, e.g. 919876543210 -> +91 98765 43210 */
const showMobile = (m) => (m && m.length === 12 && m.startsWith('91') ? `+91 ${m.slice(2, 7)} ${m.slice(7)}` : m ? `+${m}` : '');

/** Team section: each person sees only themselves and the people below them, and can add members under them. */
export default function Team({ user }) {
  const isAdmin = user.role === 'TENANT_ADMIN';
  const [view, setView] = useState('overview');
  return (
    <div>
      <div className="toolbar">
        <div className="seg" role="tablist">
          {[['overview', 'Overview'], ['members', isAdmin ? 'Members & roles' : 'My members']].map(([k, label]) => (
            <button key={k} type="button" role="tab" aria-selected={view === k}
              className={view === k ? 'seg-btn active' : 'seg-btn'} onClick={() => setView(k)}>{label}</button>
          ))}
        </div>
      </div>
      {view === 'overview' ? <TeamOverview user={user} /> : <TeamMembers user={user} />}
    </div>
  );
}

function TeamMembers({ user }) {
  const isAdmin = user.role === 'TENANT_ADMIN';
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

  // top of the tree: the admin sees the whole company, anyone else starts at themselves
  const root = isAdmin ? users.find((u) => u.role === 'TENANT_ADMIN') : users.find((u) => u.id === user.id);

  // children lookup: members without a manager report directly to the admin
  const children = useMemo(() => {
    const map = {};
    if (!root) return map;
    users.forEach((u) => {
      if (u.id === root.id) return;
      const parent = u.managerId && users.some((x) => x.id === u.managerId) ? u.managerId : root.id;
      (map[parent] = map[parent] || []).push(u);
    });
    Object.values(map).forEach((list) => list.sort((a, b) => (a.fullName || '').localeCompare(b.fullName || '')));
    return map;
  }, [users, root]);

  // the admin row starts open so the first level is visible; everything else is collapsed
  useEffect(() => {
    if (root && !initialised) {
      setExpanded(new Set([root.id]));
      setInitialised(true);
    }
  }, [root, initialised]);

  const toggle = (id) => {
    const next = new Set(expanded);
    if (next.has(id)) next.delete(id); else next.add(id);
    setExpanded(next);
  };
  const expandAll = () => setExpanded(new Set(users.map((u) => u.id)));
  const collapseAll = () => setExpanded(new Set(root ? [root.id] : []));

  const countAll = (id) => (children[id] || []).reduce((n, c) => n + 1 + countAll(c.id), 0);

  const rows = [];
  const walk = (u, depth) => {
    rows.push({ u, depth });
    if (expanded.has(u.id)) (children[u.id] || []).forEach((c) => walk(c, depth + 1));
  };
  if (root) walk(root, 0);

  const nameOf = (id) => users.find((u) => u.id === id)?.fullName;
  // people without a manager report to the company admin
  const companyAdmin = users.find((u) => u.role === 'TENANT_ADMIN');
  const managerLabel = (u) => {
    if (u.role === 'TENANT_ADMIN') return <span className="muted">—</span>;
    if (!isAdmin && u.id === user.id) return <span className="muted">—</span>; // a lead's own manager is outside their view
    const id = u.managerId && nameOf(u.managerId) ? u.managerId : companyAdmin?.id;
    const name = id ? nameOf(id) : 'Admin';
    return <span className={id === user.id ? 'manager-chip me' : 'manager-chip'}>{name}{id === user.id ? ' (me)' : ''}</span>;
  };

  // ----- members -----
  const subtreeIds = (id) => {
    const ids = new Set([id]);
    (children[id] || []).forEach((c) => subtreeIds(c.id).forEach((x) => ids.add(x)));
    return ids;
  };

  // a lead's new members report to them by default
  const openAdd = (managerId = isAdmin ? '' : user.id) => {
    setForm({ ...emptyMember, managerId: managerId ? String(managerId) : '' });
    setFormError('');
    setMemberModal({ editing: null });
  };
  const openEdit = (u) => {
    setForm({ ...emptyMember, fullName: u.fullName || '', managerId: u.managerId ? String(u.managerId) : '',
      teamRoleId: u.teamRoleId ? String(u.teamRoleId) : '', email: u.email || '', mobile: showMobile(u.mobile) });
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
        await usersApi.update(editing.id, { fullName: form.fullName, managerId, teamRoleId, active: editing.active, email: form.email, mobile: form.mobile });
      } else {
        await usersApi.create({ username: form.username, password: form.password, fullName: form.fullName, managerId, teamRoleId, email: form.email, mobile: form.mobile });
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
  // the admin manages every member; a lead manages the people below them (not themselves)
  const canManage = (u) => u.role === 'MEMBER' && (isAdmin || u.id !== user.id);

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
        <thead><tr><th>Name</th><th>Sign-in</th><th>Role</th><th>Reports to</th><th>Status</th><th></th></tr></thead>
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
                    <span className="tree-name">{u.fullName}{u.id === user.id ? ' (me)' : ''}</span>
                    {kids.length > 0 && <span className="count-pill" title="Members under this person">{countAll(u.id)}</span>}
                  </div>
                </td>
                <td className="two-line"><span>{u.username}</span>{(u.email || u.mobile) && <span className="muted">{[u.email, showMobile(u.mobile)].filter(Boolean).join(' · ')}</span>}</td>
                <td>{u.role === 'TENANT_ADMIN' ? <span className="badge on">Admin</span> : (u.teamRoleName || <span className="muted">-</span>)}</td>
                <td>{managerLabel(u)}</td>
                <td><span className={u.active ? 'badge on' : 'badge off'}>{u.active ? 'Active' : 'Inactive'}</span></td>
                <td>
                  {u.role === 'MEMBER' && u.active && (
                    <button className="plain" onClick={() => openAdd(u.id)} title="Add a member under this person">+ Add under</button>
                  )}
                  {canManage(u) && <button className="plain" onClick={() => openEdit(u)}>Edit</button>}
                  {isAdmin && <button className="plain" onClick={() => resetPassword(u)}>Reset password</button>}
                  {canManage(u) && (
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
        Click ▸ to show the members under a person. {isAdmin ? 'Members can see their own data and the data of everyone below them.' : 'You see yourself and everyone below you, and can add members under yourself or anyone in your team.'}
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
              <Field label="Email (optional, can be used to sign in)">
                <input name="email" type="email" value={form.email} onChange={onChange} maxLength={254} autoComplete="off"
                  placeholder="name@company.com" />
              </Field>
              <Field label="Mobile (optional, can be used to sign in)">
                <input name="mobile" type="tel" value={form.mobile} onChange={onChange} maxLength={25} autoComplete="off"
                  placeholder="98765 43210" pattern="[+0-9 ()\-.]{7,25}" title="Digits, with or without country code" />
              </Field>
              <Field label="Role">
                <select name="teamRoleId" value={form.teamRoleId} onChange={onChange}>
                  <option value="">No role</option>
                  {roles.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
                </select>
              </Field>
              <Field label="Reports to">
                <select name="managerId" value={form.managerId} onChange={onChange}>
                  {isAdmin && <option value="">{companyAdmin ? `${companyAdmin.fullName} (Admin)` : 'Admin'}</option>}
                  {managerOptions.map((m) => <option key={m.id} value={m.id}>{m.fullName}</option>)}
                </select>
              </Field>
            </div>
            {roles.length === 0 && (
              <p className="muted hint">{isAdmin ? 'No roles yet. Use "Manage roles" to create roles such as Sales Manager or Executive.' : 'No roles yet. Use "Manage roles" to create roles for your team.'}</p>
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
          <p className="muted hint">{isAdmin
            ? 'Roles you create can be used across the company. You can rename or delete any role.'
            : 'Roles you create can be used by you, the people above you and everyone in your team. You can change only the roles you created.'}</p>
          <table>
            <thead><tr><th>Role</th><th>Created by</th><th className="num">Members</th><th></th></tr></thead>
            <tbody>
              {roles.map((r) => (
                <tr key={r.id}>
                  <td>{r.name}</td>
                  <td className="muted">{r.createdById === user.id ? 'You' : r.createdByName}</td>
                  <td className="num">{r.memberCount}</td>
                  <td>
                    {r.canEdit && <>
                      <button className="plain" onClick={() => renameRole(r)}>Rename</button>
                      <button className="plain" onClick={() => deleteRole(r)}>Delete</button>
                    </>}
                  </td>
                </tr>
              ))}
              {roles.length === 0 && <tr><td colSpan="4" className="muted">No roles yet</td></tr>}
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