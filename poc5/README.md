# PoC 5

Objective: build MVP models without decentralization.

Required changes to PoC2

- DONE: remove decentralized setup
- DONE: move all SVC actions to SvcRules, which derives from Main.CC
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



## Coin burn tracking

The idea is to implement the burn oracle using on-ledger state, as this
1. removes the need for keeping extra state in the automation
2. enables on-ledger proofs that a certain burnedcredit has been incorporated
3. simplifies the coordination for doing this computation in a decentralized fashion

* burn
  -> burnoracleinput
  -> burnedcredit
       * claim issuance as validator (short timewindow, incentivizes availability)
          -> update validator coin acc
          -> burned user credit
               * claim issuance as user (longer timewindow starting after validator claim window)
                   -> updated user coin acc
