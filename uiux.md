6. UI/UX design specification

Design intent: a calm, instrument-grade tool — one radial gauge as the focal point, supporting telemetry around it. Precise, not gamified. The bar: it should feel better-made than AccuBattery/Ampere, not busier.

6.1 References (anchors, not to copy)

Material 3 (Expressive) as the base system; Apple Watch Activity rings for gauge-motion feel; modern EV-charger dashboards for the radial "power flow" metaphor; fintech-grade data-viz restraint for tile density. One hero metaphor (the gauge); everything else recedes.

6.2 Design tokens


Color: M3 dynamic color where available; true-black surfaces for AMOLED. Charge-speed ramp — slow (teal) · normal (green) · fast (amber) · turbo (violet) — but never hue alone: always paired with a text label + icon, and verified colour-blind-safe. Health semantics: healthy/green, degraded/amber, over-temp/red, each with label + icon.
Type: M3 type scale. Gauge primary value in display size with tabular (monospaced) figures so live digits don't jitter width; units in label size; tile values title-medium tabular, tile labels label-small.
Spacing/shape: 4dp base grid, 16dp screen margins, 8/12/16 rhythm; large rounded cards (16–24dp); tonal surfaces over shadows in dark. Material Symbols (rounded), one weight throughout.


6.3 Motion


Gauge: spring-driven (animateFloatAsState, low bounce); ~250 ms on each live sample so motion reads as continuous, ~500 ms M3-emphasized on big state changes.
Charging flow: upward gradient/particle stream inside the ring while plugged; loop ~1.5–2 s; intensity scales with watts; paused on battery.
Live numbers: rolling-digit or cross-fade, tabular width preserved.
Reduced motion: honour the system setting — drop the flow loop and digit roll, keep instant value updates (accessibility and battery).
Define easing/duration as tokens and reuse everywhere.


6.4 Mobile-first ergonomics


Bottom navigation (thumb-reachable); interactive controls in the lower two-thirds, gauge + key stat upper-middle.
Touch targets ≥ 48 dp; edge-to-edge with inset/notch handling; gesture-nav friendly.
Landscape must not break (gauge left, tiles right at minimum).


6.5 Accessibility


WCAG AA contrast (≥ 4.5:1 text); meaning never by colour alone (speed/health carry label + icon).
Full TalkBack: the gauge announces a composed summary ("Charging, 18 watts, 72 percent, 42 minutes to full"); tiles have contentDescriptions; live values use a throttled polite live region (not every sample).
Respect dynamic type / font scaling — layouts reflow, no clipping; respect high-contrast and reduced-motion.
Logical focus order.


6.6 Performance budgets


Cold start < ~800 ms to first meaningful frame; warm < 300 ms.
Hold 60 fps (≤ 16 ms/frame). Render the flow animation in a single Canvas/shader-driven pass, not many recomposing composables.
Telemetry on one background-dispatcher coroutine; UI collects a conflated Flow (~1–2 Hz for display even if sampling faster); nothing on the main thread; lifecycle-aware collection (no leaks); bounded ring buffer for the sparkline.
The app's own draw stays minimal: sample 2–5 s charging / back off idle, no wakelocks, animations stop when backgrounded or screen-off.


7. Screens (hierarchy + states)

Bottom nav across all four. Every surface defines its loading (skeleton), empty (friendly copy), unavailable (dimmed + "—", reason on tap), and calibrating (chip) states.


Dashboard (primary). Hierarchy: (1) the radial gauge — charging W when plugged, battery % on battery — seen first; (2) one prominent secondary line beneath ("Fast · 18.4 W · 42 min to full"); (3) compact telemetry tiles (current, voltage, battery temp, mAh left, thermal load); (4) current sparkline (~last 30 s). Charging vs on-battery layouts differ (the gauge metric swaps). Calibrating states render here per §4/§5.
Health. Capacity card (measured full mAh vs design, prominent); health-% ring; wear-over-time chart; design-capacity entry card (friendly one-time empty state if reflection is blocked); "measuring…" state until enough sessions.
History. Charge-session cards: start→end % arc, mAh added, wear, duration, avg/peak W, speed chip; tap → session detail; empty-state copy before any sessions.
Settings. Grouped M3 lists: Charging (alarm %, speed thresholds) · Display (units, theme incl. AMOLED, dynamic colour) · Notifications (live notification, alarm) · Data (design capacity, reset) · About.