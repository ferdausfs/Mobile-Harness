# Z.ai auth probe — manual on-device verification

The auth-probe defect fixed in v1.0.23 could NOT be caught by JVM tests or by
headless Chromium: headless Chromium **awaits** a Promise-returning script,
while a real Android `WebView.evaluateJavascript` hands its callback the
script's **synchronous** completion value (a Promise serializes to `"{}"` and
is never awaited). The store-and-poll contract is pinned by
`AuthProbePollPolicyTest`, `ZaiAuthProbePollTest` and
`EvaluateJavascriptCallSiteGuardTest`, but the final proof is a real device.

## Steps (on a phone with v1.0.23 or newer)

1. Open the app → **Z.ai** tab. If the card shows "Not signed in", tap
   **Sign in** and complete the login (Google or email). The sheet should
   close itself within ~2 seconds of the login finishing (the 2s session poll
   now reads a real result) and the card must show **Connected**.
2. If the card still shows "Not signed in", open **Diagnostics** on the Z.ai
   tab and check `[last connection check]`:
   - Expected healthy: `outcome: signed-in-bearer` (or `signed-in-cookie`).
   - If it reads `probe-error`, the `error=` field must now name the real
     reason:
     - `probe result did not arrive within Nms (page may still be loading or
       the probe fetch hung)` → the page/probe fetch never settled on the
       device network; retry the check once, then report it.
     - any other text → report it verbatim.
3. Tap **Test connection** (or Check) on the card: expected `signed-in-bearer`
   with `localToken=yes` when the site kept the token in localStorage, or
   `signed-in-cookie` when only the cookie path confirmed.
4. Sign out, sign in again, then close the sheet with **X** immediately after
   login: the final probe must still report the session (card → Connected),
   proving the close path reads the stored result too.
5. Optional: restart the app and re-check — the previous-run line in
   Diagnostics should show the last check's outcome.

## What counts as PASS

- Sheet auto-closes after login; card shows Connected.
- Diagnostics `probe:` line contains `outcome=signed-in-bearer|signed-in-cookie`
  and `localToken=yes|no` matching reality — and never
  `probe did not return a readable result` on a loaded chat.z.ai page.

## What counts as FAIL / to report

- Sheet stays open after a successful login.
- Diagnostics shows `probe-error` with the timeout message on a stable
  connection (twice in a row), or any new error text.
