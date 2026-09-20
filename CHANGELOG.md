# Changelog

## 0.7.0

### Fixed

- A call to action in an in-app message could not open a deep link into your
  own app.

  The button's URL was checked against an http and https allowlist, so
  `myapp://order/4821`, which on a deep linking product is the most natural
  button a message can have, was refused before anything happened: no
  navigation, and your own action callback was never called either. The server
  that renders these messages has always allowed the link; only the SDK
  declined to follow it.

  The rule is now the denylist the platform itself applies. The schemes that
  can run code or forge an origin are named and refused, and everything else is
  left to open, because no list could hold every customer's scheme.

  To be exact about the blast radius, since an earlier draft of this note was
  not: the message action was the only thing in this SDK that used the http and
  https rule, so `isSafeUrl` now has no callers here. It is kept, and still
  means what it meant, but nothing in this package is guarded by it. Message
  artwork is not: that is rendered server side and its image sources are
  checked there.

  The denylist also names `content:`, `jar:` and `filesystem:`, which a browser
  has no use for. This package reaches an Intent, where `content:` reads a
  ContentProvider and the other two carry a second URL inside them.

- A message with only a title and a body rendered as an empty screen, because
  the server answered nothing for a message with no designed content. That is
  a server side fix and needs no change here beyond this note.
- A scheme containing an underscore was refused. Those are not RFC 3986, and
  both platforms register them, so real apps use them.

## 0.6.0

### Added

- `Tolinku.links.resolve(url)` turns a link the system handed the app into the
  route and token it means.

  An app receives the URL that was tapped, exactly as written. That is fine
  while the URL is readable: `/order/4821` says "order" and the app can route
  it. A short link is the same route written as a code, `/s7k2p9q/4821`, and
  nothing in it says "order", nor can the code be worked out on the device. An
  app parsing the path itself sees a first segment it has never heard of and
  does nothing, so the link opens the app and then appears to fail: no error, no
  screen, no clue. Short links are what the dashboard offers for sharing and
  what a QR code carries, so this is not a rare path.

  A readable URL comes back unchanged, so an app can resolve everything rather
  than guessing which kind it has. The question goes to the link's own host,
  which is how the platform knows the Appspace, so a link on someone else's
  domain answers nothing. Never throws: this runs during a cold start, and an
  exception there is the difference between a link that did not route and an app
  that did not start.

  Needs a platform new enough to answer for a whole path on `/v1/api/path`.

### Fixed

- Referral links shared in short form could open the app without the referral
  code reaching it. Resolve incoming links with `Tolinku.links.resolve` and the code arrives
  with the rest of the link.

## 0.5.0

### Added

- `trackLinkOpen(url)` reports a link that opened the app without the browser
  being involved. A Universal Link or App Link hands the app the URL directly,
  so Tolinku is never contacted and the tap is not counted. The taps that go
  missing are the ones from people who already have the app, so a campaign aimed
  at existing customers reads as a failure exactly when it worked.

  Call it wherever the app receives a link. Both arrivals need it: a link that
  launches the app cold arrives somewhere different from one tapped while it is
  already running, and instrumenting only the second misses the more common
  case. Wiring both is safe, since the same link inside a few seconds is
  reported once.

  Only http and https links are sent. A custom scheme means Tolinku's own
  hand-off page opened the app, and that tap was already counted when the page
  was served.

  These count and bill as clicks. An Appspace set to attribute app opens only
  when reported, or never, is answered on the first call and nothing further is
  sent for the rest of the launch.

- `claimBySignals` accepts the signals as parameters, overriding what the device
  reports. The Flutter, React Native and web SDKs already did; these two took
  none, so an app holding a better value than the SDK could read had nowhere to
  put it. Overriding one signal keeps the rest: matching compares only what both
  sides supplied, so dropping the others would leave less to compare on than
  passing nothing at all.

## 0.4.0

### Added

- `destroy()` tears the SDK down. The name every Tolinku SDK uses for this.
  `shutdown()` does the same thing and still works; it is what this SDK shipped
  and breaking it would serve nobody. It is meant for deprecation later, once
  moving off it is a one-line change rather than a surprise.

### Added

- `claimDeferredLink()` recovers the link that led to an install, asking the Play
  Install Referrer first and falling back to device signal matching. Call it once
  on first launch instead of choosing between `claimByToken` and `claimBySignals`
  yourself.
- The Play Install Referrer is read by this package directly, so nothing extra
  needs installing. Android links already carried a referrer token to the store
  and nothing read it back, which left every install matched only by device
  signals: probabilistic, and expiring two hours after the click.

- `claimDeferredLink()` runs once per install and remembers it, so calling it on
  every launch costs nothing after the first. Only a real answer is remembered:
  a dropped request leaves the next launch free to try again rather than
  spending the install's one chance at attribution on a bad connection.

- Token claims now name their Appspace. It narrows what a token may claim, never
  widens it, and it is what lets a failed claim be counted: the default host
  resolves to no Appspace, so a miss previously belonged to nobody and the
  reported referrer match rate would have read 100% regardless.

### Fixed

- A literal `+` in the Play referrer is no longer read as a space. Java's
  `URLDecoder` implements form encoding where the other SDKs' decoders do not,
  so the same referrer parsed differently on Android alone.

## 0.3.0

### Fixed

- **Deferred deep link signal matching.** The signals sent for `claimBySignals` did not
  match the values recorded by the landing page, so some of them could never contribute
  to a match. See the per-SDK notes below.
- `claimBySignals` no longer reports a configuration error as a plain "no match". A `403`
  (wrong `appspaceId`) is now surfaced with an explanation instead of being swallowed.

  Note `appspaceId` is your Appspace ID, copied from the dashboard under Settings. It is
  not your subdomain or slug. Sending the slug was the cause of the report behind this
  release, and now produces an explicit error rather than a silent null.
- Android sent a bare primary language subtag (`ko`) instead of a full BCP-47 tag
  (`ko-KR`), and reported screen size in physical pixels (1080x2340) where the landing
  page records CSS pixels (412x915). Both signals therefore always failed to score.
  Now sends `toLanguageTag()` and density-independent pixels.
- Matching now also compares device pixel ratio and OS version, and reports them
  automatically where the platform exposes them.
