## Summary
<!-- What changed and why? Link to relevant issue/ticket if applicable -->

## Components Changed
<!-- Which services/frontends were modified? -->
- [ ] Backend: <!-- e.g., billing-service, auth-service -->
- [ ] Frontend: <!-- e.g., HomePage, admin-web -->
- [ ] Infrastructure: <!-- e.g., deployment scripts, Docker, Nginx -->

## Test Plan
- [ ] Full test suite passing locally in Docker (`mvn verify` — whole surefire+failsafe suite; do NOT add `-Dgroups`, which selects 0 tagged tests and false-greens)
- [ ] New tests added for new functionality
- [ ] Manual verification steps (if applicable): <!-- describe steps -->

## Database Changes
- [ ] No database/entity changes
- [ ] Entity changes present — reviewed for backward compatibility
- [ ] Migration script included (if applicable)

## Breaking Changes
- [ ] No breaking changes
- [ ] API contract changes: <!-- describe -->
- [ ] New environment variables required: <!-- list them -->
- [ ] Schema changes: <!-- describe -->

## Deploy Notes
<!-- Release request for the Mon/Wed/Fri release train (global protocol §13). The release
     board harvests this section — fill it in; "None" is fine, an empty section is a
     blocking review finding. -->
- [ ] No special deploy steps
- [ ] Feature flag flips required: <!-- flag name + ON/OFF + which env -->
- [ ] Deploy-order constraint: <!-- e.g. deploy this backend BEFORE rebuilding teclavya-web -->
- [ ] Manual/one-off steps: <!-- e.g. hand-applied DDL, .env addition on the box, data backfill -->

## Rollback Impact
- [ ] Safe to rollback (no destructive DB changes)
- [ ] Rollback requires manual steps: <!-- describe -->
