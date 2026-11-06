import { useEffect, useRef, useState } from 'react';
import { money } from '../format.js';

const H = 240;
const PAD = { top: 12, right: 8, bottom: 28, left: 56 };
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

const compactMoney = (n) => `₹${Number(n).toLocaleString('en-IN', { notation: 'compact', maximumFractionDigits: 1 })}`;
const compactNum = (n) => Number(n).toLocaleString('en-IN', { notation: 'compact', maximumFractionDigits: 1 });

/** "nice" axis: 0..max split into round steps */
function ticks(max) {
  if (max <= 0) return [0, 1];
  const raw = max / 4;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= raw);
  const out = [];
  for (let v = 0; v <= max + step * 0.001; v += step) out.push(v);
  if (out[out.length - 1] < max) out.push(out[out.length - 1] + step);
  return out;
}

function labelOf(key, period, long = false) {
  if (period === 'year') {
    const [y, m] = key.split('-').map(Number);
    return long ? `${MONTHS[m - 1]} ${y}` : MONTHS[m - 1];
  }
  const [y, m, d] = key.split('-').map(Number);
  const date = new Date(y, m - 1, d);
  if (long) return date.toLocaleDateString('en-IN', { weekday: 'short', day: '2-digit', month: 'short', year: 'numeric' });
  return period === 'week' ? date.toLocaleDateString('en-IN', { weekday: 'short' }) : String(d);
}

/** Single-series column chart of sales per day / month, with hover tooltip and a table view. */
export default function SalesChart({ points, metric, period, onBarClick }) {
  const wrap = useRef(null);
  const [width, setWidth] = useState(640);
  const [hover, setHover] = useState(null);

  useEffect(() => {
    if (!wrap.current) return undefined;
    const ro = new ResizeObserver(([e]) => setWidth(Math.max(280, Math.floor(e.contentRect.width))));
    ro.observe(wrap.current);
    return () => ro.disconnect();
  }, []);

  const value = (p) => Number(metric === 'amount' ? p.amount : p.units);
  const fmtVal = (v) => (metric === 'amount' ? money(v) : `${Number(v).toLocaleString('en-IN')} units`);
  const axis = ticks(Math.max(0, ...points.map(value)));
  const top = axis[axis.length - 1];
  const plotW = width - PAD.left - PAD.right;
  const plotH = H - PAD.top - PAD.bottom;
  const slot = plotW / points.length;
  const barW = Math.max(2, Math.min(24, slot - 2));
  const y = (v) => PAD.top + plotH - (v / top) * plotH;
  const labelEvery = period === 'month' ? 5 : 1;
  const total = points.reduce((n, p) => n + value(p), 0);

  // rounded 4px data-end, square at the baseline
  const bar = (x, v) => {
    const h = Math.max(0, (v / top) * plotH);
    if (h <= 0) return null;
    const r = Math.min(4, h, barW / 2);
    const b = PAD.top + plotH;
    const t = b - h;
    return `M${x},${b} V${t + r} Q${x},${t} ${x + r},${t} H${x + barW - r} Q${x + barW},${t} ${x + barW},${t + r} V${b} Z`;
  };

  const hp = hover != null ? points[hover] : null;
  const hx = hover != null ? PAD.left + slot * hover + slot / 2 : 0;

  return (
    <div className="chart">
      <div className="chart-wrap" ref={wrap} onMouseLeave={() => setHover(null)}>
        <svg width={width} height={H} role="img"
          aria-label={`Sales ${metric === 'amount' ? 'amount' : 'units'} per ${period === 'year' ? 'month' : 'day'}, total ${fmtVal(total)}`}>
          {axis.map((t) => (
            <g key={t}>
              <line x1={PAD.left} x2={width - PAD.right} y1={y(t)} y2={y(t)} className="chart-grid" />
              <text x={PAD.left - 8} y={y(t)} dy="0.32em" textAnchor="end" className="chart-axis">
                {metric === 'amount' ? compactMoney(t) : compactNum(t)}
              </text>
            </g>
          ))}
          {points.map((p, i) => {
            const x = PAD.left + slot * i + (slot - barW) / 2;
            const d = bar(x, value(p));
            return (
              <g key={p.key}>
                {d && <path d={d} className={hover == null || hover === i ? 'chart-bar' : 'chart-bar dim'} />}
                {(i % labelEvery === 0 || i === points.length - 1) && (
                  <text x={PAD.left + slot * i + slot / 2} y={H - 8} textAnchor="middle" className="chart-axis">
                    {labelOf(p.key, period)}
                  </text>
                )}
                <rect x={PAD.left + slot * i} y={PAD.top} width={slot} height={plotH} fill="transparent"
                  className={onBarClick && p.deals > 0 ? 'chart-hit clickable' : 'chart-hit'}
                  onClick={() => onBarClick && p.deals > 0 && onBarClick(p, labelOf(p.key, period, true))}
                  onKeyDown={(e) => { if (e.key === 'Enter' && onBarClick && p.deals > 0) onBarClick(p, labelOf(p.key, period, true)); }}
                  tabIndex={0} aria-label={`${labelOf(p.key, period, true)}: ${fmtVal(value(p))}, ${p.deals} deals`}
                  onMouseEnter={() => setHover(i)} onFocus={() => setHover(i)} onBlur={() => setHover(null)} />
              </g>
            );
          })}
        </svg>
        {hp && (
          <div className="chart-tip" style={{ left: Math.min(Math.max(hx, 90), width - 90), top: Math.max(0, y(value(hp)) - 8) }}>
            <strong>{labelOf(hp.key, period, true)}</strong>
            <span>{money(hp.amount)}</span>
            <span className="muted">{Number(hp.units).toLocaleString('en-IN')} units · {hp.deals} deal{hp.deals === 1 ? '' : 's'}</span>
            {onBarClick && hp.deals > 0 && <span className="tip-hint">Click for details</span>}
          </div>
        )}
      </div>
      <details className="chart-table">
        <summary>View as table</summary>
        <table>
          <thead><tr><th>{period === 'year' ? 'Month' : 'Day'}</th><th className="num">Deals</th><th className="num">Units</th><th className="num">Amount</th></tr></thead>
          <tbody>
            {points.map((p) => (
              <tr key={p.key}><td>{labelOf(p.key, period, true)}</td><td className="num">{p.deals}</td>
                <td className="num">{Number(p.units).toLocaleString('en-IN')}</td><td className="num">{money(p.amount)}</td></tr>
            ))}
          </tbody>
        </table>
      </details>
    </div>
  );
}
