# In-app updates on a Fire TV — a working note

PhairPlay updates itself from GitHub Releases: Settings → About → **Check for updates**. It works,
but almost nothing about it was obvious, and three of the four bugs on the way there were silent —
they produced no dialog, no exception the user could see, and in one case a log line that actively
lied. This is what a future agent needs to know before touching it.

Code: `util/UpdateChecker.kt` (the network and version half) and `ui/SettingsFragment.kt`
(`checkForUpdate`, `downloadUpdate`, `installUpdate` — the AndroidX half).

The split is not cosmetic. `UpdateChecker` is compiled by `:test-runner` on a plain JVM with no
AndroidX on the classpath, so anything touching `FileProvider`, `Intent` or `Context` has to stay
in the fragment or CI stops compiling.

---

## The flow

1. `GET https://api.github.com/repos/JObersi10/PhairPlay/releases/latest` (15s timeouts).
2. Read `tag_name`, `body` (truncated to 600 chars for the dialog), and the `assets` array.
3. Compare the tag against `BuildConfig.VERSION_NAME`.
4. Pick the asset matching `BuildConfig.FLAVOR`.
5. Download to `cacheDir/update.apk`.
6. Wrap it in a `FileProvider` URI and fire `ACTION_VIEW` at the package installer.

---

## The four things that will bite you

### 1. The APK asset must be chosen by FLAVOR, not by extension

`firetv` and `googletv` are **separate application IDs** (`com.phairplay.firetv` /
`com.phairplay.googletv`). This matters more than it looks:

> Downloading the wrong flavour does **not** fail loudly. Android sees a different package and
> installs it **alongside** the running app instead of updating it — two PhairPlays on the device,
> no error anywhere. (The reverse case, a googletv build on a Fire TV, at least fails honestly with
> `INSTALL_FAILED_OLDER_SDK`, because that flavour is minSdk 29 against the Fire TV's 25.)

The original code took `firstOrNull { name.endsWith(".apk") }`, so which of those happened depended
on the order assets were attached to the release. Match `BuildConfig.FLAVOR` against the asset name.
A lone APK is accepted as "this release ships one build"; several with no match is reported as a
failure rather than guessed at.

**When publishing, keep the flavour in the filename** — `PhairPlay-1.0.0-firetv.apk`. That string
is the entire matching mechanism.

### 2. `${'$'}` is a literal dollar sign, and it cost a whole debugging session

The FileProvider authority was written:

```kotlin
FileProvider.getUriForFile(context, "${'$'}{context.packageName}.updates", target)
```

`${'$'}` is the Kotlin idiom for escaping a dollar so it is *not* interpolated. So the authority
handed to FileProvider was the literal text `${context.packageName}.updates`, it threw
"couldn't find meta-data for provider with authority …", and every update reported a failed
download.

The catch block carried the same escape:

```kotlin
Logger.w("Update download failed — ${'$'}{it.message}")
```

so the diagnostic printed **`Update download failed — ${it.message}`** verbatim. A log line that
reports a failure while withholding the only useful part of it is worse than no log line, because
it looks like you already have the answer. If a diagnostic ever prints a template, suspect this
before anything else — and grep the file for other occurrences, because they arrive in pairs.

The authority is declared in the manifest as `${applicationId}.updates`, which *is* interpolated —
by the manifest merger, which uses the same syntax for something else entirely. Don't let the
matching text fool you into thinking the Kotlin side is fine because the XML side is.

### 3. Nothing asks for the unknown-sources grant on your behalf

`REQUEST_INSTALL_PACKAGES` in the manifest only makes the grant **available to ask for**. It does
not ask, and since Android 8 neither does the package installer.

> An app that does not hold the grant has its install intent **dropped with no dialog and nothing
> in the log**. From the sofa that is indistinguishable from the update button doing nothing.

So check it yourself:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
    !ctx.packageManager.canRequestPackageInstalls()) { … }
```

and send the user to `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` with a `package:` URI so they
land on PhairPlay's own toggle. **Fall back to the intent without the URI** — Fire OS does not
always carry the per-app screen, and an unhandled intent throws `ActivityNotFoundException` at
someone who was only trying to update.

Park the install intent in a field and re-fire it from `onResume` once the grant is present. The
APK is already in the cache; making the user re-run the whole check after granting is the kind of
small indignity that makes a working feature feel broken.

### 4. Version comparison has to be numeric, and suffix-tolerant

The app appends a flavour suffix, so `BuildConfig.VERSION_NAME` is `1.0.0-firetv` while the tag is
`v1.0.0`. Strip a leading `v`, strip anything after the first `-`, then compare the dotted parts
**as integers**. String comparison calls `1.10.0` older than `1.9.0`.

---

## Testing it without shipping a release you don't want

The obvious problem: to test the update path you need an installed build older than the release.

Temporarily set `versionName` to something lower, build, install that on the TV, then
`git checkout app/build.gradle.kts` before committing:

```bash
sed -i '' 's/versionName = "1.0.0"/versionName = "0.9.0"/' app/build.gradle.kts
./gradlew :app:assembleFiretvDebug     # with JAVA_HOME/ANDROID_HOME as in CLAUDE.md
adb -s <tv>:5555 install -r <apk>
git checkout app/build.gradle.kts
```

Two things to get right:

- **Rebuild the OLD build with your fix as well.** The old build is the one performing the update,
  so a fix that only lands in the release APK is not being tested at all. This was nearly missed.
- **Keep `versionCode` unchanged.** Comparison is on `versionName`, and lowering `versionCode` makes
  the subsequent real install a downgrade, which the package installer refuses.

Debug builds are what ship here — there is no release keystore, and `signingConfigs` reads
`KEYSTORE_PASSWORD` and friends from the environment. That is *fortunate* rather than sloppy: the
update installs over an existing app, so the signatures have to match, and every build in the
project's history is debug-signed.

---

## Known rough edge

Returning from the unknown-sources screen puts the settings list back at the top, so the user has
to navigate all the way down to the row again. Reported from the sofa, and fair. The fix is to
remember the focused row before leaving and restore focus in `onResume` — the same place the
install already resumes from. Not yet done.

---

## Checklist for publishing a release

- Tag `vX.Y.Z`. The leading `v` is stripped, so `X.Y.Z` works too.
- Attach **both** APKs, with the flavour in each filename.
- Release notes go in the GitHub release body; the first 600 characters are shown in the dialog, so
  put the headline first and the tables further down.
- Bump `versionName` **and** `versionCode` in `app/build.gradle.kts` before building.
- Verify the built APK before attaching it:
  `aapt2 dump badging <apk> | head -1` should show the version and package you expect.
