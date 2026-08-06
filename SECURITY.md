# Security Policy

NomadPortal-Android talks to an untrusted mesh (Reticulum/LXMF/NomadNet —
see `porting-notes.md` §1) and will eventually host content of its own on
that mesh. Its threat model treats every remote peer as hostile by default
(`porting-notes.md` §3). Security issues here are taken seriously, including
in this early scaffold stage.

## Reporting a vulnerability

**Do not open a public GitHub issue for a security vulnerability.**

Report it privately via [GitHub Security
Advisories](../../security/advisories/new) for this repository, or email
jamesmanley1992@gmail.com. Include:

- A description of the issue and its impact
- Steps to reproduce (a minimal repro is very helpful)
- Affected version/commit

You should get an acknowledgment within 5 days. This is currently a
single-maintainer open-source project without a formal SLA, but reports are
treated as priority work, not backlog.

## Scope

In scope:

- The Android app itself (this repo)
- Its Chaquopy-embedded Python core, once the RNS/LXMF extraction lands
  (`nomadportal_android_handoff.md`, sequencing step 1)
- The `micron2compose` renderer's handling of untrusted `.mu` content from
  remote nodes
- The self-hosted node's request handling (path traversal, `.allowed`
  enforcement, resource exhaustion — see the handoff doc's "Self-hosted
  node" section for the specific properties that must hold)

Out of scope:

- Vulnerabilities in Reticulum/LXMF themselves — report those upstream at
  [markqvist/Reticulum](https://github.com/markqvist/Reticulum) or
  [markqvist/LXMF](https://github.com/markqvist/LXMF)
- The separate Bluetooth-mesh interface repo (its own handoff doc/repo, not
  this one)

## Process notes for anyone working on this repo

- Every CI run includes CodeQL (`security-extended` query pack) and a
  dependency-review gate on new/changed dependencies (see
  `.github/workflows/`) — a red check on either of those blocks merge, it is
  not advisory.
- Fix findings at the root cause. Don't suppress a CodeQL/lint/dependency
  finding with an inline ignore unless it's a genuine false positive or a
  documented, deliberate accepted-risk decision — see the project's working
  conventions on this.
- Executable/dynamic page hosting is deliberately out of scope for this
  entire project (`nomadportal_android_handoff.md`, "Self-hosted node" —
  deliberate scope decisions"), specifically to remove an RCE-shaped
  primitive from a phone's trust boundary. Don't reintroduce it without
  reopening that decision explicitly.
