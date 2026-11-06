# Release checklist

Things to finish before the first public release. The legal documents are written to follow VITURE's developer
terms as summarised below, but they are drafts for you to review: this is not legal advice.

## Fill in the placeholders

Search for `[YOUR` and `[CONTACT` and replace them (`build.ps1 -Release` refuses to build while any remain):

- `LICENSE`: your name (copyright holder)
- `legal/EULA.md`: your name, governing country/state, contact email
- `legal/PRIVACY.md`: your name, contact email

## Ask VITURE (bd@viture.com) before publishing

Send one short enquiry and keep the reply with your release records. Ask them to confirm:

1. A **free, open-source Android TV media player**, running on **third-party hardware** (the Pocket TV Pro), bundled with
   `armeabi-v7a/libglasses.so` (and `libcloud_protocol.so`, `libcarina_vio.so`), is permitted under Section 1.3 of the
   SDK agreement as a "Developed Program".
2. Whether the **EULA may be a standard notice shown on first launch** (this app shows it with Accept / Exit and in
   Settings, About and legal), and whether the wording in `legal/EULA.md` section 2 and 9 satisfies their Section 2.
3. That **naming VITURE descriptively** ("for VITURE glasses", with a trademark disclaimer) in the README and release
   notes is acceptable. The app's own name, package and repository do not include VITURE.
4. That use on non-VITURE host hardware does not conflict with the clause about developing for other platforms.

Do not charge for the app, add advertising, or otherwise commercialise it without VITURE's written agreement
(Section 1 directs business use to bd@viture.com).

## Repository

- [ ] The `SDK/` folder is git-ignored and absent from every commit (`git ls-files | grep -i sdk` returns nothing).
- [ ] No VITURE files are in the repository (libraries, headers, demo, docs, NOTICE).
- [ ] No `.so` files and no `keystore.properties` are committed.
- [ ] README "Legal" section and `legal/` files match the final wording.

## Release build

- [ ] `tools/build_ffmpeg.sh <ndk>` has been run, so the release contains the TrueHD / DTS decoders.
- [ ] `keystore.properties` created with your own signing key (back it up; losing it breaks updates).
- [ ] `.\build.ps1 -Release` succeeds and produces `dist\simple-3dof-tv-core-release.apk`.
- [ ] The APK contains `libffmpegJNI.so` (built from source) and no NewPipe classes
      (`unzip -l` the APK; the *core* edition has no YouTube code).
- [ ] Install it on the Pocket TV, confirm the licence agreement appears on first launch, Accept works, and
      Settings, About and legal opens every document (including VITURE's licence and open-source notice).
- [ ] Head tracking works with the glasses attached; playback works from internal and USB storage.
- [ ] Publish on GitHub Releases with `LICENSE`, `legal/EULA.md`, `legal/PRIVACY.md`, `legal/THIRD_PARTY_NOTICES.md`.

## Known decisions

- **Core edition only** is published as a binary, because NewPipeExtractor (GPL-3.0) cannot be combined with VITURE's
  proprietary libraries in one distributed APK. The YouTube edition is for building from source.
- **TrueHD / DTS** are decoded in the app by FFmpeg (LGPL 2.1+), built from source by `tools/build_ffmpeg.sh` and
  linked statically into `libffmpegJNI.so`. The notices and the LGPL text ship in the app; the repository carries the
  build recipe and the JNI source, which is what the LGPL asks for (a user can rebuild with their own FFmpeg).
- The EULA's governing-law clause and indemnity are standard wording; have them reviewed if you want certainty.
