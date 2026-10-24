# Contributing to Shoal

Thanks for your interest in Shoal. This document covers how changes get in.

## Licence of contributions

Unless you explicitly state otherwise, any contribution intentionally submitted for inclusion in
the work by you, as defined in the Apache-2.0 license, shall be dual licensed under MIT OR
Apache-2.0, without any additional terms or conditions.

## Workflow

Shoal uses trunk-based development:

- `main` is always releasable. Nobody pushes to it directly.
- Work on short-lived branches and open a pull request against `main` early.
- Keep pull requests small and focused; split large changes behind feature flags.
- Rebase on `main` rather than merging `main` into your branch.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add rarest-first piece selection
fix(store): release eviction lock on error
docs: describe recycle bin retention
chore: bump iroh to 0.x
```

Common types: `feat`, `fix`, `docs`, `chore`, `refactor`, `test`, `perf`, `ci`.
Mark breaking changes with `!` (for example `feat(proto)!:`) and a `BREAKING CHANGE:` footer.

## CI gates

A pull request can merge only when CI is green. Run the same checks locally first:

```sh
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
```

## Reporting security issues

Please do not open public issues for security vulnerabilities. Contact the maintainers
privately instead.
