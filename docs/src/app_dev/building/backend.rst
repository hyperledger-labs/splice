Building and Testing App Backends
=================================

Test Driven Development
-----------------------

- Write integration tests via the APIs also used by your frontend. If
  possible (due to same/compatible langauage), share client library with frontend.
- Run most tests against locally started CN network. Keep networking
  running through all tests and isolate tests by allocating different
  users/parties.
- Run selected integration tests against CC DevNet
- Write robust code that tolerates network interruptions and process crashes, and test it using [toxiproxy](https://github.com/Shopify/toxiproxy) or like tools

.. todo::

    Get more experience wrt to testing against other services

    * MainNet vs DevNet
    * provider-provided test calls vs mocking vs provider distributing apps to be setup locally
    * How to test integration with off-ledger services?
    * How to support third-parties with testing their service dependencies?

Auth & IAM Integration
----------------------

.. todo::

   Fill this in as part of the IAM/Auth design topic

   * How to manage participant users and parties and IAM?
   * For direct users of your app
   * For third-party apps that build upon your app

Codegens
--------

* Run codegen(s) of your choice against all APIs you depend on
  non-incrementally (matters for reexports and interface instances)

Ledger Views
------------

* Apps operating on small data (splitwise) can read directly from ledger API, anything larger cannot
* Ingest all data required from a ledger into a ledger view store
* Read through that store rather than querying the ledger API directly
* Direct ledger API access can still be used for pointwise (by
  contract id/event-id/transaction-id) queries
* Ensure that a single store query returns data for a consistent offset
* If needed, store can support querying at a given offset allowing
  combination of multiple queries to a query at a consistent offset
* During development that store can be an in-memory store, for
  production apps back it by a database

Defining Read-Access APIs
-------------------------

* Define APIs in a way that allows consumers to codegen clients
  in their favorite language (openAPI/graphQL/gRPC).
* Authorize via cross-participant access tokens.

Failure Handling for Ledger API Calls
-------------------------------------

* Assume all RPCs (e.g., ledger API calls) can fail and retry on transient failures
* Only retry up to a maximum and retry with exponential backoff
* Rely on command dedup for retries
* Report errors with unique error-ids and include documention with your app mapping
  error-ids to likely causes and resolutions

Integration with Off-Ledger Services
------------------------------------

.. todo::

   Figure out what exactly we want to recommend there

Multi Domain
------------

.. todo::

   Expand as we make progress on multi-domain design
