import { useEffect, useRef, useState } from 'react';
import { customersApi } from './api.js';

const label = (c) => `${c.firstName} ${c.lastName}${c.company ? ` (${c.company})` : ''}`;

/**
 * Pick a customer by typing part of their name, email or company. Searches the server, so it works the same
 * with ten customers or a hundred thousand.
 *
 * @param value    selected customer id ('' for none)
 * @param initial  name to show for the selected customer before anything is typed (e.g. when editing)
 * @param onChange called with (id, name)
 */
export default function CustomerPicker({ value, initial = '', onChange, autoFocus }) {
  const [text, setText] = useState(initial);
  const [results, setResults] = useState([]);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const [loading, setLoading] = useState(false);
  const box = useRef(null);

  useEffect(() => {
    if (!open) return undefined;
    setLoading(true);
    const t = setTimeout(() => {
      customersApi.search(value && text === initial ? '' : text.trim(), 0, 20)
        .then((r) => { setResults(r.content); setActive(0); })
        .catch(() => setResults([]))
        .finally(() => setLoading(false));
    }, 200);
    return () => clearTimeout(t);
  }, [text, open]);

  useEffect(() => {
    const close = (e) => { if (box.current && !box.current.contains(e.target)) setOpen(false); };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, []);

  const pick = (c) => {
    const name = label(c);
    setText(name);
    setOpen(false);
    onChange(String(c.id), name);
  };

  const onKey = (e) => {
    if (!open && (e.key === 'ArrowDown' || e.key === 'Enter')) { setOpen(true); e.preventDefault(); return; }
    if (e.key === 'ArrowDown') { setActive((i) => Math.min(i + 1, results.length - 1)); e.preventDefault(); }
    else if (e.key === 'ArrowUp') { setActive((i) => Math.max(i - 1, 0)); e.preventDefault(); }
    else if (e.key === 'Enter' && results[active]) { pick(results[active]); e.preventDefault(); }
    else if (e.key === 'Escape' && open) { setOpen(false); e.stopPropagation(); }
  };

  return (
    <div className="picker" ref={box}>
      <input type="text" value={text} placeholder="Type a name, email or company..." autoFocus={autoFocus}
        role="combobox" aria-expanded={open} aria-autocomplete="list" autoComplete="off"
        onFocus={() => setOpen(true)} onKeyDown={onKey}
        onChange={(e) => { setText(e.target.value); setOpen(true); if (value) onChange('', ''); }} />
      {/* keeps the browser's "required" check: the form cannot be sent until a customer is picked */}
      <input className="picker-required" tabIndex={-1} aria-hidden="true" value={value} onChange={() => {}} required />
      {open && (
        <ul className="picker-list" role="listbox">
          {results.map((c, i) => (
            <li key={c.id} role="option" aria-selected={i === active}
              className={i === active ? 'active' : ''} onMouseEnter={() => setActive(i)}
              onMouseDown={(e) => { e.preventDefault(); pick(c); }}>
              <strong>{c.firstName} {c.lastName}</strong>
              <span className="muted">{[c.company, c.email].filter(Boolean).join(' · ')}</span>
            </li>
          ))}
          {!loading && results.length === 0 && <li className="muted picker-empty">No customers match.</li>}
          {loading && results.length === 0 && <li className="muted picker-empty">Searching...</li>}
        </ul>
      )}
    </div>
  );
}
