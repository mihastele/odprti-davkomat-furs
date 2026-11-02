# Removing sensitive data from the repository

Statements, account numbers, names, order ids and amounts must never be committed (AGENTS.md, "Testing"). If one slips in,
**deleting it in a new commit is not enough**: it stays in every older commit and in every clone. Removing it means rewriting
history. That is a destructive, outward-facing step, so it needs the maintainer's explicit approval each time.

## 1. Find out what and where

Put the values you must remove (one per line) in a file **outside** the repository, for example `/tmp/needles.txt`:

```bash
# every commit that contains any of them
for c in $(git rev-list --all); do
  git grep -I -q -F -f /tmp/needles.txt "$c" -- . ':!*.jar' ':!*.png' && echo "$c"
done
git log -S'the-value' --oneline          # the commit that introduced one value
```

Also check: other branches and tags (`git branch -a`, `git tag`), open pull requests, and forks.

## 2. Rewrite

```bash
cp -r . ../backup-before-scrub           # a local safety copy (delete it afterwards: it still holds the data)
pip install --user git-filter-repo
cat > /tmp/scrub.txt <<'LIST'
literal:EOF12345678901==>EOF00000000001
literal:2026-01-14 14:04:31+00:00==>2025-03-03 10:00:00+00:00
LIST
git filter-repo --force --replace-text /tmp/scrub.txt     # to delete a whole file instead: --path path/to/file --invert-paths
```

Replace with harmless values of the same shape so tests keep passing. `git filter-repo` removes the `origin` remote on purpose, so
add it back, then verify before pushing:

```bash
git remote add origin <url>
# 1. no commit still contains the values (rerun the loop from step 1)
# 2. the final tree is unchanged: compare `git rev-parse HEAD^{tree}` with the backup's
# 3. the build and tests pass
```

## 3. Publish the rewritten history

```bash
git push --force-with-lease=<branch>:<old-remote-commit> origin <branch>     # repeat for every rewritten branch and tag
```

`--force-with-lease` with the old commit id refuses to overwrite anything somebody else pushed in the meantime.

## 4. What the push does not fix

- **GitHub still serves the old commits by their id** (direct links, cached views, pull-request refs) until it garbage-collects
  them. Ask GitHub Support to remove cached views and unreachable commits (their guide is "Removing sensitive data from a
  repository"). Close and recreate pull requests that referenced old commits.
- **Clones and forks keep the old history.** Everyone must re-clone (or `git fetch` and `git reset --hard origin/<branch>`);
  never merge an old clone back in, or the data returns.
- **If it was a secret (password, token, key), rewriting is not enough: revoke and replace it.** Treat it as public.
- Remove your own copies: the backup, old clones, `git reflog expire --expire=now --all && git gc --prune=now`.

## Preventing it

Keep real statements outside the repository (the `.gitignore` already covers `private/`, `*.out.xml` and Trading 212's
`from_*_to_*.csv` export names), build fixtures from invented values, and scan before you push (step 1 with the values from your
own statement).
