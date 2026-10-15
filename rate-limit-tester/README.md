# Rate Limit Tester

Lets an SV operator check that their public Scan and Sequencer endpoints enforce the expected
per-IP rate limits.

## Checks

- `enforced-per-ip`: a single client cannot exceed its per-IP request budget.
- `spoofing-not-possible`: a client cannot get around its per-IP budget by manipulating request headers.

## Prerequisites

[k6](https://k6.io) v1 or later (included in the Splice nix dev shell).

## Configure

Edit `config.json` with one entry per component:

| Field              | Meaning                                                                    |
| ------------------ | -------------------------------------------------------------------------- |
| `protocol`         | `http` for Scan (`https://<scan-host>/api/scan/readyz`), `grpc` for the Sequencer (`<sequencer-host>:443`) |
| `endpoint`         | Your own node, in the format shown for `protocol`                          |
| `maxRequestsPerIp` | Expected per-IP limit in the window. If the component runs several replicas, multiply by the replica count |
| `windowSeconds`    | Length of the limit window                                                 |

## Run

```bash
k6 run -e CONFIG=$(pwd)/config.json main.ts
```

Run it from a dedicated host: the checks deliberately use up that host's request budget. Each
component/check pair runs as its own k6 scenario in a dedicated 1 minute slot, one after the other,
so they never share the budget. A run takes 2 minutes per component.

## Results

k6 lists each check as passed (✓) or failed (✗), logs a reason for every failure, and exits with a
non-zero code if any check failed.

## Adding a check

Create `checks/<name>.ts` exporting a `Check` (see `checks/index.ts`) and add it to `CHECKS`.
