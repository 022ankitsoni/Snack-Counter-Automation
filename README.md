# Snack-Counter-Automation

Java 17 + Maven Implementation for the Oslo and Bengaluru snack-counter workflow.

## Run
```bash
mvn test
```

## Design assumptions
- A refill is created when a volunteer reports a box empty.
- The system assigns one eligible volunteer: available, not on leave, and currently associated with that office (home office or temporary visit).
- Assignment uses a least-recently-assigned rotation. If nobody is eligible, it remains OPEN and the office chat channel is notified.
- The assigned volunteer accepts with a planned spend cap. Acceptance reserves budget atomically.
- Purchase completion requires a receipt reference and actual spend no higher than the reserved cap. The HR adapter receives employee ID, amount, currency, description, and receipt reference.
- HR approval/payment remains external. A webhook/polling adapter updates status; only PAID marks a refill REIMBURSED.
- The reporting exchange rate is supplied by Finance (NOK per INR) and must be recorded with the report in production.

## Production hardening
Replace in-memory maps and synchronized methods with a relational database and transactions:
- `refill` row with status/version; unique active refill per office + counter/slot if needed.
- `budget_ledger` entries for reservations, releases, and purchases; unique idempotency keys.
- Claim/accept uses conditional update (`WHERE status='ASSIGNED' AND assignee_id=? AND version=?`) so only one transaction wins.
- Outbox table for chat/HR messages, retried idempotently.
- Scheduled expiry worker for overdue assignments.
- Store receipt images in private object storage; persist an access-controlled object key, not a public URL.
- Audit log every assignment, reservation, purchase, and HR status transition.

## Scope
This is a deliberately small core domain model, not a production deployment. It omits UI, authentication/authorization implementation, real chat/HR APIs, stock-level sensors, per-item consumption analytics, and automatic currency-rate sourcing.
