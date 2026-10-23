# Fork documentation

- Keep `README.md` about NuvioTV Reshaped, with the correct platform and repository links. The mobile repository documents the phone/tablet edition; the TV repository documents Android TV.
- Preserve upstream attribution and license information, but use this fork's releases and issue tracker for downloads and support.
- Never copy the upstream README over the fork README as part of a sync, release, cherry-pick or branch merge. Intentional documentation improvements are welcome.
- For upstream merges, fetch the target, then run `bash .github/scripts/merge-nuvio-upstream.sh <target> "<message>"`; it preserves the fork README and workflows. For manual merges, explicitly retain the fork README even if Git reports no conflict.
- Run `bash .github/scripts/check-readme.sh` after documentation changes.
