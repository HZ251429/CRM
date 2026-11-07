import { useEffect, useRef } from 'react';

/** Popup dialog. Closes on Esc or when the dark backdrop is clicked. */
export default function Modal({ title, onClose, children, wide = false }) {
  const backdrop = useRef(null);
  useEffect(() => {
    // only the popup on top reacts to Esc (an alert can sit on top of a form)
    const onKey = (e) => {
      if (e.key !== 'Escape') return;
      const open = document.querySelectorAll('.modal-backdrop');
      if (open[open.length - 1] === backdrop.current) onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div className="modal-backdrop" ref={backdrop} onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className={wide ? 'modal wide' : 'modal'} role="dialog" aria-modal="true" aria-label={title}>
        <div className="modal-head">
          <h2>{title}</h2>
          <button type="button" className="modal-x" onClick={onClose} aria-label="Close">×</button>
        </div>
        {children}
      </div>
    </div>
  );
}

export function Field({ label, children, wide }) {
  return (
    <label className={wide ? 'field wide' : 'field'}>
      <span>{label}</span>
      {children}
    </label>
  );
}

/** Error popup with a single OK button, shown on top of whatever is open. */
export function AlertDialog({ title = 'Something went wrong', message, onClose }) {
  return (
    <Modal title={title} onClose={onClose}>
      <div className="alert-body" role="alert">
        <span className="alert-icon" aria-hidden="true">!</span>
        <p className="confirm-text">{message}</p>
      </div>
      <div className="modal-actions">
        <button type="button" className="primary" onClick={onClose} autoFocus>OK</button>
      </div>
    </Modal>
  );
}

/** Confirmation popup, e.g. before deleting a row. */
export function ConfirmDialog({ title = 'Confirm delete', message, confirmLabel = 'Delete', onConfirm, onCancel }) {
  return (
    <Modal title={title} onClose={onCancel}>
      <p className="confirm-text">{message}</p>
      <div className="modal-actions">
        <button type="button" onClick={onCancel} autoFocus>Cancel</button>
        <button type="button" className="danger" onClick={onConfirm}>{confirmLabel}</button>
      </div>
    </Modal>
  );
}