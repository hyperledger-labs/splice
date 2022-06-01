# Upgrading Experiments

The subdirectories to the side of this README contain our experiments with making CC upgradeable with:
1. with a constant number of ledger events
2. without requiring redeployment of CC API clients

So far we have developed two approaches:
1. `approach1/`: defines the API as interfaces with a canonical view and implementations define the templates.
2. `approach2/`: defines the API as data templates, interfaces with abstract choices, and
   interface implementations between the data templates and interfaces. Implementations then exactly define the
   body of the abstract choices.

We prefer Approach 2, as it does not require later API evolutions to depend on the templates of implementations
of previous API versions. Approach 2 also only requires O(#implementations + #interfaces) templates on the ledger,
instead of O(#implementations * #interfaces).

We consider the experiment for Approach 1 complete. We are actively working on Approach 2, and the following
sections describe the missing work.

## Approach 2

Objectives:
- validate the upgrading design on CC by evolving the API from a version that supports constant
  quantities only to adding expiring quantities, and then adding locking support
- provide a tested implementation for the final version on which we could build CC proper
- serve as the basis for sketching language improvements that lead to a good UX for
  evolvable CN applications

Package outline:
- coin-api-v1-v2-v3: a single module containing all three API versions. The reason for the single module is the current lack of retroactive instances.
- coin-impl-v100: a proper implementation of the V3 Coin API whose upgrades and bugfixes can be deployed with constant ledger events
- directoryservice: an implementation of a directory service building on the V3 Coin API
- wallet: an implementation of a wallet building on the V3 Coin API
- combined-tests: utility functions for testing the above service and tests themselves
- paywithcc-tests:


### TODO

- complete V2 API:
  - add code for abstract V2_Coin_Expire choice
- add CI support for approach2 (see approach2/Makefile and /.circle-ci/config.yml)
- define V3 API
- switch to standardized naming scheme
- port from approach1 using V3 API:
  - CC implementation
  - CC tests
  - directoryservice + tests
  - wallet + tests
  - paywithcc-tests
- add multi API version tests with reasonable coverage:
  - V3 API used with V1, V2 and V3 data
  - V1 API used with V1, V2 and V3 data
  - V2 API used with V1, V2 and V3 data
- review security properties of the resulting code
- remove approach1 once its code is no longer used

### General rules for upgradeable code

Here we collect rules as we discover them during our validation of Approach 2

- always import the api module qualified, so that all calls via interface are easily visible in
  an implementation


# Notes

Unstructed notes to process after the experiment is done.

## Simon

- could not use coin-api_2-1 as a package name, and thus used the workaround v2v1 for version 2.1
- what happens on an interface-subscription where the execution of the view code fails? The stream continues, but an error is reported on the contract on which view execution failed.
- review how to reconcile the version number within the daml files and the package-name version numbers like v101v0v0
- ensure is not straightforward to make work for retroactive interfaces
  - the easiest option is to not allow retroactive ensure clauses
  - alternative: recheck retroactive ensure clauses on all operations except archive