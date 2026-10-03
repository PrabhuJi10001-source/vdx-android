# CODE REVIEW — VDX RobotHand (Both Implementations)

**Reviewer:** Hermes (Prithu's instance) · **Date:** 2026-10-02
**Scope:** `app/src/main/java/com/vdx/RobotHand.kt` (982 ln, legacy) · `app/src/main/java/com/vdx/sonic/robot/RobotHand.kt` (700 ln, Sonic) · selectors, adapters, harness, flow catalog, telemetry, tests
**Method:** full-source read; every claim below carries file+line evidence. No self-reported status accepted.

---

## Verdict (one paragraph)

The developer's self-assessment ("built, esp. RobotHand, but not effective yet") is **accurate and honest**. The executor's *skeleton* is unusually well-built for reliability — verification gates, honest result states, bounded recovery, deadline polling. But its *eyes* are structurally weak: the only stable element anchor Android provides (`viewIdResourceName`) is **dropped by the Sonic extractor and mis-wired in the matcher**, and every adapter finds UI by single-string `contains()` heuristics that break on language, app version, and unlabeled buttons — the exact class of failure VDX's own AdversarialAudit names (F12). And the executor has **zero dedicated tests**, so "not effective" is currently unmeasured and therefore unfixable in any directed way. Effectiveness is not a rewrite away; it is a *measurement + anchor* away.

---

## 1 · Architecture map (verified)

Entry points:

| Path | Implementation | Used by |
|---|---|---|
| Voice intents (tap-to-talk) | `sonic/robot/RobotHand.kt` (`SonicEngine.kt:51` lazy; `.execute(plan)` at :264, :400, :514, :625) | **Production voice loop** |
| Dev harness tests | legacy `RobotHand.kt` (`MainActivity.kt:656, :713`, `RobotHand(this, null)`) | Test harness only — not the voice path (corrected from initial read) |
| Plans | `FlowCatalog.plan(intent)` — 20+ flows: CALL, WHATSAPP, SMS, EMAIL, CONTACT_MANAGE, BOOK_RIDE, YOUTUBE×2, SEARCH, PLAY_STORE, APP_LAUNCH/SWITCH, GO_BACK/HOME, GESTURE, READ_SCREEN/FOCUSED/NOTIFICATIONS, READ_PDF, DESCRIBE_IMAGE, SYSTEM_QUERY/TOGGLE, SET_ALARM | Both robots |

Adapter layer: `AdapterRegistry` + 9 app adapters (WhatsApp, Gmail, Phone, Uber, YouTube, Chrome, PlayStore, Settings, Generic). RobotHand resolves per-app selectors → `ActionStep`s → executes.

---

## 2 · What is genuinely solid (keep as-is)

1. **Honest result taxonomy** — `ExecutionResult = Success | Unverified(reason) | Failed(recoverable) | Cancelled`. `Unverified` exists as a first-class state — the exact opposite of the "Unverified reported as Done" finding (F08) the audit shreds. This is the right spine.
2. **Foreground verification gate** (`robot/RobotHand.kt:165-185`): after `launchApp`, the foreground package is re-read (via a11y **or** UsageStatsManager fallback when a11y is off) and compared. Crash/wrong-app → `Unverified` with reason, never fake success.
3. **Verification gate after typing** (:235-242): re-reads the field and confirms the text actually landed before proceeding.
4. **Bounded retry-with-fallback** (:378-403): max attempts, coroutine `delay` (never `Thread.sleep`), **re-reads the screen inside each attempt** so stale/expired nodes get replaced. Recovers the most common field failure (stale node post-animation).
5. **Deadline polling exists**: `Harness.waitForNode()` (`Harness.kt:94-107`) polls `refresh()` every 200 ms until selector matches or 8 s deadline — the correct wait-for-condition pattern, already written.
6. **Gesture-last ladder** (`robot/RobotHand.kt:6-9` docstring + dispatch order): semantic a11y actions → text insert → scroll/focus/global → raw gestures only on failure. Matches Google's a11y best practice and minimizes jank risk.
7. **Confirmation resume** (`handleConfirmation`): resumes from the step AFTER the confirm step; user cancel → clean `Cancelled`.

This skeleton is review-proof. The defects are all in *perception and measurement*, not control flow.

---

## 3 · Defects (evidence + fix, severity-ordered)

### D1 · CRITICAL — the stable anchor is dropped AND mis-wired
- **Evidence A:** `SonicModels.kt:258` — `UiElement` has **no `viewIdResourceName` field**. `Harness.traverse` (`Harness.kt:169→`) extracts text/contentDescription/hint/className/bounds/actions — **resource id is discarded at extraction time**.
- **Evidence B:** the irony — the *legacy* extractor captures it (`ScreenContentExtractor.kt:181`: `viewId = node.viewIdResourceName ?: ""`), and the `NodeSelector` model even has a `resourceId` field — but:
- **Evidence C:** the matcher mis-wires it: `Harness.kt:130`:
  ```kotlin
  if (sel.resourceId != null && !el.packageName.contains(sel.resourceId, ignoreCase = true)) return false
  ```
  This compares the **resource-id selector against the element's package name**. It cannot match real resource ids (`"com.whatsapp:id/send"` vs packageName `"com.whatsapp"` matches only by package-prefix accident; `"send_button"` never matches anything). Every selector that relies on `resourceId` today silently matches everything or fails everything.
- **Impact:** the ONE anchor that survives app-language changes (Hindi WhatsApp: "भेजें"), app-version rebrands, and unlabeled IconButtons — gone. This single defect explains a large share of field non-effectiveness.
- **Fix sketch:** add `viewId: String` to `UiElement`; capture `node.viewIdResourceName` in `Harness.traverse/safeViewId`; fix `matchesAll` to compare `el.viewId.equalsOrContains(sel.resourceId)`; make `resourceId` a first-class selector key in all flows.

### D2 · HIGH — single-string `contains()` selectors (the F12 engine)
- **Evidence:** `WhatsAppAdapter.kt` — send button = first clickable whose contentDescription/text `contains("send")`; message field = hint `contains("message"|"type a"|"text")`; contact search = hint `contains("search")|. Same pattern across `GenericAdapter` (finds "to:" fields by hint `"to"` — matches half the address book).
- **Impact 1 — language:** Hindi/other locale WhatsApp ships different strings. `contains("send")` dead.
- **Impact 2 — ambiguity:** "type a message" vs a group name containing "type" → firstOrNull picks wrong element; audit finding F12 ("WhatsApp Send matching a random ImageButton") is this code path.
- **Impact 3 — no scoring:** `firstOrNull` on an unordered list = order-of-tree luck, not correctness.
- **Fix sketch:** adapter selectors become **scored candidate lists**: `resourceId` (exact, primary, +10) → contentDescription-exact (+6) → text-exact (+5) → hint (+3) → className+clickable context (+1); require score ≥ threshold; tie-break by bounds centralities; log the winner + runner-up for telemetry. Legacy `findViewByText`-style helpers (legacy file :831-899, incl. a real viewId matcher at :899) can donate logic to the new matcher.

### D3 · MEDIUM — fixed sleeps still gate step transitions
- **Evidence:** `robot/RobotHand.kt:127` — `delay(STEP_DELAY_MS)` (500 ms) unconditionally before each step; legacy uses `sleep(STEP_DELAY_MS)` / `× 2` at :104/:129/:158/:163/:181 with comments like "wait for search results" (hope-based timing).
- **Impact:** slow device/animation → robot acts on a half-settled screen (clicks land on stale coordinates); fast device → 500 ms × N steps of dead latency. Both directions hurt.
- **Fix sketch:** replace unconditional sleep with `harness.waitForNode(step.selector, timeoutMs)` — the correct machinery **already exists** (Harness.kt:94) — and only fall back to a short settle delay when the step has no selector (pure gestures).

### D4 · MEDIUM — telemetry is plan-level; effectiveness is unmeasurable
- **Evidence:** only `INTENT_PARSED` (:62) + `EXECUTION_RESULT` (:68) fire — one record per plan. No per-step outcome event. So the team can see "WhatsApp flow failed" but not "step 3 of 5, contact-search click, 3rd attempt, field-miss".
- **Impact:** the developer's own words — not effective yet — cannot be quantified or located. The single most valuable investment right now.
- **Fix sketch:** emit `EXEC_STEP` per step: `{planId, stepIndex, primitive, appPackage, locale, attempt, outcome∈{ok,unverified,failed,cancelled}, durationMs, winnerSelectorKey, viewIdMatched}`. One week of real usage produces the ranked defect list automatically.

### D5 · MEDIUM — the executor has zero dedicated tests
- **Evidence:** `app/src/test/java/com/vdx/**` — 20 test files cover parser, intents, memory, telemetry, VAD, prompt templates… **no file instantiates RobotHand/FlowCatalog**. The AdversarialAudit + CapabilityCorpus guard *understanding*, not *execution*.
- **Impact:** the part that must succeed in the blind user's hand is the part with no correctness bar. Any selector fix (D1/D2) can silently regress flows.
- **Fix sketch — execution corpus (mirrors the parser corpus 1:1):** record real screen states per flow (chat-list → search → chat-open → typed → send-visible) as **fixture ScreenModels** (plain Kotlin data, JVM-testable); each test = start fixture + one step → assert `ExecutionResult` + end fixture + selected viewId. FlowCatalog completeness test: every flow's steps reference reachable selectors across the fixtures. Bar: 100%, same as the corpus. This makes D1–D3 fixes *verifiable* forever.

### D6 · LOW — two RobotHands (confusion tax)
- **Evidence:** legacy file (982 ln) duplicates flows (executeWhatsApp/Uber/YouTube/Sms/Email at :112/:220/:280/:382/:489) with its own finders (:831-899) and a *stricter* matcher variant; the two matchers even disagree (one OR-matches with null-skip per the "ponytail" comment at :643, one AND-matchers at Harness:122+). Only the dev harness uses legacy today, but new contributors cannot know which is canonical.
- **Fix sketch:** delete legacy file + its two `RobotHand(this, null)` call sites (move `runHarnessTest` onto the sonic robot); one robot, one matcher, one truth. Do this *after* D1/D2 land so the harness tests exercise the improved matcher.

### D7 · HIGH (found in deep pass) — the AdapterRegistry + adapters layer is DEAD CODE
- **Evidence A:** grep of the entire `app/src/main` tree — **nothing calls `AdapterRegistry.forIntent/forPackage`** outside its own file; no production or test code instantiates or references any of the 9 adapters (`WhatsAppAdapter`, `UberAdapter`, `GmailAdapter`, `PhoneAdapter`, `PlayStoreAdapter`, `YouTubeAdapter`, `ChromeAdapter`, `SettingsAdapter`, `GenericAdapter`) outside the `adapters/` package itself.
- **Evidence B:** the actual production path never touches them: `SonicEngine.kt:264` → `planner.plan(intent, …)` → `Planner.kt:45-73` builds plans from **PaymentBlocklist → FlowCatalog → AppKnowledgeBase seed**, and `FlowCatalog.whatsAppFlow` hardcodes its own `NodeSelector(contentDescription="Send"…)` inline (`FlowCatalog.kt:112+`, `searchAndOpenChat` just above).
- **Impact:** the ONLY tests that exercise executor-adjacent logic (`AdversarialAuditTest.F14`, `WhatsAppParseTest`) test **WhatsAppAdapter directly** — meaning the audit's F14 "Send doesn't match random ImageButton" guard protects code that **the app never runs in production**. The real production selectors (FlowCatalog inline + KnowledgeSeed targets like `targetElement = "Send"`) are untested and unguarded. This is why D2's weakness survives: the tested layer is a ghost layer.
- **Fix sketch (chooses ONE of two directions, team decision):**
  - **(a) Route production through the adapters** — Planner/FlowCatalog get their selectors from `AdapterRegistry.forIntent(type)`; then F14-style tests guard the REAL path; or
  - **(b) Delete the adapters layer** and move its scoring logic (see D2 fix) into FlowCatalog selectors.
  Either way: the layer that is tested must be the layer that executes.

### D8 · MEDIUM (found in deep pass) — KnowledgeSeed teaches English-only, text-only targets
- **Evidence A:** `AppKnowledgeBase.seedIfEmpty` seeds **every** entry with `locale = LOCALE_EN` (`AppKnowledgeBase.kt` seed block) — despite the schema being locale-keyed (`AppKnowledge` entity: `packageName+action+locale` unique index; `getSteps` supports requests in any locale with English fallback). **No Hindi/India-locale seeds exist**, and no runtime code path calls `putKnowledge` (writes exist as API only: `AppKnowledgeBase.kt:47, :73` are seeds; nothing else upserts).
- **Evidence B:** seed targets are **bare English label strings** (`NavStep("Tap send", targetElement = "Send")`, `"Search"`, `"Message"` — `AppKnowledgeBase.kt:110-170`) → `Planner.makeSelector` converts them to `NodeSelector(text = resolved)` **only** (`Planner.kt: makeSelector` comment: "Try text match first, then contentDescription" — but code does text only).
- **Impact:** the KB — the designed antidote to fragile selectors ("navigation knowledge updated without app redeployment", per the class doc) — currently *injects more English-string fragility into plans* instead of curing it. On a Hindi-locale phone this path double-fails: label wrong AND locale rows absent.
- **Fix sketch:** (1) seed `hi` locale rows using `resourceId`-anchored targets (needs D1 first); (2) extend `makeSelector` to pass `contentDescription` too; (3) wire the *learning loop* — after every verified `Success` (`isHonestSuccess()`), persist the winning selector into the KB for that app+locale (this is the self-healing design the entity was built for; telemetry from D4 feeds it).

### D9 · LOW-MEDIUM (found in deep pass) — dual extractor disagreement, sonic side silently weaker
- **Evidence:** two screen-extractor stacks coexist: legacy `ScreenContentExtractor.kt` (event-fed cache, captures `viewId`, used by `VdxAccessibilityService.onAccessibilityEvent` → `onContentChanged`) vs sonic `Harness.traverse` (fresh full-tree walk per `readScreen`, drops `viewId`). They even disagree on matcher semantics (legacy finders OR-match with null-skip incl. viewId variant at `RobotHand.kt:831-899`; sonic `matchesAll` AND-matches, no viewId).
- **Impact:** `VdxAccessibilityService` maintains a view-Id-aware cache nobody consumes (data with no reader), while the sonic path that everyone consumes re-walks the tree and loses the strongest field. Two extractors = two truths; contributors patch the wrong one.
- **Fix sketch:** after D1, make `Harness.traverse` reuse the viewId from the extractor's cache or capture its own; single canonical traversal.

---

## 4 · Suggested PR sequence (smallest-risk-first)

1. **PR-1 — instrumentation first (D4):** step-level telemetry event + dump of selector results. No behavior change; purely makes the next PRs measurable. *(Safe to ship any day.)*
2. **PR-2 — anchor fix (D1):** `UiElement.viewId` + capture + corrected `matchesAll` + fix the wrong-property matcher. With D5's corpus this is provably safe.
3. **PR-3 — scored selectors (D2):** rewire FlowCatalog/WhatsApp selectors to scored anchors; drop bare `contains("send")`. Update F12 audit case to assert the scored winner **on the production path** (see D7).
4. **PR-4 — replace sleeps (D3):** `waitForNode`-gated transitions; keep 8 s deadline; log slow transitions.
5. **PR-5 — corpus tests (D5):** fixture set + `RobotHandExecutionCorpusTest` (100% bar, joins the honest-eval suite next to AdversarialAuditTest). **Must cover the layer that actually executes** (FlowCatalog/KB-driven plans), not the ghost adapters (D7).
6. **PR-6 — single source of selectors (D7):** team picks (a) route production through adapters or (b) fold adapters into FlowCatalog; then delete the other. Tested layer = executed layer.
7. **PR-7 — locale-correct KB (D8):** Hindi seeds with resource-id targets + `makeSelector` cd-support + learning loop (persist verified winners per locale; D4 telemetry feeds it).
8. **PR-8 — one extractor (D9):** canonical traversal; legacy `ScreenContentExtractor` cache either consumed or retired; rerun harness.
9. **PR-9 — delete legacy robot (D6):** remove the old file; rerun harness on sonic robot.

---

## 5 · Plain-language verdict for Prithu

Your developer told you the truth. He built a robotic hand with honest reflexes — it double-checks each movement, admits when it's unsure, and never lies about success. What it lacks is good *eyes* (it recognizes buttons by single words like "send", which break when the phone speaks Hindi or the app updates) and a *scorecard* (nobody has counted its successes and failures per step, so "not effective" is a feeling, not a number). The deep pass added three sharper findings: the well-tested adapter layer is actually **dead code** (production uses inline/KB selectors instead — so the audit guard protects the wrong layer), the self-healing knowledge base currently seeds **English-only text targets** (double-fails on Hindi phones), and two extractor stacks disagree on what they capture. The fix is not starting over — it is: give it the stable button-IDs Android offers, make it rank candidates instead of guessing, route production through the layer the tests actually guard, count every step, seed the user's real languages, and test against recorded screens. Nine small pull-requests, in the order above, turn "not effective yet" into "measured, improving, and eventually trustworthy" — and the knowledge base was already built to become the self-healing brain the moment the learning loop is wired.

*Evidence note: every defect above was read directly from the repo at the commit current on 2026-10-02; nothing is inferred from documentation or vibes. D7-D9 were found in the second deep-evidence pass (full-tree greps of adapter/KB/extractor usage); each carries its grep or sed evidence in the text.*

---

## Appendix A · Evidence index (file:line quick reference)

| Defect | Primary evidence | Secondary |
|---|---|---|
| D1 dropped anchor | `SonicModels.kt:258` (UiElement fields) · `Harness.kt:169+` (traverse) | legacy capture at `ScreenContentExtractor.kt:181` |
| D1 mis-wired matcher | `Harness.kt:130` (`el.packageName.contains(sel.resourceId)`) | `NodeSelector.resourceId` field exists (`SonicModels.kt`) |
| D2 contains() selectors | `WhatsAppAdapter.kt` findContactField/findMessageField/findSendButton | `GenericAdapter.kt` (hint "to"/"contact") · F12 = `AdversarialAuditTest.kt:203` (F14 test) |
| D3 fixed sleeps | `sonic/robot/RobotHand.kt:127` (`delay(STEP_DELAY_MS)`) | legacy `:104,:129,:158,:163,:181` sleep comments |
| D4 plan-level telemetry only | `sonic/robot/RobotHand.kt:62,:68` (INTENT_PARSED, EXECUTION_RESULT) | no other Telemetry.log in robot |
| D5 no executor tests | grep of `app/src/test` + `app/src/androidTest` — only `eval/AdversarialAuditTest.kt` + `benchmark/CapabilityCorpusTest.kt` mention adapter/robot, and both target WhatsAppAdapter/IntentParser, not RobotHand | 20 test files total |
| D6 two robots | `MainActivity.kt:656,:713` (`RobotHand(this, null)`) vs `SonicEngine.kt:51` (sonic lazy) | matcher disagreement: legacy `:831-899` OR-match vs `Harness.kt:122-140` AND-match |
| D7 dead adapters layer | zero external callers of `AdapterRegistry`/adapters (full grep) | production plan path: `Planner.kt:45-73` (Blocklist→FlowCatalog→KB) · FlowCatalog inline selectors (`whatsAppFlow`, `searchAndOpenChat`) |
| D8 English-only KB | `AppKnowledgeBase.seedIfEmpty` (locale=EN for all) · `AppKnowledge` entity locale index | `NavStep` targets "Send"/"Search"/"Message" (`AppKnowledgeBase.kt:110-170`) · `Planner.makeSelector` text-only |
| D9 dual extractors | `VdxAccessibilityService.onAccessibilityEvent` → `ScreenContentExtractor.onContentChanged` (cache maintained) | sonic `Harness.readScreen` full re-walk, no viewId |

## Appendix B · What the solid parts actually execute (for the fix authors)

- **Plan step model:** `ActionStep(id, ActionPrimitive, description, expectedPostcondition?, timeoutMs=5000, retryCount=2)`; primitives: OpenApp, WaitForPackage(8s), ReadUiState, FindNode, WaitForNode(8s), FocusNode, SetText, ClickNode, LongClickNode, ScrollContainer, SelectOption, ReadVisibleResult, AskUser, WaitForUserConfirmation, DispatchGesture, GoBack, FailWithReason, SystemAction (`SonicModels.kt:196-222`).
- **Honest-success law:** `isHonestSuccess() = this is Success` (`SonicModels.kt:326`) — the voice loop gates DONE/ERROR state and memory episodes on it (`SonicEngine.kt:265-273`). Unverified ≠ success, everywhere. Keep this law.
- **Recovery constants:** `MAX_RECOVERY_ATTEMPTS = 2`, `RECOVERY_DELAY_MS = 350` (coroutine delay, screen re-read inside each attempt — `robot/RobotHand.kt:378-403`).
- **Click ladder:** ACTION_CLICK → clickable-parent walk → gesture tap at node bounds as last resort (`robot/RobotHand.kt:532-546`).
- **a11y-optional degradation:** non-a11y primitives (OpenApp/Wait/Gesture-less) never crash on null service; a11y steps degrade to `Unverified` with reason (`robot/RobotHand.kt:138-152`) — the reason a call flow works with accessibility off.
- **KB round-trip is wired:** serialize/deserialize JSON NavSteps, locale-aware get with EN fallback, entity indexed `packageName+action+locale` — the storage is production-grade; only what's *stored* (D8) and *who writes* is missing.