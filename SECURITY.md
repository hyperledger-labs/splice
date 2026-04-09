# Security Policy

## Supported Versions

The following versions of Splice are currently supported with security updates:

| Version | Supported          |
| ------- | ------------------ |
| latest  | :white_check_mark: |
| < 0.3.x | :x:                |

## Reporting a Vulnerability

The Splice project takes security vulnerabilities seriously. We appreciate your efforts to responsibly disclose your findings.

**Please do not report security vulnerabilities through public GitHub issues.**

### How to Report

To report a security vulnerability, please use one of the following channels:

1. **GitHub Private Security Advisory** (preferred): Navigate to the [Security tab](../../security/advisories/new) of this repository and click "Report a vulnerability" to open a draft security advisory.

2. **Email**: Send a detailed report to the Hyperledger Security mailing list at `security@hyperledger.org`. Use PGP encryption if possible (key available at [keys.openpgp.org](https://keys.openpgp.org/search?q=security%40hyperledger.org)).

### What to Include

Please include the following information to help us understand and reproduce the issue:

- Type of vulnerability (e.g., ReDoS, injection, privilege escalation, authentication bypass)
- Full path(s) of affected source files
- Step-by-step instructions to reproduce the issue
- Proof-of-concept or exploit code (if possible)
- Impact assessment — what an attacker could achieve
- Any suggested remediation

### Response Timeline

| Milestone | Target Timeframe |
|-----------|------------------|
| Initial acknowledgement | 72 hours |
| Triage and severity assessment | 7 days |
| Remediation plan communicated | 14 days |
| Patch release (critical/high) | 30 days |

### Disclosure Policy

We follow **coordinated disclosure**:

1. You report privately to the project.
2. We acknowledge and triage the report.
3. We develop and test a fix.
4. We release the fix and credit you (unless you prefer to remain anonymous).
5. You may publish details 30 days after the patch is released, or sooner by mutual agreement.

### Scope

In scope for security reports:
- `hyperledger-labs/splice` Canton Coin reference implementation (Daml + Solidity)
- Canton Validator and Super Validator node components
- Splice Amulet smart contracts
- CI/CD pipeline and supply chain security

Out of scope:
- Canton Network infrastructure operated by third parties
- Issues in upstream dependencies (report directly to those projects)
- Social engineering

### Credits

We maintain a [Hall of Fame](SECURITY_HALL_OF_FAME.md) for security researchers who responsibly disclose vulnerabilities (subject to your consent).

### References

- [Hyperledger Security Policy](https://wiki.hyperledger.org/display/SEC/Hyperledger+Security+Policy)
- [OpenSSF Security Baseline](https://baseline.openssf.org/)
- [CVSS v4.0 Scoring](https://www.first.org/cvss/v4-0/)
