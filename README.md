# Senfineco Garage — Android app

Native Android wrapper (Java, WebView) for **https://garage.senfineco.shop** — the worker sign-in
version of the Senfineco Garage app (phone + password, no OTP, no Cloudflare Access).

* Package name: `shop.senfineco.garage`
* Only `garage.senfineco.shop` opens inside the app; WhatsApp / phone / other links open outside.
* Camera + gallery file chooser for the mandatory vehicle photo in the reception inspection.
* Cookies are kept (30-day worker session), rotation and the back button are handled,
  and an offline page with "Try again" is shown when there is no internet.

## Build (automatic)

Every push to `main` runs `.github/workflows/build.yml` on GitHub Actions and produces two
artifacts on the run page (Actions → the run → Artifacts):

* `senfineco-garage-aab-v<N>` → upload the `.aab` to Google Play (Production → New release)
* `senfineco-garage-apk-v<N>` → install directly on workshop tablets

`versionCode` = the GitHub run number, so every build is newer than the previous one.

## Signing

`app/keystore/upload.jks` is the **upload key** (password in `app/keystore/keystore.properties`).
Google Play App Signing holds the real app signing key, so this key can be reset from
Play Console if it is ever lost. Keep this repository private.

## Change the site address

`MainActivity.java` → `HOME_URL` / `APP_HOST`.

---

تطبيق أندرويد خفيف يفتح garage.senfineco.shop فقط (دخول العمال برقم الجوال وكلمة السر).
كل دفعة (push) إلى main تبني ملف aab و apk تلقائياً في صفحة Actions.
