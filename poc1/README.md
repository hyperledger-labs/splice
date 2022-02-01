POC 1 - README
==============

Changes to consider:
- replace all use of `getTime` by an oracle to avoid `getTime` dependency and difficulties;
  i.e., trust SVs to correctly couple issuance to wall-clock time
  - in particular this helps recovering from an attack which made issuance during a particular day impossible.
- remove race condition for claiming burned credit wrt both validator and beneficiary wanting to
  claim it; e.g., by separately issuing burnedcredit for validator and beneficiary
- implement low-contention wallet for coins
- consider how to rate-limit CC operations issued by users; should they pay using CC?
- add explicit time-locked choice for archival of IssuanceEvent after clawback period is over