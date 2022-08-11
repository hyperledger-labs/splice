# Splitwise PoC

PoC of a [splitwise clone](https://www.splitwise.com/) built on top of CC & CC Wallet.

Splitwise is useful for a few reasons:

1. It introduces new payment flows for CC wallet.
2. It introduces an invite flow which we expect to be required for
   other apps in a very similar form.
3. It makes sense even without a provider. Therefore, we want to
   explore how a non-provider centric deployment would look like but
   also how that differs from providing the same functionality in a
   provider-centric way.

## TODO

- [ ] Introduce an `Install` contract that is used exclusively for
      writes from the UI.
- [ ] Share balances in group. Currently, you can only see your
      balance to another party but not the balance between two members
      of the group. This is an issue in the current model since you
      might have joined later. We could write the initial balances in
      `Group` and add a sequence number to both `Group` as well as the
      `BalanceUpdate`s.
- [ ] Consider how upgrading would work.
- [ ] Explore changes required for provider-centric vs non-provider centric approach
- [ ] Support entering payments in $ with conversion. (maybe non-poc)
- [ ] Build proper apps. (maybe non-poc)
- [ ] Think through install flows in detail.
- [ ] Add netting to validate the model
- [ ] Support people leaving a group
