# Chaman AutoClaim v0.5

Carrom Pool daily rewards auto-claim (Accessibility + OCR). by Chaman Bhai Production.

## v0.5
- Chest screen ("Tap chest to collect"): back button aane tak chest pe tap (counter nahi), phir "You Got" screen pe back
- "Open one more chest!" (gems wala) kabhi nahi dabta
- Generic buttons: OK / Claim / Continue / Got it, Yes/No (kharch wale dialog me hamesha No)
- Ad ke baad Congratulations / result screen pehchan ke ad loop khatam
- Log box me Copy button

## v0.4
- App logo + polished UI (cards, status chip, settings yaad rehti hain)
- Google Play Games popup: node click, animation ke baad fresh Cancel tap, fail pe system Back
- Ad close: native node, corner OCR (X/Skip/Close), corner shape-X detector, countdown dikhe to Back nahi
- Ad close na mile to `stuck_last.jpg` save (app me "Last stuck ad screenshot dekho")

## GitHub se APK banao
1. Poora folder GitHub repo me push karo (branch `main`).
2. Actions > "Build APK" (ya Run workflow).
3. Artifacts se `chaman-autoclaim-debug` download karo, zip me `app-debug.apk`.

## Phone pe setup
1. APK install, saari permissions Allow.
2. Accessibility grey ho to: App info > 3 dot > "Allow restricted settings", phir service ON.
3. "Run once" se test karo.
