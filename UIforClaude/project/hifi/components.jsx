// Lumi hi-fi components — palette from repo colors.xml
const LUMI = {
  primary: '#1A1A2E',
  primaryDark: '#16213E',
  accent: '#7C4DFF',
  accentLight: '#B388FF',
  accentSoft: '#EFE8FF',
  bg: '#F7F6FB',
  card: '#FFFFFF',
  bubbleUser: '#7C4DFF',
  bubbleLumi: '#FFFFFF',
  textPrimary: '#1A1A2E',
  textSecondary: '#5B5B6E',
  textHint: '#9A9AAB',
  line: '#E6E4F0',
  ok: '#22A06B',
  warn: '#D97A3D',
};

const FONT = "'Inter', 'Roboto', system-ui, sans-serif";

// ─── Phone frame (Android-ish, Lumi-themed) ──────────────────
function Phone({ children, dark = false, label }) {
  return (
    <div data-screen-label={label} style={{
      width: 360, height: 760, borderRadius: 40,
      background: '#0b0b12',
      padding: 8,
      boxShadow: '0 30px 80px rgba(26,26,46,0.22), 0 8px 24px rgba(26,26,46,0.12)',
      flexShrink: 0,
      fontFamily: FONT,
    }}>
      <div style={{
        width: '100%', height: '100%',
        borderRadius: 32, overflow: 'hidden',
        background: dark ? LUMI.primary : LUMI.bg,
        display: 'flex', flexDirection: 'column',
        position: 'relative',
      }}>
        <StatusBar dark={dark} />
        {children}
        <HomeIndicator dark={dark} />
      </div>
    </div>
  );
}

function StatusBar({ dark }) {
  const c = dark ? '#fff' : LUMI.primary;
  return (
    <div style={{
      height: 34, display: 'flex', alignItems: 'center',
      justifyContent: 'space-between',
      padding: '6px 22px 0', fontFamily: FONT,
      fontSize: 13, fontWeight: 600, color: c, flexShrink: 0,
    }}>
      <span>9:41</span>
      <div style={{ position: 'absolute', left: '50%', top: 10, transform: 'translateX(-50%)',
        width: 22, height: 22, borderRadius: '50%', background: '#000' }}/>
      <div style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
        <svg width="14" height="10" viewBox="0 0 14 10"><rect x="0" y="6" width="2" height="4" rx="0.5" fill={c}/><rect x="4" y="4" width="2" height="6" rx="0.5" fill={c}/><rect x="8" y="2" width="2" height="8" rx="0.5" fill={c}/><rect x="12" y="0" width="2" height="10" rx="0.5" fill={c}/></svg>
        <svg width="15" height="11" viewBox="0 0 15 11"><path d="M7.5 3.5c1.8 0 3.4.6 4.7 1.6l1.1-1.3A9 9 0 0 0 1.7 3.8l1.1 1.3c1.3-1 2.9-1.6 4.7-1.6zM7.5 7a3.4 3.4 0 0 1 2.3.9l1.1-1.3a5.2 5.2 0 0 0-6.8 0l1.1 1.3A3.4 3.4 0 0 1 7.5 7z" fill={c}/><circle cx="7.5" cy="9.5" r="1" fill={c}/></svg>
        <div style={{ width: 22, height: 10, border: `1.2px solid ${c}`, borderRadius: 3, position: 'relative', opacity: 0.9 }}>
          <div style={{ position: 'absolute', inset: 1.5, width: '78%', background: c, borderRadius: 1 }}/>
          <div style={{ position: 'absolute', right: -3, top: 2.5, width: 2, height: 4, background: c, borderRadius: 1 }}/>
        </div>
      </div>
    </div>
  );
}

function HomeIndicator({ dark }) {
  return (
    <div style={{ height: 22, display:'flex', alignItems:'center', justifyContent:'center', flexShrink: 0 }}>
      <div style={{ width: 120, height: 4, borderRadius: 2, background: dark? 'rgba(255,255,255,0.55)' : 'rgba(26,26,46,0.45)' }}/>
    </div>
  );
}

// ─── Icons ──────────────────────────────────────────────────
const Icon = {
  mic: (s = 20, c = 'currentColor') => (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none"><rect x="9" y="3" width="6" height="12" rx="3" fill={c}/><path d="M5 11a7 7 0 0 0 14 0M12 18v3" stroke={c} strokeWidth="2" strokeLinecap="round"/></svg>
  ),
  send: (s = 20, c = 'currentColor') => (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none"><path d="M3 12L21 4l-4 18-4-8-10-2z" stroke={c} strokeWidth="2" strokeLinejoin="round" fill="none"/></svg>
  ),
  plus: (s = 20, c = 'currentColor') => (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none"><path d="M12 5v14M5 12h14" stroke={c} strokeWidth="2" strokeLinecap="round"/></svg>
  ),
  settings: (s = 22, c = 'currentColor') => (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none"><circle cx="12" cy="12" r="3" stroke={c} strokeWidth="2"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3h0a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8v0a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z" stroke={c} strokeWidth="1.5"/></svg>
  ),
  back: (s = 22, c = 'currentColor') => (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none"><path d="M15 6l-6 6 6 6" stroke={c} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>
  ),
  chevR: (s = 18, c = 'currentColor') => (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none"><path d="M9 6l6 6-6 6" stroke={c} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/></svg>
  ),
  camera: (s = 16, c = 'currentColor') => (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none"><rect x="3" y="7" width="18" height="13" rx="2" stroke={c} strokeWidth="2"/><path d="M8 7l1.5-2h5L16 7" stroke={c} strokeWidth="2"/><circle cx="12" cy="13" r="3.5" stroke={c} strokeWidth="2"/></svg>
  ),
  close: (s = 20, c = 'currentColor') => (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none"><path d="M6 6l12 12M18 6L6 18" stroke={c} strokeWidth="2" strokeLinecap="round"/></svg>
  ),
  check: (s = 18, c = 'currentColor') => (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none"><path d="M5 12l5 5L20 7" stroke={c} strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"/></svg>
  ),
  dot: (c) => <span style={{ display:'inline-block', width: 8, height: 8, borderRadius: '50%', background: c }}/>,
};

// ─── Chat bubble ────────────────────────────────────────────
function Bubble({ text, user = false, time, tag }) {
  return (
    <div style={{ display:'flex', justifyContent: user ? 'flex-end' : 'flex-start', marginBottom: 8 }}>
      <div style={{
        maxWidth: '78%',
        background: user ? LUMI.bubbleUser : LUMI.bubbleLumi,
        color: user ? '#fff' : LUMI.textPrimary,
        borderRadius: user ? '18px 18px 4px 18px' : '18px 18px 18px 4px',
        padding: '10px 14px',
        fontSize: 15, lineHeight: 1.35,
        boxShadow: user ? '0 2px 8px rgba(124,77,255,0.25)' : '0 1px 2px rgba(26,26,46,0.06)',
        border: user ? 'none' : `1px solid ${LUMI.line}`,
      }}>
        <div>{text}</div>
        {(time || tag) && (
          <div style={{
            fontSize: 10, marginTop: 4, display: 'flex', gap: 6, justifyContent:'flex-end',
            color: user ? 'rgba(255,255,255,0.72)' : LUMI.textHint,
            fontFeatureSettings: '"tnum"',
          }}>
            {tag && <span style={{ textTransform:'uppercase', letterSpacing: 0.5, fontWeight: 600 }}>{tag}</span>}
            {time && <span>{time}</span>}
          </div>
        )}
      </div>
    </div>
  );
}

// ─── Chip ───────────────────────────────────────────────────
function Chip({ children, kind = 'default' }) {
  const styles = {
    default: { bg: '#fff', fg: LUMI.textSecondary, bd: LUMI.line, dot: null },
    ok:      { bg: '#fff', fg: LUMI.ok, bd: '#CDE8DA', dot: LUMI.ok },
    off:     { bg: '#fff', fg: LUMI.textHint, bd: LUMI.line, dot: LUMI.textHint },
    accent:  { bg: LUMI.accentSoft, fg: LUMI.accent, bd: '#D9C8FF', dot: null },
  }[kind];
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 6,
      padding: '4px 10px', borderRadius: 99,
      background: styles.bg, color: styles.fg,
      border: `1px solid ${styles.bd}`,
      fontSize: 12, fontWeight: 600, fontFamily: FONT,
      whiteSpace: 'nowrap',
    }}>
      {styles.dot && <span style={{ width: 6, height: 6, borderRadius: '50%', background: styles.dot }}/>}
      {children}
    </span>
  );
}

// ─── Round avatar with Lumi glyph ──────────────────────────
function LumiGlyph({ size = 28 }) {
  return (
    <div style={{
      width: size, height: size, borderRadius: '50%',
      background: `conic-gradient(from 180deg, ${LUMI.accent}, ${LUMI.accentLight}, ${LUMI.accent})`,
      display: 'flex', alignItems:'center', justifyContent:'center',
      boxShadow: 'inset 0 0 0 2px rgba(255,255,255,0.6)',
      flexShrink: 0,
    }}>
      <div style={{ width: size * 0.45, height: size * 0.45, borderRadius: '50%', background: '#fff' }}/>
    </div>
  );
}

Object.assign(window, { LUMI, FONT, Phone, StatusBar, HomeIndicator, Icon, Bubble, Chip, LumiGlyph });
