export default function Logo({ size = 40, showText = true, light = false }) {
  return (
    <div className="logo">
      <svg width={size} height={size} viewBox="0 0 48 48" aria-label="HZ logo">
        <defs>
          <linearGradient id="hzg" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" style={{ stopColor: 'var(--primary)' }} />
            <stop offset="1" style={{ stopColor: 'var(--primary-2)' }} />
          </linearGradient>
        </defs>
        <rect width="48" height="48" rx="12" fill="url(#hzg)" />
        <text x="24" y="31" textAnchor="middle" fontSize="20" fontWeight="800"
          fontFamily="Inter, system-ui, sans-serif" fill="#fff" letterSpacing="-1">HZ</text>
      </svg>
      {showText && (
        <div className="logo-text">
          <strong style={light ? { color: '#fff' } : undefined}>HZ</strong>
          <span>CRM</span>
        </div>
      )}
    </div>
  );
}
