Debugging
=========

* How to debug the Daml state of a running application?

  * For small active contract sets (<100 contracts) , you can use the Navigator to inspect the
    state: https://docs.daml.com/tools/navigator/index.html
  * For larger active contract sets (<100k contracts),
    you will want to query the Ledger API directly using the repl of your
    favorite programming language

    * Python: https://github.com/digital-asset/dazl-client
    * TypeScript: https://docs.daml.com/app-dev/bindings-ts/daml-ledger/index.html
    * Scala: use the Canton console built into your validator's participant
      node: https://docs.daml.com/canton/usermanual/console.html

  * For large active contract sets (>100k contracts), use

    * The persistent cache of your app backend, ideally built on a DB with
      good query support, e.g., PostgreSQL

* How to roll-out a bugfix?

    * Redeploy the affected components as explained in :doc:`deployment`
