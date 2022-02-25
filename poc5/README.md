# PoC 5

Objective: build MVP models without decentralization.

Required changes to PoC2

- DONE: remove decentralized setup
- DONE: move all SVC actions to IssuanceRules, which derives from Main.CC
- DONE: change credit burn to coin burn
- DONE implement contention-avoiding, state-update-efficient coin collection
- DONE implement coin transfer with fees
- DONE switch to configurable issuance interval, and staggered issuance, claim sequence
- implement proper validator sign-up by implementing a OpenBusiness.Domain app on top of CC
- DONE implement proper user sign-up by switching to Coin2 instead of UserAccount, CoinAccount, Coin
- reintroduce price as CoinRewardRatio and issue BurnReceipts as ValidatorMiningReward and AppMiningReward contracts
- drive all time related activities off an IssuanceClock with a 5 min tick rate,
  and a 1min tock delay for removing a clock contract


## Contract and fee structure

Desiderata:
- low friction user onboarding
- charge value-proportional fees for value-added actions (coin splitting, transfer and locking)
- charge cost-covering fees for administrative actions (holding and merging coins)
- fees are cost-competitive with fees in credit card, blockchain, and cash-clearing networks
- reasonably active users do not pay any fees for initating transfers
- app operator's pay lower transfer fees when there is less overall app activity on the network
- very active users are incentivized to run their own participant node
- users are disincentivized to hold many coin contracts

Proposed solution:
- every owner of a coin is a coin user -- no extra contract required to become a user
- charge holding fee on every coin using ExpiringQuantity
- rebate that holding fee from the transfer fees generated
- charge a fixed locking fee on lock, unlock is free
- charge for both coin creation and coin updates, incentivizing users to keep their coins merged together
- allow a party to claim validator rights for a specific user; and have the SVC check these rights
- create receipts for burns that allow validators and app operators to claim a rebate on the fees attributed to them


### Why a fixed fee for locking

- unlock-transfer-lock should be an operation that does not destroy any prepaid value; a fixed fee does not require extra state


## On getTime

TODO: once the models are complete see whether we can manage time-bound fees based on clock contracts that advance
at the rate of the issuance cycle.


## Onboarding workflows

- any that owns a coin is a user
- a party can request to be recognized as a user's validator, the SVC checks the mapping, and grants the right
- a user's validator has the right to claim a user's burn receipts

- CC domain connectivity is handled as a CC app in a prepaid model
  - anybody can request a participant to be recognized against an initial prepaid cost (paid using locked coin)
  - the request is accepted if the domain has capacity
  - a contract is created stating the participant node's balance on the domain
  - the domain operator regularly bills that contract
  - the domain operator guarantees to give at least XX hours of heads-up before expiring that contract if the bill is not paid

Next steps:
- DONE: replace validator column in CoinAccount with explicit ValidatorForUser contracts
- DONE: remove public party and do all actions with readAs 'svc'
- switch to coin-only model
- streamline test scripts wrt onboarding
