# The project's debug key

Every debug build of RockLogik Connect, on any machine or on CI, is signed with one key, because
Google sign-in only works for an APK whose certificate SHA-1 is registered on the Android OAuth
client (ROCKLOGIK-82). A machine's own `~/.android/debug.keystore` would give each build a
different SHA-1.

- `debug.keystore` is **not committed** (the repository is public). It is the standard Android
  debug keystore format: alias `androiddebugkey`, store and key password `android`.
- `debug.sha1` **is committed**: the SHA-1 registered with Google. The build reads the keystore's
  certificate and stops if it does not match, so a wrong key fails loudly instead of producing an
  APK that cannot sign in.

**Setting up a machine.** Get `debug.keystore` from the founder and put it here. On the founder's
workstation it is a copy of `~/.android/debug.keystore` made on 13 October 2026.

**CI.** The workflow writes it from the repository secret `DEBUG_KEYSTORE_BASE64`, which holds
the base64 of this file. To set or rotate it:

```sh
base64 -w0 keystore/debug.keystore | gh secret set DEBUG_KEYSTORE_BASE64
```

A rotated key means a new SHA-1 in `debug.sha1` and on the Android OAuth client, in the same sitting.
Release builds are signed with a separate key (ROCKLOGIK-106) and their own OAuth client.
