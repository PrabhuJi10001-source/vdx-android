domain: VDX

# VDX — First Release: "Call a Person" Spec

**Authority:** Founder directive 2026-08-31 ("One task chuno: call a person.")
**Bar:** Board/ECE (customers paid, not charity). No fabricated resilience.
**Status:** Design-approved target for release 0.1.x.

---

## 1. The one job

**"Get me to my person, by voice, when I'm confused or scared — on a phone that needs no account."**

Not "communication." Not "assistant." One verb: **call.** The 62-year-old who is afraid of pressing the wrong thing and calls their son instead — meet them *at that exact moment* and make it effortless.

Chosen because it is the only task among the four that:
1. **Survives Play review** — needs no AccessibilityService (see §3), so it ships on the store, not side-load.
2. **Has recoverable stakes** — worst case is a wrong call: hang up. Send-money errors are irreversible trust destruction.
3. **Is a job, not a category.**

The follow-on tasks (send money, read my messages) arrive in later releases **only after** the voice-call trust is proven. They will re-open the accessibility question — see §6.

---

## 2. Product promise (copy — verbatim, no drift)

> **No login. No cloud. One file stays on your phone.**

For the call release the third phrase resolves to: *one call connects you to your person.* The promise is the same three beats everywhere it appears — store listing, first screen, install consent.

Privacy earns trust. Retrieval/connection earns attention. **Pehle parinaam, architecture baad mein.**

---

## 3. Play-clean architecture (the release's core constraint)

**Target:** no AccessibilityService, no sensitive-API declaration, no `isAccessibilityTool` misuse, ships through Play review. This is the whole point of choosing call.

| Concern | Decision | Where it lives today |
|---|---|---|
| Place the call | `Intent.ACTION_DIAL` (opens dialer pre-filled, user taps Call) — **not** `ACTION_CALL` | `SystemController.kt:153` already prefers DIAL |
| Voice capture | Tap-to-talk bubble (explicit activation — never ambient) | `BubbleForegroundService` |
| STT | **On-device `SpeechRecognizer` is the PRIMARY path** (already wired) | `SonicEngine.kt:675` |
| STT fallback | BYOK cloud ASR (Groq/OpenAI/Gemini/Sarvam) only when on-device fails **and** user supplied a key in KeyVault | `SonicEngine.kt:704-707` |
| Contact resolve | Spoken name → `ContactsContract` lookup → DIAL intent | existing resolve code |
| Confirm | Destructive-action confirm rule: "Call Dad?" confirm before DIAL | `.cursorrules` rule 4 + `ConfirmationToken` |

**Hard rule for this release:** the Call flow must complete with **no AccessibilityService enabled**. If it doesn't launch the dialer without a11y on a clean install, it is not done. The a11y services (`VdxAccessibilityService` / `GestureAccessibilityService`) exist and may stay in the codebase for later releases, but the Call release must not touch them — and must not request them during setup.

**STT honesty caveat (do not hide):** Android on-device ASR quality varies by OEM and locale; on some devices `SpeechRecognizer` may route to Google cloud. VDX does **not** add its own cloud hop by default, and every transcript stays on-device unless the user explicitly enabled a BYOK key. The Play listing and disclosure say: *"Voice is processed on your device when possible; no account and no VDX cloud."* The spec for India wedge favors Sarvam (DPDP-regional) if on-device underperforms — but only as user-enabled, never silent.

---

## 4. First-run (adapts the file-retrieval onboarding to the call job)

The earlier "recover one lost file" onboarding proves the *retrieval* product. The Call release uses the same three-clue principle inverted — you already know *who*, you make *connecting* trivial.

- **Screen 1 — the person:** "Who do you want to reach by voice?" → the phrase *behind* the bubble tap. Not a list of features.
- **Screen 2 — permission the moment it's needed:** Overlay (bubble) + MIC asked on the **first orb tap**, never as a home wall button (matches the Live Home rule already in the tree).
- **Screen 3 — one live try:** "Say a name." They speak → contact resolves → confirm → `ACTION_DIAL` opens. Show the dialer **before** any explanation.
- **Closing line:** "VDX called them from what you said. No account. No cloud. That's the whole app — and it does more." *(only then — a 3-line explanation of broader indexing.)*

Results first. Architecture after.

---

## 5. Marketing: the 45-second memory test (Call edition)

**The hook — this is the whole campaign:**
> **"Call her again."**

Not "private voice AI assistant." One felt panic anyone over 50 has lived: the second they realize they're staring at a phone they're afraid to use and just want their son on the line. You can't *feel* a category phrase; you can *feel* a connection.

**Structure (45s, unscripted):**
- **0–5s:** "Show me someone you call when you're worried." — real person, their actual phone, their real contact.
- **5–30s:** they tap the bubble, say a name — maybe haltingly. The phone starts to dial.
- **30–40s:** it connects (or shows the dialer ready). **Their face at :35 is the entire ad.**
- **40–45s:** "VDX dialed them from what you said. No account. No cloud. One tap."

**Casting rule:** real people, their own contacts, no actors, no scripted relief. The unscripted exhale when *their* daughter's name resolves and the dialer opens — that is the proof. Privacy earns trust (the promise); connection earns attention (the :35 exhale).

The :35 moment in the ad and Screen 3 in the app are the **same scene**. The campaign IS the first-run.

---

## 6. Deferred (explicit non-goals for THIS release)

| Task | Why deferred | Accessiblity wall it hits later |
|---|---|---|
| Send money (UPI) | Irreversible error = destroy trust | Financial API + automation prohibition |
| Read my messages aloud | The defining "drive another app" case = Assistants-rejected pattern | Needs AccessibilityService + non-tool declaration, and autonomous-driving prohibition looms |
| Find a photo | Low stakes, no emotional hook | None — but doesn't prove trust |

**§3-adjacent reality check (documented, not solved here):** any future release that must *drive another app's UI* (read WhatsApp aloud, send money inside an app) requires the AccessibilityService — and per current Play policy a non-disability assistant doing so sits in the "prohibited autonomous driving" band with only `isAccessibilityTool=true` as an escape, which we cannot claim (not a disability tool). That is a **strategy decision for a later session**, not a blocker to this release. This release's entire merit is that it does not touch that wall.

---

## 7. Done / not-done (Cody bar — verified, not self-reported)

**Done when (real tool output, not grep):**
1. `adb uninstall com.vdx.alpha` → fresh install → launch. (No `-r`.)
2. Call flow runs with **no AccessibilityService enabled** — the dialer opens via `ACTION_DIAL`.
3. Screenshot + `uiautomator dump` shows the DIAL screen; no ANR, no `StackOverflowError`/`FATAL` in `adb logcat`.
4. On-device STT is the primary path confirmed (cloud BYOK only behind KeyVault, off by default).
5. `./gradlew.bat testDebugUnitTest` passes; `AdversarialAuditTest` still 100%.
6. Confirm gate present before DIAL (destructive-action rule).

**Not done if:** any of the above is unverified, the a11y services are required, or a cloud key is required for the happy path. No static-audit "looks clean" substitute.
