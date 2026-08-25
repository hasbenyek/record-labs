# Record Labs playback remote config

`playback.json` is a declarative playback policy read by Record Labs in the background. A
config-only commit is picked up by compatible app versions without producing a new APK release.

Safe maintenance controls:

- `fallbackClientOrder`: reorder the allowlisted InnerTube fallback clients.
- `disabledClientIds`: temporarily disable an allowlisted client.
- `forceAnonymousPublicPlayback`: keep public playback independent from the signed-in session.
- `preferFallbackWhenLoggedIn`: start signed-in public playback at the fallback list.
- `skipWebRemixHeadValidation`: preserve the direct Media3 test for the main WEB_REMIX stream.
- `validateLastFallback`: validate the last client before handing its URL to Media3.
- `clientOverrides`: override only `clientVersion` and `userAgent` for an allowlisted client.

Every change must also update `revision`. The Android client rejects unknown fields, unknown client
IDs, duplicate/empty fallback lists, expired documents, malformed versions/User-Agents, and
documents larger than 32 KiB. If fetching or validation fails, it keeps the last valid policy or
the bundled defaults.

Example override:

```json
"clientOverrides": {
  "WEB_REMIX": {
    "clientVersion": "1.20260213.01.00"
  }
}
```

This file cannot change application code, API endpoints, cookies, tokens, or other credentials.
