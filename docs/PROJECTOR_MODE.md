# Projector Mode & the Now Playing screen — a porting guide

How PhairPlay's audio-reactive "projector mode" and its full-screen Now Playing card
work, written so another app can implement the same thing from scratch. Everything
here is running code, and most of the constants exist because a plausible-looking
alternative was tried on a real TV and looked wrong.

Nothing here is AirPlay-specific. All you need is **decoded PCM before it reaches the
speakers**, plus the artwork bitmap and the track metadata.

---

## 1. Projector mode: three orbs on true black

The problem it solves: a projector has no bezel. A normal "album art background"
fills a rectangle, and the edge of that rectangle is a visible line of light on the
wall. Fading the edges does not fix it — the eye still reads a lit rectangle with
dark corners.

The fix is to have **no edge at all**: light sources whose falloff reaches true black
well inside the frame. The light simply runs out, the way a real glow does.

### Three orbs, one per frequency band

| Orb | Band | Why it moves the way it does |
|-----|------|------------------------------|
| 0 | Bass | Slow. Bass *is* slow. |
| 1 | Vocals (centre channel) | Medium. Tracks the singer. |
| 2 | Treble | Fast. A hi-hat is over in 30 ms. |

Each orb carries its own palette colour, orbits on its own slow ellipse, and swells
on its own band.

### Drawing one orb

Two passes, and the split matters:

1. **Halo** — a large radial gradient, **constant alpha**.
2. **Core** — a small radial gradient at `0.34 ×` the halo radius, alpha rides the beat.

> **The single most important rule: the halo's alpha must never follow the beat.**
> The halo covers most of the frame, so riding the beat lifts the *whole picture* a
> shade on every kick. On a projector that reads as "the black gets lighter and darker
> with the music", which is the one thing this mode must never do. The beat shows as
> **size** (both passes) and as **core brightness** (a small fraction of the area, so
> it can punch hard without measurably lifting the black anywhere else).

Blend both with **SCREEN** inside a `saveLayer`, so where two orbs overlap their light
*adds* like two real glows instead of one occluding the other.

**Falloff.** A linear fade to transparent leaves a visible ring where the gradient
terminates. Use a four-stop ramp with a long near-zero tail:

```
stops  = [0.00, 0.34, 0.62, 0.85, 1.00]
alphas = [0xFF, 0xA8, 0x42, 0x0A, 0x00]
```

The long tail is what preserves the black; the middle stops are what make it read as a
glow rather than a dot. An earlier, tighter ramp put ~84 % of the falloff inside 55 %
of the radius and a 259 px orb looked like a bright dot on 1080p.

The core ramp whitens its centre (`lighten(tint, 0.55)`), because a real glow's hottest
point desaturates toward white. Straight tint at full alpha looks like flat paint.

**Performance.** Build each `RadialGradient` at unit scale (centre `0,0`, radius `1`)
once, cache it, and per frame apply a `Matrix` of `setScale(r, r)` +
`postTranslate(cx, cy)` via `setLocalMatrix`. Rebuild only when the orb's tint changes,
quantised (`color and 0xF8F8F8`). Allocating gradients per frame is a real stutter
source on low-end TV hardware.

### Motion

Drive x with `cos` and y with `sin`, from **different** long-period animators per orb,
each on its own phase offset:

```
cx = w*ORB_X[k] + cos(px*2π + PHASE[k]) * DRIFT_X * w
cy = h*ORB_Y[k] + sin(py*2π + PHASE[k]) * DRIFT_Y * h
```

with three animators at 20 s / 27 s / 34 s (near-prime periods, so the composition
never visibly repeats), `REVERSE`, linear.

Straight-line drift was the first attempt: it reads as *sliding*, and two orbs
approaching along the same axis never really pass through one another. Ellipses let
them wander into each other's halos from varying directions, fuse under SCREEN into one
brighter mass, and drift apart — which is the whole point of the mode.

```
ORB_X    = [0.42, 0.54, 0.66]     ORB_Y    = [0.46, 0.54, 0.48]
DRIFT_X  = 0.09                   DRIFT_Y  = 0.04
PHASE    = [0.0, 2.1, 4.2]
BASE_RADIUS = 0.24 (× short side)  BEAT_SWELL = 0.60
```

### The edge guarantee

Clamp each orb's radius to the distance from its own centre to the nearest side, less a
margin:

```
room = min(cx, cy, w-cx, h-cy) - shortSide*0.05
radius = min(radius, room)
```

A hard geometric bound, so it holds at any aspect ratio and any beat strength. Note the
margin is measured against the **short** side while the tightest gap on a wide screen is
usually on the long one — so choose anchors and drift amplitudes such that the clamp does
not bite at 16:9 at all. It is the safety net for odd window shapes, not the mechanism.

---

## 2. Getting the three bands out of PCM

A filter bank, not an FFT. Three one-pole low-passes are a few multiplies per sample and
the visual difference at this scale is nil.

```
alpha = 1 - exp(-2π * fc / sampleRate)
lp   += alpha * (sample - lp)
```

- **Bass** = `lp160`
- **Treble** = `sample - lp4000`
- **Vocals** — see below

Accumulate the square of each band per block, then `sqrt(sum/count)` for RMS.

### Vocals: mid/side, not "the mid frequencies"

Band-passing 300–3400 Hz gives you the guitars, the snare and the synths as well as the
singer — so that orb just tracked "loud" and looked like the other two.

Lead vocals are almost always **centre-panned**, and most other things are not. So:

```
mid  = (L + R) / 2      // centre-panned content survives
side = (L - R) / 2      // centre-panned content cancels
```

Band-pass **both**, then subtract the energies:

```
vocal = max(0, rms(bandpass(mid)) - rms(bandpass(side))) / 32768
```

What is in the centre and in the vocal range, minus what merely shares that range. The
orb now goes quiet during instrumental breaks even when the music is loud, which is the
observable test that it works.

*(Mono input degrades gracefully: `side` is 0, so this reduces to a plain band-pass.)*

### Normalisation and shaping

Raw RMS is useless directly — quiet tracks never light up, loud ones pin at 1.0. Per
band, keep a decaying peak:

```
peak[b] = max(raw[b], peak[b] * 0.9995)      // per emission, ~30/sec
ratio   = clamp(raw[b] / peak[b], 0, 1)
norm    = clamp((ratio - 0.06) / (1 - 0.06), 0, 1)    // noise gate
shaped  = norm ^ 0.9
level  += (shaped - level) * (shaped > level ? 0.30 : 0.045)   // attack / release
```

> **Level-against-peak has a ceiling problem, and you will hit it.** A band that is
> *steadily* loud — bass on anything four-on-the-floor — sits at its own decaying peak, so
> `ratio ≈ 1` continuously and the orb is pinned bright and motionless. It is lit, it is
> "correct", and it shows you nothing. A port that fixed this normalises against a slow
> per-band **baseline** (~1.5 s EMA) and renders the *rise above* it instead:
>
> ```
> base   += 0.02 * (raw - base)                       // ~1.5s follow, seeded on first window
> excess  = clamp(raw/base - 1, 0, EXCESS_MAX) / EXCESS_MAX
> ```
>
> "At its own average" now means **0**, not 1, so it takes a genuine kick to light up and
> the orb settles between hits. Keep `EXCESS_MAX` generous (~1.1): set it tight and normal
> hits clip flat at 1 and you are back where you started.
>
> **One exception, and it is not optional: vocals.** A sustained note has no swell, so a
> pure swell measure goes dark exactly while someone is singing. The vocal band alone also
> needs an absolute `presence = raw / decayingPeak`, taking `max(swell, presence·0.9)`.
> Bass and treble stay pure-swell or they become a constant glow.

> **The decay constant is the thing that will bite you.** It is applied per emission, so
> its per-second effect is `decay^rate`. At `0.985` and ~100 calls/sec the peak collapses
> to 0.22 of itself every second, chases the signal, and **every band pins at 1.00**. At
> `0.99985` it holds a peak for nearly a minute and everything sits at 0.10–0.30. Both were
> shipped and both looked broken. Compute the per-second figure, don't eyeball the constant.

Asymmetric follow (fast attack, slow release) is deliberate: a glow should arrive with
the hit and fade afterwards. Symmetric smoothing looks like breathing on a timer.

Emit every **33 ms**. Log the three values every 2 s — three numbers that move
independently is the only cheap proof the split works.

---

## 3. A/V sync — where the beat actually goes wrong

**This is the bug most likely to be reproduced, so read it before tuning anything else.**

Band levels are measured when PCM *arrives*, but the audio is heard after it clears your
queue and the platform's output buffer. Show the levels immediately and the orbs lead the
sound by a couple of hundred milliseconds — about half a beat at 160 BPM. Every level is
correct; the visuals are simply early. It reads as "the orbs aren't on the beat."

So hold each measurement by the true latency before displaying it:

```
delay = (framesStillAheadOfThisPCM)/sampleRate + userTrim
```

**Which frames are "still ahead" depends entirely on where you take the measurement**, and
getting that wrong is worth hundreds of milliseconds:

- Measuring where packets **arrive** (before your jitter/reorder queue): your queue depth
  *is* ahead of them, so include it, plus the output latency below.
- Measuring where PCM is **written to the output** (after the queue, which is the natural
  place — you already have the decoded samples in hand): that PCM has left the queue. The
  queue depth belongs to packets you have not measured yet. Include it and you add the
  whole queue as pure lag — 14–40 packets at 352 frames each is 110–320 ms, and it drifts
  as the queue breathes, so the error is not even constant.

where `outputLatency()` is **measured, not assumed** — on Android,
`AudioTrack.getTimestamp()` gives the frame the hardware is playing, so
`framesWritten - ts.framePosition` is what is genuinely pending.

> **Do not add the output buffer's capacity either.** We did, on top of the measured
> latency, and it double-counted: capacity is not fill, and whatever is really in there is
> exactly what `getTimestamp()` already reports. Same symptom as no delay at all, opposite
> sign.

The **same** delay must apply to every consumer of that audio (orbs, full-screen pulse,
anything else) or they react on different beats.

Bluetooth link delay is not exposed by any Android API. That is what a user-facing trim
slider is for.

---

## 4. Tempo (BPM)

Detect onsets on the bass envelope (a rise crossing a threshold), keep the last N
inter-onset intervals in the 200-2000 ms range, and score candidate tempos by
**harmonic agreement**:

```
for candidate in 60..180 step 0.5:
    period = 60000 / candidate
    score  = count of intervals where, for nearest integer m = round(interval/period),
             1 <= m <= 4 and |interval/period - m| / m <= 0.08
```

Best score wins; confidence is `score / intervals`, reported only above 0.5.

The point of scoring against *integer multiples* is that a missed beat produces an
interval of exactly 2 or 3 periods, which still votes for the correct tempo. The earlier
implementation took the **median of folded intervals**, which only rescues clean doubles
and folds missed beats onto unrelated tempos — it never cleared 50 % confidence on
material a human reads instantly. Harmonic scoring gets 75–92 % on the same tracks.

Needs at least 8 intervals before reporting.

### Hysteresis is not optional

A single confidence threshold makes the readout unusable. Real music sits either side of
any fixed bar from window to window, so the meter flicks between a number and blank
several times a minute on a track whose tempo plainly never changes. Nothing is wrong
with the estimate -- the display is being asked a yes/no question every few seconds about
something that is true for the length of a song.

Use two bars: cross **0.50** to acquire a lock, but fall under **0.28** for **six
consecutive** windows to lose it. A real tempo change still drops the lock within a few
seconds; an ambiguous bar does not.

Then **fold octave errors onto the existing lock**. Scoring against integer multiples is
what lets the estimator survive missed beats, but it also means 85 and 170 explain the
same track equally well, and which one wins can flip on nothing more than where the
window was cut. When a fresh estimate lands within ~12% of half, double or quadruple the
current lock, it is the same tempo counted differently -- keep the lock. Anything genuinely
different is near neither multiple and passes through.

Ease adopted values (~12% per update) rather than snapping, so a half-time bar nudges the
readout instead of jerking it.

> **And charge more for moving a lock than for keeping one.** Using the same 0.50 bar to
> *acquire* a tempo and to *replace* one lets the readout ratchet: a device log walked
> 129 → 146 → 152 → 157 on a track whose tempo never changed, every step taken at exactly
> 50 %. Each window nudged the lock a little, and the next window then scored the
> neighbourhood of the nudged value rather than the true tempo. A candidate that folds onto
> the current lock still costs the normal bar; a genuinely different number needs ~0.72, or
> the lock holds.

---

## 5. Palette extraction

Feed the artwork to Android's `Palette` with `maximumColorCount(16)`.

**Use the named swatches *and* `palette.swatches`.** The named ones are *roles*
(vibrant, muted, dark muted…), and on a cover with one dominant colour several roles are
filled by shades of that same colour. A blue-and-red cover produced three blue orbs
because the pool handed to the hue filter contained no red at all. `palette.swatches` is
the full quantised set, where a strong secondary colour appears even when it holds no
named role.

Then **branch on whether the artwork has colour at all**:

```
maxSat = max saturation across the ORIGINAL swatches
monochrome = maxSat < 0.18
```

> **The two looks need different brightness, and this is easy to get backwards.** The
> full-screen field is four screen-sized sources SCREEN-blended, so it washes out if each
> colour is bright; the projector orbs are small on true black, where every bit of alpha is
> light thrown at a wall in a dark room. Dimming the shared palette fixes one and ruins the
> other. Keep the palette as-is and dim per look: a value scale on the field's colours, and
> the *alphas* on the orbs.

**Colour art** — push toward vivid, and clamp value at *both* ends:

```
s = clamp(s * 1.45, 0.55, 1.0)
v = clamp(v, 0.55, 0.92)
```

The floor matters as much as the ceiling: dark swatches were reaching the orbs at
`v=0.09`, which on black is no glow at all — drawn perfectly, entirely invisible. The
ceiling stops a pale swatch washing out text laid over it.

**Monochrome art** — keep it grey and separate the orbs by **brightness**:

```
s = 0
v = 0.45 + v * 0.55          // map, don't clamp: preserve the artwork's own spread
```

> A black-and-white sleeve still carries a few percent of chroma from JPEG subsampling.
> Multiply that residue by 1.45 and floor it at 0.55 and you have invented a confident
> colour that is not in the artwork — then hue-rotating it for the other orbs invents two
> more. Detect this from the **original** saturations; after the boost every case looks
> saturated and the test cannot distinguish them.

**Spreading.** For colour art, pick colours ≥ 40° apart in hue. For monochrome, ≥ 0.16
apart in value. If there are not enough distinct entries, **synthesise** the shortfall by
rotating hue (47° steps) or stepping value down — do **not** cycle the picks you already
have. Cycling is what produced logs reading `51°, 353°, 51°` (orbs 0 and 2 identical) and
`0°, 0°, 0°` (three identical orbs) — indistinguishable from the bug it was meant to fix.

> **Rotating hue is the lesser evil, not a good one.** It buys separation by painting a
> colour the artwork does not contain: on an all-orange cover, 47° steps produce blue and
> green orbs, which is confidently wrong rather than merely dull. A port that hit this
> fills the shortfall with lighter and darker **shades of the accents the art does have**
> (same hue, stepped value/saturation) and reserves hue rotation for nothing at all. An
> orange album gives orange shades. Monochrome art already takes the value path above,
> which is the same idea arrived at from the other direction.

Finally, **reject near-black palettes** (`brightest v < 0.35`) and keep the previous one.
Between-track placeholders and partially-decoded images arrive as valid bitmaps, and
letting them through makes the backdrop lurch to black and back on every track change.

The orbs read palette slots **0, 1, 2** — the three most-separated hues. (Reading slots
0, 2, 4 was the blue-and-red bug: the distinct colours land in the low slots, and the
high ones hold the near-duplicate top-ups.)

Cross-fade over ~1.5 s, and blend each orb from the *old* toward the *new* colour across
that fade. Reading the current palette directly makes the orbs hold the old hue for the
whole fade and then snap.

Log the three final hues on every palette change. When three orbs come out the same
colour, that one line tells you whether extraction failed or drawing did — and that is
the difference between an hour of debugging and a minute.

---

## 5a. The intensity control is a render amplitude, never a pre-clip multiplier

Whatever you call the user-facing sensitivity setting, the obvious implementation is
`level = clamp(level * amp, 0, 1)` and it is wrong in a way that is invisible in code and
obvious on screen.

Band levels already reach 0.9+ on anything with a kick in it. Multiply by anything above 1
and the top of the range flattens against the clamp: every hit above roughly `1/amp`
renders identically. Turning the intensity **up** produces **less** visible movement, and
the highest two settings become indistinguishable from each other.

Two fixes, either is fine:

- **Apply the amplitude to the render, not the level.** Keep levels 0..1 and use them as
  `radius = base * (1 + level*ride*amp)`, `alpha = rest + level*range*amp`, with generous
  caps. The level stays honest and only the drawing gets louder.
- **Make the curve steeper instead of the gain higher.** A soft knee —
  `(1 - e^(-k·x)) / (1 - e^(-k))` — gives the same extra response on quiet detail but
  approaches full scale instead of colliding with it, so a loud passage still has
  somewhere to go. Short-circuit it at `k = 1` so the default setting is the raw level.

---

## 5b. Memory, on a device that has none

A Fire TV runs its low-memory killer hot, and the visuals are not what costs you.

- **Decode cover art at the size you draw it.** Senders push whatever resolution they
  like — 600² is common, Apple goes to 1400². A 1400² ARGB_8888 bitmap is 7.8 MB held for
  a whole track to fill a 340 dp tile. Decode with `inSampleSize` against a target near the
  tile size. `Palette` downsamples internally anyway, so this costs nothing in colour
  quality.
- **Let the cross-fade go when it is finished.** A `TransitionDrawable` holds *both*
  covers for as long as it is the view's drawable. Set it and never replace it and every
  previous cover stays reachable for the whole of the next track — two full-size bitmaps
  live at all times instead of one, permanently. Hand the view the new drawable on its own
  once the fade has played.
- **Cache gradients by key, not by frame.** A `RadialGradient` per blob per frame is four
  native allocations 60 times a second for objects whose parameters changed on none of
  them. Rebuild only when the colour or geometry actually changes.
- **Check what the platform is holding before optimising your own code.** A debug install
  runs `status=run-from-apk` — no AOT compilation, so ART keeps the entire dex in *private
  dirty* memory. On this app that is 37 MB, larger than the Java heap, native heap and
  graphics memory combined. `dumpsys meminfo <pkg>` shows it as `.dex mmap` private dirty,
  and no amount of bitmap tuning touches it; a release build does.

---

## 6. The full-screen Now Playing card

Horizontal row: a 340 dp artwork tile, 64 dp gap, then a text column at `weight=1`,
inside 72 dp padding. Title, artist, album, a composer/year line, a custom progress bar
with elapsed/remaining, and a source pill at the bottom ("Audio from <device>").

### Recent behaviour worth copying

**Artwork hold-over.** When metadata arrives with no artwork, do **not** clear the
existing image immediately — post a 2.5 s delayed clear, and cancel it if a new image
arrives first. Senders routinely push a metadata update a beat before the image, so
clearing eagerly makes the cover flicker out and back on every track change.

**Position from the audio clock, not wall time.** Senders push progress every few
seconds. Extrapolating from the last push drifts by seconds. Instead, derive position
from the timestamp *currently reaching the speakers* (newest arrival − queue − output
buffer). It cannot drift, and it stops by itself during a pause.

**Text-focus darkening.** Rather than a full-screen scrim, darken a radial region
positioned under the text block only. Enough contrast for legibility without muting the
rest of the backdrop. Cache the `RadialGradient` and rebuild only when its geometry
changes.

**Screensaver.** After an idle timeout, fade the backdrop out, dim the card to 32 %, and
pixel-shift it on a fixed cycle for burn-in protection. Have the shift touch **only
translation** — no alpha, no scale — or it fights the dim animator and the card visibly
brightens on every move.

**Layout presets.** Menu cycles six layouts: full size, small centred, and the four
corners. The mini presets show artwork + title + artist only — no pill, progress or
credits — but the bottom-edge progress line stays in all of them.

> Implement mini as a **scale**, not as a smaller layout. The card's text column is
> `weight=1`, so shrinking its layout bounds makes it *reflow*: at half width the artwork
> tile consumes the entire row and the text column is laid out at zero width. Every field is
> visible, correctly styled, and has no space to occupy — which looks exactly like "the
> setting does nothing". Scaling the composed result preserves every proportion.
>
> **Keep the pivot centred and reach the corners by translation.** Pivoting at the target
> corner is the obvious approach and it cannot be animated — pivot is not an animatable
> property, so changing it teleports the view into a new frame of reference and the scale
> animation then runs from the wrong place. With a centred pivot the scaled card is a
> `w·s × h·s` rect in the middle of the frame, and each corner is arithmetic:
> `tx = ±((w − w·s)/2 − margin)`. That interpolates cleanly. Recompute on resize — it is
> in pixels.

### Motion

The card should feel like it has weight. Four rules carry most of it:

**One animator per view.** Android gives a `View` a single `ViewPropertyAnimator`, so
starting a fade and then starting a transform on the same view throws the fade away --
and any code path that cancels before it builds will silently strip properties another
path set. Put everything a view does in one call, and give each view one function that
owns its transform.

**Animate the arrival, not the departure.** On a track change, update the text and then
fade and lift the *new* text in. Animating the old text out requires a snapshot to be
honest about what is on screen, and nobody will notice the difference.

**Move things together.** The cover settling in from 94% and the text lifting 10dp should
share a moment, so the change reads as one event rather than two widgets reacting
separately. A crossfade on its own reads as a slideshow.

**Overshoot, lightly.** A card thrown to a corner that stops dead reads as a jump-cut
however long the duration is; a small settle reads as weight. Keep the tension well under
the platform default -- at Android's `OvershootInterpolator` default of 2.0 a half-screen
card visibly bounces off the edge of the screen. 0.9-1.1 is the useful range.

Durations that worked on a TV at 3m viewing distance: layout moves **460 ms**, track-change
text **340 ms**, artwork crossfade **~500 ms**, panel slides **220-260 ms**.

**Reset animated state wherever the animation can be skipped.** Every property you animate
needs an explicit reset on the paths that bypass the animation -- a resize landing mid-flight
otherwise strands a view part-faded and offset for the rest of the session. This is the
single most common way expressive motion turns into a stuck UI.

**Picture-in-picture** is a genuine window resize, not a thumbnail scale. Text sizes must
go **down**, not up. (A previous attempt set 96 sp on the theory that the activity is
scaled like an image; it simply overflowed a 384×216 window and nothing was visible.) In
PiP, drop the artwork tile entirely — it alone is wider than the window — and use the
artwork as a darkened full-bleed background instead.

---

## 7. Checklist

- [ ] Bands emitted ~30/sec; the three values move **independently** in the log
- [ ] Vocal orb goes quiet in instrumental breaks while the music stays loud
- [ ] Peak-decay per-second figure computed, not eyeballed
- [ ] Visual delay = queue + **measured** output latency, with no double-count
- [ ] The same delay feeds every consumer of the audio
- [ ] Halo alpha constant; beat drives size and core brightness only
- [ ] Radius clamped to distance-to-nearest-edge
- [ ] Gradients cached at unit scale, transformed by matrix per frame
- [ ] Palette pool includes `palette.swatches`, not just the named roles
- [ ] Greyscale artwork detected pre-boost and kept grey
- [ ] Near-black palettes rejected
- [ ] Orbs read palette slots 0/1/2
- [ ] Shortfall synthesised, never cycled
- [ ] Mini layouts scale a composed view; they do not re-lay-out a narrower one
- [ ] Smoothing runs per **frame**, not per audio callback
- [ ] Tempo has two thresholds (acquire/hold) and folds octave errors onto the lock
- [ ] Every animated property has an explicit reset on the paths that skip the animation
- [ ] One animator per view; no two code paths animating the same view separately
- [ ] Field and orb brightness tuned separately, not via the shared palette
- [ ] Blob centres bounded to their own region, or they pile onto the middle when the
      drift animators line up

That last one is subtle: easing inside the audio callback makes smoothness a side effect
of the audio block size — the same constants gave near-instant tracking at 100 calls/sec
and visible stepping at 10. The audio thread should set **targets**; the render loop eases
toward them. It is the only way a fixed smoothing constant means anything.

---

## Source map

| Concern | File |
|---|---|
| Orbs, palette, blending | `ui/DynamicBackground.kt` |
| Bands, vocals, tempo, A/V delay | `airplay/handshake/AudioStreamServer.kt` |
| Card, presets, screensaver, PiP | `ui/NowPlayingScreen.kt` |
