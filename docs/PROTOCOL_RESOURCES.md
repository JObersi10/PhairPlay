# Protocol resources

External references for the AirPlay / RAOP / DLNA work, plus what has already been
checked against them. The second half matters as much as the first: several of these
have been read closely and do **not** contain what we need, and re-reading them is a
cost this file exists to avoid.

## Primary — AirPlay 2

- **UxPlay wiki, AirPlay2 protocol** — https://github.com/FDH2/UxPlay/wiki/AirPlay2-protocol
  Actively maintained (Nov 2025). The best single index of AirPlay 2 reverse engineering.
- **UxPlay crypto / pairing** — https://github.com/FDH2/UxPlay/wiki/crypto
  Legacy pairing (`pair-pin-start` + `pair-setup-pin` + `pair-verify`) and `pair-setup` + `pair-verify`.
- **pyatv protocol docs** — https://pyatv.dev/documentation/protocols
  Ships the MediaRemote `.proto` files. `SendCommandMessage.proto`, `CommandInfo.proto` and
  `ProtocolMessage.proto` are where `MediaRemote.kt`'s field numbers and `Command` enum come from.
- **openairplay unofficial spec** — https://openairplay.github.io/airplay-spec/
- **openairplay receiver (python)** — https://github.com/openairplay/airplay2-receiver
- **Java AirPlay2 receiver** — https://github.com/warren-bank/Java-AirPlay2-Receiver
- **Emanuele Cozzi, AirPlay 2 internals** — https://emanuelecozzi.net/docs/airplay2/rtsp/
  and features: https://emanuelecozzi.net/docs/airplay2/features
- **SteeBono AirPlay2 protocol** — https://github.com/SteeBono/airplayreceiver/wiki/AirPlay2-Protocol
- **pair_ap** — https://github.com/ejurgensen/pair_ap

## Primary — AirPlay 1 / RAOP

- **nto unofficial AirPlay spec** — http://nto.github.io/AirPlay.html
- **Airtunes2** — https://git.zx2c4.com/Airtunes2/about/
- **airplay-internal** — https://air-display.github.io/airplay-internal/
- **RAOP-Player auth protocol** — https://htmlpreview.github.io/?https://github.com/philippe44/RAOP-Player/blob/master/doc/auth_protocol.html
- **libraop** — https://github.com/philippe44/libraop
- **airplay-protocol** — https://github.com/watson/airplay-protocol
- **RTSP RFC 2326** — https://datatracker.ietf.org/doc/html/rfc2326
- **HTTP digest auth, RFC 2617** — https://www.ietf.org/rfc/rfc2617.txt
- **SRP (rfc5054 compat)** — https://github.com/cocagne/csrp/tree/rfc5054_compat

## HLS (relevant to AirPlay URL video)

- HLS v7, RFC 8216 — https://datatracker.ietf.org/doc/html/rfc8216
- Apple HLS docs — https://developer.apple.com/documentation/http-live-streaming

## Dead ends — checked, do not re-check without new information

**Receiver → sender transport control (the media-button wall).**
Checked 2026-08-17/18: pyatv, openairplay's spec *and* its receiver source, UxPlay's `raop.c`,
nto.github.io, emanuelecozzi.net, SteeBono's wiki.

- Every source describes exactly one mechanism: **DACP**. The sender must supply `DACP-ID` and
  `Active-Remote` on its RTSP requests; the receiver then talks to the sender's `_dacp._tcp`
  service over plain HTTP (`GET /ctrl-int/1/pause`).
- **No public implementation sends receiver → sender commands by any other route.** openairplay
  parses `/command` and never sends one. UxPlay only exports the DACP-ID to a file for an external
  tool to use.
- Stream **type 130 is named "Remote control"** in both emanuelecozzi.net's SETUP table and
  SteeBono's wiki, and its SETUP shape and framing are documented in neither. Our senders never
  open one.
- `MediaRemote.encodeSendCommand` builds a genuine `SendCommandMessage` from pyatv's protos, so the
  *encoding* is sourced and correct. The *delivery* is not: the `/command` plist keys we had
  (`sendCommand`, `mrCommandFromReceiver`) were invented and appear in no source. Sending is gated
  off behind `MRP_SEND_ENABLED`.

What would actually move this: a packet capture of a real Apple TV or HomePod being controlled by
its sender, or documentation of stream type 130. Nothing short of that.

**The shairport-sync srcvers workaround (issue #2014)** — dropping the advertised `srcvers` below
~350 made an iOS 18 sender start sending `DACP-ID` again. `_raop._tcp` has been pinned to 350.0
here for some time and the log still reads `GET /info WITHOUT DACP-ID — sender withheld remote
authority (srcvers 350.0)` on iOS 26.1 and iPadOS 27. That number is our own advertised value, so
the workaround is confirmed applied and ineffective on current iOS. See `AirPlayVersion.kt` for the
one remaining untested variable (`_airplay._tcp` was still claiming a current Apple TV).

**Google Cast** — port 8009 is permanently held by `com.amazon.cast.sink`, and a receiver must
answer `DeviceAuthMessage` with a Google-CA-signed certificate chain that cannot be obtained.
