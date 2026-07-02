# Activity Upgrades — SDD Progress

Plan: plans/2026-07-01-activity-upgrades.md
Branch: feat/activity-upgrades

## Tasks
- [x] Task 1: Fix ActivityStateTest time source
- [x] Task 2: Push-interval & auto-pause-threshold prefs
- [x] Task 3: Configurable spacing in ActivityPushDecider
- [x] Task 4: Thread push interval through pusher + engine
- [x] Task 5: Moving-time accounting + distance freeze in ActivitySession
- [x] Task 6: Manual pause/resume in engine (+ paused nameplate)
- [x] Task 7: AutoPauseDetector (pure)
- [x] Task 8: Wire auto-pause into engine
- [x] Task 9: ActivityMetric.AVG_PACE (render + human + ripple)
- [x] Task 10: Enable AVG_PACE in default recording set
- [x] Task 11: Pause/resume + interval control plumbing
- [x] Task 12: Push-interval picker on idle home
- [x] Task 13: Pause/Resume button while recording
- [x] Task 14: Embed recent history on Activity home
- [x] Task 15: Activity tab opens on idle home
- [ ] Task 16: Version bump, docs, verification, cleanup

## Execution Log
Task 1: complete (commits 216431b..e57f33c, review clean)
Task 2: complete (commits e57f33c..ebe9df5, review clean)
Task 3: complete (commits ebe9df5..8ed19f1, review clean; claude-local draft corrupted, controller rewrote + fixed detekt)
Task 4: complete (commits 8ed19f1..02b1251, review clean)
  Minor (for final review): forceNextRepushesIdenticalText omits explicit minSpacingMs (style); unchanged_render_within_spacing test relies on default spacing implicitly (robustness).
Task 5: complete (commits 02b1251..60a210c, review clean; plan one-liner blocks expanded multi-line for detekt Wrapping)
Task 6: complete (commits 60a210c..55d8336, review clean after fix; Important fix: preserve pausedAtMs across ingest+tick, added regression test, pinned PAUSE nameplate assertion)
Task 7: complete (commits 55d8336..fb1f65b, review clean after fix; claude-local wrote both NEW files faithfully (only trailing newline missing); controller fixed plan-bug (slow-streak start moved to onSample) + Important dropout hole (reset streak on GPS gap > timeout))
Task 8: complete (commits fb1f65b..f591f0c, review clean)
  Minor (for final review): pausedAtMs_survives_ingest_and_tick relies on default auto-pause threshold not firing (add Float.MAX_VALUE guard); report RED "trivial pass" wording for the negative precedence test.
Task 9: complete (commits f591f0c..00a1bca, review clean; +5th ripple: next()-cycle test now includes AVG_PACE after PRESSURE; RECORDING_METRICS deferred to Task 10)
  Minor (for final review): redundant `private` on avgPaceSecPerKm inside private companion; no direct movingTimeMs=0 blank test.
Task 10: complete (commits 00a1bca..4840e97, review clean SPEC+QUALITY no findings; AVG_PACE at RECORDING_METRICS[1]; implementer found 4 MORE ripple tests beyond brief's 5 (reEnabling_keeps_position, moveDown_persists, start_selects_first_enabled_recording_metric, cycle_metric_forces_immediate_push) all correct under gate; reviewer re-derived all 9 edits; 581 tests green)
Task 11: complete (commits 4840e97..b861b7e, review clean SPEC+QUALITY no findings; launcher pause/resume + service ACTION_PAUSE/RESUME (foreground=false) + controller onPause/onResume/setPushInterval (idle-only, preset-only) + pushIntervalMs/PresetsMs; compile-forced ripple: 2nd FakeLauncher in ActivityMetricsControllerTest got no-op overrides; gated with assembleDebug (androidMain) + unit suite + detekt)
Task 12: complete (commits b861b7e..9132bb2, review clean SPEC+QUALITY no findings; IntervalPicker chip row on idle home (selected=filled Button), onSelectInterval callback wired to setPushInterval; 5 call sites incl 2 fixtures; @Suppress LongParameterList on ActivityScreen (9 params, AppViewModel precedent, targeted not baseline); gated testDebugUnitTest+assembleDebug+detekt)
Task 13: complete (commits 9132bb2..6a5e201, review clean SPEC+QUALITY no findings; Pause/Resume toggle in RunningContent guarded by state.recording (Resume=filled when paused, else Pause=outlined), wired onPause/onResume to controller; 4 files (ScreenCases needed no change); purely additive 13 lines; gates re-run green)
Task 14: complete (commits 6a5e201..9a8d2ca, review clean SPEC+QUALITY no findings; replaced lastSummary card with clickable RecentActivities list (cap 3) wired to activityHistory()/openActivity + historyRevision subscribe; controller fixed 2 plan-snippet bugs: nullable summary?.let guard + Modifier.clickable instead of Card(onClick=) to avoid ExperimentalMaterial3Api; removed 2 now-unused imports; History button retained; 5 files; gates re-run green)
Task 15: complete (commit 9a8d2ca..4240119, controller-verified diff (7-line deletion by haiku); removed glance-auto-start LaunchedEffect + its now-unused import from ActivityTab; activityState still used; assembleDebug+detekt green)

WHOLE-BRANCH REVIEW (fork, full context, origin/main...4240119): found 2 real Important cross-task-seam bugs the per-commit reviews structurally couldn't see. Both fixed via TDD:
  - commit 1d05f3b: snapshot() saved avg pace over wall-clock elapsed, not moving time -> saved/home/history disagreed with live AVG_PACE on any paused activity. Now uses session.movingTimeMs(end) for movingTimeMs + pace divisor (guard moving>0). +regression test saved_summary_uses_moving_time_not_elapsed.
  - commit af54bc2: auto-pause detector was constructed in startLive() (glance) and evaluated regardless of recording -> stationary compass glance pushed PAUSE to watch with no Resume button. startLive() now sets autoPause=null (recording-only invariant). +regression test glance_does_not_auto_pause_when_stationary.
  Reviewer's 5 logged Minors: all deferred as non-blocking (style/cosmetic/test-robustness). Full suite 588 green, detekt clean, assembleDebug SUCCESSFUL at af54bc2. Review verdict: GO pending on-device hardware verification.

INDEPENDENT REVIEW (Fable 5, /code-review high, origin/main...deee1e0): 8 finders / 26 candidates / 6 verifiers -> 10 findings: 6 CONFIRMED bugs, 1 PLAUSIBLE (interval floor defense-in-depth, deferred), 3 cleanup (deferred). All 6 bugs fixed via TDD (RED->GREEN), one commit each:
  - 31ef340: glance->record upgrade reused the glance session -> glance time/distance leaked into the recording. beginRecording() now starts a fresh ActivitySession.
  - d674c70: pace window survived pause -> first PACE after resume spanned the paused gap. resume() clears the window (lastAccepted kept for distance).
  - 811d921: auto-pause detector kept its slow streak across an explicit Resume -> instant re-pause. resume() resets the detector.
  - 07f3e1a: TIME metric rendered wall-clock elapsedMs -> ticked up while paused (README promised freeze). renderTime/humanTime now read movingTimeMs. +SAMPLE_STATE movingTimeMs follow-through.
  - b434ee0: observable push interval: controller pushIntervalMs Long getter -> StateFlow; picker highlight now recomposes on tap. TDD (compile-fail RED) in ActivityControllerTest.
  - 2d5e203: ActivityTab called activityHistory() (full JSON decode) every recomposition incl ~1 Hz during sessions -> idle-only + remember(historyRevision); +SAMPLE_STATE movingTimeMs.
  Fix-subagents 1-2 (sonnet) did fixes 1-4; subagent quota exhausted mid-pass, controller did fixes 5-6 inline. Final triple gate at the full fix tree: 594 tests / 0 failures, detekt clean, assembleDebug SUCCESSFUL.

Task 16 (release wrap-up): COMPLETE except user-gated steps. Done: VERSION 0.31.2->0.32.0; README feature entry; whole-branch review + 2 fixes; independent review + 6 fixes; branch contains latest origin/main; v0.32.0 tag free; PR opened Thu ~03:30 MT (outside no-push window). PENDING before MERGE (merge auto-cuts the signed release): on-device hardware verification per Phase 6 banner. Tracker git rm'd in the wrap-up commit.
