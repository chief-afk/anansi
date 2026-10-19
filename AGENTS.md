# Anansi - repo rules

## Mission

Anansi is a free, open-source Android web novel reader that hosts third-party
source ecosystems behind one internal source interface: LNReader v3 and v1-era
JS plugins first, OPDS catalogs and local EPUB/TXT alongside, Legado and Lua as
gated follow-ups. Kotlin + Jetpack Compose, Material 3, dark-first. The audio
reader (platform text-to-speech) is a first-class feature, not an add-on.

## Posture (hard rules)

- MIT, non-commercial forever: no ads, no analytics, no tracking, no crash
  SDKs, no accounts.
- Ships zero sources and zero default repositories. Content sources come from
  user-added repo URLs only. The project never operates a curated source repo.
- No anti-circumvention code of any kind: no CAPTCHA or challenge bypass, no
  paywall logic. Browser-equivalent HTTP only.
- Distributed via GitHub Releases only; never published to app stores.
- Plugin and source code is supported via clean-room adapters only. GPL
  projects (legado and its forks, Kotatsu, DropSauce, Shosetsu kotlin-lib) are
  read-only references: never copy their code into this MIT repo.

## Git and publishing

- Never open PRs against LNReader, Mihon, Kotatsu, or any other upstream.
- Commit titles: under 50 characters, lowercase, no prefixes.

## Compatibility surfaces

- The LNReader backup format (`lnreader_backup_*.json`) is a permanent import
  surface and must keep restoring in every release.
- Anansi's own backup export will be versioned JSON; older exports must keep
  restoring forever.
- LNReader plugin API compatibility (v3 line first, v1-era adapter second) is a
  supported surface: incompatible plugins fail loudly with a visible reason,
  never silently.
