# Security policy

## Reporting a vulnerability

Report findings through our bug bounty program on HackerOne:
https://hackerone.com/common_codes. Do not report vulnerabilities via GitHub
issues.

## Safe harbour

Security research conducted in good faith and in accordance with this policy
and the applicable rules of the bug bounty program
(https://hackerone.com/common_codes) will not lead to legal action from us. Stay
within scope, do not access or modify data belonging to other people, and
maintain the confidentiality of vulnerability information and affected data.
Vulnerability details may be disclosed only after we have confirmed that the
vulnerability has been remediated.

## Scope

The mirror is published on a regular basis. **Only the latest published state is in scope** for security research; earlier commits are historical snapshots.

Findings that depend on configuration that this repository does not contain are out of scope.

## Known Issues

Some issues are already known and tracked internally. A report matching one of
them may be closed as a duplicate without detail.

## Dependencies

The backend depends on open source libraries. Vulnerabilities in upstream
dependencies should be reported to the respective upstream project; their use here is in scope only
where this codebase's usage is itself the flaw.
