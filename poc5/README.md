# PoC 5

Objective: build MVP models without decentralization.

Required changes to PoC2

- DONE: remove decentralized setup
- move all SVC actions to SvcRules, which derives from Main.CC
- replace BurnOracle by choice param on SvcRules
- change credit burn to coin burn
- design and implement proper validator sign-up
- design and implement proper user sign-up
- implement contention-avoiding, state-update-efficient coin collection
- implement coin transfer with fees


CC(Rules)
- onboards validators and users
- triggers issuance


ValidatorRules

Validator
