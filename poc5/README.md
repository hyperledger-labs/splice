# PoC 5

Objective: build MVP models without decentralization.

Required changes to PoC2

- DONE: remove decentralized setup
- DONE: move all SVC actions to SvcRules, which derives from Main.CC
- DONE: change credit burn to coin burn
- design and implement proper validator sign-up
- design and implement proper user sign-up
- implement contention-avoiding, state-update-efficient coin collection
- implement coin transfer with fees
- switch to configurable issuance interval, and staggered issuance, claim sequence


CC(Rules)
- onboards validators and users
- triggers issuance


ValidatorRules

Validator


## Validator and user onboarding

Assumption: domain run by SVC

- any user can invite a new validator for a fee plus initial minimal credit amount
  - this whitelists domain connection requests for SVC domain for that validator
  - invites are time-limited
  - the validator party can then accept the invite once it's participant node is connected
  - the SVC confirms that a validator is a proper participant node
    (this is required to avoid that users sign-up for their own validator on a hosted validator)

- any validator can request a user account for a party on that validator
  - user accounts are specific to validators; coin account key becomes (svc, validator, user, number)
  - validator rules enforce fees and limits on the number of user accounts per validator
  - validators can suspend user accounts if they find the account to be used against the rules (to be specified)
  - validators can close empty user accounts the same way as the SVC can
  - QUESTION: can the SVC deny a user account where the user is not hosted on the validator?
     ANSWER: currently no, but this opens up an attack where a user gets hosted on many validators,
       which increases load on the SVC domain


- validator is a signatory on the useraccount
- every validator also has a user account
- validators onboard users
- validator rules govern whether new users can be onboarded, and how many users there can be per validator
- all users of one validator are hosted on the same CC domain
- participant node operators that act as CC validators are expected to


## Coin burn tracking

The idea is to implement the burn oracle using on-ledger state, as this
1. removes the need for keeping extra state in the automation
2. enables on-ledger proofs that a certain burnedcoin has been incorporated
3. simplifies the coordination for doing this computation in a decentralized fashion

* burn
  -> burnoracleinput
  -> burnedcoin
       * claim issuance as validator (short timewindow, incentivizes availability)
          -> update validator coin acc
          -> burned user coin
               * claim issuance as user (longer timewindow starting after validator claim window)
                   -> updated user coin acc


## Fee structure

- sender burns for validator that enables the sending
  - self-hosted sender only burns a fixed account modification fee
- receiver burns for itself
- transfer of value is when fees are levied
- account modification fee burn is used to incentivize batch deposits
- on lock: transfer locking fee to locker

- holding an account costs a time-based fee, which is represented as startsExpiring - expiresAt
- the fees paid for actions initiated by an account allow moving the start of the expiry up to a week into the future
- lock duration needs to be prepaid based on that time-based fee

- account modifications outside of transfers will burn a small modificaiton fee to cover cost of account tracking


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
- replace validator column in CoinAccount with explicit ValidatorForUser contracts
- remove public party and do all actions with readAs 'svc'
- streamline test scripts wrt onboarding


### Outdated: Validator onboarding

Assumption: there is a sponsor that has a party whose CoinAccount cointains sufficient funds for an invite.

1. sponsor gets validator's participant node coordinates out of band
2. sponsor creates a time-limited invite and an untransferred coin
3. sponsor shares invite + coin + coin rules contract out-of-band with the validator
4. svc domain picks up that invite and uses it to accept the domain join request of validator participant node
5. validator uses its validator party to submit the accept of the invite using off-ledger disclosure, which
   - accepts the coin transfer
   - creates a coin account from the coin
   - creates a validator account which expires at the same time the coin account expires

The validator can refresh the expiry timer by paying for its validator account, thereby proving that it is active.
Validators run a bot that takes care of this.

TODO:
- push quantity onto invite
- have svc check that validator is really a participant node, and each participant node is represented only once


## Outdated: User to validator mapping management

1. validator uses ValidatorAccount + ValidatorRules to create an InviteUser request together with an untransferred coin
2. SVC checks that the user is hosted at the validator, and if yes, accepts the request, which
   - accepts the coin transfer
   - creates a coin account from the coin
3. If the user is not hosted on the validator, then the invite is declined

TODO:
- push quantity into invite