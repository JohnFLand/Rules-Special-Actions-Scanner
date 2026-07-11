/*
 *  Rules Special Actions Scanner
 *
 *  Scans Rule Machine and Button Controller child apps, reports each rule's
 *  App Type and Last Run time, and checks each rule's internal configuration
 *  JSON for selected Rule Machine special-action keywords.
 *
 *  Based on John's Private Boolean Manager structure, but this is read-only:
 *  it does not read, set, toggle, schedule, or bulk-apply Private Booleans.
 *
 *  Keywords checked:
 *      getWhile
 *      repeatActs
 *      getEndRepeat
 *      getStopRepeat
 *      getWaitRule
 *      getWaitEvents
 *
 *  1.06: Adds a Modes column. During Phase 2 the rule's configuration JSON is
 *  parsed and walked recursively; any setting whose "type" is "mode" contributes
 *  its selected mode name(s) to the rule's Modes list. This catches mode
 *  triggers, mode conditions/required expressions, and Set Mode actions that
 *  are configured through Hubitat mode inputs. Rules that reference modes only
 *  indirectly (e.g., via hub variables or custom commands) are not detected.
 *
 *  1.07: Fixes mode detection for Rule Machine 5.x / Button Controller. RM does
 *  not use "mode"-type inputs; it stores mode selections as plain enum settings
 *  holding mode IDs, using naming conventions:
 *      modesX<n>   mode trigger selections    (e.g. modesX1 = ["4"])
 *      modes<n>    mode condition selections  (e.g. modes2  = ["1"])
 *      mode.<n>    Set Mode action target     (e.g. mode.3  = 4)
 *      modesY<n>   legacy mode restrictions
 *  The scanner now also matches settings by these name patterns and translates
 *  the ID values to mode names via location.getModes(). Values that do not map
 *  to a known mode ID or mode name on this hub are ignored, which filters out
 *  unrelated settings that happen to match the name pattern. "mode"-type inputs
 *  are still detected as before.
 *
 *  1.08: Adds stale-match detection for special-action keywords. Rule Machine
 *  never garbage-collects deleted actions: their settings, clipboard entries
 *  (clipList), and old variable settings (varSettingsOld) remain in the rule's
 *  stored configuration forever, so the previous whole-text keyword search
 *  reported special actions that are no longer part of the rule ("false
 *  positives"). Phase 1 now also captures the rule's live action keys from the
 *  statusJson appState entries whose names begin with "actionList" (RM's
 *  authoritative list of the actions the rule actually still contains). In
 *  Phase 2, every keyword occurrence in the configuration JSON has its numeric
 *  index suffix (e.g. the "2.1" in "repeatActs2.1") checked against that live
 *  key set:
 *      - at least one occurrence maps to a live action  -> live (green check)
 *      - occurrences exist but none maps to a live key  -> stale (orange check)
 *      - no occurrence                                  -> not found (dash)
 *  Stale matches indicate leftover cruft from deleted actions, clipboard
 *  copies, or old settings — useful when hunting rules that need cleanup. A
 *  new "Treat stale-only keyword matches as not found" toggle (Controls
 *  section) renders stale matches as dashes instead, for anyone who just
 *  wants them suppressed; it re-renders from cached scan data, no re-scan
 *  needed. If no live action-key set could be read for a rule (older platform,
 *  unreadable statusJson, empty actionList), detection falls back to the
 *  previous behavior and reports every keyword hit as live rather than risk
 *  false negatives. The suffix/actionList key formats are a best-guess match
 *  against RM 5.x internals and are not a formal public API.
 *
 *  1.09: Phase 2 reliability fixes for large installs (300+ rules) and slow
 *  hubs, based on a scan that hit the fixed 10-minute Phase 2 cap with 100+
 *  rules left unscanned, dozens of HTTP 408 timeouts, and a
 *  ConcurrentModificationException in configurePump:
 *  - The Phase 2 total-timeout backstop now scales with rule count instead
 *    of a fixed 600 seconds, so big scans are no longer cut off mid-queue.
 *    The heartbeat still catches genuinely stalled scans within 30 seconds.
 *  - Rules whose configure/json request times out (HTTP 408 / watchdog) or
 *    fails are automatically retried once, with a longer 90-second timeout,
 *    at the back of the queue. Only rules that fail both attempts are marked
 *    unknown. A late-arriving success for a request the watchdog had already
 *    given up on is also accepted instead of discarded.
 *  - The heartbeat no longer treats slow-but-bounded in-flight requests as a
 *    stall; it only fires when nothing is in flight and nothing is being
 *    launched.
 *  - All Phase 2 shared state (in-flight map, results, counters, retry
 *    bookkeeping) is now guarded by a lock. Previously the pump iterated the
 *    in-flight map while concurrent async callbacks mutated it, causing the
 *    ConcurrentModificationException; finalize could also run concurrently
 *    from the heartbeat, the total-timeout, and normal completion.
 *  - When Phase 2 ends before every rule was scanned, the log and the app
 *    page now say so explicitly (how many rules were never reached), instead
 *    of a "Phase 2 complete" message that looked like a normal finish.
 *
 *  1.10: Adds a fourth keyword state for the mixed case. Previously,
 *  detection stopped at the first live occurrence of a keyword, so a rule
 *  with an active While AND leftover While entries from a deleted action
 *  looked identical to a clean rule — its cruft was invisible and it was
 *  excluded from the stale-leftover count. Detection now examines all
 *  occurrences and distinguishes:
 *      true        -> live only            (green check)
 *      "livestale" -> live + stale cruft   (green check with orange *)
 *      "stale"     -> stale cruft only     (orange check)
 *      false       -> not found            (dash)
 *  Occurrences without an index suffix still count only toward the live side
 *  (they cannot be judged) and never mark a rule as carrying cruft. The
 *  "Rules with stale keyword leftovers" summary count now includes mixed
 *  rules; per-keyword counts include plain-live and mixed. The suppress
 *  toggle shows stale-only cells as dashes and mixed cells as plain green.
 *  CSV/print exports use "Yes+Stale" for the mixed state. Also: a successful
 *  configure/json retry is now logged explicitly, and the Phase 2 completion
 *  log line includes the stale-leftover rule count.
 *
 *  1.11: Fixes a lingering Phase 2 progress message. The pump wrote its
 *  "N/M complete, X active" status line outside the lock, so a slow pump
 *  thread could stamp a stale progress message onto the app page after
 *  finalize had already cleared it (observed as "24/25 complete, 1 active"
 *  persisting in state after a successful 25-rule scan). The status write
 *  now happens inside the lock and only while the scan is still live, and
 *  finalize clears scanStatus after taking ownership of the scan rather
 *  than before. Field validation note: a real-hub scan confirmed Phase 1
 *  liveKeys extraction works for both RM and BC rules, and that actionList
 *  keys are plain integers — so the liberal prefix matching in
 *  isActionKeyLive cannot mistakenly classify leftover entries as live.
 *
 *  1.12: Reworks keyword detection around ground truth from a real rule's
 *  status page. RM does NOT append the action index to the keyword
 *  (the 1.08 assumption); the keyword is a bare setting VALUE and the index
 *  is a dot-suffix on the setting NAME: actType.6 = "repeatActs",
 *  actSubType.3 = "getWaitRule". Under the old suffix-regex, every
 *  occurrence therefore read as "bare" and classified live — right answers
 *  on clean rules, but structurally blind to a genuine settings ghost like
 *  a leftover actType.4. Detection is now a structured walk of the parsed
 *  configure/json (mirroring the modes walk) that attributes each keyword
 *  VALUE to its NAME's trailing index and classifies by actionList
 *  membership. Three shapes are recognized: {name, value} settings entries,
 *  map-keyed settings, and the legacy name-embedded form ("getWhile3").
 *  Additionally, Phase 1 now mines statusJson state directly: the "actions"
 *  HashMap (each entry's "method" field, keyed by action key) is compared
 *  against actionList — the literal definition of rule cruft — and tokens
 *  found in clipboard buffers / old-settings snapshots (clipList, cutAction,
 *  copyL, varSettingsOld) count as stale by definition. Phase 1 and Phase 2
 *  hits are merged per keyword into the same four-state model. A token map
 *  folds RM's dual vocabulary (actType "repeatActs" vs actSubType/method
 *  "getRepeat") onto one column. Tokens that cannot be attributed by any
 *  recognized shape fall back to raw-text matching and count as live —
 *  unexpected payload formats still degrade to the pre-1.08 behavior, never
 *  to false negatives.
 *
 *  1.13: Adds "clip" to the stale-by-definition state entries. Field
 *  validation of 1.12 (a Cut Repeat action) confirmed correct stale
 *  classification via orphaned actType/actSubType settings and the orphaned
 *  actions-map entry, and revealed that this RM version stores the clipboard
 *  in state under "clip" — a name not previously guarded. A clipboard entry
 *  is now detected as stale even on an RM version that fully cleans the
 *  settings and actions map on Cut.
 *
 *  1.14: Truly fixes the lingering "Phase 2: checking… N/M complete" line.
 *  The 1.11 lock-ordering fix was insufficient because the root cause is a
 *  Hubitat platform behavior: app state is loaded when an execution starts
 *  and committed when it exits, last-writer-wins per changed key. A pump
 *  execution that legitimately wrote a progress message while the scan was
 *  live could finish — and commit — moments after finalize's execution
 *  committed its clear, resurrecting the stale message. No in-method
 *  ordering or locking can prevent a commit-time overwrite, and the symptom
 *  is intermittent (observed on a 1.12 scan, absent on an otherwise
 *  identical 1.13 scan). The progress line now lives in a @Field static
 *  (scanStatusLine), which IS shared live across concurrent executions, so
 *  the PHASE2_LOCK ordering genuinely holds; the legacy state.scanStatus key
 *  is removed on upgrade. The partial-completion notice ("Phase 2 ended
 *  before scanning N rules") moves to its own state.scanNotice key, written
 *  only by finalize — single writer, so it persists safely across reboots.
 *
 *  1.15: Marker legibility. The mixed live+stale cell is now a plain blue
 *  checkmark (the green-check-with-orange-asterisk was hard to read on
 *  high-resolution screens), and the stale-only checkmark changes from
 *  orange to red. Tooltips, the legend, Notes, and the Controls toggle text
 *  are updated to match; sort order, CSV/print values ("Yes+Stale",
 *  "Stale"), and detection logic are unchanged.
 *
 *  1.16: The mixed live+stale marker becomes a double checkmark — one green
 *  (the live action) immediately followed by one red (the cruft) — replacing
 *  1.15's blue checkmark, which proved to be a poor indicator color on some
 *  screens. The pair is wrapped in white-space:nowrap so it cannot split
 *  across lines in a narrow column. Legend, tooltips, Notes, and the
 *  Controls toggle text updated; sort order, CSV/print values, and detection
 *  logic unchanged.
 *
 *  Notes:
 *  - Uses Hubitat local/internal JSON endpoints:
 *      /hub2/appsList
 *      /installedapp/statusJson/{appId}
 *      /installedapp/configure/json/{appId}
 *      /apps/api/{thisAppId}/setpref?key={prefKey}&value={prefValue}
 *      /apps/api/{thisAppId}/report
 *      /apps/api/{thisAppId}/RM-BC_Special_Actions.csv
 *  - Some of these endpoints are not a formal public API and could change in a
 *    future Hubitat platform release.
 */

import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String RM_BASE_URL                    = "http://127.0.0.1:8080"
@Field static final int    SCAN_TIMEOUT_SECS              = 360   // max seconds before Phase 1 scan is force-finalized
@Field static final int    LOGS_OFF_DELAY_SECS            = 1800  // seconds before debug logging auto-disables
@Field static final int    CONFIGURE_MAX_IN_FLIGHT        = 3     // max simultaneous configure/json requests during keyword scan
@Field static final int    CONFIGURE_REQUEST_TIMEOUT_SECS = 20    // per-rule watchdog timeout for configure/json callbacks (first attempt)
@Field static final int    CONFIGURE_RETRY_TIMEOUT_SECS   = 90    // per-rule timeout for the retry attempt (slow BC/RM pages)
@Field static final int    CONFIGURE_MAX_ATTEMPTS         = 2     // attempts per rule before its special actions are marked unknown
@Field static final int    CONFIGURE_TOTAL_TIMEOUT_SECS   = 600   // minimum backstop before Phase 2 is force-finalized; scaled up with rule count at scan start
@Field static final int    CONFIGURE_HEARTBEAT_SECS       = 30    // silence timeout for Phase 2 progress

@Field static final List<String> SPECIAL_ACTION_KEYS = [
    "getWhile",
    "repeatActs",
    "getEndRepeat",
    "getStopRepeat",
    "getWaitRule",
    "getWaitEvents"
]

@Field static final Map SPECIAL_ACTION_LABELS = [
    getWhile     : "While",
    repeatActs   : "Repeat",
    getEndRepeat : "End Repeat",
    getStopRepeat: "Stop Repeat",
    getWaitRule  : "Wait for Expression",
    getWaitEvents: "Wait for Event"
]

// RM stores special actions with more than one vocabulary: the actType.N
// setting for a Repeat holds "repeatActs" while its actSubType.N setting and
// the state actions-map "method" field hold "getRepeat". This map folds every
// known token onto the scanner's canonical column key.
@Field static final Map<String, String> KEYWORD_TOKEN_MAP = [
    getWhile     : "getWhile",
    repeatActs   : "repeatActs",
    getRepeat    : "repeatActs",
    getEndRepeat : "getEndRepeat",
    getStopRepeat: "getStopRepeat",
    getWaitRule  : "getWaitRule",
    getWaitEvents: "getWaitEvents"
]

// State variables whose contents are stale by definition: clipboard buffers
// (Cut/Copy actions) and old variable-settings snapshots. Any special-action
// token found inside these counts as a stale occurrence for its column.
// "clip" observed in the field (RM 5.1 era) holding a Cut action's full
// definition; the others cover older/alternate RM versions.
@Field static final List<String> STALE_STATE_ENTRY_NAMES = [
    "clip", "clipList", "cutAction", "copyL", "varSettingsOld"
]

// Trailing action-key index on a setting name, e.g. the "6" in "actType.6"
// or a nested "2.1" in "foo.2.1".
@Field static final java.util.regex.Pattern SETTING_NAME_INDEX_PATTERN =
    java.util.regex.Pattern.compile(/.*?\.(\d+(?:\.\d+)*)$/)

// Transient scan state lives in @Field static to avoid database writes during a scan.
// If the app class is reloaded during a scan (e.g., on code save or hub restart),
// the scan is abandoned and can be run again.
@Field static String    currentScanId           = null
@Field static Long      scanStartMs             = 0L
@Field static List<Map> scanRuleQueue           = null
@Field static Map       scanPartialResults      = null   // keyed by ruleId String; holds RM/BC rule scan rows
@Field static String    configureScanId         = null   // Phase 2 scan ID, separate from Phase 1
@Field static List<Map> configureQueue          = null   // rule list for Phase 2 configure/json queue
@Field static Map       configureResults        = [:]    // ruleId -> Map keyword -> Boolean, or null when unreadable
@Field static Integer   configureNextIdx        = 0      // next configureQueue index to launch
@Field static Integer   configureInFlight       = 0      // number of configure/json requests currently in flight
@Field static Integer   configureTotalRules     = 0      // total number of rules expected in Phase 2
@Field static Map       configureInflight       = [:]    // ruleId -> [startedMs, name], for dropped-response watchdog
@Field static Map       modeIdToName            = null   // hub mode ID -> mode name, built at Phase 2 start
@Field static Map       configureLiveKeys       = [:]    // ruleId -> List<String> live action keys captured from Phase 1 statusJson
@Field static Map       configurePhase1Hits     = [:]    // ruleId -> Map<column, "live"|"stale"|"livestale"> keyword hits from Phase 1 state (actions map, clipboard buffers)
// Transient scan-progress line shown on the app page. Deliberately a static,
// NOT state: Hubitat loads state when an app execution starts and commits it
// when the execution exits (last-writer-wins per changed key), so a pump
// execution that finishes just after finalize would resurrect a stale
// progress message no matter how the writes are ordered in-method. Statics
// are shared live across concurrent executions, so ordering under
// PHASE2_LOCK actually holds.
@Field static String    scanStatusLine          = null
@Field static Map       configureAttempts       = [:]    // ruleId -> number of configure/json attempts launched
@Field static final Object PHASE2_LOCK          = new Object()  // guards all Phase 2 shared state above (pump, callbacks, heartbeat, finalize can run on different threads)

// RM/BC mode-selection setting names: modesX<n> (triggers), modes<n> (conditions),
// mode.<n> (Set Mode action target), modesY<n> (legacy restrictions), plus bare
// "mode"/"modes" used by mode-type inputs in other contexts.
@Field static final java.util.regex.Pattern MODE_SETTING_NAME_PATTERN = ~/^modes?[XY]?\d*(\.\d+)?$/

definition(
    name:           "Rules Special Actions Scanner 1.16",
    namespace:      "John Land",
    author:         "John Land & AI",
    description:    "Scans RM/BC rules and reports selected special-action keywords found in rule configuration JSON.",
    category:       "Utility",
    singleInstance: false,
    installOnOpen:  true,
    oauth:          true,
    iconUrl:        '',
    iconX2Url:      '',
    importUrl:      "https://raw.githubusercontent.com/JohnFLand/Special-Actions-Scanner/refs/heads/main/Rules_Special_Actions_Scanner.groovy"
)

preferences {
    page(name: "mainPage")
}

mappings {
    path("/setpref")                 { action: [GET: "handleSetPrefEndpoint"] }
    path("/report")                  { action: [GET: "handleReportEndpoint"] }
    path("/RM-BC_Special_Actions.csv") { action: [GET: "handleRmCsvEndpoint"] }
}

// ============================================================
// Lifecycle
// ============================================================

void installed() {
    syncAppInstanceLabel()
    if (debugEnable) log.debug "SAS: installed — ${getAppDisplayName()}"
    checkOAuth()
    initialize()
}

void updated() {
    if (debugEnable) log.debug "SAS: updated — label: '${app.label}', scan active: ${currentScanId != null || configureScanId != null}"

    // Keep the Hubitat app instance label and this page title in sync with the
    // custom App instance name field.  This avoids the double-title effect where
    // Hubitat's outer header shows the renamed instance but the dynamicPage title
    // still shows the static app-code name.
    syncAppInstanceLabel()

    boolean scanWasActive = (currentScanId != null || configureScanId != null)
    state.remove("scanStatus")   // legacy key from <=1.13; superseded by the scanStatusLine static
    initialize()
    if (scanWasActive) {
        scanStatusLine = "<i>Scan was cancelled because app settings were saved. Click Scan again to run again.</i>"
    } else {
        reRenderReportIfCached()
    }
}

private String getAppDisplayName() {
    String requested = settings?.vAppLabel?.toString()?.trim()
    if (requested) return requested

    String currentLabel = app?.label?.toString()?.trim()
    if (currentLabel) return currentLabel

    return app?.name?.toString() ?: "Rules Special Actions Scanner"
}

private void syncAppInstanceLabel() {
    String requested = settings?.vAppLabel?.toString()?.trim()
    if (!requested) return

    String currentLabel = app?.label?.toString()?.trim()
    if (requested == currentLabel) return

    try {
        app.updateLabel(requested)
        if (debugEnable) log.debug "SAS: app label updated to '${requested}'"
    } catch (Exception e) {
        log.warn "SAS: app label update failed — ${e.message}"
    }
}

private void resetAppInstanceLabel() {
    String defaultName = app?.name?.toString() ?: "Rules Special Actions Scanner"
    try {
        app.updateLabel(defaultName)
        app.updateSetting("vAppLabel", [value: defaultName, type: "text"])
        log.info "Rules Special Actions Scanner: app label reset to app name '${defaultName}'"
    } catch (Exception e) {
        log.warn "Rules Special Actions Scanner: app label reset failed — ${e.message}"
    }
}


void initialize() {
    if (currentScanId != null) {
        log.warn "initialize: aborting in-progress scan (scanId: ${currentScanId}) — re-scan when ready"
    }

    currentScanId      = null
    scanStartMs        = 0L
    scanRuleQueue      = null
    scanPartialResults = null

    unschedule("finalizeScanTimeout")
    unschedule("finalizeUsageScan")
    unschedule("configurePump")
    unschedule("configureHeartbeat")

    state.remove("scanRuleQueue")
    synchronized (PHASE2_LOCK) {
        configureScanId         = null
        configureQueue          = null
        configureResults        = [:]
        configureNextIdx        = 0
        configureInFlight       = 0
        configureTotalRules     = 0
        configureInflight       = [:]
        modeIdToName            = null
        configureLiveKeys       = [:]
        configurePhase1Hits     = [:]
        configureAttempts       = [:]
        scanStatusLine          = null
    }

    unsubscribe()

    if (debugEnable) {
        runIn(LOGS_OFF_DELAY_SECS, "logsOff")
    }
}

void logsOff() {
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
}

// Re-render the report HTML using rows cached in state.scanRowsJson.
// Called from updated() so display-setting changes apply on Done without a rescan.
void reRenderReportIfCached() {
    if (!state.scanRowsJson) return
    try {
        List<Map> rows = new groovy.json.JsonSlurper().parseText(state.scanRowsJson) as List<Map>
        state.reportHtml = buildReportHtml(rows)
        if (debugEnable) log.debug "SAS: report re-rendered from cached scan data (${rows.size()} rules)"
    } catch (Exception e) {
        log.warn "reRenderReportIfCached: could not re-render — ${e.message}"
    }
}

// ============================================================
// OAuth token management
// ============================================================

private String getAppTypeId() {
    String typeId = null
    try {
        httpGet([uri: RM_BASE_URL, path: "/hub2/userAppTypes", timeout: 15]) { resp ->
            List apps = resp.data instanceof List ? (List) resp.data : []
            Map match = apps.find { it.name == app.name }
            if (match) typeId = match.id?.toString()
        }
    } catch (Exception e) {
        log.debug "getAppTypeId: could not fetch user app types — ${e.message}"
    }
    return typeId
}

private boolean autoEnableOAuth() {
    String typeId = getAppTypeId()
    if (!typeId) {
        log.warn "autoEnableOAuth: could not determine app type ID — OAuth must be enabled manually in Apps Code"
        return false
    }

    String internalVer = null
    try {
        httpGet([uri: RM_BASE_URL, path: "/app/ajax/code", query: [id: typeId], timeout: 15]) { resp ->
            internalVer = resp.data?.version?.toString()
        }
    } catch (Exception e) {
        log.error "autoEnableOAuth: could not fetch app code version — ${e.message}"
        return false
    }
    if (!internalVer) {
        log.error "autoEnableOAuth: app code version was null — cannot proceed"
        return false
    }

    boolean success = false
    try {
        httpPost([
            uri                : RM_BASE_URL,
            path               : "/app/edit/update",
            requestContentType : "application/x-www-form-urlencoded",
            body               : [id: typeId, version: internalVer, oauthEnabled: "true", _action_update: "Update"],
            timeout            : 20
        ]) { resp ->
            success = true
        }
        if (success) log.info "autoEnableOAuth: OAuth successfully enabled on app code (typeId: ${typeId})"
    } catch (Exception e) {
        log.error "autoEnableOAuth: POST to /app/edit/update failed — ${e.message}"
    }
    return success
}

boolean checkOAuth() {
    if (state.accessToken) return true
    try {
        createAccessToken()
        if (state.accessToken) {
            log.info "Special Actions Scanner: OAuth token created"
            return true
        }
    } catch (Exception e) {
        log.debug "checkOAuth: OAuth not yet enabled — attempting auto-enable via hub API..."
        if (autoEnableOAuth()) {
            try {
                createAccessToken()
                if (state.accessToken) {
                    log.info "Special Actions Scanner: OAuth auto-enabled and token created successfully"
                    return true
                }
            } catch (Exception e2) {
                log.error "checkOAuth: OAuth was enabled but token creation still failed — ${e2.message}"
            }
        }
    }
    return false
}

def renderJson(Map m) {
    return render(contentType: "application/json", data: groovy.json.JsonOutput.toJson(m))
}

// ============================================================
// UI
// ============================================================

def mainPage() {
    checkOAuth()
    syncAppInstanceLabel()

    // Re-render the cached report immediately when the stale-suppression
    // toggle changes (submitOnChange re-runs this page before Done is clicked).
    boolean curSuppress = isSuppressStaleEnabled()
    if (state.suppressStaleRendered != null && state.suppressStaleRendered != curSuppress) {
        reRenderReportIfCached()
    }
    state.suppressStaleRendered = curSuppress

    int pollInterval = (currentScanId || configureScanId) ? 5 : 0

    dynamicPage(name: "mainPage", title: "<b>${htmlEncode(getAppDisplayName())}</b>", install: true, uninstall: true, refreshInterval: pollInterval) {

        section("NOTE: Scanning may take a while, be patient!") {
            input name: "btnScan", type: "button", title: "Scan All RM/BC Rules for Special Actions", width: 12

            if (state.lastScan) {
                String scanTimeHtml = "Phase 1 scan time: ${state.phase1ScanDuration ?: state.scanDuration ?: '00:00'}; " +
                                      "Phase 2 scan time: ${state.phase2ScanDuration ?: '00:00'}; " +
                                      "total scan time: ${state.totalScanDuration ?: state.scanDuration ?: '00:00'}"
                paragraph "<b>Last scan:</b> ${state.lastScan} (${scanTimeHtml})"
            } else {
                paragraph "No scan has been run yet."
            }
            if (scanStatusLine) {
                paragraph scanStatusLine
            }
            if (state.scanNotice) {
                paragraph state.scanNotice
            }
            if (state.lastError) {
                paragraph "<span style='color:red'><b>Last error:</b> ${htmlEncode(state.lastError.toString())}</span>"
            }
        }

        section("") {
            if (!state.accessToken) {
                paragraph "<span style='color:red;font-weight:bold;'>✗ Report links and UI preference persistence not active</span> — automatic OAuth setup failed.<br>" +
                          "Please enable it manually as a fallback:<br>" +
                          "1. Go to <b>Apps Code</b>, open this app, click the <b>three-dot menu</b>, select <b>OAuth</b>, and press <b>Enable OAuth in Smartapp</b>.<br>" +
                          "2. Return here and re-open the app — the token will be created automatically."
            }
        }

        section("Rule Machine and Button Controller Special Actions", hideable: true, hidden: false) {
            if (state.scannedCount != null) {
                String summaryHtml = buildSummaryHtml()
                paragraph summaryHtml
            }

            paragraph(state.reportHtml ?: "Click <b>Scan All RM/BC Rules for Special Actions</b> to begin.")
        }

        section("Controls", hideable: true, hidden: true) {
            input "vAppLabel", "text", title: "<b>App instance name</b>", defaultValue: getAppDisplayName(), submitOnChange: true, width: 9
            input "btnResetAppLabel", "button", title: "Reset to App Name", width: 3

            if (state.accessToken) {
                String base = "/apps/api/${app.id}/report?access_token=${state.accessToken}"
                if (state.scanRowsJson) {
                    String rmCsvUrl = "/apps/api/${app.id}/RM-BC_Special_Actions.csv?access_token=${state.accessToken}"
                    paragraph "<br><b>RM/BC Special Actions Table</b> &nbsp;" +
                        "<a href='${base}' target='_blank'>" +
                        "&#128196; Open Printable Report</a>" +
                        " &nbsp;|&nbsp; " +
                        "<a href='${rmCsvUrl}'>&#11015; Download CSV</a>"
                } else {
                    paragraph "<small>Run <b>Scan All RM/BC Rules for Special Actions</b> to enable RM/BC reports.</small>"
                }
            } else {
                paragraph "<small>OAuth setup required before reports are available.</small>"
            }

            paragraph "<br><br>"
            input "suppressStale", "bool",
                title: "<b>Treat stale-only keyword matches as not found</b>",
                defaultValue:   false,
                submitOnChange: true
            paragraph "<small>Keyword matches found only in stale/leftover rule entries (deleted actions, " +
                      "clipboard copies, old settings — anything no longer referenced by the rule's actionList) " +
                      "are normally shown as a red checkmark, and mixed live+stale matches as a green " +
                      "and red checkmark pair. Turn this on to show stale-only cells as a " +
                      "dash and mixed cells as a plain green checkmark. " +
                      "Takes effect immediately from cached scan data; no re-scan needed.</small>"

            input "debugEnable", "bool",
                title: "<b>Enable debug logging</b>",
                defaultValue:   false,
                submitOnChange: true
        }

        section("Notes", hideable: true, hidden: true) {
            paragraph '''
                <b>Overview</b><br>
                This app scans Rule Machine (<b>RM</b>) and Button Controller (<b>BC</b>) rules and
                displays whether each rule's internal configuration JSON contains any of these
                special-action keywords:<br>
                <b>While</b> (<code>getWhile</code>), <b>Repeat</b> (<code>repeatActs</code>),
                <b>End Repeat</b> (<code>getEndRepeat</code>), <b>Stop Repeat</b> (<code>getStopRepeat</code>),
                <b>Wait for Expression</b> (<code>getWaitRule</code>), and <b>Wait for Event</b> (<code>getWaitEvents</code>).
                <br><br>
                <b>Scanning</b><br>
                The scan has two phases. Phase 1 reads each rule's runtime status JSON to get
                Last Run, and also captures the rule's <i>live action keys</i> from state
                variables whose names begin with <code>actionList</code> — Rule Machine's list of
                the actions the rule actually still contains. Phase 2 reads each rule's
                configuration JSON and searches it for the six keywords. Phase 2 is queued with
                a small number of simultaneous requests; a rule whose request times out or fails
                is automatically retried once with a longer timeout, and only rules that fail
                both attempts are marked unknown. The overall Phase 2 time limit scales with the
                number of rules, so large installs are not cut off mid-scan; if Phase 2 ever
                does end early, the app page and log state how many rules were never reached.
                <br><br>
                <b>Live vs. stale matches</b><br>
                Rule Machine stores each action's type as a bare keyword in a setting whose
                name carries the action's index (e.g. <code>actType.6 = repeatActs</code>), and
                mirrors it in the state <code>actions</code> map's <code>method</code> field.
                RM does not always garbage-collect deleted actions, and clipboard buffers
                (Cut/Copy) plus old variable-settings snapshots persist indefinitely. The scan
                attributes every keyword occurrence to its action index and checks that index
                against the rule's <code>actionList</code>; occurrences in clipboard buffers
                count as stale by definition. A keyword found only in such leftovers is shown
                as a <span style='color:#c00;font-weight:bold;'>red checkmark</span> —
                cruft, useful when hunting rules that need cleanup. A rule can also have both:
                an active special action <i>and</i> stale leftovers for the same keyword. That
                mixed case is shown as a pair of checkmarks,
                <span style='font-weight:bold;white-space:nowrap;'><span style='color:green;'>&#10003;</span><span style='color:#c00;'>&#10003;</span></span>
                (one green for the live action, one red for the cruft) — the
                action is real, but there is also cruft to clean. The <b>Treat stale-only
                keyword matches as not found</b> toggle in Controls shows stale-only cells as
                dashes and mixed cells as plain green checkmarks.
                Occurrences that cannot be attributed to any action index are shown as live,
                and if a rule's live action-key list could not be read, every keyword hit in
                that rule is shown as live — unreadable data never produces false negatives.
                <br><br>
                <b>Modes column</b><br>
                During Phase 2 the configuration JSON is also parsed and searched for mode
                settings: native <code>mode</code>-type inputs, and Rule Machine's enum settings
                named <code>modesX&lt;n&gt;</code> (mode triggers), <code>modes&lt;n&gt;</code>
                (mode conditions/required expressions), <code>mode.&lt;n&gt;</code> (Set Mode
                actions), and <code>modesY&lt;n&gt;</code> (legacy restrictions). Mode IDs in
                those settings are translated to mode names using this hub's mode list; values
                that do not correspond to a mode on this hub are ignored. The resulting mode
                names are listed in the <b>Modes</b> column. A dash means no mode settings were
                found; a red question mark means the rule's configuration JSON could not be read.
                Rules that reference modes only indirectly (for example via hub variables or
                custom commands) are not detected.
                <br><br>
                <b>Table</b><br>
                Shows Rule ID, Rule name (linked to its config page), App Type, one column for
                each keyword, Modes, and Last Run. Keyword cells show a green checkmark when the
                keyword is found in live actions only, a green+red checkmark pair when it is
                found in a live action AND in stale/leftover entries, a red checkmark when it is found only
                in stale/leftover entries, a dash when it is not found, or a red question mark
                when the rule's configuration JSON could not be read. Summary keyword counts include live matches (plain and mixed); the
                stale-leftover rule count includes both stale-only and mixed rules.
                <br><br>
                The <b>Hide rows with no Special Actions</b> button hides rows where all six
                keyword columns are known and none is present. Unknown/skipped rows and rows with
                stale-only matches remain visible (unless stale matches are suppressed via the
                Controls toggle, in which case stale-only rows are hidden too).
                The <b>Show all rows</b> button restores them. Column headers are clickable to sort.
                The hide-column buttons persist without clicking <b>Done</b>.
                <br><br>
                <b>Controls section</b><br>
                App instance rename, Reset to App Name, printable HTML report, CSV export, and debug logging toggle.
                There are no Private Boolean setters, bulk-apply controls, or scheduled apply controls.
                <br><br>
                <b>WARNING</b><br>
                This app uses Hubitat local/internal JSON endpoints that are not a formal public
                API and could change in a future platform update.
                <br>
            '''
        }
    }
}

String buildSummaryHtml() {
    Map counts = getKeywordCountsFromState()
    StringBuilder sb = new StringBuilder()
    sb << "<div id='rm-summary' style='margin:0;padding:0;line-height:1.5;font-size:1em;'>"
    sb << "<b>Rules scanned:</b> ${state.scannedCount ?: 0}; "
    sb << "<b>Rules with Special Actions:</b> ${state.specialActionRuleCount ?: 0}; "
    sb << "<b>Rules with stale keyword leftovers:</b> ${state.staleMatchRuleCount ?: 0}"
    if (isSuppressStaleEnabled()) sb << " <i>(stale markers hidden)</i>"
    sb << "; <b>Unknown/skipped:</b> ${state.specialActionUnknownCount ?: 0}"
    SPECIAL_ACTION_KEYS.each { String key ->
        sb << "; <b>${htmlEncode(labelForKeyword(key))}:</b> ${counts[key] ?: 0}"
    }
    sb << "; <b>Rules using Modes:</b> ${state.modeRuleCount ?: 0}"
    sb << "<br><br></div>"
    return sb.toString()
}

Map getKeywordCountsFromState() {
    try {
        return new groovy.json.JsonSlurper().parseText(state.specialActionCountsJson ?: "{}") as Map
    } catch (Exception ignored) {
        return [:]
    }
}

def appButtonHandler(String btn) {
    switch (btn) {
        case "btnScan":
            scanRules()
            break
        case "btnResetAppLabel":
            resetAppInstanceLabel()
            break
        default:
            log.warn "Unknown button: ${btn}"
            break
    }
}

// ============================================================
// Scanning — async sequential statusJson chain + queued configure/json pass
// ============================================================

void scanRules() {
    state.lastError                 = null
    state.specialActionRuleCount    = null
    state.specialActionUnknownCount = null
    state.specialActionCountsJson   = null
    state.modeRuleCount             = null

    state.scanStartedMs             = null
    state.phase1EndedMs             = null
    state.phase1ScanDuration        = null
    state.phase2ScanDuration        = null
    state.totalScanDuration         = null
    state.scanDuration              = null

    scanStatusLine                  = "<i>Scan in progress…</i>"
    state.scanNotice                = null
    state.remove("scanStatus")   // legacy key from <=1.13; no longer used
    state.reportHtml                = null
    state.scanRowsJson              = null

    unschedule("finalizeScanTimeout")
    runIn(SCAN_TIMEOUT_SECS, "finalizeScanTimeout")

    List<Map> ruleApps = getRuleMachineRuleApps()

    if (ruleApps.isEmpty()) {
        unschedule("finalizeScanTimeout")
        state.scannedCount              = 0
        state.specialActionRuleCount    = 0
        state.specialActionUnknownCount = 0
        state.specialActionCountsJson   = groovy.json.JsonOutput.toJson(emptyKeywordCounts())
        state.modeRuleCount             = 0
        state.scanStartedMs             = null
        state.phase1EndedMs             = null
        state.lastScan                  = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
        state.phase1ScanDuration        = "00:00"
        state.phase2ScanDuration        = null
        state.totalScanDuration         = "00:00"
        state.scanDuration              = "00:00"
        state.reportHtml                = "<p>No Rule Machine or Button Controller rules found.</p>"
        scanStatusLine                  = null
        return
    }

    Long   nowMs         = now() as Long
    String scanId        = nowMs.toString()
    state.scanStartedMs  = nowMs
    String scanStartTime = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)

    List<Map> queue = ruleApps.collect { Map r ->
        [id       : r.id                 as String,
         name     : r.name               as String,
         appType  : (r.appType ?: "RM")  as String]
    }

    scanStatusLine = "<i>Scan started: ${scanStartTime} — scanning ${queue.size()} rules…</i>"

    scanRuleQueue      = queue
    scanPartialResults = [:]
    currentScanId      = scanId
    scanStartMs        = nowMs

    log.info "SAS: scan started — ${queue.size()} rules (scanId: ${scanId})"

    Map first = queue[0]
    asynchttpGet("handleStatusResponse",
        [uri: RM_BASE_URL, path: "/installedapp/statusJson/${first.id}", timeout: 60],
        [scanId     : scanId,
         ruleId     : first.id,
         ruleName   : first.name,
         appType    : first.appType,
         nextIdx    : 1,
         totalRules : queue.size()]
    )
}

void handleStatusResponse(resp, data) {
    String scanId = data.scanId as String
    if (currentScanId != scanId) return

    String ruleId = data.ruleId as String

    try {
        Map status = [:]
        try {
            int httpStatus = resp.getStatus() as int
            if (httpStatus == 200) {
                Object raw = resp.getData()
                if (raw instanceof Map) {
                    status = raw as Map
                } else if (raw != null) {
                    status = new groovy.json.JsonSlurper().parseText(raw.toString()) as Map ?: [:]
                }
            } else {
                log.warn "HTTP ${httpStatus} for rule ${ruleId} (${data.ruleName})"
            }
        } catch (Exception e) {
            log.warn "Error parsing statusJson for rule ${ruleId}: ${e.message}"
        }

        if (scanPartialResults == null) scanPartialResults = [:]

        List<String> liveKeys  = extractLiveActionKeys(status)
        Map          stateHits = extractStateKeywordHits(status, liveKeys as Set)

        if (debugEnable) {
            log.debug "Scanned status: ${data.ruleName} (${ruleId}, ${data.appType}) LastRun=${extractLastRun(status)}, liveActionKeys=${liveKeys}, stateKeywordHits=${stateHits}"
        }

        scanPartialResults[ruleId] = [
            id             : ruleId,
            name           : data.ruleName,
            appType        : data.appType,
            lastRun        : extractLastRun(status),
            liveKeys       : liveKeys,
            stateHits      : stateHits,
            specialActions : emptyKeywordCounts(),
            specialUnknown : true,
            modes          : []
        ]

    } catch (Exception e) {
        log.warn "handleStatusResponse error for rule ${ruleId} (${data.ruleName}): ${e.message}"
        if (scanPartialResults == null) scanPartialResults = [:]
        scanPartialResults[ruleId] = [
            id             : ruleId,
            name           : data.ruleName as String,
            appType        : (data.appType ?: "RM") as String,
            lastRun        : "",
            liveKeys       : [],
            stateHits      : [:],
            specialActions : emptyKeywordCounts(),
            specialUnknown : true,
            modes          : []
        ]
    } finally {
        if (currentScanId != scanId) return

        int nextIdx    = (data.nextIdx    ?: 0) as int
        int totalRules = (data.totalRules ?: 0) as int

        if (debugEnable) log.debug "Completed statusJson ${nextIdx}/${totalRules}: ${data.ruleName} (${ruleId})"

        if (nextIdx < totalRules) {
            Map nextRule = scanRuleQueue[nextIdx]
            asynchttpGet("handleStatusResponse",
                [uri: RM_BASE_URL, path: "/installedapp/statusJson/${nextRule.id}", timeout: 60],
                [scanId     : currentScanId,
                 ruleId     : nextRule.id                 as String,
                 ruleName   : nextRule.name               as String,
                 appType    : (nextRule.appType ?: "RM")  as String,
                 nextIdx    : nextIdx + 1,
                 totalRules : totalRules]
            )
        } else {
            finalizeScan()
        }
    }
}

void resetConfigureHeartbeat(String reason = "") {
    if (configureScanId == null) return

    unschedule("configureHeartbeat")
    runIn(CONFIGURE_HEARTBEAT_SECS, "configureHeartbeat")

    if (debugEnable && reason) {
        log.debug "SAS: Phase 2 heartbeat reset (${reason})"
    }
}

void configureHeartbeat() {
    if (configureScanId == null) return

    Integer done; Integer total; Integer active; Integer next
    synchronized (PHASE2_LOCK) {
        done   = (configureResults?.size() ?: 0) as Integer
        total  = (configureTotalRules ?: 0) as Integer
        active = (configureInFlight ?: 0) as Integer
        next   = (configureNextIdx ?: 0) as Integer
    }

    log.warn "SAS: Phase 2 heartbeat timeout — no configure/json progress for ${CONFIGURE_HEARTBEAT_SECS}s; finalizing partial results. Done=${done}/${total}, active=${active}, nextIdx=${next}"

    finalizeUsageScan()
}

// Move a failed rule (timeout, non-200, unparseable) back to the end of the
// queue for another attempt, or mark it unknown once its attempts are used
// up. Returns true when a retry was queued. Callers must hold PHASE2_LOCK.
private boolean requeueOrMarkUnknown(String rid, String nm, String reason) {
    int attempts = (configureAttempts?.get(rid) ?: 0) as int
    if (attempts < CONFIGURE_MAX_ATTEMPTS && configureQueue != null) {
        log.warn "SAS: configure/json ${reason} for rule ${rid} (${nm}) — will retry with a ${CONFIGURE_RETRY_TIMEOUT_SECS}s timeout (${attempts} of ${CONFIGURE_MAX_ATTEMPTS} attempts used)"
        configureQueue << [id: rid, name: nm]
        return true
    }
    log.warn "SAS: configure/json ${reason} for rule ${rid} (${nm}) — special actions marked unknown after ${attempts} attempt(s)"
    configureResults[rid] = null
    return false
}

void configurePump() {
    if (configureScanId == null) return

    unschedule("configurePump")

    boolean madeProgress = false
    Integer done; Integer total; Integer active

    synchronized (PHASE2_LOCK) {
        Long nowMs = now() as Long
        if (configureResults  == null) configureResults  = [:]
        if (configureInflight == null) configureInflight = [:]
        if (configureAttempts == null) configureAttempts = [:]

        // Watchdog: expire in-flight requests that have exceeded the timeout
        // used for their attempt (plus a small grace period), then retry or
        // mark them unknown. Iterate over a snapshot of the keys — the map is
        // mutated below.
        List<String> inflightIds = new ArrayList<String>(configureInflight.keySet())
        inflightIds.each { String rid ->
            Map info = (configureInflight[rid] instanceof Map) ? (Map) configureInflight[rid] : [:]
            Long startedMs   = (info.startedMs ?: 0L) as Long
            int  timeoutSecs = (info.timeoutSecs ?: CONFIGURE_REQUEST_TIMEOUT_SECS) as int
            if (startedMs && (nowMs - startedMs) > ((timeoutSecs + 5) * 1000L)) {
                requeueOrMarkUnknown(rid, (info.name ?: "") as String, "watchdog timeout (${timeoutSecs}s)")
                configureInflight.remove(rid)
                configureInFlight = Math.max(0, (configureInFlight ?: 0) - 1)
                madeProgress = true
            }
        }

        while ((configureInFlight ?: 0) < CONFIGURE_MAX_IN_FLIGHT &&
               (configureNextIdx  ?: 0) < (configureQueue?.size() ?: 0)) {

            Map rule = configureQueue[configureNextIdx] as Map
            configureNextIdx = (configureNextIdx ?: 0) + 1

            String rid   = rule.id as String
            String nm    = rule.name as String
            String cfgId = configureScanId

            // Skip anything already resolved (e.g., a late response arrived
            // for a rule that had also been requeued for retry).
            if (configureResults.containsKey(rid) || configureInflight.containsKey(rid)) continue

            int attempt = ((configureAttempts[rid] ?: 0) as int) + 1
            configureAttempts[rid] = attempt
            int tmo = (attempt >= 2) ? CONFIGURE_RETRY_TIMEOUT_SECS : CONFIGURE_REQUEST_TIMEOUT_SECS

            configureInflight[rid] = [startedMs: nowMs, name: nm, timeoutSecs: tmo]
            configureInFlight = (configureInFlight ?: 0) + 1
            madeProgress = true

            try {
                asynchttpGet(
                    "handleConfigureResponse",
                    [
                        uri     : RM_BASE_URL,
                        path    : "/installedapp/configure/json/${rid}",
                        timeout : tmo
                    ],
                    [
                        cfgScanId : cfgId,
                        ruleId    : rid,
                        ruleName  : nm
                    ]
                )
            } catch (Exception e) {
                log.warn "SAS: could not start configure/json for rule ${rid} (${nm}): ${e.message}"
                configureInflight.remove(rid)
                configureInFlight = Math.max(0, (configureInFlight ?: 0) - 1)
                requeueOrMarkUnknown(rid, nm, "launch failure")
                madeProgress = true
            }
        }

        done   = (configureResults?.size() ?: 0) as Integer
        total  = (configureTotalRules ?: 0) as Integer
        active = (configureInFlight ?: 0) as Integer

        // Write the progress line while still holding the lock, and only if
        // the scan is still live. Because scanStatusLine is a static (shared
        // live across executions), the lock ordering with finalize genuinely
        // holds — unlike state, whose per-execution commit could resurrect a
        // stale message.
        if (configureScanId != null && total > 0 && done < total) {
            scanStatusLine = "<i>Phase 2: checking configure/json for special-action keywords… ${done}/${total} complete, ${active} active</i>"
        }
    }

    // The scan is not stalled while bounded requests are still in flight —
    // the watchdog above guarantees each one resolves. Only reset-starve the
    // heartbeat when nothing is in flight and nothing was launched.
    if (madeProgress || (active ?: 0) > 0) {
        resetConfigureHeartbeat("pump progress")
    }

    if (total <= 0 || done >= total) {
        finalizeUsageScan()
        return
    }

    runIn(5, "configurePump")
}

void handleConfigureResponse(resp, data) {
    String cfgScanId = data.cfgScanId as String
    if (configureScanId != cfgScanId) return

    String ruleId   = data.ruleId as String
    String ruleName = data.ruleName ?: ""

    // Parse the response outside the lock — only bookkeeping is guarded.
    Map    resultMap  = null
    String failReason = null

    try {
        int httpStatus = resp.getStatus() as int
        if (httpStatus == 200) {
            Object raw = resp.getData()
            Set<String>  liveKeys  = liveKeysForRule(ruleId)
            Map          p1Hits    = phase1HitsForRule(ruleId)
            Map          foundMap  = detectSpecialActionsFromRaw(raw, liveKeys, p1Hits)
            List<String> modesList = detectModesFromRaw(raw)
            if (foundMap != null) {
                resultMap = [keywords: foundMap, modes: (modesList ?: [])]
            } else {
                failReason = "unparseable payload"
            }
            if (debugEnable) log.debug "configure/json ${ruleId}: specialActions=${foundMap}, modes=${modesList}"
        } else {
            failReason = "HTTP ${httpStatus}"
        }
    } catch (Exception e) {
        log.warn "handleConfigureResponse ${ruleId} (${ruleName}): ${e.message}"
        resultMap  = null
        failReason = failReason ?: "callback error"
    }

    if (configureScanId != cfgScanId) return

    synchronized (PHASE2_LOCK) {
        if (configureScanId != cfgScanId) return
        if (configureResults == null)  configureResults  = [:]
        if (configureInflight == null) configureInflight = [:]

        boolean wasInflight = configureInflight.containsKey(ruleId)

        if (!wasInflight) {
            // The watchdog already expired this attempt. A late success is
            // still useful: record it (or upgrade an unknown), so a pending
            // retry gets skipped and the rule doesn't show as '?'.
            if (resultMap != null && (!configureResults.containsKey(ruleId) || configureResults[ruleId] == null)) {
                if (debugEnable) log.debug "SAS: late configure/json success for rule ${ruleId} (${ruleName}) accepted"
                configureResults[ruleId] = resultMap
            }
            return
        }

        configureInflight.remove(ruleId)
        configureInFlight = Math.max(0, (configureInFlight ?: 0) - 1)

        if (resultMap != null) {
            configureResults[ruleId] = resultMap
            int attemptsUsed = (configureAttempts?.get(ruleId) ?: 1) as int
            if (attemptsUsed > 1) {
                log.info "SAS: configure/json retry succeeded for rule ${ruleId} (${ruleName}) on attempt ${attemptsUsed}"
            }
        } else {
            requeueOrMarkUnknown(ruleId, ruleName, failReason ?: "unknown failure")
        }
    }

    resetConfigureHeartbeat("callback")

    configurePump()
}

// Extract the rule's live action keys from its statusJson payload. RM keeps
// the authoritative list of a rule's current actions in state variables whose
// names begin with "actionList" (RM 5.x: "actionList"; Button Controller may
// keep one list per button). Everything else in the "actions" HashMap /
// settings that is not referenced there is leftover cruft from deleted
// actions. statusJson appState item values arrive as strings (e.g.
// "[2.1, 2.3, 3]" or JSON-ish text), so the keys are harvested liberally as
// dotted numeric tokens rather than by parsing a specific format.
private List<String> extractLiveActionKeys(Map status) {
    Set<String> keys = [] as Set
    try {
        status?.appState?.each { item ->
            String n = item?.name?.toString() ?: ""
            if (n.startsWith("actionList")) {
                String v = item?.value?.toString() ?: ""
                java.util.regex.Matcher m = (v =~ /\d+(?:\.\d+)*/)
                while (m.find()) { keys << m.group() }
            }
        }
    } catch (Exception e) {
        log.warn "extractLiveActionKeys: ${e.message}"
    }
    return keys as List
}

// Fold a live/stale occurrence pair into a hit-state string.
private String hitStateFor(boolean live, boolean stale) {
    if (live && stale) return "livestale"
    if (live)          return "live"
    if (stale)         return "stale"
    return null
}

// Scan a rule's statusJson Application State for special-action keyword hits.
// Two sources:
//  1. The "actions" HashMap — RM's per-action metadata, keyed by action key,
//     each entry carrying a "method" field (e.g. "getRepeat"). Keys present
//     in actionList are live; keys absent from it are the classic cruft your
//     status page shows for deleted actions.
//  2. Clipboard buffers and old-settings snapshots (clipList, cutAction,
//     copyL, varSettingsOld) — any keyword token in these is stale by
//     definition.
// Returns Map<column, "live"|"stale"|"livestale">, empty when nothing found
// or no live-key info is available (in which case Phase 2's conservative
// fallback governs on its own).
private Map extractStateKeywordHits(Map status, Set<String> liveKeys) {
    Map hits = [:]
    try {
        if (liveKeys == null || liveKeys.isEmpty()) return hits

        Map liveByCol  = [:]
        Map staleByCol = [:]

        status?.appState?.each { item ->
            String n = item?.name?.toString() ?: ""
            Object v = item?.value

            if (n == "actions") {
                Map actionsMap = null
                if (v instanceof Map) {
                    actionsMap = (Map) v
                } else if (v != null) {
                    try {
                        Object parsed = new groovy.json.JsonSlurper().parseText(v.toString())
                        if (parsed instanceof Map) actionsMap = (Map) parsed
                    } catch (Exception ignored) { /* fall through to token scan below */ }
                }
                if (actionsMap != null) {
                    actionsMap.each { Object k, Object entry ->
                        String actionKey = k?.toString() ?: ""
                        String method = (entry instanceof Map) ? ((Map) entry).get("method")?.toString() : null
                        String col = method ? KEYWORD_TOKEN_MAP[method] : null
                        if (col && actionKey) {
                            if (isActionKeyLive(actionKey, liveKeys)) liveByCol[col] = true
                            else                                      staleByCol[col] = true
                        }
                    }
                } else if (v != null) {
                    // Unparseable actions blob: find tokens but attribute
                    // conservatively as live (cannot pair token with key).
                    String txt = v.toString()
                    KEYWORD_TOKEN_MAP.each { String token, String col ->
                        if (txt.contains(token)) liveByCol[col] = true
                    }
                }
            } else if (STALE_STATE_ENTRY_NAMES.contains(n) && v != null) {
                String txt = v.toString()
                KEYWORD_TOKEN_MAP.each { String token, String col ->
                    if (txt.contains(token)) staleByCol[col] = true
                }
            }
        }

        SPECIAL_ACTION_KEYS.each { String col ->
            String s = hitStateFor(liveByCol[col] == true, staleByCol[col] == true)
            if (s) hits[col] = s
        }
    } catch (Exception e) {
        log.warn "extractStateKeywordHits: ${e.message}"
    }
    return hits
}

// Fetch the Phase 1 state-side keyword hits for a rule (empty map if none).
private Map phase1HitsForRule(String ruleId) {
    try {
        Object h = configurePhase1Hits?.get(ruleId)
        if (h instanceof Map) return (Map) h
    } catch (Exception e) {
        log.warn "phase1HitsForRule ${ruleId}: ${e.message}"
    }
    return [:]
}

// Fetch the Phase 1 live action keys for a rule as a Set (null if none stored).
private Set<String> liveKeysForRule(String ruleId) {
    try {
        Object lk = configureLiveKeys?.get(ruleId)
        if (lk instanceof List) {
            return ((List) lk).collect { it?.toString() }.findAll { it } as Set
        }
    } catch (Exception e) {
        log.warn "liveKeysForRule ${ruleId}: ${e.message}"
    }
    return null
}

// True when an index suffix taken from a keyword occurrence (e.g. the "2.1"
// in "repeatActs2.1") refers to a live action. Matching is deliberately
// liberal to avoid false negatives:
//   - a bare keyword with no suffix cannot be judged, so it counts as live;
//   - exact match against the live key set counts as live;
//   - dotted prefixes count ("2.1.3" is live when action "2.1" or "2" is);
//   - nested live keys count in reverse ("2" is live when "2.1" is live).
private boolean isActionKeyLive(String suffix, Set<String> liveKeys) {
    if (!suffix) return true
    if (liveKeys.contains(suffix)) return true

    String s = suffix
    while (s.indexOf('.') >= 0) {
        s = s.substring(0, s.lastIndexOf('.'))
        if (liveKeys.contains(s)) return true
    }

    String pfx = suffix + "."
    return liveKeys.any { Object k -> k?.toString()?.startsWith(pfx) }
}

// Recursively walk parsed configure/json collecting special-action keyword
// occurrences. RM stores the keyword as a bare setting VALUE and carries the
// action key as a dot-suffix on the setting NAME (e.g. actType.6 =
// "repeatActs", actSubType.3 = "getWaitRule") — the keyword itself has no
// index appended. Two shapes are handled, mirroring collectModeSettings:
// entries of the form {name:..., value:...} and plain map key -> value pairs.
// Occurrences are recorded per column as live or stale according to whether
// the name's index is in the rule's live action-key set; occurrences whose
// name carries no index cannot be judged and count as live.
private void collectKeywordHits(Object node, Set<String> liveKeys, Map liveByCol, Map staleByCol, int depth) {
    if (depth > 50) return

    if (node instanceof Map) {
        Map m = (Map) node

        // Shape 1: a settings entry {name: "actType.6", type: "enum",
        // value: "repeatActs"} — attribute the value to the name.
        String settingName = m.get("name")?.toString() ?: ""
        if (settingName && m.containsKey("value")) {
            recordKeywordOccurrences(settingName, m.get("value"), liveKeys, liveByCol, staleByCol)
        }
        if (settingName) {
            recordNameEmbeddedKeyword(settingName, liveKeys, liveByCol, staleByCol)
        }

        // Shape 2: plain map-keyed settings {"actType.6": "repeatActs"}.
        // Only keys that carry a dot-index are examined here: attributing a
        // structural key like "value" or "method" (which has no index) would
        // register a bare occurrence and wrongly classify ghosts as live.
        // Index-less settings are still covered by the raw-text fallback.
        m.each { Object k, Object v ->
            String key = k?.toString() ?: ""
            if (key && key != "name" && key != "type" && key != "value") {
                if (SETTING_NAME_INDEX_PATTERN.matcher(key).matches()) {
                    recordKeywordOccurrences(key, v, liveKeys, liveByCol, staleByCol)
                }
                recordNameEmbeddedKeyword(key, liveKeys, liveByCol, staleByCol)
            }
        }

        m.values().each { Object child -> collectKeywordHits(child, liveKeys, liveByCol, staleByCol, depth + 1) }
    } else if (node instanceof List) {
        ((List) node).each { Object child -> collectKeywordHits(child, liveKeys, liveByCol, staleByCol, depth + 1) }
    }
}

// Shape 3: legacy/name-embedded form where the setting name is the token
// with the index appended directly (e.g. "getWhile3" or "repeatActs2.1").
// Anchored exactly to token+digits so ordinary names cannot match.
private void recordNameEmbeddedKeyword(String name, Set<String> liveKeys, Map liveByCol, Map staleByCol) {
    if (!name) return
    KEYWORD_TOKEN_MAP.each { String token, String col ->
        if (!name.startsWith(token)) return
        String rest = name.substring(token.length())
        if (rest && rest ==~ /\d+(?:\.\d+)*/) {
            if (isActionKeyLive(rest, liveKeys)) liveByCol[col] = true
            else                                 staleByCol[col] = true
        }
    }
}

// Examine one name/value pair for keyword tokens and classify each hit by
// the trailing index of the name (actType.6 -> "6"). Values may be a single
// token, or occasionally list-like; a simple contains() per token suffices
// because the tokens are distinctive camelCase identifiers.
private void recordKeywordOccurrences(String name, Object value, Set<String> liveKeys, Map liveByCol, Map staleByCol) {
    if (value == null) return
    String valText
    if (value instanceof CharSequence) {
        valText = value.toString()
    } else if (value instanceof List) {
        // Multi-select values arrive as lists of tokens; scalar elements are
        // joined so they can be attributed to this name's index. Nested
        // containers are covered by the recursive walk.
        valText = ((List) value).findAll { !(it instanceof Map) && !(it instanceof List) }
                                .collect { it?.toString() ?: "" }.join(",")
    } else if (value instanceof Map) {
        valText = null   // containers are walked separately
    } else {
        valText = value.toString()
    }
    if (!valText) return

    KEYWORD_TOKEN_MAP.each { String token, String col ->
        if (!valText.contains(token)) return
        java.util.regex.Matcher nm = SETTING_NAME_INDEX_PATTERN.matcher(name)
        if (nm.matches()) {
            String idx = nm.group(1)
            if (isActionKeyLive(idx, liveKeys)) liveByCol[col] = true
            else                                staleByCol[col] = true
        } else {
            liveByCol[col] = true      // no index on the name: cannot judge
        }
    }
}

// Classify each special-action keyword using the configure/json payload
// merged with Phase 1 state-side hits (actions map, clipboard buffers):
//   true        - live occurrences only
//   "livestale" - live occurrences plus stale leftovers
//   "stale"     - stale leftovers only
//   false       - keyword not present at all
// When no live action-key set is available for the rule, classification
// falls back to the pre-1.08 behavior: any token anywhere counts as live.
// A raw-text fallback also covers payload shapes the structured walk does
// not recognize, so unexpected formats degrade to live rather than to
// false negatives.
private Map detectSpecialActionsFromRaw(Object raw, Set<String> liveKeys, Map phase1Hits) {
    try {
        if (raw == null) return null

        String jsonText
        Object parsed = null
        if (raw instanceof CharSequence) {
            jsonText = raw.toString()
            try { parsed = new groovy.json.JsonSlurper().parseText(jsonText) } catch (Exception ignored) { }
        } else {
            jsonText = groovy.json.JsonOutput.toJson(raw)
            parsed = raw
        }

        boolean haveLiveInfo = (liveKeys != null && !liveKeys.isEmpty())

        Map liveByCol  = [:]
        Map staleByCol = [:]

        if (haveLiveInfo && parsed != null) {
            collectKeywordHits(parsed, liveKeys, liveByCol, staleByCol, 0)
        }

        // Merge Phase 1 state-side hits (actions map vs actionList, plus
        // clipboard buffers and old-settings snapshots).
        if (haveLiveInfo && phase1Hits instanceof Map) {
            phase1Hits.each { Object k, Object v ->
                String col = k?.toString()
                String s   = v?.toString()
                if (!SPECIAL_ACTION_KEYS.contains(col)) return
                if (s == "live"      || s == "livestale") liveByCol[col]  = true
                if (s == "stale"     || s == "livestale") staleByCol[col] = true
            }
        }

        Map found = [:]
        SPECIAL_ACTION_KEYS.each { String col ->
            boolean anyLive  = liveByCol[col]  == true
            boolean anyStale = staleByCol[col] == true

            // Raw-text fallback: token present somewhere, but neither the
            // structured walk nor Phase 1 attributed it (or no live-key info
            // exists at all). Count it as live — conservative, and identical
            // to the pre-1.08 behavior for unrecognized payload shapes.
            if (!anyLive && !anyStale) {
                boolean rawHit = KEYWORD_TOKEN_MAP.any { String token, String c -> c == col && jsonText.contains(token) }
                if (rawHit) anyLive = true
            }

            if (anyLive)       { found[col] = anyStale ? "livestale" : true }
            else if (anyStale) { found[col] = "stale" }
            else               { found[col] = false }
        }
        return found
    } catch (Exception e) {
        log.warn "detectSpecialActionsFromRaw: ${e.message}"
        return null
    }
}

// Build the hub's mode ID -> name map from location.getModes(). Called at
// Phase 2 start (and lazily from detectModesFromRaw as a fallback).
private void buildModeIdMap() {
    Map idMap = [:]
    try {
        location?.getModes()?.each { Object m ->
            String mid   = m?.id?.toString()
            String mname = m?.name?.toString()
            if (mid && mname) idMap[mid] = mname
        }
    } catch (Exception e) {
        log.warn "buildModeIdMap: could not read location modes — ${e.message}"
    }
    modeIdToName = idMap
    if (debugEnable) log.debug "SAS: location modes: ${idMap}"
}

// Parse the configure/json payload and collect the mode names a rule uses.
// Detects both "mode"-type inputs (value is the mode name) and RM/BC enum
// settings matching MODE_SETTING_NAME_PATTERN (value is a mode ID, translated
// to a name via location.getModes()). Returns a sorted list of mode names,
// an empty list when no mode settings are found, or null when the payload
// could not be parsed (Modes will then show as unknown for that rule).
private List<String> detectModesFromRaw(Object raw) {
    try {
        if (raw == null) return null
        if (modeIdToName == null) buildModeIdMap()

        Object parsed
        if (raw instanceof CharSequence) {
            parsed = new groovy.json.JsonSlurper().parseText(raw.toString())
        } else {
            parsed = raw
        }

        Set<String> modes = [] as Set
        collectModeSettings(parsed, modes, 0)
        return modes.sort { it.toLowerCase() }
    } catch (Exception e) {
        log.warn "detectModesFromRaw: ${e.message}"
        return null
    }
}

// Recursive walk over the parsed configure/json structure. Harvests mode
// values from three shapes:
//   1. A Map node with "type" == "mode" — a native mode input; its "value"
//      holds mode name(s) directly (non-strict translation).
//   2. A Map node whose "name" entry matches MODE_SETTING_NAME_PATTERN —
//      RM-style settings entry like [name: "modesX1", type: "enum",
//      value: ["4"]]; values are mode IDs (strict translation).
//   3. A Map entry whose *key* matches the pattern — in case the payload
//      nests settings as {"modesX1": ...} rather than name/value objects
//      (strict translation).
// Uses explicit get()/values() calls because Groovy property access on a Map
// (node.value) reads map entries, which is what we want for get(), but
// node.values must be the Map method, not an entry lookup.
private void collectModeSettings(Object node, Set<String> modes, int depth) {
    if (depth > 50) return

    if (node instanceof Map) {
        Map m = (Map) node

        if (m.get("type")?.toString() == "mode") {
            harvestModeValues(m.get("value"), modes, false)
        }

        String settingName = m.get("name")?.toString() ?: ""
        if (settingName && MODE_SETTING_NAME_PATTERN.matcher(settingName).matches()) {
            harvestModeValues(m.get("value"), modes, true)
        }

        m.each { Object k, Object v ->
            String key = k?.toString() ?: ""
            if (key && key != "name" && key != "type" &&
                MODE_SETTING_NAME_PATTERN.matcher(key).matches()) {
                harvestModeValues(v, modes, true)
            }
        }

        m.values().each { Object child -> collectModeSettings(child, modes, depth + 1) }
    } else if (node instanceof List) {
        ((List) node).each { Object child -> collectModeSettings(child, modes, depth + 1) }
    }
}

// Add mode name(s) derived from a raw setting value to the modes set.
// Handles scalars, Lists, Maps (digs into their "value" entry), and Strings
// that encode a JSON array (e.g. '["4","1"]'). When translateStrictly is true
// (name-pattern matches), a value only counts if it maps to a known mode ID
// or matches a known mode name on this hub — this filters out unrelated
// settings that happen to match the name pattern. When false (mode-type
// inputs), untranslatable values are kept as-is since the value is expected
// to already be the mode name.
private void harvestModeValues(Object v, Set<String> modes, boolean translateStrictly) {
    if (v == null) return

    if (v instanceof List) {
        ((List) v).each { Object it -> harvestModeValues(it, modes, translateStrictly) }
        return
    }

    if (v instanceof Map) {
        harvestModeValues(((Map) v).get("value"), modes, translateStrictly)
        return
    }

    String s = v.toString().trim()
    if (!s) return

    if (s.startsWith("[") && s.endsWith("]")) {
        try {
            Object parsed = new groovy.json.JsonSlurper().parseText(s)
            if (parsed instanceof List) {
                ((List) parsed).each { Object it -> harvestModeValues(it, modes, translateStrictly) }
                return
            }
        } catch (Exception ignored) {}
    }

    Map idMap = modeIdToName ?: [:]

    String byId = idMap[s]?.toString()
    if (byId) {
        modes << byId
        return
    }

    Object byName = idMap.values().find { Object n -> n?.toString()?.equalsIgnoreCase(s) }
    if (byName != null) {
        modes << byName.toString()
        return
    }

    if (!translateStrictly) modes << s
}

// Coerce a row's stored modes value (which round-trips through state JSON)
// back into a clean List<String>.
private List<String> normalizeModesList(Object raw) {
    if (!(raw instanceof List)) return []
    return ((List) raw).collect { it?.toString() }.findAll { it }
}

void finalizeUsageScan() {
    unschedule("finalizeUsageScan")
    unschedule("configurePump")
    unschedule("configureHeartbeat")

    Map     cfgRes     = null
    Integer totalRules = 0

    // Take ownership of the scan under the lock: null the scan ID first so
    // any late callbacks, pumps, or a second concurrent finalize (heartbeat
    // vs. total-timeout vs. normal completion) bail out cleanly, then
    // snapshot and clear the shared state. scanStatus is cleared inside the
    // lock, after ownership is taken — clearing it any earlier lets a pump
    // thread that is still mid-cycle re-stamp a stale progress message that
    // would then linger on the app page until the next scan.
    synchronized (PHASE2_LOCK) {
        if (configureScanId == null) return
        configureScanId = null
        cfgRes     = new HashMap(configureResults ?: [:])
        totalRules = (configureTotalRules ?: 0) as Integer

        configureQueue          = null
        configureResults        = null
        configureNextIdx        = 0
        configureInFlight       = 0
        configureTotalRules     = 0
        configureInflight       = [:]
        configureAttempts       = [:]
        configureLiveKeys       = [:]
        configurePhase1Hits     = [:]

        scanStatusLine          = null
    }

    log.info "SAS: Phase 2 finalizing"

    try {
        List<Map> rows = new groovy.json.JsonSlurper().parseText(state.scanRowsJson ?: "[]") as List<Map>
        int neverScanned = 0
        rows = rows.collect { Map r ->
            String id = r.id?.toString()
            Object res = cfgRes.containsKey(id) ? cfgRes[id] : null
            if (!cfgRes.containsKey(id)) neverScanned++
            Map resMap = (res instanceof Map) ? (Map) res : null
            if (resMap != null && resMap.get("keywords") instanceof Map) {
                r.specialActions = normalizeKeywordMap(resMap.get("keywords") as Map)
                r.specialUnknown = false
                r.modes          = normalizeModesList(resMap.get("modes"))
            } else {
                r.specialActions = emptyKeywordCounts()
                r.specialUnknown = true
                r.modes          = []
            }
            return r
        }

        updateSpecialActionStats(rows)

        Long phase2EndMs         = now() as Long
        Long startedMs           = (state.scanStartedMs ?: scanStartMs ?: phase2EndMs) as Long
        Long phase1EndMs         = (state.phase1EndedMs ?: startedMs) as Long

        state.phase1ScanDuration = state.phase1ScanDuration ?: formatScanDuration(phase1EndMs - startedMs)
        state.phase2ScanDuration = formatScanDuration(phase2EndMs - phase1EndMs)
        state.totalScanDuration  = formatScanDuration(phase2EndMs - startedMs)
        state.scanDuration       = state.totalScanDuration
        state.lastScan           = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)

        state.scanRowsJson       = groovy.json.JsonOutput.toJson(rows)
        state.reportHtml         = buildReportHtml(rows)

        if (neverScanned > 0) {
            log.warn "SAS: Phase 2 ended in ${state.phase2ScanDuration} BEFORE scanning ${neverScanned} of ${rows.size()} rules (heartbeat or total-timeout backstop) — they are marked '?'. Run Scan again to fill them in."
            state.scanNotice = "<i>Phase 2 ended before scanning ${neverScanned} of ${rows.size()} rules (marked <span style='color:#c00;font-weight:bold;'>?</span>). The hub may have been busy — running Scan again may fill them in.</i>"
        }
        log.info "SAS: Phase 2 complete in ${state.phase2ScanDuration}; total scan time ${state.totalScanDuration} — ${state.specialActionRuleCount ?: 0} of ${rows.size()} rules with detected special actions; ${state.staleMatchRuleCount ?: 0} with stale keyword leftovers; ${state.specialActionUnknownCount ?: 0} unknown/skipped"

    } catch (Exception e) {
        log.warn "finalizeUsageScan: ${e.message}"
    } finally {
        state.scanStartedMs     = null
        state.phase1EndedMs     = null
    }
}

void finalizeScan() {
    unschedule("finalizeScanTimeout")

    List<Map> asyncRules     = scanRuleQueue      ?: []
    Map       partialResults = scanPartialResults ?: [:]

    List<Map> rmRows = asyncRules.collect { Map rule ->
        Map row = partialResults[rule.id as String] as Map
        if (row) return row
        log.warn "No statusJson response for ${rule.id} (${rule.name}) — Last Run is unknown"
        return [id: rule.id as String,
                name: rule.name as String,
                appType: (rule.appType ?: "RM") as String,
                lastRun: "",
                liveKeys: [],
                stateHits: [:],
                specialActions: emptyKeywordCounts(),
                specialUnknown: true,
                modes: []]
    }

    Long phase1EndMs         = now() as Long
    Long startedMs           = (state.scanStartedMs ?: scanStartMs ?: phase1EndMs) as Long
    state.scannedCount       = rmRows.size()
    state.lastScan           = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
    state.phase1EndedMs      = phase1EndMs
    state.phase1ScanDuration = formatScanDuration(phase1EndMs - startedMs)
    state.scanDuration       = state.phase1ScanDuration

    try {
        state.scanRowsJson = groovy.json.JsonOutput.toJson(rmRows)
    } catch (Exception e) {
        log.warn "finalizeScan: could not cache scan rows — ${e.message}"
        state.scanRowsJson = null
    }

    updateSpecialActionStats(rmRows)
    state.reportHtml = buildReportHtml(rmRows)
    scanStatusLine = "<i>Phase 2: checking configure/json for special-action keywords…</i>"

    if (!asyncRules.isEmpty()) {
        buildModeIdMap()

        // Live action keys and state-side keyword hits captured from Phase 1
        // statusJson, used in Phase 2 to separate live keyword matches from
        // stale/leftover ones.
        Map liveKeyMap = [:]
        Map p1HitsMap  = [:]
        rmRows.each { Map r ->
            String rid = r.id as String
            liveKeyMap[rid] = (r.liveKeys  instanceof List) ? (List) r.liveKeys  : []
            p1HitsMap[rid]  = (r.stateHits instanceof Map)  ? (Map)  r.stateHits : [:]
        }

        // Backstop before Phase 2 is force-finalized. Scaled to the worst
        // case (every rule burning its first-attempt and retry timeouts, at
        // CONFIGURE_MAX_IN_FLIGHT concurrency) so large/slow hubs are not cut
        // off mid-scan; the heartbeat still catches genuine stalls quickly.
        int perRuleWorstSecs = CONFIGURE_REQUEST_TIMEOUT_SECS + CONFIGURE_RETRY_TIMEOUT_SECS + 10
        int batches          = (int) Math.ceil(asyncRules.size() / (double) CONFIGURE_MAX_IN_FLIGHT)
        int totalTimeoutSecs = Math.max(CONFIGURE_TOTAL_TIMEOUT_SECS, batches * perRuleWorstSecs)

        synchronized (PHASE2_LOCK) {
            configureLiveKeys   = liveKeyMap
            configurePhase1Hits = p1HitsMap
            configureScanId     = (currentScanId ?: "scan") + "_cfg"
            configureQueue      = asyncRules
            configureResults    = [:]
            configureInflight   = [:]
            configureAttempts   = [:]
            configureNextIdx    = 0
            configureInFlight   = 0
            configureTotalRules = asyncRules.size()
        }

        runIn(totalTimeoutSecs, "finalizeUsageScan")
        resetConfigureHeartbeat("start")
        configurePump()

        log.info "SAS: Phase 2 started — ${asyncRules.size()} configure/json requests queued; max in flight: ${CONFIGURE_MAX_IN_FLIGHT}; heartbeat: ${CONFIGURE_HEARTBEAT_SECS}s; retries per rule: ${CONFIGURE_MAX_ATTEMPTS - 1}; total-timeout backstop: ${formatScanDuration(totalTimeoutSecs * 1000L)}"
    }

    currentScanId      = null
    scanPartialResults = null
    scanRuleQueue      = null

    log.info "SAS: Phase 1 scan complete in ${state.phase1ScanDuration}: ${rmRows.size()} RM/BC rules"
}

void finalizeScanTimeout() {
    if (currentScanId != null) {
        int total = scanRuleQueue?.size() ?: 0
        log.warn "Scan timeout: finalizing with partial results (${total} rules in queue)"
        finalizeScan()
    }
}

// ============================================================
// Rule discovery
// ============================================================

List<Map> getRuleMachineRuleApps() {
    List<Map> rules = []
    Set<String> seenIds = [] as Set

    Map params = [
        uri         : RM_BASE_URL,
        path        : "/hub2/appsList",
        contentType : "application/json"
    ]

    try {
        httpGet(params) { resp ->
            resp.data?.apps?.each { parentApp ->
                def pd = parentApp?.data
                String parentType  = pd?.type?.toString()  ?: ""
                String parentName  = pd?.name?.toString()  ?: ""
                String parentLabel = pd?.label?.toString() ?: ""
                String appType     = getSupportedAutomationAppType(parentType, parentName, parentLabel)

                if (appType) {
                    parentApp?.children?.each { child ->
                        collectRmLeafRules(child, appType, rules, seenIds, 0)
                    }
                }
            }
        }
    } catch (Exception e) {
        state.lastError = "Unable to read /hub2/appsList. This may be temporary; try Scan again. Error: ${e.message}"
        log.warn state.lastError
    }

    if (debugEnable) log.debug "SAS: discovered ${rules.size()} RM/BC rules from /hub2/appsList"
    return rules.sort { it.name?.toLowerCase() ?: "" }
}

private void collectRmLeafRules(Object node, String parentAppType, List<Map> rules, Set<String> seenIds, int depth) {
    if (depth > 6) return
    List children = (node?.children ?: []) as List
    if (children.isEmpty()) {
        def d = node?.data
        if (d?.id && d?.name) {
            String id = d.id.toString()
            if (!seenIds.contains(id)) {
                String childType         = d?.type?.toString()    ?: ""
                String childAppName      = d?.appName?.toString() ?: ""
                String childDetectedType = getSupportedAutomationAppType(childType, childAppName)
                String finalAppType      = (parentAppType == "BC" || childDetectedType == "BC") ? "BC" : (childDetectedType ?: parentAppType)

                seenIds << id
                String ruleName = d.name.toString()
                rules << [
                    id       : id,
                    name     : ruleName,
                    appType  : finalAppType
                ]
            }
        }
    } else {
        children.each { child -> collectRmLeafRules(child, parentAppType, rules, seenIds, depth + 1) }
    }
}

String getSupportedAutomationAppType(String type, String name, String label = "") {
    String combined = [type, name, label].findAll { it }.join(" ").toLowerCase()

    if (!combined) return null

    if (combined.contains("basic button controller") || combined.contains("basicbuttoncontroller")) {
        return null
    }

    if (combined.contains("button controller") || combined.contains("buttoncontroller")) {
        return "BC"
    }

    if (combined.contains("rule machine") || combined.contains("rulemachine")) {
        return "RM"
    }

    return null
}

// ============================================================
// Preference persistence endpoint
// ============================================================

def handleSetPrefEndpoint() {
    if (!state.accessToken) return renderJson([status: "error", message: "OAuth not active"])

    String key   = params?.key?.toString()
    String value = params?.value?.toString()
    if (!key) return renderJson([status: "error", message: "missing key"])

    Set allowedKeys = (["hideColRuleId", "hideColAppType", "hideColModes", "hideColLastRun"] + SPECIAL_ACTION_KEYS.collect { "hideCol_${it}" }) as Set

    if (!(key in allowedKeys)) {
        return renderJson([status: "error", message: "unsupported preference key"])
    }

    Map prefs = (state.userPrefs ?: [:]) as Map
    prefs[key] = value
    state.userPrefs = prefs
    return renderJson([status: "success"])
}

boolean getPref(String key, boolean defaultVal = false) {
    Map prefs = (state.userPrefs ?: [:]) as Map
    if (prefs.containsKey(key)) return prefs[key]?.toString() == "true"
    return defaultVal
}

// ============================================================
// Report endpoints — printable HTML and CSV export
// ============================================================

def handleReportEndpoint() {
    if (!state.accessToken) {
        render contentType: "text/plain", data: "OAuth not active — re-open the app to retry."
        return
    }
    render contentType: "text/html; charset=UTF-8", data: buildRmPrintHtml()
}

def handleRmCsvEndpoint() {
    if (!state.accessToken) { render contentType: "text/plain", data: "OAuth not active."; return }
    render contentType: "text/csv; charset=UTF-8", data: buildRmCsv()
}

private String printHtmlShell(String title, String subtitle, String tableHtml) {
    String safeTitle = htmlEncode(title)
    String safeSubtitle = htmlEncode(subtitle)
    return "<!DOCTYPE html>" +
        "<html lang='en'><head><meta charset='UTF-8'>" +
        "<title>${safeTitle}</title>" +
        "<style>" +
        "body{font-family:Arial,sans-serif;font-size:12px;margin:16px;}" +
        "h2{font-size:16px;margin-bottom:2px;}" +
        "p.sub{font-size:11px;color:#555;margin:0 0 12px;}" +
        "table{border-collapse:collapse;width:100%;}" +
        "th,td{border:1px solid #bbb;padding:4px 8px;text-align:left;vertical-align:top;}" +
        "th{background:#e8e8e8;font-weight:bold;}" +
        "tr:nth-child(even){background:#f7f7f7;}" +
        ".c{text-align:center;}" +
        "@media print{body{margin:6mm;font-size:11px;}a{text-decoration:none;color:inherit;}thead{display:table-header-group;}tr{page-break-inside:avoid;}}" +
        "</style></head><body>" +
        "<h2>${safeTitle}</h2>" +
        "<p class='sub'>${safeSubtitle}</p>" +
        tableHtml +
        "</body></html>"
}

String buildRmPrintHtml() {
    List<Map> rows = []
    try { rows = new groovy.json.JsonSlurper().parseText(state.scanRowsJson ?: "[]") as List<Map> } catch (Exception ignored) {}
    rows = rows.sort { it.name?.toString()?.toLowerCase() ?: "" }

    StringBuilder sb = new StringBuilder()
    sb << "<table><thead><tr>"
    (["Rule ID", "Rule", "App Type"] + SPECIAL_ACTION_KEYS.collect { String key -> labelForKeyword(key) } + ["Modes", "Last Run"]).each { String h ->
        sb << "<th>${htmlEncode(h)}</th>"
    }
    sb << "</tr></thead><tbody>"
    boolean suppressStale = isSuppressStaleEnabled()
    rows.each { Map r ->
        Map actions = normalizeKeywordMap(r.specialActions instanceof Map ? (Map) r.specialActions : [:])
        boolean unknown = r.specialUnknown == true
        sb << "<tr>"
        sb << "<td class='c'>${htmlEncode(r.id)}</td>"
        sb << "<td>${htmlEncode(r.name)}</td>"
        sb << "<td class='c'>${htmlEncode(r.appType ?: '')}</td>"
        SPECIAL_ACTION_KEYS.each { String key ->
            String cellState = keywordCellState(actions[key], suppressStale)
            String v = unknown ? "?" :
                       (cellState == "live"      ? "Yes" :
                        cellState == "livestale" ? "Yes+Stale" :
                        cellState == "stale"     ? "Stale" : "No")
            sb << "<td class='c'>${v}</td>"
        }
        List<String> modesList = normalizeModesList(r.modes)
        String modesVal = unknown ? "?" : htmlEncode(modesList.join(", "))
        sb << "<td class='c'>${modesVal}</td>"
        sb << "<td class='c'>${htmlEncode(r.lastRun ?: '')}</td>"
        sb << "</tr>"
    }
    sb << "</tbody></table>"

    String subtitle = "Last scan: ${state.lastScan ?: 'never'} — ${rows.size()} rules"
    return printHtmlShell("Rule Machine and Button Controller Special Actions", subtitle, sb.toString())
}

String buildRmCsv() {
    List<Map> rows = []
    try { rows = new groovy.json.JsonSlurper().parseText(state.scanRowsJson ?: "[]") as List<Map> } catch (Exception ignored) {}
    rows = rows.sort { it.name?.toString()?.toLowerCase() ?: "" }

    StringBuilder sb = new StringBuilder()
    sb << (["Rule ID", "Rule", "App Type"] + SPECIAL_ACTION_KEYS.collect { String key -> labelForKeyword(key) } + ["Modes", "Last Run"]).collect { escapeCsv(it) }.join(",") << "\n"
    boolean suppressStale = isSuppressStaleEnabled()
    rows.each { Map r ->
        Map actions = normalizeKeywordMap(r.specialActions instanceof Map ? (Map) r.specialActions : [:])
        boolean unknown = r.specialUnknown == true
        List vals = [r.id, r.name, r.appType]
        SPECIAL_ACTION_KEYS.each { String key ->
            String cellState = keywordCellState(actions[key], suppressStale)
            vals << (unknown ? "?" :
                     (cellState == "live"      ? "Yes" :
                      cellState == "livestale" ? "Yes+Stale" :
                      cellState == "stale"     ? "Stale" : "No"))
        }
        vals << (unknown ? "?" : normalizeModesList(r.modes).join(", "))
        vals << r.lastRun
        sb << vals.collect { escapeCsv(it) }.join(",") << "\n"
    }
    return sb.toString()
}

@CompileStatic
private String escapeCsv(Object v) {
    if (v == null) return ""
    String s = v.toString().replace('"', '""')
    return (s.contains(",") || s.contains('"') || s.contains("\n")) ? "\"${s}\"" : s
}

// ============================================================
// Last Run extraction
// ============================================================

String extractLastRun(Map status) {
    String lastEvtDate = ""
    String lastEvtTime = ""
    String timeFormat  = ""
    String dateFormat  = ""

    status?.appState?.each { item ->
        String n = item?.name?.toString() ?: ""
        if (n == "lastEvtDate") lastEvtDate = item?.value?.toString() ?: ""
        if (n == "lastEvtTime") lastEvtTime = item?.value?.toString() ?: ""
        if (n == "timeFormat")  timeFormat  = item?.value?.toString() ?: ""
        if (n == "dateFormat")  dateFormat  = item?.value?.toString() ?: ""
    }

    if (!lastEvtDate) return ""

    java.text.SimpleDateFormat outDateTimeFmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
    java.text.SimpleDateFormat outDateFmt     = new java.text.SimpleDateFormat("yyyy-MM-dd")
    java.text.SimpleDateFormat outTimeFmt     = new java.text.SimpleDateFormat("HH:mm")

    boolean hasTimeComponent = lastEvtDate.toUpperCase().contains("AM") ||
                               lastEvtDate.toUpperCase().contains("PM") ||
                               lastEvtDate.indexOf(":", 6) >= 0

    if (hasTimeComponent) {
        List<String> fullDateFmts = [
            "dd-MMM-yyyy hh:mm:ss a",
            "dd-MMM-yyyy HH:mm:ss",
            "dd-MMM-yyyy hh:mm a",
            "dd-MMM-yyyy HH:mm",
            "MM/dd/yyyy hh:mm:ss a",
            "MM/dd/yyyy HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd hh:mm:ss a"
        ]
        for (String fmt : fullDateFmts) {
            try {
                return outDateTimeFmt.format(new java.text.SimpleDateFormat(fmt).parse(lastEvtDate))
            } catch (Exception ignored) {}
        }
        log.warn "extractLastRun: unrecognized full datetime '${lastEvtDate}' — add format to extractLastRun if needed"
        return "* ${lastEvtDate}"
    }

    if (!lastEvtDate.matches(/\d{4}-\d{2}-\d{2}/)) {
        List<String> dateFmts = (dateFormat ? [dateFormat] : []) + ["dd-MMM-yyyy", "MM/dd/yyyy", "dd/MM/yyyy", "MMM dd, yyyy"]
        String normalizedDate = null
        for (String fmt : dateFmts) {
            try {
                normalizedDate = outDateFmt.format(new java.text.SimpleDateFormat(fmt).parse(lastEvtDate))
                break
            } catch (Exception ignored) {}
        }
        if (normalizedDate) {
            lastEvtDate = normalizedDate
        } else {
            log.warn "extractLastRun: unrecognized date format '${lastEvtDate}' — add format to extractLastRun if needed"
            lastEvtDate = "* ${lastEvtDate}"
        }
    }

    if (!lastEvtTime) return lastEvtDate

    List<String> timeFmts = timeFormat ? [timeFormat] : []
    timeFmts += ["hh:mm:ss a", "h:mm:ss a", "HH:mm:ss", "hh:mm a", "h:mm a", "HH:mm", "h:mm"]
    for (String fmt : timeFmts) {
        try {
            return "${lastEvtDate} ${outTimeFmt.format(new java.text.SimpleDateFormat(fmt).parse(lastEvtTime))}"
        } catch (Exception ignored) {}
    }

    log.warn "extractLastRun: could not parse time '${lastEvtTime}' (timeFormat='${timeFormat}') — add format to extractLastRun if needed"
    return "* ${lastEvtDate} ${lastEvtTime}"
}

// ============================================================
// Shared report assets (CSS + JS)
// ============================================================

String buildSharedReportAssets(String prefEndpoint = "") {
    StringBuilder sb = new StringBuilder()
    sb << "<style>"
    sb << "table.rmlogcheck{border-collapse:collapse;width:100%;}"
    sb << "table.rmlogcheck th,table.rmlogcheck td{border:1px solid #ccc;padding:4px 7px;text-align:left;vertical-align:middle;}"
    sb << "table.rmlogcheck th{background-color:#FFD700;color:#000;cursor:pointer;font-weight:bold;user-select:none;white-space:nowrap;}"
    sb << "table.rmlogcheck th:hover{background-color:#FFC700;}"
    sb << "table.rmlogcheck th.sort-asc::after{content:' ▲';font-size:0.8em;}"
    sb << "table.rmlogcheck th.sort-desc::after{content:' ▼';font-size:0.8em;}"
    sb << "table.rmlogcheck td.center,table.rmlogcheck th.center{text-align:center;}"
    sb << "table.rmlogcheck td.rmcol-lastrun{white-space:nowrap;}"
    sb << ".rmcol-toggle-bar{margin:4px 0 8px;font-size:0.9em;line-height:1.9;}"
    sb << ".rmcol-btn{display:inline-block;cursor:pointer;padding:2px 8px;margin-right:6px;border:1px solid #aaa;border-radius:3px;background:#e8e8e8;user-select:none;}"
    sb << ".rmcol-btn.hidden-col{text-decoration:line-through;opacity:0.45;background:#ccc;}"
    sb << ".rmname-filter{padding:2px 6px;font-size:0.9em;border:1px solid #aaa;border-radius:3px;vertical-align:middle;}"
    sb << ".rmcheck-action-btn{display:inline-block;cursor:pointer;padding:2px 9px;margin-right:4px;border:1px solid #888;border-radius:3px;background:#f0f0f0;color:#333;font-weight:bold;user-select:none;}"
    sb << "table.rmlogcheck td.rmcol-special,table.rmlogcheck th.rmcol-special{width:94px;min-width:94px;}"
    sb << "table.rmlogcheck td.rmcol-modes,table.rmlogcheck th.rmcol-modes{min-width:110px;}"
    sb << "</style>"
    sb << "<script>var rmPrefEndpoint = ${groovy.json.JsonOutput.toJson(prefEndpoint ?: null)};</script>"

    sb << '''<script>
function sortRmLogTable(tableId, columnIndex) {
    const table = document.getElementById(tableId);
    if (!table) return;
    const tbody = table.querySelector('tbody');
    if (!tbody) return;
    const rows = Array.from(tbody.querySelectorAll('tr'));
    const headers = table.querySelectorAll('th');
    if (!window.rmLogTableSorts) window.rmLogTableSorts = {};
    if (!window.rmLogTableSorts[tableId]) window.rmLogTableSorts[tableId] = {};
    const currentDirection = window.rmLogTableSorts[tableId][columnIndex] || 'asc';
    const newDirection = currentDirection === 'asc' ? 'desc' : 'asc';
    window.rmLogTableSorts[tableId][columnIndex] = newDirection;
    headers.forEach(header => { header.classList.remove('sort-asc', 'sort-desc'); });
    if (headers[columnIndex]) headers[columnIndex].classList.add('sort-' + newDirection);
    rows.sort((a, b) => {
        const aCell = a.querySelectorAll('td')[columnIndex];
        const bCell = b.querySelectorAll('td')[columnIndex];
        let aText = aCell ? (aCell.getAttribute('data-sort') || aCell.textContent || '').trim() : '';
        let bText = bCell ? (bCell.getAttribute('data-sort') || bCell.textContent || '').trim() : '';
        const aIsNumber = aText !== '' && isFinite(Number(aText));
        const bIsNumber = bText !== '' && isFinite(Number(bText));
        let comparison = 0;
        if (aIsNumber && bIsNumber) {
            comparison = Number(aText) - Number(bText);
        } else {
            comparison = aText.toLowerCase().localeCompare(bText.toLowerCase());
        }
        return newDirection === 'asc' ? comparison : -comparison;
    });
    rows.forEach(row => tbody.appendChild(row));
}

function persistPref(key, value) {
    if (!key || !rmPrefEndpoint) return;
    fetch(rmPrefEndpoint + '&key=' + encodeURIComponent(key) + '&value=' + encodeURIComponent(value))
        .catch(function(e) { console.warn('persistPref failed:', e.message); });
}

function toggleRmCol(cls, btn) {
    var hiding = btn.className.indexOf('hidden-col') === -1;
    document.querySelectorAll('.' + cls).forEach(function(el) { el.style.display = hiding ? 'none' : ''; });
    btn.className = hiding ? 'rmcol-btn hidden-col' : 'rmcol-btn';
    persistPref(btn.dataset.prefKey, String(hiding));
}

function wildcardToRegex(pattern) {
    var result = '';
    for (var i = 0; i < pattern.length; i++) {
        var ch = pattern[i];
        if (ch === '*') { result += '.*'; }
        else if (ch === '?') { result += '.'; }
        else if ('.+^$()|[]{}'.indexOf(ch) >= 0 || ch === String.fromCharCode(92)) { result += String.fromCharCode(92) + ch; }
        else { result += ch; }
    }
    return new RegExp('^' + result + '$', 'i');
}

var rmSpecialFilterActive = false;

function rmToggleSpecialFilter() {
    var btn = document.getElementById('rm-hide-no-special');
    rmSpecialFilterActive = !rmSpecialFilterActive;
    if (btn) btn.textContent = rmSpecialFilterActive ? 'Show all rows' : 'Hide rows with no Special Actions';
    applyRmRowFilters();
}

function applyRmRowFilters() {
    var filter = (document.getElementById('rmname-filter')?.value || '').trim();
    var useWildcard = filter.indexOf('*') >= 0 || filter.indexOf('?') >= 0;
    var regex = null;
    if (filter && useWildcard) {
        try { regex = wildcardToRegex(filter); } catch(e) { regex = null; }
    }

    document.querySelectorAll('#rmlog_table tbody tr').forEach(function(row) {
        var name = row.getAttribute('data-rule-name') || '';
        var showByName = true;
        if (filter) {
            showByName = regex ? regex.test(name) : name.toLowerCase().indexOf(filter.toLowerCase()) >= 0;
        }
        var showBySpecial = true;
        if (rmSpecialFilterActive) {
            // Hide only rows that are known to have no special actions.
            // Unknown/skipped rows remain visible so they can be reviewed.
            showBySpecial = row.getAttribute('data-special-any') !== 'false';
        }
        row.style.display = (showByName && showBySpecial) ? '' : 'none';
    });
}
</script>'''
    return sb.toString()
}

// ============================================================
// RM/BC table HTML
// ============================================================

String buildReportHtml(List<Map> rows) {
    String prefEndpoint = ""
    if (state.accessToken) {
        prefEndpoint = "/apps/api/${app.id}/setpref?access_token=${state.accessToken}"
    } else {
        log.warn "buildReportHtml: no access token — UI hide preferences will not persist until OAuth is active."
    }

    StringBuilder sb = new StringBuilder()
    sb << buildSharedReportAssets(prefEndpoint)

    if (!rows) {
        sb << "<p>No rules found. Click <b>Scan All RM/BC Rules for Special Actions</b> to begin.</p>"
        return sb.toString()
    }

    boolean cfgHideColRuleId  = getPref("hideColRuleId",  false)
    boolean cfgHideColAppType = getPref("hideColAppType", false)
    boolean cfgHideColModes   = getPref("hideColModes",   false)
    boolean cfgHideColLastRun = getPref("hideColLastRun", false)

    Map hideKeyword = [:]
    SPECIAL_ACTION_KEYS.each { String key -> hideKeyword[key] = getPref("hideCol_${key}", false) }

    String btnColRuleId  = cfgHideColRuleId  ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnColAppType = cfgHideColAppType ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnColModes   = cfgHideColModes   ? "rmcol-btn hidden-col" : "rmcol-btn"
    String btnColLastRun = cfgHideColLastRun ? "rmcol-btn hidden-col" : "rmcol-btn"

    sb << "<div class='rmcol-toggle-bar'>"
    sb << "<span id='rm-hide-no-special' class='rmcheck-action-btn' onclick='rmToggleSpecialFilter()'>Hide rows with no Special Actions</span>"
    sb << "<span style='display:inline-block;width:0.35in;'></span>"
    sb << "<b>Hide columns:</b>&nbsp;"
    sb << "<span id='rmtoggle-rmcol-ruleid'  class='${btnColRuleId}'  data-pref-key='hideColRuleId'  onclick=\"toggleRmCol('rmcol-ruleid',this)\">Rule ID</span>"
    sb << "<span id='rmtoggle-rmcol-apptype' class='${btnColAppType}' data-pref-key='hideColAppType' onclick=\"toggleRmCol('rmcol-apptype',this)\">App Type</span>"
    SPECIAL_ACTION_KEYS.each { String key ->
        String cls = colClassForKeyword(key)
        String btnCls = hideKeyword[key] ? "rmcol-btn hidden-col" : "rmcol-btn"
        sb << "<span id='rmtoggle-${cls}' class='${btnCls}' data-pref-key='hideCol_${htmlEncode(key)}' onclick=\"toggleRmCol('${cls}',this)\">${htmlEncode(labelForKeyword(key))}</span>"
    }
    sb << "<span id='rmtoggle-rmcol-modes' class='${btnColModes}' data-pref-key='hideColModes' onclick=\"toggleRmCol('rmcol-modes',this)\">Modes</span>"
    sb << "<span id='rmtoggle-rmcol-lastrun' class='${btnColLastRun}' data-pref-key='hideColLastRun' onclick=\"toggleRmCol('rmcol-lastrun',this)\">Last Run</span>"
    sb << "&nbsp;&nbsp;<b>Filter:</b>&nbsp;"
    sb << "<input id='rmname-filter' type='text' class='rmname-filter' placeholder='Filter rule name (substring or * ? wildcards)' oninput='applyRmRowFilters()' style='width:330px;'>"
    sb << "</div>"

    sb << "<div style='font-size:0.85em;color:#555;margin:2px 0 6px;'>"
    sb << "<span style='color:green;font-weight:bold;'>&#10003;</span> = active special action &nbsp; "
    if (isSuppressStaleEnabled()) {
        sb << "<i>(stale-only matches are currently shown as not found — see Controls)</i> &nbsp; "
    } else {
        sb << "<span style='font-weight:bold;white-space:nowrap;'><span style='color:green;'>&#10003;</span><span style='color:#c00;'>&#10003;</span></span> = active, plus stale leftovers &nbsp; "
        sb << "<span style='color:#c00;font-weight:bold;'>&#10003;</span> = found only in stale/leftover entries (deleted actions, clipboard, old settings) &nbsp; "
    }
    sb << "<span style='color:#aaa;'>—</span> = not found &nbsp; "
    sb << "<span style='color:#c00;font-weight:bold;'>?</span> = could not be read"
    sb << "</div>"

    sb << "<table id='rmlog_table' class='rmlogcheck'><thead><tr>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',0)\" class='center rmcol-ruleid'>Rule ID</th>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',1)\" class='sort-asc'>Rule</th>"
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',2)\" class='center rmcol-apptype'>App Type</th>"
    int colIndex = 3
    SPECIAL_ACTION_KEYS.each { String key ->
        String cls = colClassForKeyword(key)
        sb << "<th onclick=\"sortRmLogTable('rmlog_table',${colIndex})\" class='center rmcol-special ${cls}'>${htmlEncode(labelForKeyword(key))}</th>"
        colIndex++
    }
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',${colIndex})\" class='center rmcol-modes'>Modes</th>"
    colIndex++
    sb << "<th onclick=\"sortRmLogTable('rmlog_table',${colIndex})\" class='center rmcol-lastrun'>Last Run</th>"
    sb << "</tr></thead><tbody>"

    rows.each { Map r ->
        String id       = htmlEncode(r.id)
        String nameSort = htmlEncode(r.name?.toString()?.replaceAll(/<[^>]+>/, '') ?: "")
        String nameHtml = renderNameHtml(r.name)
        String appType  = htmlEncode(r.appType ?: "RM")
        String lastRun  = htmlEncode(r.lastRun ?: "")

        Map actions = normalizeKeywordMap(r.specialActions instanceof Map ? (Map) r.specialActions : [:])
        boolean unknown = r.specialUnknown == true
        boolean suppressStale = isSuppressStaleEnabled()
        boolean anySpecial = !unknown && SPECIAL_ACTION_KEYS.any { String key -> keywordCellState(actions[key], suppressStale) in ["live", "livestale"] }
        boolean anyStale   = !unknown && SPECIAL_ACTION_KEYS.any { String key -> keywordCellState(actions[key], suppressStale) == "stale" }
        String specialAny = unknown ? "unknown" : (anySpecial ? "true" : (anyStale ? "stale" : "false"))

        sb << "<tr data-rule-name='${nameSort}' data-special-any='${specialAny}'>"
        sb << "<td class='center rmcol-ruleid' data-sort='${id}'>${id}</td>"
        sb << "<td data-sort='${nameSort}'><a href='/installedapp/configure/${id}' target='_blank'>${nameHtml}</a></td>"
        sb << "<td class='center rmcol-apptype' data-sort='${appType}'>${appType}</td>"
        SPECIAL_ACTION_KEYS.each { String key ->
            String cls = colClassForKeyword(key)
            String cellState = keywordCellState(actions[key], suppressStale)
            String disp
            String sortVal
            if (unknown) {
                disp    = "<span title='configure/json unknown or skipped' style='color:#c00;font-weight:bold;'>?</span>"
                sortVal = "2"
            } else if (cellState == "live") {
                disp    = "<span style='color:green;font-weight:bold;'>&#10003;</span>"
                sortVal = "4"
            } else if (cellState == "livestale") {
                disp    = "<span title='Active special action, but this rule ALSO carries stale/leftover entries for it not referenced by its current actionList (e.g., deleted actions, clipboard copies, old settings)' style='font-weight:bold;white-space:nowrap;'>" +
                          "<span style='color:green;'>&#10003;</span><span style='color:#c00;'>&#10003;</span></span>"
                sortVal = "3"
            } else if (cellState == "stale") {
                disp    = "<span title='Found only in stale/leftover entries not referenced by this rule&#39;s current actionList (e.g., deleted actions, clipboard copies, old settings)' style='color:#c00;font-weight:bold;'>&#10003;</span>"
                sortVal = "1"
            } else {
                disp    = "<span style='color:#aaa;'>—</span>"
                sortVal = "0"
            }
            sb << "<td class='center rmcol-special ${cls}' data-sort='${sortVal}'>${disp}</td>"
        }
        List<String> modesList = normalizeModesList(r.modes)
        String modesJoined = modesList.join(", ")
        String modesDisp = unknown
            ? "<span title='configure/json unknown or skipped' style='color:#c00;font-weight:bold;'>?</span>"
            : (modesList ? htmlEncode(modesJoined) : "<span style='color:#aaa;'>—</span>")
        String modesSort = unknown ? "" : htmlEncode(modesJoined.toLowerCase())
        sb << "<td class='center rmcol-modes' data-sort='${modesSort}'>${modesDisp}</td>"
        sb << "<td class='center rmcol-lastrun' data-sort='${lastRun}'>${lastRun}</td>"
        sb << "</tr>"
    }

    sb << "</tbody></table>"

    List<String> colClassesToHide = []
    if (cfgHideColRuleId)  colClassesToHide << "'rmcol-ruleid'"
    if (cfgHideColAppType) colClassesToHide << "'rmcol-apptype'"
    SPECIAL_ACTION_KEYS.each { String key ->
        if (hideKeyword[key]) colClassesToHide << "'${colClassForKeyword(key)}'"
    }
    if (cfgHideColModes)   colClassesToHide << "'rmcol-modes'"
    if (cfgHideColLastRun) colClassesToHide << "'rmcol-lastrun'"
    if (colClassesToHide) {
        sb << "<script>setTimeout(function(){[${colClassesToHide.join(',')}].forEach(function(cls){document.querySelectorAll('.'+cls).forEach(function(el){el.style.display='none';});});},0);</script>"
    }

    return sb.toString()
}

// ============================================================
// Stats and helper functions
// ============================================================

Map emptyKeywordCounts() {
    Map m = [:]
    SPECIAL_ACTION_KEYS.each { String key -> m[key] = false }
    return m
}

// Normalize a stored keyword map (which round-trips through state JSON) back
// to four-state values: true (live only), "livestale" (live plus stale
// leftovers), "stale" (found only in leftover entries), or false (not found).
Map normalizeKeywordMap(Map raw) {
    Map m = [:]
    SPECIAL_ACTION_KEYS.each { String key ->
        Object v = raw?.get(key)
        String s = v?.toString()
        if (v == true || s == "true")   { m[key] = true }
        else if (s == "livestale")      { m[key] = "livestale" }
        else if (s == "stale")          { m[key] = "stale" }
        else                            { m[key] = false }
    }
    return m
}

// Resolve a normalized keyword value to its display state, honoring the
// "Treat stale-only keyword matches as not found" toggle (which also hides
// the stale half of a mixed live+stale match).
// Returns "live", "livestale", "stale", or "none".
String keywordCellState(Object v, boolean suppressStale) {
    String s = v?.toString()
    if (v == true || s == "true") return "live"
    if (s == "livestale") return suppressStale ? "live" : "livestale"
    if (s == "stale") return suppressStale ? "none" : "stale"
    return "none"
}

boolean isSuppressStaleEnabled() {
    return settings?.suppressStale == true
}

void updateSpecialActionStats(List<Map> rows) {
    Map counts = [:]
    SPECIAL_ACTION_KEYS.each { String key -> counts[key] = 0 }

    Integer ruleCount = 0
    Integer unknownCount = 0
    Integer modeRuleCount = 0
    Integer staleRuleCount = 0

    rows.each { Map r ->
        boolean unknown = r.specialUnknown == true
        if (unknown) {
            unknownCount++
        } else {
            Map actions = normalizeKeywordMap(r.specialActions instanceof Map ? (Map) r.specialActions : [:])
            boolean any = false
            boolean anyStale = false
            SPECIAL_ACTION_KEYS.each { String key ->
                String s = actions[key]?.toString()
                boolean live      = (actions[key] == true || s == "livestale")
                boolean hasStale  = (s == "stale" || s == "livestale")
                if (live) {
                    counts[key] = (counts[key] ?: 0) + 1
                    any = true
                }
                if (hasStale) anyStale = true
            }
            if (any) ruleCount++
            if (anyStale) staleRuleCount++
            if (normalizeModesList(r.modes)) modeRuleCount++
        }
    }

    state.specialActionRuleCount    = ruleCount
    state.specialActionUnknownCount = unknownCount
    state.specialActionCountsJson   = groovy.json.JsonOutput.toJson(counts)
    state.modeRuleCount             = modeRuleCount
    state.staleMatchRuleCount       = staleRuleCount
}

String labelForKeyword(String key) {
    return (SPECIAL_ACTION_LABELS[key] ?: key)?.toString() ?: ""
}

String colClassForKeyword(String key) {
    return "rmcol-sa-" + (key ?: "").replaceAll(/[^A-Za-z0-9_-]/, "_")
}

@CompileStatic
String formatScanDuration(Long elapsedMs) {
    Long safeMs = elapsedMs ?: 0L
    if (safeMs < 0L) safeMs = 0L
    Long totalSeconds = Math.round(safeMs / 1000.0D) as Long
    Long minutes      = Math.floor(totalSeconds / 60.0D) as Long
    Long seconds      = totalSeconds % 60L
    return String.format("%02d:%02d", minutes, seconds)
}

@CompileStatic
String htmlEncode(Object value) {
    if (value == null) return ""
    return value.toString()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&#39;")
}

@CompileStatic
String renderNameHtml(Object value) {
    if (value == null) return ""
    String encoded = htmlEncode(value)
    return encoded.replaceAll(
        /&lt;span style=(?:&#39;|&quot;)color:([a-zA-Z#0-9]+)(?:&#39;|&quot;)&gt;(.*?)&lt;\/span&gt;/,
        "<span style='color:\$1'>\$2</span>"
    )
}
