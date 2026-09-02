# Identifying a track the sender never named

Apple Music and Podcasts push a now-playing plist, so the Now Playing screen knows the title, the
artist and the artwork. **Raw system audio does not.** A browser tab, a game, a video in Safari —
anything that is not a media app handing over metadata — arrives as bare PCM, and the screen has
nothing to show but "Audio from *device*". Cover art has nothing to look up either, because that
lookup is keyed on a track name.

Fingerprinting is the only thing that closes that gap. Off by default:
**Now Playing → Identify unknown tracks** (`AppSettings.identifyTracks`).

## Why ShazamIO could not simply be used

It is a Python library, and the fingerprinting is not in the Python — it lives in `shazamio-core`, a
separate **Rust** crate whose only public surface is a **PyO3 module** (`Recognizer`, `Signature`,
`SignatureSong`). There is no C ABI, so neither half runs on Android and neither can be JNI-wrapped
without writing a shim against the crate's internals.

So `media/shazam/ShazamSignature.kt` is a port of `shazamio-core`'s `fingerprinting` module —
`algorithm.rs` and `signature_format.rs` — **written from those sources**, not from a description of
them. Every constant in it (the neighbour offsets, the 46/49-frame lookbacks, the magnitude curve,
the header magics) is meaningless alone and wrong if approximated. Re-read the Rust before changing
any of it.

The crate's decoding front end — rodio, symphonia, an ffmpeg fallback, for turning mp3/ogg/m4a
*files* into PCM — was left out entirely. `AudioStreamServer` has already decoded the stream. That
is the bulk of `algorithm.rs` and none of its substance.

## How it is validated

A ported wire format cannot be held to "looks reasonable", so it is checked two ways that can
actually fail:

- **The header is pinned against the project's own golden signature.** `tests/data/probe.flac.uri`
  in the upstream repo, base64-decoded, gives the real bytes: magics at 0 and 12, the sample-rate id
  shifted left 27 at 28, sample count plus 0.24 seconds' worth at 40, CRC32 over everything past its
  own field.
- **The detector is checked against physics.** Fed tones at 1237 Hz and 3001 Hz it must report peaks
  within 25 Hz of them. That fails if the Hanning window, the FFT, the spreading or the
  bin-to-hertz conversion is wrong — which is every part of the algorithm that has no other check.

**Byte-equality with the golden end to end is not claimed and is not reachable.** The golden was
produced through rodio's resampler from 44.1 kHz; a different resampler moves the samples, which
moves the peaks, which changes every byte after the header. A test asserting a match there could
only be made to pass by weakening it.

That golden is also what caught the one real bug in the port. The header proper is 48 bytes, but two
more `u32`s sit between it and the first band block, so the fixed preamble is **56** — and both size
fields are still measured against 48. Allocating 48 overflowed the buffer. That off-by-eight is easy
to write and invisible afterwards.

## The pipeline

```
AudioStreamServer (decoded PCM, 44.1kHz stereo)
  → TrackIdentifier.offer()          no-op unless armed: one volatile read
  → PcmCapture                       downmix + resample to mono 16kHz, 12s
  → ShazamSignature.generate()       ~1500 FFTs, on a background thread
  → ShazamClient.identify()          POST to amp.shazam.com
  → PhairPlayService.withIdentification()
```

Cover art comes back as a URL and is fetched on the identifier's own worker thread, bounded — the
URL is a third party's and its size is not ours to trust. Shazam serves `coverarthq` at 400x400,
which is soft on a 1080p card, so the mzstatic size segment is rewritten to ask for 800x800 with the
original URL kept as a fallback for when that rewrite does not resolve.

### It re-checks on a timer, and why it has to

Nameless audio carries no track identity, so there is **no track-change event** to hang a
re-identification on — a browser tab moving to the next song in a playlist looks exactly like the
same song continuing. Keying the answer to the SENDER instead gave one identification per session:
the first song's title stayed on screen through every song after it.

So it re-identifies on an interval — **Now Playing → Re-check what is playing** (12 / 15 / 30 / 60
seconds, default 30). The floor is one capture window: a period under twelve seconds cannot be
honoured, because that is how long a fingerprint takes to gather, and the timer would simply always
be due. "Continuously" is offered but is not the default — five lookups a minute at an endpoint with
no contract is the fastest way to get a client refused.

Worst-case staleness is the interval plus the window, and a capture that straddles a song change
matches the OLD song, pushing it to roughly twice that. At 30 s that is ~42 s, occasionally ~72 s.

While the device is in power-save mode the interval is floored at
`AppSettings.LOW_POWER_IDENTIFY_INTERVAL_SEC`. The stored setting is not overwritten, only clamped,
so leaving power-save restores whatever was chosen.

### A miss does not clear the name; silence does

Lookups fail for ordinary reasons — an intro, a quiet passage, someone talking over the music — and
blanking a correct title because one twelve-second window happened to be unrecognisable is worse
than leaving it. The only thing that clears an identification is the audio going quiet for
`QUIET_CLEAR_MS` (15 s), which is the one signal that genuinely means "whatever this was, it is
over".

Silence is detected from the peak sample of each packet, examined with a stride — the question is
only "is anything happening", which the loudest sample answers as well as an average and far more
cheaply on a thread that is decoding audio in real time.

### An identified track has a name but no POSITION

`NowPlayingInfo.identified` marks a title as ours rather than the sender's, and it exists for two
reasons.

**The UI needs it.** Fingerprinting joins a song partway through and nothing says how far in, so an
elapsed counter would start at 0:00 and be wrong by however much already played. `NowPlayingScreen`
skips the durationless clock-start for identified tracks; an unknown position shown as nothing beats
a fabricated one shown as a number.

**The service needs it more.** `withIdentification` decides "did the sender name this?" from
`hasMetadata`, which only means "has a title". Once an identification has been applied, the value in
`_nowPlaying` carries OUR title — so re-applying it read as the sender naming the track, called
`clearIdentification()`, and wiped the result while cancelling the identifier. That is why only the
FIRST song was ever identified: **every match after it destroyed itself on arrival.** The
`&& !info.identified` guard is what stops that, and it is not optional.

### It only runs when the sender named nothing

`withIdentification` is the single decision point. If `NowPlayingInfo.hasMetadata` is true it
cancels any capture, drops any stored identification and returns the sender's data untouched —
**the sender always wins**, because a track it names is authoritative and an identification is a
guess. The guess only ever fills a hole.

That also handles someone switching from a browser tab to Apple Music mid-session: the moment real
metadata arrives, the guess is discarded.

### Arming is split in two

`request()` is called from the service, which knows whether metadata is missing. The capture is
created later, on the audio thread, which is the only place that knows the negotiated sample rate
and channel count. Passing the format down from the service would mean plumbing it out of a
per-slot `AudioStreamServer` and back, to arrive where the PCM already comes from.

### Cost

The resample is **not** a re-encode — the audio is already decoded, so it is one multiply-add per
output sample, once per track. Fingerprinting is a few hundred milliseconds of FFTs on a
minimum-priority daemon thread, and the network wait is one round trip. Nothing runs per frame, and
the disarmed path on the packet thread is a volatile read and a return.

One attempt per arming. A miss is not retried on a loop — an unrecognised track stays
unrecognised, and re-sending the same twelve seconds is how a client gets refused.

## Privacy

- **Off by default.** It sends a fingerprint of what is playing to a third party.
- **An audio fingerprint is sent, not audio.** The signature is a list of spectral peak positions;
  it cannot be turned back into sound.
- **`geolocation` and `context` are sent empty.** The Rust core fills a geolocation in (altitude
  300, latitude 45, longitude 2 — a point in France) but the Python that actually issues the request
  sends `{}`, and that is what is copied here. Nothing about where the television is leaves the
  device. Do not add anything that changes this.
- Language, country and timezone come from the device locale, because a match carries localised
  titles and a pinned `en`/`GB` would hand a Japanese listener romanised names for their own music.

## The standing risk

**This is not a public API.** There is no key, no documented contract, and no promise it keeps
working. It can begin refusing unknown clients at any time — and when it does, the failure will look
exactly like "this track is not in the database".

That is why every failure path in `ShazamClient` logs what actually happened, including the error
body on a non-2xx, rather than collapsing to `null`. Without that, a refusal and a miss are
indistinguishable in the log, and the feature would appear to simply stop working for no reason.
