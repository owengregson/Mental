# Security Policy

## Supported versions

Only the **latest release** on the [releases page](https://github.com/owengregson/Mental/releases)
receives security fixes. One jar covers the full supported server range
(Paper 1.9.4 → 26.x, plus Folia), so updating is always a drop-in
replacement — there are no maintained older lines to backport to.

| Version | Supported |
| ------- | --------- |
| Latest release | ✅ |
| Anything older | ❌ — update to the latest jar |

## Reporting a vulnerability

Please **do not open a public issue** for security problems.

Use GitHub's private vulnerability reporting instead:
[Report a vulnerability](https://github.com/owengregson/Mental/security/advisories/new)
(Security tab → *Report a vulnerability*). Reports are private to the
maintainer until a fix ships.

Include what you can of:

- the Mental version (`/version Mental`) and server version/platform,
- what an attacker can do (e.g. crash the server, bypass reach validation,
  spoof another player's packets), and
- reproduction steps or a proof-of-concept.

You can expect an acknowledgement within a week. Confirmed vulnerabilities
are fixed in the next release, and the advisory is published once the fix
is available.

## Scope notes

- Mental parses raw inbound packets on the netty thread (the parse rim) —
  malformed-packet handling and anything reachable pre-authentication are
  **in scope** and especially interesting.
- The server's own libraries (netty, snakeyaml, guava, gson provided by
  Paper at runtime) are **out of scope** here — Mental compiles against but
  never bundles them; vulnerabilities in those belong upstream with
  [PaperMC](https://github.com/PaperMC/Paper/security). Dependencies that
  ARE shipped inside `Mental-<version>.jar` (PacketEvents, Adventure,
  bStats) are in scope.
