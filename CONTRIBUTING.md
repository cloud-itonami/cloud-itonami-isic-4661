# Contributing

`cloud-itonami-isic-4661` accepts contributions to the OSS blueprint, capability
bindings, policy tests, documentation and operator model.

## Development
The capability layer lives in `kotoba-lang/*` libraries. This repo holds the
business blueprint and operator contracts.

```bash
clojure -M:test
clojure -M:lint
```

## Rules
- Do not commit real operating, personal or credential data.
- Keep delivery-operation scheduling, shipment records and disclosures behind the Fuel Depot Operations Governor.
- Treat workflows as high-risk: add tests for tank/pipeline-equipment-control gating,
  tank-integrity-clearance and hazmat-storage-safety-clearance gating, record integrity, safety-concern
  escalation and audit logging.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
