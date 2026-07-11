# Rules Special Actions Scanner

A [Hubitat Elevation](https://hubitat.com/) app for Rule Machine (RM) and Button Controller (BC) rules.

The app scans RM/BC rules and builds a sortable table showing whether each rule appears to contain selected Rule Machine special actions, and which hub modes (if any) each rule uses. It is intended as a read-only audit/reporting tool. It does **not** modify rules, Private Booleans, triggers, actions, or rule settings.

---

## What It Detects

### Special-action keywords

The app searches each rule's internal configuration JSON for these Rule Machine keywords:

| Display label | Internal keyword |
|---------------|------------------|
| **While** | `getWhile` |
| **Repeat** | `repeatActs` |
| **End Repeat** | `getEndRepeat` |
| **Stop Repeat** | `getStopRepeat` |
| **Wait for Expression** | `getWaitRule` |
| **Wait for Event** | `getWaitEvents` |

A detected keyword usually means that the rule contains the corresponding type of special action, wait, or repeat structure.

### Live vs. stale keyword matches

Rule Machine does not always garbage-collect deleted actions: their settings, the matching entries in the state `actions` map, clipboard buffers (Cut/Copy actions, stored in state entries such as `clip`, `clipList`, `cutAction`, `copyL`), and old variable-settings snapshots (`varSettingsOld`) can remain in a rule's stored data indefinitely. A plain whole-text keyword search would therefore report special actions that are no longer part of the rule.

To separate real matches from leftovers, the app attributes every keyword occurrence to an **action index** and checks that index against the rule's `actionList` — Rule Machine's authoritative list of the actions the rule actually still contains, captured in Phase 1. Rule Machine stores each action's type as a bare keyword in a setting whose *name* carries the index (e.g. `actType.6 = repeatActs`, `actSubType.3 = getWaitRule`), and mirrors it in the state `actions` map's `method` field, keyed by the same index. The scan recognizes:

- `{name, value}` settings entries and plain map-keyed settings in the configuration JSON, attributing each keyword value to the trailing index of its setting name
- the legacy name-embedded form (`getWhile3`), for older Rule Machine storage
- the state `actions` map (each entry's `method`, keyed by action index) compared against `actionList`
- keyword tokens inside clipboard buffers and old-settings snapshots, which count as stale by definition

Rule Machine uses more than one vocabulary for the same action (`repeatActs` in `actType`, `getRepeat` in `actSubType`/`method`); a token map folds these onto one column. Occurrences from all sources are merged per keyword:

- Live occurrences only → **live** (green ✓)
- Live occurrences *plus* stale leftovers for the same keyword → **mixed** (green+red ✓✓ pair)
- Stale occurrences only → **stale** (red ✓)
- No occurrence → not found (dash)

Stale matches indicate leftover cruft from deleted actions, clipboard copies, or old settings — useful when hunting rules that need cleanup; the mixed pair means the action is real but the rule also carries cruft to clean. The **Treat stale-only keyword matches as not found** toggle (Controls section) renders stale-only matches as dashes and mixed matches as plain green checkmarks; it re-renders from cached scan data, so no re-scan is needed.

Occurrences that cannot be attributed to any action index fall back to raw-text matching and count as live, and if no live action-key set could be read for a rule (older platform, unreadable statusJson, empty actionList), every keyword hit in that rule is reported as live — unreadable or unrecognized data never produces false negatives. The setting-name and `actionList` key formats are matched against current Rule Machine 5.x internals (validated against real rules) and are not a formal public API.

### Modes

The app also parses each rule's configuration JSON and collects the hub modes the rule uses. It detects native `mode`-type inputs as well as Rule Machine's internal enum settings that hold mode selections:

| Setting name pattern | Rule Machine usage |
|----------------------|--------------------|
| `modesX<n>` | Mode trigger selections (e.g. *Mode becomes Away*) |
| `modes<n>` | Mode condition / required-expression selections (e.g. *Mode is Day*) |
| `mode.<n>` | Set Mode action target (e.g. *Mode: Away*) |
| `modesY<n>` | Legacy mode restrictions |

Rule Machine stores these selections as mode **IDs**; the app translates them to mode names using this hub's own mode list (`location.getModes()`). Values that do not correspond to a mode defined on this hub are ignored, which filters out unrelated settings that happen to match a name pattern.

Modes referenced only indirectly — for example via hub variables or custom commands — are not detected.

---

## Installation

1. In the Hubitat web UI, go to **Apps Code → + New App** and paste in the app's Groovy source.
2. Save the app code.
3. Go to **Apps → + Add User App** and select **Rules Special Actions Scanner 1.16**.
4. The app will attempt to create an OAuth token automatically on first open. If it does not, enable OAuth manually in Apps Code for this app and re-open it.
5. No scan runs automatically on install. Click **Scan All RM/BC Rules for Special Actions** to begin.

---

## Usage

### Scanning

Click **Scan All RM/BC Rules for Special Actions**.

The scan has two phases:

**Phase 1** reads each rule's runtime status JSON to collect basic report data, including **Last Run**, and captures the rule's live action keys from its `actionList` state entries plus state-side keyword hits (the `actions` map and clipboard buffers), used for live/stale keyword classification.

**Phase 2** reads each rule's internal configuration JSON, walks its parsed structure to attribute each special-action keyword to its action index, classifies each occurrence as live or stale against the rule's live action keys, merges in the Phase 1 state-side hits, and parses the JSON to collect the rule's mode usage.

Phase 2 uses a queued `configure/json` pass with only a small number of simultaneous requests. This helps prevent one very large rule or dropped response from stopping the rest of the scan. A rule whose request times out or fails is automatically retried once, with a longer 90-second timeout, at the back of the queue; only rules that fail both attempts are marked unknown/skipped. A late-arriving success for a request the watchdog had already given up on is accepted instead of discarded.

The overall Phase 2 time limit scales with the number of rules, so large installs (300+ rules) are not cut off mid-scan. If Phase 2 ever does end early (heartbeat or total-timeout backstop), the app page and log state explicitly how many rules were never reached; those rules are marked with a red **?**, and running the scan again may fill them in.

After a scan, the top summary line shows:

- **Rules scanned**
- **Rules with Special Actions**
- **Rules with stale keyword leftovers**
- **Unknown/skipped**
- Counts for **While**, **Repeat**, **End Repeat**, **Stop Repeat**, **Wait for Expression**, and **Wait for Event** (live and mixed matches; the stale-leftover count covers both stale-only and mixed rules)
- **Rules using Modes**

The scan-time line shows Phase 1 time, Phase 2 time, and total scan time.

Clicking **Done** and reopening the app re-renders the table from cached data, so no rescan is needed for display-only changes. The cached table may be stale until a new scan is run.

---

## Special Actions Table

The table lists every discovered RM and BC rule with the following columns:

| Column | Description |
|--------|-------------|
| **Rule ID** | Hubitat internal app ID for the rule |
| **Rule** | Rule name, linked directly to its configuration page |
| **App Type** | `RM` for Rule Machine or `BC` for Button Controller |
| **While** | Checkmark when `getWhile` is detected |
| **Repeat** | Checkmark when `repeatActs` is detected |
| **End Repeat** | Checkmark when `getEndRepeat` is detected |
| **Stop Repeat** | Checkmark when `getStopRepeat` is detected |
| **Wait for Expression** | Checkmark when `getWaitRule` is detected |
| **Wait for Event** | Checkmark when `getWaitEvents` is detected |
| **Modes** | Comma-separated list of the hub modes the rule uses (e.g. `Away, Day`) |
| **Last Run** | Date and time of the most recent trigger event, formatted as `yyyy-MM-dd HH:mm` when available |

### Cell meanings

| Cell | Meaning |
|------|---------|
| Green **✓** | The keyword was found in live actions of this rule only |
| Green+red **✓✓** | The keyword was found in a live action AND in stale/leftover entries — the action is real, and there is also cruft to clean |
| Red **✓** | The keyword was found only in stale/leftover entries (deleted actions, clipboard copies, old settings) not referenced by the rule's current actionList |
| Mode names | The rule uses the listed hub modes (Modes column) |
| Grey **—** | The keyword was not detected, or no mode settings were found |
| Red **?** | The rule's configuration JSON could not be read after two attempts, or the rule was never reached before the scan ended |

When **Treat stale-only keyword matches as not found** is enabled in Controls, red checkmarks are shown as dashes, mixed pairs are shown as plain green checkmarks, and the legend above the table notes that stale-only matches are currently suppressed.

### Sorting

Click any column header to sort by that column. Click the same header again to reverse the sort direction. The Modes column sorts alphabetically by the mode-name list.

### Filtering

Use the rule-name filter above the table to show only matching rules. The filter supports plain substring matching and `*` / `?` wildcards.

The filter state is browser-side and does not require clicking **Done**.

### Hide rows with no Special Actions

Click **Hide rows with no Special Actions** to hide rows where all six keyword columns are known and none of the keywords were detected. Mode usage does not affect this filter.

Rows marked with a red **?** remain visible, because the app could not determine whether those rules contain special actions. Rows with stale-only matches also remain visible — unless stale matches are suppressed via the Controls toggle, in which case stale-only rows are hidden too.

Click **Show all rows** to restore the hidden rows.

### Hide columns

The hide-column buttons above the table let you show or hide:

- Rule ID
- App Type
- While
- Repeat
- End Repeat
- Stop Repeat
- Wait for Expression
- Wait for Event
- Modes
- Last Run

Column visibility persists without clicking **Done**.

---

## Reports

In the **Controls** section, after a scan:

- **Open Printable Report** opens a formatted HTML report of the scanned rules.
- **Download CSV** downloads the same table data as a CSV file.

Reports use the cached data from the most recent scan and include the Modes column. Both reports respect the current **Treat stale-only keyword matches as not found** setting.

The CSV export uses the friendly column labels:

```text
Rule ID, Rule, App Type, While, Repeat, End Repeat, Stop Repeat, Wait for Expression, Wait for Event, Modes, Last Run
```

Keyword columns contain `Yes` (live match), `Yes+Stale` (mixed live+stale match), `Stale` (stale-only match), `No` (not found), or `?` (unknown/skipped). When stale suppression is enabled, stale-only matches are exported as `No` and mixed matches as `Yes`. The Modes value is the comma-separated mode-name list (blank when the rule uses no modes, `?` when the rule was unknown/skipped).

---

## Controls Section

| Control | Description |
|---------|-------------|
| **App instance name** | Rename this app instance |
| **Reset to App Name** | Reset the instance label back to the app's default name |
| **Open Printable Report** | Open a printable HTML report after a scan |
| **Download CSV** | Download a CSV export after a scan |
| **Treat stale-only keyword matches as not found** | Show stale-only matches (red checkmarks) as dashes and mixed matches as plain green checkmarks; takes effect immediately from cached scan data, no re-scan needed |
| **Enable debug logging** | Turns on verbose debug output to the Hubitat log; auto-disables after 30 minutes |

This app intentionally has no Private Boolean setters, bulk-apply controls, scheduled apply controls, or rule-modification controls.

---

## Debug Logging

When **Enable debug logging** is turned on in the Controls section, additional output appears in the Hubitat log, including:

- **Lifecycle events** — install, update, rename, and scan-cancel events
- **Rule discovery count** — number of RM/BC rules found from `/hub2/appsList`
- **Per-rule Phase 1 results** — rule name, ID, app type, Last Run, live action keys, and state-side keyword hits as rules are scanned
- **Per-rule Phase 2 results** — keyword-detection (live/stale) and mode-detection results from each rule's `configure/json` response
- **Hub mode map** — the mode ID → name map read from the hub at the start of Phase 2
- **Phase 2 queue progress** — active request count, completed count, heartbeat resets, and watchdog activity
- **Timeout/retry/unknown handling** — rules whose configuration JSON timed out or failed, retry attempts, late-response acceptance, and rules marked unknown after both attempts
- **Re-render-from-cache confirmations** — when the table is rebuilt from cached data on Done press

Debug logging auto-disables after 30 minutes to avoid filling the hub log.

---

## Technical Notes

The app uses the following Hubitat local/internal endpoints:

| Endpoint | Purpose |
|----------|---------|
| `/hub2/appsList` | Discover RM and BC rules |
| `/installedapp/statusJson/{appId}` | Read per-rule status data — Last Run, live action keys (`actionList`), and state-side keyword hits (`actions` map, clipboard buffers) — during Phase 1 |
| `/installedapp/configure/json/{appId}` | Read per-rule configuration JSON for keyword and mode detection during Phase 2 |
| `/apps/api/{appId}/setpref` | Persist column-hide preferences |
| `/apps/api/{appId}/report` | Printable HTML report |
| `/apps/api/{appId}/RM-BC_Special_Actions.csv` | CSV export |
| `/hub2/userAppTypes`, `/app/ajax/code`, `/app/edit/update` | One-time OAuth auto-enable on first open, if OAuth is not already enabled |

Mode ID → name translation uses the standard app API `location.getModes()` rather than an internal endpoint. The mode map is rebuilt at the start of each Phase 2 pass, so mode renames on the hub are picked up on the next scan.

> **Warning:** The `/hub2/*`, `/installedapp/*`, and `/app/*` endpoints above are internal Hubitat APIs and are not formal public APIs. They could change in a future Hubitat platform update.

---

## Limitations

- **Read-only keyword detection.** The app detects the presence of selected internal keyword strings. It does not parse or understand the full logical structure of the rule.
- **Live/stale classification depends on Rule Machine internals.** The setting-name conventions (`actType.<n>`, `actSubType.<n>`), the state `actions` map / `actionList` formats, and the clipboard-buffer names used to separate live from stale matches are matched against current Rule Machine 5.x internals (validated against real rules) and are not a formal public API. Occurrences that cannot be attributed to an action index — and all hits in any rule whose live action-key list cannot be read — are reported as live rather than risk false negatives.
- **Mode detection depends on Rule Machine's setting names.** Modes are found by matching Rule Machine's internal setting-name conventions (`modesX<n>`, `modes<n>`, `mode.<n>`, `modesY<n>`) and native `mode`-type inputs. A future Rule Machine version that introduces a new convention would require a small pattern update in the app.
- **Indirect mode references are not detected.** Rules that set or test modes via hub variables or custom commands will show no modes.
- **Internal Hubitat APIs.** The app relies on internal JSON endpoints whose structure could change in future Hubitat platform versions.
- **Large Phase 2 responses.** Very large rule configuration JSON may time out. The affected rule is automatically retried once with a longer 90-second timeout; only if both attempts fail is it shown with a red **?**, and the scan continues.
- **Unknown rows are preserved by the row filter.** Unknown/skipped rows remain visible when **Hide rows with no Special Actions** is active, because the app cannot safely classify them as having no special actions.
- **Basic Button Controller excluded.** Basic Button Controller rules are not included because they use a different internal structure.
- **Cached display can be stale.** Clicking **Done** and reopening can re-render cached data without a rescan; run a scan to refresh actual hub data.
- **Saving settings cancels an in-progress scan.** Clicking **Done** while a scan is running abandons that scan; click Scan again to re-run it.

---

## Credits

Designed initially by John Land. Built with AI assistance and adapted from the structure of the Private Boolean Manager app.
