# Tickbuddy Mobile Integration Guide

Handoff document for building an Android companion app that syncs to the Tickbuddy Nextcloud backend. This file is the single source of truth for the HTTP API, data model, auth, and sync semantics. If anything here contradicts the code, the code wins — re-read `lib/Controller/*.php` and `lib/Service/*.php` and update this doc.

Backend repo: https://github.com/martinhammer/Tickbuddy
Nextcloud compatibility: 32–34
Backend version at time of writing: 1.0.6

> Don't hardcode that version — the app now advertises its version and feature set at runtime through the Nextcloud capabilities endpoint. Read it on connect; see §1 "Capability & version discovery".

---

## 1. Transport, auth, and request conventions

Tickbuddy exposes an **OCS (Open Collaboration Services) API** hosted inside a Nextcloud instance. OCS is a thin Nextcloud convention layered on top of plain HTTP + JSON.

### Base URL

```
{nextcloudBaseUrl}/ocs/v2.php/apps/tickbuddy
```

Example: `https://cloud.example.com/ocs/v2.php/apps/tickbuddy/api/tracks`

The user must supply their Nextcloud server URL — it is not discoverable. Accept it as a configuration field, validate scheme is `https://` (allow `http://` only with a toggle for self-hosted dev), strip trailing slashes.

### Authentication

Use **HTTP Basic Auth** with a Nextcloud **app password** (NOT the user's login password).

1. Direct users to `Settings → Security → Devices & sessions → Create new app password` on their Nextcloud instance.
2. They copy the generated token and paste it (or scan a QR) into the app, along with their login name.
3. Store credentials in Android's `EncryptedSharedPreferences` or the Keystore-backed `MasterKey` API. Never log them.
4. Send on every request: `Authorization: Basic base64(login:app-password)`.

App passwords can be revoked per-device without affecting the user's main password. If a request returns `401`, treat credentials as invalid and prompt re-entry.

> There is also a "Login Flow v2" poll-based flow (`/index.php/login/v2`) if you want to avoid having users paste tokens — recommended for production UX. See [Nextcloud login flow docs](https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html). Out of scope for this doc, but worth implementing.

### Required headers

Every request MUST include:

| Header | Value | Purpose |
|---|---|---|
| `OCS-APIRequest` | `true` | Required by Nextcloud for any OCS call. Missing it → 404 or HTML error page. |
| `Accept` | `application/json` | Forces JSON response envelope. |
| `Authorization` | `Basic …` | See above. |

For `POST`/`PUT` requests sending form data, also set `Content-Type: application/x-www-form-urlencoded`. The backend reads params via `$this->request->getParam(...)`, which works with form-encoded bodies AND JSON bodies (Nextcloud merges both into the same param bag). Prefer form-encoded — it's what the existing web frontend uses and is well tested.

### OCS response envelope

Every OCS response is wrapped. A successful `GET /api/tracks` returns:

```json
{
  "ocs": {
    "meta": {
      "status": "ok",
      "statuscode": 200,
      "message": "OK"
    },
    "data": [ /* actual payload */ ]
  }
}
```

Your HTTP client should unwrap `response.ocs.data` before handing it to the rest of the app. Error responses have the same envelope with `status: "failure"` and `statuscode` matching the HTTP status.

HTTP status codes used by the backend:

| Code | Meaning |
|---|---|
| 200 | OK (reads, most writes) |
| 201 | Created (new track) |
| 204 | No Content (delete) |
| 400 | Bad input (invalid type/name, missing required param, wrong mode, file too large) |
| 401 | Unauthenticated |
| 403 | Track limit reached |
| 404 | Track not found |
| 409 | DB constraint conflict (e.g. duplicate on reorder) |

### CSRF

Not applicable for API clients authenticating with Basic auth — Nextcloud exempts Basic-authenticated OCS requests from CSRF. Don't send any CSRF token.

### Capability & version discovery

Tickbuddy registers a Nextcloud **capability**, so the client can discover the installed app version and which optional API features the server supports — without version-sniffing or probing endpoints.

**Important: this endpoint is NOT under the Tickbuddy base URL.** It's a core Nextcloud endpoint:

```
GET {nextcloudBaseUrl}/ocs/v2.php/cloud/capabilities
```

Same headers as every other call (`OCS-APIRequest: true`, `Accept: application/json`, Basic auth). Unwrap the usual OCS envelope, then read `data.capabilities.tickbuddy`:

```json
{
  "version": "1.0.6",
  "apiVersion": 1,
  "features": {
    "import": true,
    "export": true,
    "counterTracks": true,
    "privateTracks": true,
    "syncDelta": false,
    "counterIncrement": false
  }
}
```

- **`version`** — the installed app version string (from `info.xml`). Show it in an "About" / server-info screen.
- **`apiVersion`** — an integer contract version for the client-facing API. Bumped on a breaking change. Branch on this, not on `version`, when you need to gate behavior.
- **`features`** — capability flags. Treat every flag as **optional and possibly absent**: if the whole `tickbuddy` key is missing, the app isn't installed on that server (fail the connection with a clear message). If a specific flag is missing, assume `false`. Two flags matter for sync and will flip to `true` in a future backend (see §4): `syncDelta` (a "changed since X" delta endpoint exists) and `counterIncrement` (a commutative increment endpoint exists). Gate those code paths on the flags so the app lights them up automatically when the server is upgraded — no client release required.

Recommended usage: fetch capabilities once right after credential validation and cache it (refresh on app update or daily). A present `data.capabilities.tickbuddy` also doubles as a clean "is Tickbuddy installed here?" check during onboarding.

---

## 2. Data model

Two per-user tables, both scoped by `user_id` on the server side (you never send `user_id` — it's inferred from auth).

### Track

```
id          int        server-assigned
name        string     1..? chars, trimmed, non-empty
type        enum       "boolean" | "counter"    (immutable after create)
sortOrder   int        client-influenced display order
private     bool       hides from default views; export must opt-in
```

- **Max 99 tracks per user.** Creating the 100th returns `403` with `{message: "Maximum of 99 tracks reached"}`.
- **Track `type` is immutable.** `PUT /api/tracks/{id}` silently ignores any `type` field. If a user wants a different type, they must delete and recreate.
- **Deleting a track cascades**: all its ticks are deleted server-side in the same transaction.

### Tick

```
id       int       server-assigned (do not persist client-side; see sync section)
trackId  int       foreign key to Track
date     string    ISO date, "YYYY-MM-DD" exactly (no time, no timezone)
value    int       for boolean: always 1 when present; for counter: > 0
```

Two hard rules that your sync layer must respect:

1. **Sparse storage.** A tick row existing means "yes" or "count = value". No row means "no" or "count = 0". A boolean tick is never stored as `value=0`. A counter tick with value 0 is deleted, not kept.
2. **Uniqueness.** `(user_id, track_id, date)` is unique. You can't have two ticks for the same track on the same day.

### Dates

- Always `YYYY-MM-DD`, e.g. `"2026-04-24"`.
- Dates are **user-local**. The backend does no timezone math — the day a tick belongs to is whatever day the client says it is. Use the device's local date when the user taps "today".
- Weekend highlighting, first-day-of-week, etc. are **client concerns**. Backend stores and returns dates verbatim.

---

## 3. Endpoint reference

All paths below are relative to `{nextcloudBaseUrl}/ocs/v2.php/apps/tickbuddy`. All requests need the headers from §1. (The one exception is version/feature discovery, which lives on the core `/ocs/v2.php/cloud/capabilities` endpoint — see §1.)

### Tracks

#### `GET /api/tracks`
List all tracks for the current user. Returns tracks in `sortOrder` ascending, then `id`.

Response `data`:
```json
[
  {"id": 1, "name": "Meditate", "type": "boolean", "sortOrder": 1, "private": false},
  {"id": 2, "name": "Cups of coffee", "type": "counter", "sortOrder": 2, "private": false}
]
```

#### `POST /api/tracks`
Create a new track.

Form params:
- `name` (string, required, trimmed, non-empty)
- `type` (string, required, `"boolean"` or `"counter"`)

Responses:
- `201` + serialized track on success
- `400` on invalid name or type: `{message: "..."}`
- `403` if already at 99 tracks

#### `PUT /api/tracks/{id}`
Update an existing track. All fields optional; omitted fields untouched.

Form params (any subset):
- `name` (string, trimmed, non-empty if provided)
- `sortOrder` (int) — usually set via `/reorder` instead
- `private` (boolean; accepts `true`/`false`/`1`/`0`/`yes`/`no` via PHP `FILTER_VALIDATE_BOOLEAN`)

`type` is silently ignored if sent.

Responses: `200` + updated track, `404`, or `409`.

#### `PUT /api/tracks/reorder`
Bulk reorder. Send the desired order of track IDs; server rewrites `sortOrder` of each to match array index.

Form params:
- `trackIds[]` (array of ints, required, non-empty). In form-encoded bodies this is `trackIds[]=1&trackIds[]=5&trackIds[]=3`.

Response: `200` + full list of the user's tracks in the new order.

#### `DELETE /api/tracks/{id}`
Delete a track and all its ticks. `204` on success, `404` if not found.

### Ticks

#### `GET /api/ticks?from=YYYY-MM-DD&to=YYYY-MM-DD`
Fetch all ticks for the current user with `date` in `[from, to]` inclusive. Both params required. No pagination — keep ranges sensible (the web client uses 30-day windows, with infinite scroll extending ~30 days at a time).

Response `data`:
```json
[
  {"id": 42, "trackId": 1, "date": "2026-04-24", "value": 1},
  {"id": 43, "trackId": 2, "date": "2026-04-24", "value": 3}
]
```

Only existing ticks are returned. A missing (track, date) pair means "not ticked".

#### `POST /api/ticks/toggle`
Toggle a boolean tick. Only for `type=boolean` tracks — returns `400` for counter tracks.

Form params:
- `trackId` (int, required)
- `date` (string, required, `YYYY-MM-DD`)

Response: `{"ticked": true}` if the tick now exists, `{"ticked": false}` if it was removed.

#### `POST /api/ticks/set`
Set a counter value. Only for `type=counter` tracks — returns `400` for boolean tracks.

Form params:
- `trackId` (int, required)
- `date` (string, required, `YYYY-MM-DD`)
- `value` (int, required). `<= 0` deletes the tick; `> 0` creates or updates it.

Response: `{"value": <stored value>}`. `0` means no row exists.

### Preferences

#### `GET /api/preferences`
Response: `{"defaultView": "journal" | "readonly" | "analytics"}`. Default `"journal"` if never set. The mobile app is free to ignore this if it has its own navigation, but respecting it matches cross-device expectations.

#### `PUT /api/preferences`
Form param: `defaultView` (one of the three enum values). Unknown values fall back to `"journal"`.

### Import / Export

#### `GET /api/export?includePrivate=true|false`
Returns the full user data as a JSON blob:

```json
{
  "version": 1,
  "exportedAt": "2026-04-24T15:30:00+00:00",
  "tracks": [
    {"name": "Meditate", "type": "boolean", "sortOrder": 1, "private": false}
  ],
  "ticks": [
    {"track": "Meditate", "date": "2026-04-24", "value": 1}
  ]
}
```

Note: ticks reference tracks by **name**, not id, to be portable across instances. Track IDs are intentionally omitted.

#### `POST /api/import/json`  (multipart)
Import a previously exported JSON file.

Multipart form:
- `file` — the JSON file
- `mode` — `"replace"` (wipe all existing tracks+ticks first) or `"merge"` (add non-conflicting)
- Max 20 MB

Likely out of scope for a mobile client; mention only if you want to offer "restore from backup".

#### `POST /api/import`  (multipart)
Imports a Tickmate `.db` SQLite file (Android Tickmate app's export format). Also probably out of scope for mobile unless you want to offer Tickmate migration.

---

## 4. Sync strategy (recommended)

As of the version this doc targets, the backend has no sync protocol of its own (no `modifiedAt`, no delta endpoint, no ETags, no push). So the mobile app must build sync client-side. Suggested approach below.

> **Forward-compat:** two of the sharpest edges here (full-range polling and lost counter increments) are slated to be fixed server-side and are already discoverable via capability flags (§1). Guard those code paths on `features.syncDelta` and `features.counterIncrement` so the app upgrades its own behavior when it detects a newer backend. The full-poll + absolute-set approach below remains the correct fallback whenever the flags are `false`.

### Local schema

Mirror the server shape, plus sync metadata:

```
tracks(local_id, server_id NULLABLE, name, type, sort_order, private,
       dirty INT, deleted INT, updated_at_local)

ticks(local_id, server_id NULLABLE, track_local_id, date, value,
      dirty INT, deleted INT, updated_at_local)
```

`dirty=1` means "local change not yet pushed". `deleted=1` is a tombstone until confirmed deleted on server.

### Pull

On app open, on pull-to-refresh, and periodically (e.g. every 15 min while foregrounded):

1. `GET /api/tracks` → reconcile by `server_id` (and fall back to `name` match for rows that have no `server_id` yet because they were created offline). Track rows missing from the response are considered deleted server-side → delete locally unless the local row is `dirty` (then it's a conflict — see below).
2. `GET /api/ticks?from=X&to=Y` for the visible date range, plus any date range that has `dirty` ticks pending push. Reconcile by `(trackId, date)` — that's the natural key.

> When `features.syncDelta` is `true` (§1), a future backend will let you replace step 2's full-window re-download with an incremental "changed since X" pull. Until then, full-range polling is required. One caveat to design for now: because storage is sparse (a removed tick is a *deleted row*, not a zero), any future delta mechanism will need to convey deletions too — don't assume a delta pull can be a simple "rows where modifiedAt > X" over live rows.

### Push

Walk dirty rows in order: **track creates → track updates → track deletes → tick changes**. The order matters because ticks reference tracks.

- Track created offline: `POST /api/tracks`, store returned `id` as `server_id`, clear `dirty`.
- Track renamed: `PUT /api/tracks/{server_id}`.
- Track deleted: `DELETE /api/tracks/{server_id}`; on 404 treat as already gone.
- Tick toggled (boolean): `POST /api/ticks/toggle` — this is idempotent *per tap*, not per state. If you replay a queued toggle that was already applied, you'll flip it back. Safer pattern: instead of queuing "toggle", queue the **desired end state** (`ticked: true/false`) and on push compare with server state. A pragmatic shortcut: after queueing a toggle, immediately fetch the current server value before pushing, and only toggle if it differs.
- Tick counter set: `POST /api/ticks/set` with the desired value — this IS idempotent, always safe to replay.

### Conflict resolution

Two devices edit the same track name, or toggle the same tick. Recommended policy: **last-write-wins, server authoritative after pull**. When reconciling pull:

- If server row differs from local, and local is not `dirty`: take server value (silent overwrite).
- If server row differs and local IS `dirty`: push local on next push cycle; accept whatever server returns. For deletes specifically: if server shows a track the user deleted locally (and the delete is still queued), push the delete. If the user edited a track the server has deleted, create a new track with the local name + type.

Counter ticks are the trickiest case — if both devices incremented the same tick offline, both push the same absolute value and one increment is silently lost. Note this is **not** a last-write-wins timing problem you can fix with a timestamp: both writes carry the same wrong value. The real fix is a commutative operation. When `features.counterIncrement` is `true` (§1), send user taps as signed deltas to `POST /api/ticks/increment` and let the server compose them; concurrent increments then merge correctly. While the flag is `false`, fall back to `POST /api/ticks/set`, live with the lost-increment risk for v1, and consider a subtle UI hint when a counter was edited on multiple devices.

### Offline queue

All writes go to the local DB first (optimistic). A `WorkManager` job with `NetworkType.CONNECTED` constraint drains the dirty queue. On `401` stop the worker and surface a re-auth prompt. On `5xx` exponential-backoff retry.

---

## 5. Gotchas and non-obvious behaviors

- **`OCS-APIRequest: true` is not optional.** Without it, Nextcloud's router may return HTML login pages or 404s instead of JSON, and the error will be cryptic. Hardcode this in your HTTP client.
- **Boolean encoding.** PHP's `FILTER_VALIDATE_BOOLEAN` accepts `true|false|1|0|yes|no|on|off`. Send `"true"`/`"false"` strings to be safe.
- **Reorder is destructive.** `PUT /api/tracks/reorder` rewrites `sortOrder` on every listed track to `1..N`. If you omit tracks, their sort orders are untouched — which can produce duplicates. Always send the full list.
- **`value` on boolean ticks is always 1.** Don't write UI logic that assumes it could be 2. Don't send a value to `/toggle`.
- **Date range query is inclusive on both ends.** `from=2026-04-01&to=2026-04-30` includes both the 1st and the 30th.
- **Private tracks are soft-hidden, not encrypted.** Any client that reads `GET /api/tracks` sees them. The `private` flag controls default visibility in the web UI (there's a "Show private tracks" toggle) and whether they appear in exports. The mobile app should offer the same UX — a toggle/PIN gate on showing private tracks — but do not rely on `private` for any security claim.
- **No pagination.** Track and tick endpoints return all matching rows. Ticks are bounded by the `from/to` window. Tracks are capped at 99 per user so unbounded list is fine.
- **No push / websockets.** The mobile app has to poll.
- **Capabilities live on the core endpoint, not the app endpoint.** Version/feature discovery is `GET /ocs/v2.php/cloud/capabilities` (§1) — under `/cloud`, not `/apps/tickbuddy`. It still needs `OCS-APIRequest: true`. If `data.capabilities.tickbuddy` is absent, the app isn't installed on that server; treat it as a setup failure, not an empty result.
- **Don't gate features on the version string.** Parse `apiVersion` (int) or check individual `features` flags. String-comparing `version` ("1.0.5" vs "1.0.10") is a footgun.
- **Nextcloud hosts this app; the domain is NOT under our control.** Don't assume HTTPS, don't pin certificates. Allow any user-supplied hostname that validates via system trust store.
- **Time zones in `exportedAt`.** The export timestamp uses the server's timezone (PHP `date('c')`). Don't rely on it matching the user's timezone.
- **`OCS-APIRequest` header is case-insensitive in HTTP, but Nextcloud reads it as given.** Most HTTP clients normalize — verify yours does.

---

## 6. Minimal curl examples for manual testing

```bash
NC=https://cloud.example.com
USER=alice
APP_PASS=xxxx-xxxx-xxxx

# Discover version + features (core endpoint, note the /cloud path)
curl -u "$USER:$APP_PASS" \
  -H "OCS-APIRequest: true" -H "Accept: application/json" \
  "$NC/ocs/v2.php/cloud/capabilities"
# → look for .ocs.data.capabilities.tickbuddy

# List tracks
curl -u "$USER:$APP_PASS" \
  -H "OCS-APIRequest: true" -H "Accept: application/json" \
  "$NC/ocs/v2.php/apps/tickbuddy/api/tracks"

# Create a boolean track
curl -u "$USER:$APP_PASS" \
  -H "OCS-APIRequest: true" -H "Accept: application/json" \
  -d "name=Meditate&type=boolean" \
  "$NC/ocs/v2.php/apps/tickbuddy/api/tracks"

# Toggle today
curl -u "$USER:$APP_PASS" \
  -H "OCS-APIRequest: true" -H "Accept: application/json" \
  -d "trackId=1&date=2026-04-24" \
  "$NC/ocs/v2.php/apps/tickbuddy/api/ticks/toggle"

# Fetch a range
curl -u "$USER:$APP_PASS" \
  -H "OCS-APIRequest: true" -H "Accept: application/json" \
  "$NC/ocs/v2.php/apps/tickbuddy/api/ticks?from=2026-04-01&to=2026-04-30"
```

If any of those return HTML, you forgot `OCS-APIRequest: true`.

---

## 7. Checklist for a minimum-viable mobile client

- [ ] Server URL + login + app password entry with validation round-trip (`GET /api/tracks` with the credentials — a 200 proves all three)
- [ ] Read `GET /ocs/v2.php/cloud/capabilities` on connect: confirm `tickbuddy` is present (app installed), cache `version` + `features`, gate optional code paths on the flags
- [ ] Credentials in Keystore-backed encrypted storage
- [ ] Local SQLite mirror with `dirty` / `deleted` flags
- [ ] Read: pull tracks on open, pull ticks for visible range
- [ ] Write: optimistic local apply, queued push, retry on network
- [ ] Today screen: grid of tracks with checkbox (boolean) / ±buttons (counter)
- [ ] History screen: scrollable back by day, matches web "View journal"
- [ ] Track management: add/edit/delete/reorder, respect 99-track limit
- [ ] Private tracks: local toggle to show/hide, no security claim
- [ ] 401 handler that clears session and prompts re-auth
- [ ] Handle `type` immutability in UI (no "change type" button)
- [ ] Don't send `value=0` to `/toggle`; don't send toggles to counter tracks
- [ ] Show installed backend `version` in an About/server-info screen
- [ ] Gate `counterIncrement` / `syncDelta` code paths on capability flags so the app upgrades behavior automatically against newer backends

Nice-to-have:
- [ ] Nextcloud Login Flow v2 instead of pasted app password
- [ ] Widget for today's boolean tracks
- [ ] Export/import UI using `GET /api/export` and `POST /api/import/json`
- [ ] Notification/daily reminder (purely client-side)
