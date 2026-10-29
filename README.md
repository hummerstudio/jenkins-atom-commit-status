# jenkins-atom-commit-status

Commit status — port of **github / gitlab-plugin** (198,332) as an atom.

One atom for GitHub, GitLab and Gitea commit statuses: the three APIs differ only in URL and body shape.
`UNSTABLE` maps to `failure` on GitHub and `failed` on GitLab.

## Build and smoke test

```bash
./build.sh
./tools/smoke.sh
```
