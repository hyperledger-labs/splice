# Splice DAML Contract Patterns

## Package Structure

### Core Packages (under `daml/`)
- `splice-amulet` — core token (Amulet, LockedAmulet, AmuletRules, mining rounds, reward coupons)
- `splice-dso-governance` — DsoRules, voting, SV management, synchronizer config
- `splice-wallet` — WalletAppInstall, TransferOffer, MintingDelegation, TransferPreapproval
- `splice-wallet-payments` — AppPaymentRequest, AcceptedAppPayment, Subscriptions
- `splice-amulet-name-service` — AnsRules, AnsEntry, AnsEntryContext
- `splice-validator-lifecycle` — ValidatorOnboarding, UsedSecret
- `splice-util` — base utility types
- `splice-util-featured-app-proxies` — WalletUserProxy, DelegateProxy
- `splice-util-batched-markers` — BatchedMarkersProxy
- `splice-util-token-standard-wallet` — MergeDelegation
- `splitwell` — Group, GroupRequest, BalanceUpdate, TransferInProgress

### Token Standard (under `token-standard/`)
16 packages defining generic interfaces:
- `splice-api-token-holding-v1` — Holding interface (owner, instrumentId, amount, lock)
- `splice-api-token-transfer-instruction-v1` — TransferInstruction + TransferFactory interfaces
- `splice-api-token-allocation-v1` — Allocation interface
- `splice-api-token-metadata-v1` — Metadata and extra args
- `splice-api-token-burn-mint-v1` — Burn/mint capabilities
- `splice-api-featured-app-v1/v2` — Featured app reward interfaces

### Dependency Order
```
splice-util → splice-amulet → splice-wallet-payments → splice-wallet
                             → splice-amulet-name-service
                             → splice-dso-governance
                                                      → splitwell
```

## Key Templates

### Amulet (core token)
- **Signatories:** `dso`, `owner`
- **Fields:** `dso`, `owner`, `amount: ExpiringAmount`
- **Implements:** `Token.HoldingV1.Holding`
- **Ensure:** `validExpiringAmount`

### AmuletRules (transfer & round management)
- **Signatory:** `dso`
- **Key choices:** `AmuletRules_Transfer`, `AmuletRules_AdvanceOpenMiningRounds`, `AmuletRules_MiningRound_StartIssuing`, `AmuletRules_MiningRound_Close`

### DsoRules (governance)
- **Signatory:** `dso`
- **Key choices:** `RequestVote`, `CastVote`, `ConfirmAction`, `ExecuteConfirmedAction`, `AddSv`, `OffboardSv`, `OnboardValidator`, `GrantFeaturedAppRight`

### Mining Round Lifecycle
```
OpenMiningRound → SummarizingMiningRound → IssuingMiningRound → ClosedMiningRound
```
- Multiple rounds open simultaneously (2+ at a time) for propagation delay tolerance
- Rewards tied to specific round numbers
- ClosedMiningRound serves as proof of closure for reward expiry

## Reward System

### Coupon Types
1. **ValidatorRewardCoupon** — from user fees, earned by validator
2. **AppRewardCoupon** — from usage fees, earned by app provider (featured/unfeatured)
3. **SvRewardCoupon** — from per-round issuance, earned by SV (weighted)
4. **ValidatorFaucetCoupon** / **ValidatorLivenessActivityRecord** — activity tracking
5. **DevelopmentFundCoupon** — DSO-allocated development funding

### Issuance Computation
- Per tranche: `issuancePerCoupon = min(cap, rewardsAvailable / totalCoupons)`
- Unclaimed rewards cascade to next tranche
- Rounding errors favor rewardees (excess goes to unclaimed)

### Featured App Activity Markers
```
FeaturedAppRight → FeaturedAppActivityMarker → AppRewardCoupon
```
- Rights held by providers, markers created on activity, DSO automation batch-converts to coupons
- Weight constraints: `0.0 < weight <= 10000.0`, max 20 beneficiaries (V1)

## Common Template Patterns

### Signatory Patterns
- **DSO-only:** Config contracts (AmuletRules, DsoRules, AnsRules)
- **Symmetric:** Bilateral agreements (LockedAmulet, AcceptedTransferOffer)
- **Single + observer:** Unilateral rights (FeaturedAppRight: signatory dso, observer provider)

### Propose-Accept Workflow
1. Create proposal contract (all parties see it)
2. Accept → creates agreement contract (consuming proposal)
3. Or: Reject / auto-expire
- Examples: TransferOffer, AppPaymentRequest, MintingDelegationProposal, ExternalPartySetupProposal

### Governance Voting
1. `DsoRules_RequestVote` — SV creates vote request
2. `DsoRules_CastVote` — SVs cast votes
3. Threshold reached → `Confirmation` created
4. `DsoRules_ExecuteConfirmedAction` — DSO executes

### Expiry Mechanisms
- **Round-based:** Coupons expire when their round closes (requires `ClosedMiningRound` proof)
- **Time-based:** TransferPreapproval, AnsEntry, AppInstallOffers — checked at choice exercise time
- **Lock-based:** `TimeLock.expiresAt` — owner can self-unlock after deadline

### External Party Support
- `TransferCommand` separates sender signature from execution (24h delay allowed)
- Nonce-based ordering prevents reordering/duplication
- `ExternalPartyAmuletRules` implements TransferFactory & AllocationFactory interfaces

### Non-Consuming vs Consuming
- **Non-consuming:** Reads (`OpenMiningRound_Fetch`, `WalletUserProxy_PublicFetch`), repeated ops (`MintingDelegation_Mint`)
- **Consuming:** State transitions, final operations (archive, expire, withdraw)

## Upgrade Patterns

### Backwards Compatibility (REQUIRED)
- All DAML changes must be backwards-compatible per Canton upgrading docs
- Deprecated choices coexist with V2 replacements (e.g., `Amulet_Expire` → `Amulet_ExpireV2`)
- Optional fields use `Optional` type for forward compatibility
- `ExtFoo` variant constructors are legacy workarounds — add new constructors directly now
- Enums must stay enums (all nullary constructors) — don't accidentally convert to variant

### Package Config
`AmuletConfig.packageConfig` tracks vetted package versions per component — prevents unvetted code from executing.

### Version Bumping
When changing any DAR:
1. Bump version in `daml.yaml` and all recursive dependents
2. `sbt damlBuild; sbt damlDarsLockFileUpdate`
3. Update `DarResources.scala` version references manually
4. `sbt updateDarResources`
5. Update `apps/package.json` DAML package versions
6. `sbt npmInstall; sbt compile`

## Testing

### DAML Script Tests
Located in `-test` packages (e.g., `splice-amulet-test/daml/Splice/Scripts/`)
```daml
testSomething : Script () = do
  (app, dso, (sv1, sv2, sv3, sv4)) <- initMainNet
  [(dsoRulesCid, _)] <- query @DsoRules dso
  submitMulti [sv1] [dso] $ exerciseCmd dsoRulesCid DsoRules_RequestVote with ...
  submitMultiMustFail [sv1] [dso] $ exerciseCmd ...  -- unhappy path
```

### Key Test Files
- `TestAmuletRulesTransfer.daml`, `TestRewardComputation.daml`, `TestGovernance.daml`
- `TestOnboarding.daml`, `TestPatching.daml`, `TestSvRewards.daml`
- `TestExternalParty.daml`, `TestTransferPreapproval.daml`

## Critical Invariants

- Amulet amounts: `validExpiringAmount` ensure clause
- Coupon amounts: `amount > 0.0`
- Activity marker weights: `0.0 < weight <= 10000.0`
- Round numbers: non-negative (`isDefinedRound`)
- TransferCommand nonces: sequential, starting at 0
- Amulet ownership: `owner != dso`

## DAML Changes Require Governance Votes
All DAML changes on a production system require SV majority vote. Discuss with Splice maintainers before proposing changes.
