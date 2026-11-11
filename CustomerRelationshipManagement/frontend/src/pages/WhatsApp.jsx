import { Fragment, useEffect, useRef, useState } from 'react';
import { whatsappApi } from '../api.js';
import { fmtDateTime } from '../format.js';

/** stored as country code + number, e.g. 919876543210 -> +91 98765 43210 */
const showPhone = (m) => (m && m.length === 12 && m.startsWith('91') ? `+91 ${m.slice(2, 7)} ${m.slice(7)}` : m ? `+${m}` : '');

/** WhatsApp formatting: *bold*, _italic_, line breaks */
function Formatted({ text }) {
  return String(text || '').split('\n').map((line, i, all) => (
    <Fragment key={i}>
      {line.split(/(\*[^*\n]+\*|_[^_\n]+_)/g).map((part, j) => (
        part.length > 2 && part.startsWith('*') && part.endsWith('*') ? <strong key={j}>{part.slice(1, -1)}</strong>
          : part.length > 2 && part.startsWith('_') && part.endsWith('_') ? <em key={j}>{part.slice(1, -1)}</em>
            : <Fragment key={j}>{part}</Fragment>
      ))}
      {i < all.length - 1 && <br />}
    </Fragment>
  ));
}

const time = () => new Date().toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' });

/**
 * The WhatsApp assistant: link your WhatsApp number to your account, and try the assistant in a test chat that
 * behaves like WhatsApp (menus, buttons and lists) before the real WhatsApp number is connected.
 */
export default function WhatsApp({ user }) {
  const [status, setStatus] = useState(null);
  const [code, setCode] = useState(null);
  const [error, setError] = useState('');
  const [now, setNow] = useState(Date.now());

  const [phone, setPhone] = useState('');
  const [chat, setChat] = useState([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [listFor, setListFor] = useState(null); // the message whose list is open
  const endRef = useRef(null);

  const loadStatus = () => whatsappApi.status().then((s) => {
    setStatus(s);
    if (s.linked) { setPhone(showPhone(s.phone)); setCode(null); }
  }).catch((e) => setError(e.message));
  useEffect(() => { loadStatus(); }, []);
  useEffect(() => { const t = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(t); }, []);
  useEffect(() => { endRef.current?.scrollIntoView({ block: 'end' }); }, [chat, sending]);

  const getCode = async () => {
    setError('');
    try { setCode(await whatsappApi.linkCode()); } catch (e) { setError(e.message); }
  };
  const unlink = async () => {
    if (!window.confirm('Unlink this WhatsApp number? The assistant will stop recognising it.')) return;
    try { await whatsappApi.unlink(); setPhone(''); setChat([]); loadStatus(); } catch (e) { setError(e.message); }
  };

  const send = async ({ text, replyId, label }) => {
    if (sending) return;
    const shown = label || text;
    if (!shown || !phone.trim()) return;
    setChat((c) => [...c, { me: true, text: shown, at: time() }]);
    setInput('');
    setListFor(null);
    setSending(true);
    try {
      const replies = await whatsappApi.send({ phone, text: replyId ? null : text, replyId: replyId || null });
      setChat((c) => [...c, ...replies.map((r) => ({ ...r, at: time() }))]);
      if (!status?.linked && replies.some((r) => /Linked!/.test(r.text))) loadStatus();
    } catch (e) {
      setChat((c) => [...c, { system: true, text: e.message }]);
    } finally {
      setSending(false);
    }
  };

  if (!status) return error ? <p className="error">{error}</p> : <p className="muted">Loading...</p>;

  const secondsLeft = code ? Math.max(0, Math.round((new Date(code.expiresAt).getTime() - now) / 1000)) : 0;
  const canChat = status.testChat && phone.trim();

  return (
    <div className="wa-page">
      <section className="dcard wa-link">
        <h3 className="dcard-title"><span className="wa-logo" aria-hidden="true">✆</span> Your WhatsApp</h3>
        {error && <p className="error">{error}</p>}
        {status.linked ? (
          <>
            <p className="wa-linked">Linked to <strong>{showPhone(status.phone)}</strong></p>
            <p className="muted small">since {fmtDateTime(status.linkedAt)}. Messages from this number act as you ({user.fullName || user.username}).</p>
            <div className="wa-actions">
              <button type="button" onClick={getCode}>Link a different number</button>
              <button type="button" className="danger-outline" onClick={unlink}>Unlink</button>
            </div>
          </>
        ) : (
          <>
            <p>Link your WhatsApp number to log activities and sales, check your balance and see your tasks from WhatsApp.</p>
            {!code && <button type="button" className="primary" onClick={getCode}>Get link code</button>}
          </>
        )}
        {code && (
          <div className="wa-code-box">
            {secondsLeft > 0 ? (
              <>
                <span className="muted small">Send this code from your WhatsApp:</span>
                <strong className="wa-code">{code.code}</strong>
                <span className="muted small">Expires in {Math.floor(secondsLeft / 60)}:{String(secondsLeft % 60).padStart(2, '0')} · works once</span>
                {status.testChat && (
                  <button type="button" className="link" onClick={() => setInput(code.code)}>Put it in the test chat →</button>
                )}
              </>
            ) : (
              <>
                <span className="muted">This code has expired.</span>
                <button type="button" onClick={getCode}>Get a new code</button>
              </>
            )}
          </div>
        )}
        <h4 className="detail-label sub-head">What you can do on WhatsApp</h4>
        <ul className="wa-features">
          <li>📝 Log a meeting, call or visit, with or without a sale</li>
          <li>📦 Check how many units you can still sell</li>
          <li>🎯 See your open tasks</li>
          <li>👤 Add a customer</li>
        </ul>
        <p className="muted small">Type <strong>menu</strong> at any time, or <strong>cancel</strong> to stop what you are doing.
          Everything you do on WhatsApp follows the same rules as the website.</p>
      </section>

      {status.testChat && (
        <section className="wa-phone" aria-label="Test chat">
          <header className="wa-head">
            <span className="wa-avatar" aria-hidden="true">HZ</span>
            <span className="wa-title"><strong>HZ CRM assistant</strong><small>Test chat · no real WhatsApp messages are sent</small></span>
          </header>
          <div className="wa-from">
            <label>Writing from
              <input type="tel" value={phone} onChange={(e) => setPhone(e.target.value)} disabled={status.linked}
                placeholder="Your WhatsApp number, e.g. 98765 43210" />
            </label>
          </div>
          <div className="wa-chat" aria-live="polite">
            {chat.length === 0 && (
              <p className="wa-hint">{status.linked ? 'Say "hi" to start.' : 'Enter your number above, then send your link code.'}</p>
            )}
            {chat.map((m, i) => (
              m.system ? <p key={i} className="wa-system">{m.text}</p> : (
                <div key={i} className={m.me ? 'wa-msg me' : 'wa-msg'}>
                  <div className="wa-bubble">
                    <Formatted text={m.text} />
                    <span className="wa-time">{m.at}</span>
                  </div>
                  {!m.me && m.buttons?.length > 0 && (
                    <div className="wa-buttons">
                      {m.buttons.map((b) => (
                        <button key={b.id} type="button" disabled={sending}
                          onClick={() => send({ replyId: b.id, label: b.title })}>{b.title}</button>
                      ))}
                    </div>
                  )}
                  {!m.me && m.list && (
                    <div className="wa-buttons">
                      <button type="button" disabled={sending} onClick={() => setListFor(m)}>☰ {m.list.buttonText}</button>
                    </div>
                  )}
                </div>
              )
            ))}
            {sending && <div className="wa-msg"><div className="wa-bubble wa-typing">typing…</div></div>}
            <div ref={endRef} />
          </div>
          {listFor && (
            <div className="wa-sheet" role="dialog" aria-label={listFor.list.buttonText}>
              <div className="wa-sheet-head">
                <strong>{listFor.list.buttonText}</strong>
                <button type="button" className="plain" onClick={() => setListFor(null)} aria-label="Close">✕</button>
              </div>
              <ul>
                {listFor.list.rows.map((r) => (
                  <li key={r.id}>
                    <button type="button" onClick={() => send({ replyId: r.id, label: r.title })}>
                      <strong>{r.title}</strong>
                      {r.description && <span className="muted">{r.description}</span>}
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
          <form className="wa-input" onSubmit={(e) => { e.preventDefault(); send({ text: input.trim() }); }}>
            <input value={input} onChange={(e) => setInput(e.target.value)} placeholder={canChat ? 'Message' : 'Enter your number first'}
              disabled={!canChat} maxLength={4096} aria-label="Message" />
            <button type="submit" disabled={!canChat || !input.trim() || sending} aria-label="Send">➤</button>
          </form>
        </section>
      )}
    </div>
  );
}
