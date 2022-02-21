# POC 1 - README

Changes to consider:
- replace all use of `getTime` by an oracle to avoid `getTime` dependency and difficulties;
  i.e., trust SVs to correctly couple issuance to wall-clock time
  - in particular this helps recovering from an attack which made issuance during a particular day impossible.
- remove race condition for claiming burned credit wrt both validator and beneficiary wanting to
  claim it; e.g., by separately issuing burnedcredit for validator and beneficiary
- implement low-contention wallet for coins
- consider how to rate-limit CC operations issued by users; should they pay using CC?
- add explicit time-locked choice for archival of IssuanceEvent after clawback period is over



# Architecture


## Rate and Resource Limiting

Goals:
1. The network is open to all users and validators.
2. All users get a fair share of the service.
3. Users can reasonably predict their future quality of service.

Core problem: effectively and efficiently divide the limited capacity of the running system.

High-level Approach:
1. Have the Daml models enforce limits that guarantee that the size of the ACS stored by SVs
   is bounded in cardinality and total contract size for *any* sequence of successful transactions
   submitted by parties hosted on non SV-nodes.
2. Have the Daml model enforce limits on the rate of successful transactions affecting the SVs
   that can be submitted by parties hosted on non SV-nodes, where the rate is measured relative to
   the ledger effective time.
3. Allocate some small base bandwidth to every validator connected to the domain, and share the remaining
   bandwidth on a best-effort basis with all validators, while aggressively throttling the validators
   that submit failed transactions. We expect this policy to work in tandem with Measure 2 to enable
   the Daml models to manage "fair" resource allocation.
4. Have the SV govern the limits chosen in the above measures such that the system always remains
   operational and well-utilized.

The PoC1 contains examples of the Daml design patterns for implementing these measures.
They are explained in the following sections.

### Limiting the ACS Size

Principles:
- every choice that creates more contracts than it consumes must be guarded by a limit.
- all variable-sized fields in contracts are length-limited via corresponding ensure clauses.

Examples:
- CoinRules.Coin_OpenAccount: numCoinAccounts and maxNumCoinAccounts guard the creation of Coin contracts.

Overall approach:
- use a hierarchical bootstrap and limit the number of invites for every level on a contract controlled by the level above
  CC levels: SVC -> SVs
             SVC -> Validators
             Validators -> Users
- use a "stateful" contracts with balances and fetchByKey for accessing them, instead of free-floating assets contracts
  - use multiple sequentially numbered state contracts where concurrent actions by the same user are required
  - when sharing state contracts with a wide set of parties the services provider regularly
    equalizes their available balances, and applications are encouraged to randomly pick a state contract
    (e.g., using a PRNG seeded with getTime and a fetchByKey)
- minimize the size of contracts using appropriate types and by sharing common data via fetchByKey contracts

Note: the approaches based on fetchByKey will continue to work without unique keys, by handling the uniqueness
at the application level where required.

### Limiting the ACS Change Rate

Principle: every choice must be rate limited against ledger effective time.

See 'modifyCoin' for an example.


### Ensuring a Fair Share

Principles:
- use the limits to control growth of the system state such that system utilization always remains below capacity
- set low, but useful limits by default
- allow users to request higher limits
  - grant requests at the SVCs discretion
  - automate the granting of common requests while making sure to keep a maximal limit in place
  - incentivize users to use less resources by charging appropriate per-use or per-time fees

No example yet in the PoC.


### Handling unforeseen situations

Motivation: unforeseen situations (e.g., a hack) require the abilty to suspend system operation.

Principles:
- all choices must be guarded by at least one circuit breaker contract that disables them.
- circuit breaker contracts should be structured such that parts of the system can
  be suspended individually and as whole both at a whole-system level and per-user.

No example exists in the PoC yet.


# On Fees

Principles:
1. Every workflow instantiation should charge an up-front fee covering the cost
   of all workflow steps that do not charge their own fees.
2. Fees should cover the cost of all non-acting parties.
3. Every workflow stakeholder should be able to terminate the workflow in
   a bounded amount of time.
4. Fees should be reasonably predictable by all stakeholders.

Approaches:
1. For fixed step workflows: charge for all steps on initiation.
   Example: requesting a directory entry.
2. For unbounded workflows:
   a. if the main state contract has a balance on itself (e.g. Credit or Coin)
      i. charge an account usage rate and deduct it as part of any action on the balance
      ii. allow counterparties to initiate charging that rate and on a zero balance
          to terminate the workflow
      iii. store the rate itself on the contract to provide predictability

TODO: more to be said once we gain some experience

## CC Fees

User:
- allocating a user account costs a fee payable in coin or credit
- DONE: user is always allocated together with credit account #0
- DONE: credit charge on account open, prepays for account close
  - TODO: consider charging this fee by slashing instead of burning to avoid frequent, low value payments
- user can cancel its account unilaterally if all accounts are zero after applying minimum spend rules
- svc and user can close credit accounts other than #0 whose balance is zero after applying minimum spend rules
  (done on the user account gives a guarantee that svc cannot induce churn)
- svc can close user account with zero balance for more than X time
- max num accounts and rate limits on accounts can be changed using UserAccountRules
- user can rebalance credit accounts for a slash fee proportional to the number of
  changed credit accounts. Slash fee is used to avoid frequent, low value payments.

Credit
- DONE: minimum spend per day tracked per credit account and determined on account open
- DONE: the maximum amount of time that can be prepaid for an account is capped
- DONE: minimum payment amount enforced in CreditRules

# Daml model structure


{- TODO: discuss naming. There are competing priorites:
   1. Make users feel good:
         Wallet holding Coin and Credit

   2. Align with crypt world: perhaps the following works?
         UserAccount controlling CoinAccount and CreditAccount

   2. Align to intuition about software systems:
        UserAccount, CoinWallet, CreditWallet

-}

# UX Improvement Work

## API Usage

- well-defined error identification for assertions to simplify building a UI that presents meaningful error messages

# Reliability Work

- test, test, test

## Robust fee and time calculation

- ensure that the conversions for time-based rates do not lead to overflows or underflows
- check rounding on fee calculation
