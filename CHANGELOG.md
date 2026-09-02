# Changelog

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
