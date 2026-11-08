import { useState } from 'react';
import { login } from '../api.js';
import Logo from '../Logo.jsx';
import PasswordInput from '../PasswordInput.jsx';

export default function Login({ onSuccess }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      onSuccess(await login(username, password));
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-hero">
        <Logo size={56} light />
        <h2>Build stronger customer relationships.</h2>
        <p>Track customers, deals and activities in one place.</p>
      </div>
      <div className="login-panel">
        <form onSubmit={submit} className="login-card">
          <h1>Welcome back</h1>
          <p className="muted">Sign in to your HZ CRM account</p>
          {error && <div className="alert">{error}</div>}
          <label>Username, email or mobile
            <input value={username} onChange={(e) => setUsername(e.target.value)} placeholder="mike@ACME, mike@acme.com or 98765 43210"
              autoComplete="username" autoCapitalize="none" spellCheck={false} required maxLength={254} />
            <span className="muted small left">Your username (<b>name@tenantcode</b>), or the email or mobile number on your account</span>
          </label>
          <label>Password
            <PasswordInput value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
          </label>
          <button type="submit" className="primary" disabled={busy}>{busy ? 'Signing in...' : 'Sign in'}</button>
          <p className="muted small">&copy; {new Date().getFullYear()} HZ. All rights reserved.</p>
        </form>
      </div>
    </div>
  );
}