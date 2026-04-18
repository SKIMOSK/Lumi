// ─── Screen: Classic Chat (Home) ────────────────────────────
function ScreenChat({ onOpenSettings, onOpenPair, onOpenVoice, onOpenAction }) {
  return (
    <Phone label="01 Chat">
      {/* App bar */}
      <div style={{
        padding: '8px 14px 10px', display:'flex', alignItems:'center', gap: 10,
        background: LUMI.primary, color: '#fff',
        borderBottomLeftRadius: 18, borderBottomRightRadius: 18,
      }}>
        <LumiGlyph size={32} />
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 17, fontWeight: 700, letterSpacing: -0.2 }}>Lumi</div>
          <div style={{ fontSize: 11, color: 'rgba(255,255,255,0.7)', marginTop: 1 }}>ascult romana · online</div>
        </div>
        <button onClick={onOpenPair} style={iconBtnDark}>{Icon.camera(18, '#fff')}</button>
        <button onClick={onOpenSettings} style={iconBtnDark}>{Icon.settings(20, '#fff')}</button>
      </div>

      {/* Status chips row */}
      <div style={{
        padding: '10px 14px', display:'flex', gap: 8, background: LUMI.bg,
        borderBottom: `1px solid ${LUMI.line}`, flexWrap: 'wrap',
      }}>
        <button onClick={onOpenPair} style={{ all:'unset', cursor:'pointer' }}>
          <Chip kind="ok">Lumi Dev-01 · 78%</Chip>
        </button>
        <Chip kind="ok">Notificari ON</Chip>
        <Chip kind="accent">Mod Actiune</Chip>
      </div>

      {/* Conversation */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '14px 14px 4px', background: LUMI.bg }}>
        <div style={{
          textAlign: 'center', fontSize: 11, color: LUMI.textHint,
          margin: '0 0 10px', textTransform: 'uppercase', letterSpacing: 0.8,
        }}>Azi · 09:38</div>
        <Bubble text="Buna Andrei! Cu ce te ajut?" time="09:38" tag="fast" />
        <Bubble user text="Cat e ceasul la Tokyo?" time="09:40" />
        <Bubble text="E 15:41 acum — cu 6 ore inaintea ta." time="09:40" tag="fast" />
        <Bubble user text="Seteaza un timer de 5 min pentru paste" time="09:41" />
        <Bubble text={<>Gata, timer „Paste" pornit — <b>5:00</b>.</>} time="09:41" tag="fast" />
        <Bubble user text="Trimite-i mamei ca vin acasa pe la 18:30" time="09:42" />
        <div onClick={onOpenAction} style={{ cursor: 'pointer' }}>
          <Bubble
            text={<>Pregatesc mesajul pentru Mama — <span style={{ textDecoration:'underline', color: LUMI.accent }}>vezi preview</span>.</>}
            time="09:42" tag="expert" />
        </div>
      </div>

      {/* Input bar */}
      <div style={{
        padding: '8px 10px 10px', background: '#fff',
        borderTop: `1px solid ${LUMI.line}`,
        display: 'flex', alignItems: 'center', gap: 8,
      }}>
        <button style={iconBtnGhost}>{Icon.plus(20, LUMI.textSecondary)}</button>
        <div style={{
          flex: 1, background: LUMI.bg, borderRadius: 22,
          padding: '10px 14px', fontSize: 14, color: LUMI.textHint,
          border: `1px solid ${LUMI.line}`,
        }}>Scrie sau vorbeste…</div>
        <button onClick={onOpenVoice} style={{
          width: 44, height: 44, borderRadius: 22, border: 'none',
          background: LUMI.accent, color: '#fff', cursor: 'pointer',
          display:'flex', alignItems:'center', justifyContent:'center',
          boxShadow: '0 4px 12px rgba(124,77,255,0.4)',
        }}>{Icon.mic(22, '#fff')}</button>
      </div>
    </Phone>
  );
}

const iconBtnDark = {
  width: 36, height: 36, borderRadius: 18, border: 'none',
  background: 'rgba(255,255,255,0.12)', cursor: 'pointer',
  display:'flex', alignItems:'center', justifyContent:'center',
};
const iconBtnGhost = {
  width: 40, height: 40, borderRadius: 20, border: 'none',
  background: 'transparent', cursor: 'pointer',
  display:'flex', alignItems:'center', justifyContent:'center',
};

// ─── Screen: Voice (Pulsing Orb) ────────────────────────────
function ScreenVoice({ onClose }) {
  const [t, setT] = React.useState(0);
  React.useEffect(() => {
    let raf;
    const loop = () => { setT(p => p + 1); raf = requestAnimationFrame(loop); };
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
  }, []);
  const pulse = 1 + Math.sin(t * 0.06) * 0.04;

  return (
    <Phone label="02 Voice">
      <div style={{
        flex: 1, display:'flex', flexDirection:'column',
        background: `radial-gradient(140% 80% at 50% 35%, ${LUMI.accentSoft} 0%, ${LUMI.bg} 60%)`,
      }}>
        {/* Header */}
        <div style={{ padding: '14px 16px', display:'flex', alignItems:'center', gap: 10 }}>
          <button onClick={onClose} style={{ ...iconBtnGhost, background: '#fff', border: `1px solid ${LUMI.line}` }}>
            {Icon.close(18, LUMI.textPrimary)}
          </button>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 11, fontWeight: 700, letterSpacing: 1.2, color: LUMI.accent, textTransform: 'uppercase' }}>● Ascult</div>
            <div style={{ fontSize: 12, color: LUMI.textSecondary, marginTop: 1 }}>Romana · prin telefon</div>
          </div>
          <Chip kind="ok">Lumi Dev-01</Chip>
        </div>

        {/* Orb */}
        <div style={{ flex: 1, display:'flex', flexDirection:'column', alignItems:'center', justifyContent:'center', padding: '0 28px' }}>
          <div style={{ position: 'relative', width: 200, height: 200, display:'flex', alignItems:'center', justifyContent:'center' }}>
            {/* outer rings */}
            <div style={{
              position:'absolute', inset: -20, borderRadius: '50%',
              border: `1px solid ${LUMI.accentLight}`, opacity: 0.35,
              transform: `scale(${pulse + 0.05})`, transition: 'transform 0.1s',
            }}/>
            <div style={{
              position:'absolute', inset: -40, borderRadius: '50%',
              border: `1px solid ${LUMI.accentLight}`, opacity: 0.18,
              transform: `scale(${pulse + 0.02})`, transition: 'transform 0.1s',
            }}/>
            <div style={{
              width: 180, height: 180, borderRadius: '50%',
              background: `radial-gradient(circle at 35% 30%, ${LUMI.accentLight}, ${LUMI.accent} 55%, ${LUMI.primary} 110%)`,
              boxShadow: `0 20px 60px rgba(124,77,255,0.45), inset 0 -20px 40px rgba(26,26,46,0.35)`,
              transform: `scale(${pulse})`, transition: 'transform 0.1s',
            }}/>
          </div>

          <div style={{ fontSize: 24, fontWeight: 700, color: LUMI.textPrimary, textAlign:'center', marginTop: 28, letterSpacing: -0.3 }}>
            „trimite mamei ca vin
            <span style={{ color: LUMI.accent }}> acasa</span>…"
          </div>
          <div style={{ fontSize: 13, color: LUMI.textSecondary, marginTop: 6 }}>
            transcrie in timp real · 0:03
          </div>

          {/* Waveform */}
          <div style={{ display:'flex', gap: 4, alignItems:'flex-end', height: 48, marginTop: 24 }}>
            {[14, 28, 38, 22, 44, 30, 18, 36, 26, 14, 32, 20, 40, 28, 16].map((h, i) => {
              const dh = h + Math.sin((t + i * 8) * 0.08) * 6;
              return <div key={i} style={{
                width: 4, height: Math.max(6, dh), borderRadius: 2,
                background: i % 4 === 0 ? LUMI.accent : LUMI.primary,
              }}/>;
            })}
          </div>
        </div>

        {/* Actions */}
        <div style={{ padding: '10px 20px 16px', display:'flex', gap: 10 }}>
          <button onClick={onClose} style={{
            flex: 1, height: 52, borderRadius: 26, border: `1.5px solid ${LUMI.line}`,
            background: '#fff', color: LUMI.textPrimary,
            fontSize: 15, fontWeight: 600, cursor: 'pointer',
          }}>Anuleaza</button>
          <button onClick={onClose} style={{
            flex: 2, height: 52, borderRadius: 26, border: 'none',
            background: LUMI.accent, color: '#fff',
            fontSize: 15, fontWeight: 700, cursor: 'pointer',
            boxShadow: '0 8px 20px rgba(124,77,255,0.4)',
          }}>Trimite la Lumi</button>
        </div>
      </div>
    </Phone>
  );
}

// ─── Screen: Pair (3-step wizard) ───────────────────────────
function ScreenPair({ onClose }) {
  const [step, setStep] = React.useState(2);
  const total = 3;

  const steps = [
    { title: 'Scoate Lumi din cutie', body: 'Il gasesti in punga cu spuma. Incarca-l 5 minute daca LED-ul nu se aprinde.' },
    { title: 'Porneste Lumi', body: 'Tine apasat butonul lateral 3 secunde pana LED-ul devine albastru.' },
    { title: 'Apropie-l de telefon', body: 'Tinem-l la mai putin de 30 cm si asteptam sa-l gaseasca.' },
  ];
  const cur = steps[step - 1];

  return (
    <Phone label="03 Pair">
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', background: LUMI.bg }}>
        {/* top */}
        <div style={{ padding: '14px 14px 8px', display:'flex', alignItems:'center' }}>
          <button onClick={onClose} style={iconBtnGhost}>{Icon.close(20, LUMI.textPrimary)}</button>
          <div style={{ flex: 1, textAlign:'center', fontSize: 12, fontWeight: 600, color: LUMI.textSecondary, letterSpacing: 0.6 }}>
            PAS {step} DIN {total}
          </div>
          <div style={{ width: 40 }}/>
        </div>

        {/* Step dots */}
        <div style={{ display:'flex', justifyContent:'center', gap: 6, padding: '4px 0 20px' }}>
          {Array.from({ length: total }).map((_, i) => (
            <div key={i} style={{
              width: i + 1 === step ? 24 : 8, height: 8, borderRadius: 4,
              background: i + 1 <= step ? LUMI.accent : LUMI.line,
              transition: 'all 0.2s',
            }}/>
          ))}
        </div>

        {/* Device illustration */}
        <div style={{ flex: 1, display:'flex', alignItems:'center', justifyContent:'center', padding: '0 30px' }}>
          <div style={{ position: 'relative' }}>
            {/* Ripples */}
            <div style={{
              position:'absolute', inset: -30, borderRadius: '50%',
              border: `2px solid ${LUMI.accentLight}`, opacity: 0.3, animation: 'lumiRipple 2s ease-out infinite',
            }}/>
            <div style={{
              position:'absolute', inset: -55, borderRadius: '50%',
              border: `2px solid ${LUMI.accentLight}`, opacity: 0.2, animation: 'lumiRipple 2s ease-out 0.5s infinite',
            }}/>
            {/* Device puck */}
            <div style={{
              width: 200, height: 200, borderRadius: 40,
              background: `linear-gradient(135deg, ${LUMI.primaryDark} 0%, ${LUMI.primary} 100%)`,
              boxShadow: '0 30px 60px rgba(26,26,46,0.25)',
              display: 'flex', alignItems:'center', justifyContent:'center',
              position: 'relative',
            }}>
              <div style={{
                width: 72, height: 72, borderRadius: '50%',
                background: `radial-gradient(circle at 30% 30%, ${LUMI.accentLight}, ${LUMI.accent} 55%, #5B3BE0 110%)`,
                boxShadow: `0 0 36px ${LUMI.accent}, inset 0 -8px 20px rgba(0,0,0,0.3)`,
              }}/>
              <div style={{
                position: 'absolute', bottom: 16, left: '50%', transform: 'translateX(-50%)',
                fontSize: 10, fontWeight: 700, letterSpacing: 2.5, color: 'rgba(255,255,255,0.55)',
              }}>LUMI</div>
            </div>
          </div>
        </div>

        {/* Copy */}
        <div style={{ padding: '0 24px 16px', textAlign:'center' }}>
          <div style={{ fontSize: 24, fontWeight: 700, color: LUMI.textPrimary, letterSpacing: -0.3 }}>{cur.title}</div>
          <div style={{ fontSize: 14, color: LUMI.textSecondary, marginTop: 8, lineHeight: 1.5 }}>{cur.body}</div>
        </div>

        {/* CTA */}
        <div style={{ padding: '0 16px 16px', display:'flex', gap: 10 }}>
          {step > 1 && (
            <button onClick={() => setStep(step - 1)} style={{
              flex: 1, height: 52, borderRadius: 26, border: `1.5px solid ${LUMI.line}`,
              background: '#fff', color: LUMI.textPrimary,
              fontSize: 15, fontWeight: 600, cursor: 'pointer',
            }}>Inapoi</button>
          )}
          <button
            onClick={() => step < total ? setStep(step + 1) : onClose()}
            style={{
              flex: 2, height: 52, borderRadius: 26, border: 'none',
              background: LUMI.accent, color: '#fff',
              fontSize: 15, fontWeight: 700, cursor: 'pointer',
              boxShadow: '0 8px 20px rgba(124,77,255,0.4)',
            }}>
            {step < total ? 'Continua →' : 'Gata!'}
          </button>
        </div>
      </div>
    </Phone>
  );
}

// ─── Screen: Settings (Grouped Cards) ───────────────────────
function ScreenSettings({ onClose }) {
  return (
    <Phone label="04 Settings">
      <div style={{ flex: 1, display:'flex', flexDirection:'column', background: LUMI.bg }}>
        {/* App bar */}
        <div style={{
          padding: '12px 14px', display:'flex', alignItems:'center', gap: 10,
          background: '#fff', borderBottom: `1px solid ${LUMI.line}`,
        }}>
          <button onClick={onClose} style={iconBtnGhost}>{Icon.back(22, LUMI.textPrimary)}</button>
          <div style={{ fontSize: 18, fontWeight: 700, color: LUMI.textPrimary, letterSpacing: -0.2 }}>Setari</div>
        </div>

        <div style={{ flex: 1, overflowY:'auto', padding: '16px 14px 24px' }}>
          {/* Creier */}
          <SettingsCard
            icon="🧠"
            title="Creier"
            subtitle="Gemini 2.5 Flash + Pro"
            meta="cheia API setata"
            tone="default"
          />
          {/* Voce */}
          <SettingsCard
            icon="🎙"
            title="Voce"
            subtitle="Romana · 5 tururi memorie"
            meta="STT activ"
            tone="default"
          />
          {/* Actiuni — emphasized */}
          <SettingsCard
            icon="⚡"
            title="Actiuni"
            subtitle={<><b style={{ color: LUMI.accent }}>Pornit</b> · cere confirmare</>}
            meta="timere, mesaje, apeluri"
            tone="accent"
          />
          {/* Device */}
          <SettingsCard
            icon="◐"
            title="Lumi Dev-01"
            subtitle="Conectat · 78% baterie"
            meta="−54 dBm · excelent"
            tone="default"
          />
          {/* Permissions */}
          <SettingsCard
            icon="🔐"
            title="Permisiuni"
            subtitle="Notificari, Contacte, Microfon"
            meta="toate acordate"
            tone="default"
          />
          {/* Danger */}
          <div style={{ marginTop: 16, textAlign:'center' }}>
            <button style={{
              background: 'transparent', border: 'none', cursor:'pointer',
              fontSize: 13, fontWeight: 600, color: LUMI.warn, padding: '12px 16px',
            }}>Sterge conversatia si memoria</button>
          </div>
        </div>
      </div>
    </Phone>
  );
}

function SettingsCard({ icon, title, subtitle, meta, tone }) {
  const accent = tone === 'accent';
  return (
    <div style={{
      background: accent ? LUMI.accentSoft : '#fff',
      border: `1px solid ${accent ? '#D9C8FF' : LUMI.line}`,
      borderRadius: 16, padding: '14px 14px',
      marginBottom: 10,
      display:'flex', alignItems:'center', gap: 12,
      boxShadow: accent ? '0 2px 8px rgba(124,77,255,0.1)' : '0 1px 2px rgba(26,26,46,0.04)',
      cursor: 'pointer',
    }}>
      <div style={{
        width: 44, height: 44, borderRadius: 14,
        background: accent ? LUMI.accent : LUMI.bg,
        display:'flex', alignItems:'center', justifyContent:'center',
        fontSize: 22, flexShrink: 0,
        filter: accent ? 'grayscale(0)' : 'none',
      }}>{icon}</div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 16, fontWeight: 700, color: LUMI.textPrimary, letterSpacing: -0.2 }}>{title}</div>
        <div style={{ fontSize: 13, color: LUMI.textSecondary, marginTop: 2 }}>{subtitle}</div>
        <div style={{ fontSize: 11, color: LUMI.textHint, marginTop: 4, textTransform:'uppercase', letterSpacing: 0.6, fontWeight: 600 }}>{meta}</div>
      </div>
      <div>{Icon.chevR(20, LUMI.textHint)}</div>
    </div>
  );
}

// ─── Screen: Action confirmation (Bottom sheet) ─────────────
function ScreenAction({ onClose, onConfirm }) {
  const [countdown, setCountdown] = React.useState(5);
  React.useEffect(() => {
    if (countdown <= 0) { onClose(); return; }
    const id = setTimeout(() => setCountdown(c => c - 1), 1000);
    return () => clearTimeout(id);
  }, [countdown]);

  return (
    <Phone label="05 Action">
      {/* Chat behind */}
      <div style={{
        padding: '8px 14px 10px', display:'flex', alignItems:'center', gap: 10,
        background: LUMI.primary, color: '#fff',
        borderBottomLeftRadius: 18, borderBottomRightRadius: 18,
      }}>
        <LumiGlyph size={32} />
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 17, fontWeight: 700 }}>Lumi</div>
          <div style={{ fontSize: 11, color: 'rgba(255,255,255,0.7)' }}>pregateste actiunea…</div>
        </div>
      </div>
      <div style={{ flex: 1, padding: '14px 14px 4px', background: LUMI.bg, opacity: 0.35, pointerEvents:'none' }}>
        <Bubble user text="Trimite-i mamei ca vin acasa pe la 18:30" time="09:42" />
        <Bubble text="Pregatesc mesajul pentru Mama." time="09:42" tag="expert" />
      </div>

      {/* Scrim */}
      <div style={{
        position:'absolute', inset: 0, background: 'rgba(26,26,46,0.35)', zIndex: 1,
        borderRadius: 32,
      }}/>

      {/* Sheet */}
      <div style={{
        position: 'absolute', left: 0, right: 0, bottom: 0, zIndex: 2,
        background: '#fff',
        borderTopLeftRadius: 28, borderTopRightRadius: 28,
        padding: '10px 18px 18px',
        boxShadow: '0 -8px 30px rgba(26,26,46,0.15)',
      }}>
        <div style={{ width: 40, height: 4, background: LUMI.line, borderRadius: 2, margin: '0 auto 14px' }}/>

        <div style={{ display:'flex', alignItems:'center', gap: 12, marginBottom: 14 }}>
          <div style={{
            width: 44, height: 44, borderRadius: 14, background: '#25D366',
            display:'flex', alignItems:'center', justifyContent:'center', fontSize: 22, color: '#fff',
          }}>✉</div>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 11, fontWeight: 700, color: LUMI.accent, letterSpacing: 0.8, textTransform:'uppercase' }}>Trimite WhatsApp</div>
            <div style={{ fontSize: 17, fontWeight: 700, color: LUMI.textPrimary, letterSpacing: -0.2 }}>Mama</div>
            <div style={{ fontSize: 12, color: LUMI.textSecondary }}>+40 ••• ••• 234</div>
          </div>
        </div>

        <div style={{
          background: LUMI.accentSoft, border: `1px solid #D9C8FF`,
          borderRadius: 14, padding: '12px 14px', fontSize: 15, color: LUMI.textPrimary,
          marginBottom: 14, lineHeight: 1.4,
        }}>
          „Salut mama, vin acasa pe la 18:30."
        </div>

        <div style={{ display:'flex', gap: 10 }}>
          <button onClick={onClose} style={{
            flex: 1, height: 48, borderRadius: 24, border: `1.5px solid ${LUMI.line}`,
            background: '#fff', color: LUMI.textPrimary,
            fontSize: 14, fontWeight: 600, cursor: 'pointer',
          }}>Nu trimite</button>
          <button onClick={onConfirm} style={{
            flex: 2, height: 48, borderRadius: 24, border: 'none',
            background: LUMI.accent, color: '#fff',
            fontSize: 14, fontWeight: 700, cursor: 'pointer',
            boxShadow: '0 6px 14px rgba(124,77,255,0.35)',
            display:'flex', alignItems:'center', justifyContent:'center', gap: 8,
          }}>{Icon.check(18, '#fff')} Trimite</button>
        </div>
        <div style={{
          textAlign:'center', marginTop: 10, fontSize: 12, color: LUMI.textHint,
          fontFeatureSettings: '"tnum"',
        }}>
          auto-anulare in {countdown}s
        </div>
      </div>
    </Phone>
  );
}

Object.assign(window, { ScreenChat, ScreenVoice, ScreenPair, ScreenSettings, ScreenAction });
