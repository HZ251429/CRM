/** Small stroke icons (24×24 grid) that follow the current text colour. */
const PATHS = {
  target: <><circle cx="12" cy="12" r="9" /><circle cx="12" cy="12" r="5" /><circle cx="12" cy="12" r="1.5" /></>,
  clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
  box: <><path d="M3 7.5 12 3l9 4.5v9L12 21l-9-4.5z" /><path d="M3 7.5 12 12l9-4.5M12 12v9" /></>,
  cart: <><path d="M3 4h2l2.4 11h10.2L20 8H6.2" /><circle cx="9" cy="19" r="1.4" /><circle cx="17" cy="19" r="1.4" /></>,
  card: <><rect x="3" y="5.5" width="18" height="13" rx="2" /><path d="M3 10h18M7 15h4" /></>,
  trend: <><path d="m3 17 6-6 4 4 8-8" /><path d="M15 7h6v6" /></>,
  calendar: <><rect x="3.5" y="5" width="17" height="15" rx="2" /><path d="M3.5 10h17M8 3v4M16 3v4" /></>,
  users: <><circle cx="9" cy="8" r="3.2" /><path d="M3 20c0-3.3 2.7-6 6-6s6 2.7 6 6" /><path d="M16 4.6a3.2 3.2 0 0 1 0 6.3M18 14.3c1.8.8 3 2.6 3 4.7" /></>,
  user: <><circle cx="12" cy="8" r="3.6" /><path d="M5 20c0-3.9 3.1-7 7-7s7 3.1 7 7" /></>,
  check: <><circle cx="12" cy="12" r="9" /><path d="m8 12.5 2.7 2.7L16 10" /></>,
  play: <><circle cx="12" cy="12" r="9" /><path d="M10 8.8v6.4l5-3.2z" /></>,
  pause: <><circle cx="12" cy="12" r="9" /><path d="M9.5 9v6M14.5 9v6" /></>,
  rupee: <><path d="M7 5h10M7 9h10M7 5h3.5a4 4 0 0 1 0 8H7l7 6" /></>,
  chart: <><path d="M4 20V4M4 20h16" /><rect x="7" y="12" width="3" height="5" rx=".6" /><rect x="12" y="8" width="3" height="9" rx=".6" /><rect x="17" y="5" width="3" height="12" rx=".6" /></>,
  handshake: <><path d="m11 17 2 2a1.4 1.4 0 0 0 2-2" /><path d="m14 14 2.5 2.5a1.4 1.4 0 0 0 2-2L15 11l-3 1-2-2 3-3 5 1 3 3-2 3" /><path d="M3 11 6 8l4 1M3 11l4 4 2 2a1.4 1.4 0 0 0 2-2" /></>,
  arrow: <><path d="M5 12h14M13 6l6 6-6 6" /></>,
  bell: <><path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z" /><path d="M10 20.5a2 2 0 0 0 4 0" /></>,
  userPlus: <><circle cx="9.5" cy="8" r="3.5" /><path d="M3 20c0-3.6 2.9-6.5 6.5-6.5S16 16.4 16 20" /><path d="M19 8v6M16 11h6" /></>,
  note: <><path d="M5 3.5h10l4 4v13H5z" /><path d="M14.5 3.5v4.5H19M8.5 12h7M8.5 16h5" /></>,
  sun: <><circle cx="12" cy="12" r="4" /><path d="M12 2.5v2M12 19.5v2M4.6 4.6 6 6M18 18l1.4 1.4M2.5 12h2M19.5 12h2M4.6 19.4 6 18M18 6l1.4-1.4" /></>,
};

export default function Icon({ name, size = 20, className = '', title }) {
  return (
    <svg className={`icon ${className}`} width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden={title ? undefined : true} role={title ? 'img' : undefined}>
      {title && <title>{title}</title>}
      {PATHS[name] || PATHS.target}
    </svg>
  );
}
