# Splitwise Clone

[splitwise clone](https://www.splitwise.com/) built on top of CC & CC Wallet.

This is our guiding example for app dev guidelines.
## Provider-Centric vs Non Provider-Centric

### Provider-centric

- Daml model extented with provider field
- UI hosted by provider
- Provider is implementation provider on all contracts
- Install contract signed by user & provider

### Non-provider centric

- Impl provider = group owner
- Users host UI themselves, UI requires no custom backend, talks
  directly to ledger/JSON API
- Install contract signed by user
