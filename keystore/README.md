# Signing keys / Chiavi di firma

`keys.tar.enc` contains the app signing keys, **encrypted** (AES-256, PBKDF2).
The password is stored only in the GitHub Actions secret `SIGNING_PASSWORD`;
without it the file is useless. Releases are signed in CI with key rotation
(APK Signature Scheme v3), so new versions install over older ones.

`keys.tar.enc` contiene le chiavi di firma, **cifrate**. La password sta solo nel
segreto `SIGNING_PASSWORD` di GitHub Actions.
