Building and Testing Daml Models
================================

Getting Started Template
------------------------

.. todo::

   Provide a project template to build an installable third-party application

Payments and integration with CC Wallet
---------------------------------------

* Never work against CC directly, instead go through Wallet APIs
* Each CC write (transfer, locking) must require confirmation through wallet

Integration with Directory App
------------------------------

* No resolution within Daml, resolve names off-ledger via APIs of directory service
* Design Daml models to work with parties not human readable names + resolution
* If needed, check that resolution is still accurate in transaction by
  passing in cid of entry & fetch it


Integration with Third-Party Services
-------------------------------------

* Work only against interfaces in the public API
* If templates are needed as escape hatch, raise an issue with the
  provider

Testing your Model
------------------

* Write setup Daml scripts that take dependencies on other services as
  arguments
* Use the setup scripts provided by your dependencies to set them up
* Write integration tests, i.e., ``submit`` s for all parts of your model
* Write unit tests (tests without ``submit``) for individual functions where they’re non-trivial

Providing an API for other services to integrate with
-----------------------------------------------------

* Provide a self-contained subset of your API in a separate package as
  a public API. This subset of the API is the one that users interact
  with on the write side, e.g., by exercising choices on it. However,
  note that users still have to audit the rest of the API &
  implementation for vetting purposes and may want to read some of
  those contracts.

App Rights
----------

* App rights defined by choices and tx filter on install contract, whereby
  the tx filter is encoded as a Daml value
* UIs only write through install contract & read through tx filter
  defined by install contract.

Validating your Model
---------------------

* Check that rights of the install contract are sufficient for UIs
* APIs for internal workflows are separated from APIs provided for consumers
* Peak create/archive event throughput matches network capabilities & hardware
* Peak ACS requirement matches hardware
* All transaction are of bounded size
* Validate that the information flows given by Daml’s privacy rules
  are sufficient for a UI to provide information to users and allow
  them to act while not disclosing too much information.
* Contract payload size is linear in number of signatories
* Contracts have <= 100 signatories
* All fields of unbounded size (text, list, maps, …) are bounded via
  an ensure clause. Fields on choice arguments are bounded via
  asserts.

Common patterns
---------------

* propose-and-accept where propose is signed by proposer & shared via explicit disclosure
* credit-based payment to guard open choices. This can act as a form of rate limiting and makes sure the service gets compensated even if people try to absue it.
* contract expiry: round based expiry usually preferred to make sure it syncs up with core CC changes. Keep in mind that there is no monotonicity check though so if that is an issue, you might want to consider time based expiry (similar to coin locking).
* receipt contracts and workflow references
* Batch choices for common operations that accept a list of inputs and
  perform the same operation on each of them
* store reference data as contracts with the provider as the only signatory

  * use explicit disclosure to distribute the reference data
  * provide an open choice if you want to allow using that reference data in
    workflows where the provider is no authorizer; and thus ``fetch``'s
    authorization rules would be too strict
  * make the open choice consume some token or update some state contract to
    rate limit it or charge for its use, if required

styleguide
----------
* naming conventions like <template>_<choice>
* module structure, e.g., batteries included modules


Anti-Patterns
-------------
* Avoid observers since they allow for spam, instead distribute initially via explicit disclosure and then rely on signatories afterwards
* Avoid contention, in particular contract keys contention. Minimal contention is fine and can be solved by retries
* Avoid unexpirable contracts, any active contract should be time-limited
* Avoid free open choice since they allow for spam, instead ensure that there is some cost paid by the actor on-ledger when executing an open choice
* Avoid contracts with contract id references to archived contracts.
  Instead prefer references via keys or make sure contracts get updated in sync.
  If you do required contracts whose payload stores contract id's, then
  ensure to validate the contract id's activeness before creating a contract
  storing it.
* contention on disclosed contract-ids

How to make your model evolvable?

.. todo::

  Expand this section once the upgrading design is complete.
