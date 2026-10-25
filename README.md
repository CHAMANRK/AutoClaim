# Chaman AutoClaim v0.5.3

Carrom Pool daily rewards auto-claim (Accessibility + OCR). by Chaman Bhai Production.

## v0.5.3
- Game open wait: max 12 sec. 5 sec baad har 1 sec check (lobby / koi page / popup dikhe toh turant aage)
- Cleanup phase + global back finder: popups, Play Games, daily page, chest, unknown pages -> bottom-left back, corner X, OK/close buttons, last me system Back (max 3, sirf non-lobby)
- Lobby 3 baar capture (1 sec gap), tabhi Free Rewards / claim tab. 60 sec me confirm na ho toh bhi aage badhta hai
- Anjaan page ka text log me ("Anjaan page: ...") + last scan screenshot

## v0.5.2
- BUG FIX: ad chalte chalte "claim ho gaya" aake run band ho jata tha. Ad ke andar "Home" 2 baar aane se lobby samajh liya jata tha.
  Ab: lobby = neeche ke 15% me kam se kam 3 alag nav words (Home/Friends/Equipment/Events/Shop),
  ad ke text (install/download/google play...) dikhe toh game screen nahi, aur 2 baar lagatar dikhna zaroori.
- Run tabhi complete jab lobby dikhe (warna 6 extra wait)
- Bina Collect ke ad screen mile toh bhi close karne ki koshish

## v0.5.1
- Ad close: white X (yellow/colored square ke andar) bhi detect hota hai
- Playable ads: X na mile toh "Google Play >>" dabata hai (max 2), store khule toh Back, phir X
- Ad me system Back max 2 baar
- Play Games popup: pehle system Back, phir Cancel tap (5 try tak), Continue kabhi nahi

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
