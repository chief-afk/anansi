# Anansi

A free, open-source, non-commercial Android reader for web novels.

Anansi the Spider is the legendary West African trickster god, known for
spinning intricate webs of words, clever deceptions, and tales; in the old
stories he becomes the keeper of all stories. This app takes the name for the
same reason: one library, many source ecosystems, behind a single internal
source interface.

Supported source ecosystems in v0.1.0:

- LNReader v3 JavaScript plugins
- LNReader v1-era JavaScript plugins
- OPDS 1.x catalogs
- Local EPUB and TXT files (imported into app-private storage)

Incompatible plugins and catalogs fail loudly with a visible reason; they are
never silently skipped or silently disabled.

## Get Anansi

APKs are published on the GitHub Releases page. Anansi requires Android 8.0
or newer. There are no app-store distributions and there is no account or
sign-in; add your own content sources after installing.

## Posture

- MIT licensed. Non-commercial by intent: this project will never monetize,
  and the MIT license puts no restrictions on downstream use.
- No ads, no analytics, no tracking, no crash-reporting SDKs, no accounts.
- Ships zero sources and zero default repositories. Content sources come from
  user-added repository URLs only. Anansi never ships or operates a curated
  repository (permanent).
- Distributed via GitHub Releases only; never published to app stores.
- Browser-equivalent HTTP only. No anti-circumvention code of any kind: no
  CAPTCHA or challenge bypass, no paywall logic.

## Compatibility surfaces (permanent)

- LNReader backup import: `lnreader_backup_*.json` files (the official v1
  shape and the common fork shape) must keep restoring in every release.
- Anansi's own backup export will be versioned JSON; older exports must keep
  restoring forever (export itself is still a backlog item in v0.1.0).
- LNReader plugin API compatibility (v3 line first, v1-era adapter second):
  incompatible plugins fail loudly with a visible reason, never silently.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build requirements, the unit and
lint checks, release signing, and contribution rules.

