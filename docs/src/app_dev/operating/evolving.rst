Evolving
========

.. TODO(M1-14): improve these docs once we have clarity on how upgrading works

* How to evolve a running application to satisfy new requirements?

  1. Determine which of your application components need to change
  2. Use the support for backwards compatible evolution of your read access
     API technology (gRPC or OpenAPI) and Daml package upgrading
  3. Build and test the change in a staging environment
  4. Deploy to production once you are happy with the change
