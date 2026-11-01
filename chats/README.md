# محادثات سنفينكو · Senfineco Chats — Android

The staff chat app (WhatsApp / Instagram inbox of the Senfineco bot) for Android, package **shop.senfineco.chats**
(same id as the iPhone app). It is a Trusted Web Activity: it opens https://bot.senfineco.shop/staff/ full screen in
Chrome, so sign-in, notifications (web push), voice notes and attachments work as on the site, and changes to the
site need no app update.

- Build: `.github/workflows/chats.yml` (Actions → *Build Senfineco Chats* → Run workflow). Commits to `chats/` use
  `[skip ci]` so the Garage workflow does not run. Outputs: run artifacts and the release `chats-v<N>`
  (never marked Latest, so the Garage download links are untouched).
- Signing: the Garage upload key (secret `UPLOAD_KEYSTORE_BUNDLE`); Google Play App Signing keeps the app signing key.
- Full screen: `https://bot.senfineco.shop/.well-known/assetlinks.json` must list `shop.senfineco.chats` with the
  SHA-256 of the Play app-signing certificate (Play Console → Test and release → App integrity) and of the upload
  certificate (`upload-cert.txt` in each release). Without it the app still works, with a small address bar.
- Direct install (newest build): https://github.com/senfineco-shop/senfineco-garage-android/releases/download/chats-latest/senfineco-chats.apk
