# Cleanup plan: fresh public repo

Part A is done (2026-11-07):
- `Sargemck/practical_headphones` is now `Sargemck/practical_headphones-archive`, private (`gh repo view` → `{"name":"practical_headphones-archive","visibility":"PRIVATE"}`).
- The local remote is renamed `origin` → `archive`, and fetch works.
- `main` = `archive/main` = `47fbc73` (v3 signing).

This plan covers part B. Approved by the reviewer on 2026-11-07, with the changes below folded in:
- commit 1's redaction
- no PR numbers in commit bodies
- last-work author dates
- Q1–Q3 answered
- commit 12 also updates the branch and PR wording

## 1. New history

Built locally on an orphan branch `public` with `git commit-tree`. Nothing is rebased or force-pushed. Each milestone commit takes the **exact tree** of its point on `main`'s first-parent history, so every intermediate tree matches the archive byte for byte.

| # | Commit subject (≤ 72 chars) | Tree from | Author date |
|---|---|---|---|
| 1 | S0: project setup, toolchain and phone toolkit | `398ad84`, redacted (below) | last work commit |
| 2 | M0: Bluetooth spike and protocol findings | `0242e36` | last work commit |
| 3 | M1: BMAP protocol module with fake device | `e83f6cb` | last work commit |
| 4 | M2: service, connection and state | `ed36496` | last work commit |
| 5 | M3: settings UI | `4600bab` | last work commit |
| 6 | UI pass: offline view, quick modes, All modes sheet | `7f30c4b` | last work commit |
| 7 | S1: source control spike | `49abfc4` | last work commit |
| 8 | M4: custom mode editor and permission states | `b0e3f37` | last work commit |
| 9 | M5: Quick Settings tile | `697b928` | last work commit |
| 10 | M6: auto-connect | `94b1004` | last work commit |
| 11 | M7: release prep and review fixes | `47fbc73` | last work commit |
| 12 | Prepare the repository for publishing | §3 changes on top of current `main` | build time |

- **Body:** 2–4 lines per commit, saying what the milestone delivered. No archive PR numbers: they'd be dead links in public.
- **Trailers:** none.
- **Identity:** author and committer `Sargemck <3854431+Sargemck@users.noreply.github.com>`. That's already the only identity in the archive apart from GitHub's merge committer, which goes away.
- **Dates:** author date is the full timestamp of the milestone's last non-merge commit. For S0 and M7 that's the last first-parent commit. M4 uses its last device-run commit (`ce169ca`, 2026-11-04), not the merge-day screenshot-test fix, so the log is in date order. Committer date is the build time.

**Redactions in commits 1–11 (expected):**
- **Commit 1 only.** The S0 tree `398ad84` has the phone serial and details of who manages the phone's screen-lock policy in `docs/FINDINGS.md`. They're redacted the same way `5a804ed` did it, applying only that commit's privacy lines: the serial becomes `<serial redacted>`, and the profile and policy wording becomes generic ("a second user profile", "device policy").
- **All trees, found by the pre-scan on 2026-11-07.** Since M0, FINDINGS has named the headphones by their real paired name, with the last two address bytes. That breaks the "no paired-device names" rule. In every tree the name becomes `<name>` and the bytes become `XX:XX`. `main` gets the same redaction first, so the final tree has it too. The M2 notification screenshot (`docs/images/m2-notification.png`) shows the same name. In every tree that has it, it becomes a grey box, as in the M3 screenshots.

- **Every tree from M0 on, second round (2026-11-07).** CLAUDE.md's hard rule had a last clause about the phone's management setup. It's dropped, so the rule lists serial numbers, full MAC addresses and paired-device names.

**Proof:**
- `git diff --stat <commit> <archive point>` for commits 1–11 shows only `docs/FINDINGS.md`, plus `docs/images/m2-notification.png` from commit 4 (M2) on. The line diff contains only the redactions above.
- `git diff --stat main public` shows only the §3 renames and the listed edits. The output goes into the result section.

## 2. PII scan (before the first push)

- **Where the patterns live.** Collected into a local file in the session scratchpad, never committed:
  - the phone's adb serial
  - every full MAC from the raw S1 and M4 logs (headphones, phone, PCs, other paired devices), in colon form and in the logs' space-separated hex form
  - the paired-device names from the same logs: PCs, handheld, other phones, the headphones' real name, watch name
  - Sargemck's personal email
- **What gets checked, in every commit of `public`:**
  1. `git grep -i -f patterns <commit>` on every tree. Expect 0 hits.
  2. Any full MAC shaped string, `([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}`, in every tree. Each hit is listed and must be a redacted or made-up value (e.g. `02:00:00:00:00:0x`, `XX:XX:…`).
  3. Emails: `git log --format='%ae %ce'`, plus every `@` address in every tree and message. Only the GitHub noreply address, and third-party addresses in vendored files like the Gradle wrapper licence.
  4. Files: no `keystore.properties`, `*.jks` or `*.keystore` in any tree (`git log --name-only`).
  5. Device management: grep for management, policy and employer-related terms. Every hit is listed for Sargemck to judge.
  6. Images: the 13 PNGs in `docs/images` are device screenshots, so I look at each by eye for status bars, names and notifications. The goldens and fastlane screenshots use demo data.
- **If something turns up.** If a hit is in an intermediate tree only, that tree is redacted when its commit is built, and the diff proof notes it. Nothing is pushed until the scan is clean. The result section lists every check with its hit count.

## 3. Layout (commit 12)

- `docs/FINDINGS.md` stays.
- `PLAN.md` → `docs/process/PLAN.md`.
- `docs/plans/*` → `docs/process/plans/*`, including this file, with every file name in lower case (`M0.md` → `m0.md`, `S1.md` → `s1.md`, …).
- `docs/plans/hands-on.md` → `docs/process/hands-on.md`.
- **CLAUDE.md:** → `.claude/CLAUDE.md`. Claude Code's memory docs say: "A project CLAUDE.md can be stored in either `./CLAUDE.md` or `./.claude/CLAUDE.md`" (code.claude.com/docs/en/memory).
- **References to the moved files** are updated to their new paths: CLAUDE.md's "Work from `PLAN.md`" and `docs/plans/<milestone>.md`, PLAN.md's own, FINDINGS, M3 and S1.
- **The debug spike stays.** Release builds don't include it.
- **PLAN.md and this plan.** Where they still describe milestone branches and PRs, they're updated to CLAUDE.md's work-on-`main` rule.
- **`.claude/settings.json`.** Today it only denies `git push --force` / `-f`. It does **not** deny `Read(./keystore.properties)` and has no attribution setting. Commit 12 adds:
  - `"deny": [..., "Read(./keystore.properties)"]`
  - `"attribution": { "commit": "", "pr": "", "sessionUrl": false }`

## 4. Front page

- **README:**
  - one-paragraph pitch
  - screenshots from `fastlane/.../phoneScreenshots`
  - features
  - install: GitHub Releases now, F-Droid later
  - requirements: Android 12+, auto-connect Android 16+
  - privacy
  - not affiliated with Bose
  - credits (bosectl)
- **Repo description** (draft): "Android app for Bose QC Ultra Headphones (2nd Gen): modes, noise control, EQ and a Quick Settings tile, straight over Bluetooth."
- **Topics** (draft): `android`, `kotlin`, `jetpack-compose`, `material-you`, `bluetooth`, `headphones`, `bose`, `qc-ultra`.
- **URL references.** The only one is PRIVACY.md's contact link. The new repo has the same name, so it stays valid. Check: `git grep -i archive` and `git grep practical_headphones-archive` both 0 hits. The About screen and NOTICE link only to bosectl and the Apache licence. fastlane has no repo URL.

## 5. Publishing (after approval)

1. Build `public`, run the §2 scan and the §1 proof, and run `./gradlew test lint assembleDebug` on the final tree. Write the results into §6 of this file, in the new history's commit 12.
2. `gh repo create Sargemck/practical_headphones --private --description "…"`, then `gh repo edit --add-topic …`.
3. `git remote add origin https://github.com/Sargemck/practical_headphones.git`, then `git push origin public:main`. It's a new repo, so this is a normal push.
4. Local setup: the old local `main` is renamed `archive-main`, and a new `main` tracks `origin/main`. The archive remote is kept. Work continues on `main`.
5. Stays private until Sargemck says launch: `gh repo edit --visibility public` is his call.

## 6. Result

Second run, 2026-11-07, with the new history rebuilt locally on branch `public`.

**History.** Commit 12 is this commit.

```text
4189e2f S0: project setup, toolchain and phone toolkit        2026-10-27T01:26
5efff57 M0: Bluetooth spike and protocol findings             2026-10-28T11:39
e9ceb96 M1: BMAP protocol module with fake device             2026-10-28T18:39
6167a18 M2: service, connection and state                     2026-10-28T23:56
20e6eef M3: settings UI                                       2026-10-30T00:31
beb22ca UI pass: offline view, quick modes, All modes sheet   2026-11-03T18:05
9fb5c92 S1: source control spike                              2026-11-04T01:49
8f9839b M4: custom mode editor and permission states          2026-11-04T01:44
8360b6a M5: Quick Settings tile                               2026-11-04T01:15
a70ee72 M6: auto-connect                                      2026-11-04T02:07
f71db85 M7: release prep and review fixes                     2026-11-07T14:40
```

- By day, the log is in date order.
- Within 2026-11-04 it isn't: S1, M4 and M5 were finished in parallel that night, so their last work commits interleave (01:49, 01:44, 01:15).
- One identity throughout: `Sargemck <3854431+Sargemck@users.noreply.github.com>` as author and committer. No trailers.

**Proof, commits 1–11 against their archive points** (`git diff --shortstat` and files):

```text
4189e2f vs 398ad84  1 file,  +9 -10  docs/FINDINGS.md
5efff57 vs 0242e36  2 files, +4 -4   CLAUDE.md, docs/FINDINGS.md
e9ceb96 vs e83f6cb  2 files, +4 -4   CLAUDE.md, docs/FINDINGS.md
6167a18 vs ed36496  3 files, +4 -4   CLAUDE.md, docs/FINDINGS.md, docs/images/m2-notification.png
20e6eef vs 4600bab  3 files, +4 -4   (same three)
beb22ca vs 7f30c4b  3 files, +4 -4   (same three)
9fb5c92 vs 49abfc4  3 files, +4 -4   (same three)
8f9839b vs b0e3f37  3 files, +4 -4   (same three)
8360b6a vs 697b928  3 files, +4 -4   (same three)
a70ee72 vs 94b1004  3 files, +4 -4   (same three)
f71db85 vs 47fbc73  3 files, +4 -4   (same three)
```

Every added line in commits 1–11 is one of the §1 redactions (checked by grep: 0 other added lines per commit).

**Proof, final tree against the archive's `main`** (`git diff --stat -M archive-main public`):

```text
 CLAUDE.md => .claude/CLAUDE.md            |  6 +++---
 .claude/settings.json                     |  8 +++++++-
 README.md                                 | 14 ++++++++------
 docs/FINDINGS.md                          |  2 +-
 PLAN.md => docs/process/PLAN.md           |  8 ++++----
 docs/{plans => process}/hands-on.md       |  0
 docs/{ => process}/plans/cleanup.md       |  0
 docs/{plans/M0.md => process/plans/m0.md} |  0
 docs/{plans/M1.md => process/plans/m1.md} |  0
 docs/{plans/M2.md => process/plans/m2.md} |  0
 docs/{plans/M3.md => process/plans/m3.md} |  0
 docs/{ => process}/plans/m4.md            |  0
 docs/{ => process}/plans/m5.md            |  0
 docs/{ => process}/plans/m6.md            |  0
 docs/{ => process}/plans/m7.md            |  0
 docs/{plans/S1.md => process/plans/s1.md} |  0
 16 files changed, 23 insertions(+), 15 deletions(-)
```

**PII scan, all 12 commits (trees and messages):**

| # | Check | Hits |
|---|---|---|
| 1 | Local pattern list (40 patterns): phone serial, 6 full MACs in 5 forms each, the headphones' known address bytes, paired-device names, personal email, wallet/carrier strings seen in screenshots | 0 real. 1 false positive: the v2 smartwatch tile idea in PLAN.md, a product name, not a paired device. |
| 2 | Any MAC-shaped string | 2, both made-up test values in `ParsersTest.kt` |
| 3 | Email addresses other than the noreply one (trees, messages, identities) | 0 |
| 4 | `keystore.properties`, `*.jks`, `*.keystore` in any tree | 0 |
| 5 | Device-management terms | 0 |
| 6 | Images: distinct PNGs ever in `docs/images` | 13, all checked by eye. `m2-notification.png` is the redacted version in every tree that has it. |
| 7 | The removed README section, or its draft marker, in any commit | 0 |

**Tests:** `./gradlew test lint assembleDebug` on the final tree passes, 253 tests, 0 failures.

**New repo:** `Sargemck/practical_headphones`, private. The rebuilt history replaces the first push with one agreed force-push. The force-push deny rule stays for everything after.

## Decisions (review, 2026-11-07)

- **Q1.** Commit 12 stays separate.
- **Q2.** All plan file names lower case.
- **Q3.** No review log. The PR threads stay in the private archive.
- **Second round (Sargemck and reviewer):**
  - The README doesn't describe the project's process.
  - The CLAUDE.md clause is dropped in every commit.
  - M4 is dated by `ce169ca`.
  - The private repo is force-updated once with the rebuilt history. The force-push deny rule stays for everything after.
