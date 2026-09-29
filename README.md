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

## Fixed download link (tablets / workers)

Every successful build is also published as a GitHub Release (`v<N>`, marked *Latest*) with
fixed asset names, so these links never change:

* APK (always the newest build): **https://github.com/senfineco-shop/senfineco-garage-android/releases/latest/download/senfineco-garage.apk**
* Version info: https://github.com/senfineco-shop/senfineco-garage-android/releases/latest/download/version.json
* AAB for Play: https://github.com/senfineco-shop/senfineco-garage-android/releases/latest/download/senfineco-garage.aab

The app itself checks `version.json` on start (at most every 3 hours) and shows an
"Update" banner when a newer build exists — tapping it downloads the APK from the fixed link.
The check is skipped for installs that came from Google Play (Play delivers those updates).

Changes made on the website (garage.senfineco.shop) need **no** app update at all — the app
always shows the live site.

## Signing

The **upload key** is NOT stored in this repository. It lives in one repository secret,
`UPLOAD_KEYSTORE_BUNDLE` (base64 of a tar.gz holding `keystore/upload.jks` +
`keystore/keystore.properties`), which the workflow restores into `app/keystore/` at build time.
Google Play App Signing holds the real app signing key, so the upload key can be reset from
Play Console if it is ever lost. Keep a copy of the key files somewhere safe.

## Change the site address

`MainActivity.java` → `HOME_URL` / `APP_HOST`.

---

تطبيق أندرويد خفيف يفتح garage.senfineco.shop فقط (دخول العمال برقم الجوال وكلمة السر).
كل دفعة (push) إلى main تبني ملف aab و apk تلقائياً في صفحة Actions وتنشر إصداراً (Release).

الرابط الثابت لتحميل أحدث نسخة للعمال (لا يتغير أبداً):
https://github.com/senfineco-shop/senfineco-garage-android/releases/latest/download/senfineco-garage.apk

التطبيق يفحص بنفسه وجود نسخة أحدث ويعرض شريط «تحديث» في الأعلى؛ أما تعديلات الموقع نفسه
فتظهر للعامل فوراً بدون أي تحديث للتطبيق.
