Introduction
============


* What is the Canton Network?

  * The **Canton Network (CN)** is a network of multi-party business processes
    operated by different business entities in the form of CN applications.
  * A **CN application** is a set of Canton participant and domain
    nodes and supporting code operated by a single business entity for the
    purpose of providing access to a particular multi-party business process
    to other entities on the Canton network.

* What business processes are well-suited to be provided as a CN applications,
  which ones not?

  * **well-suited:**

    * business processes whose state and state changes must be captured in
      full by all participating business entities for compliance reasons

    * concrete examples:

      - trading, issuing, life-cycling financial assets
      - governance processes for legal entities
      - billing processes (e.g., in health-care)
      - KYC, credit rating
      - trade finance and the tracking of goods in transit


  * **less-suited:**

    * business processes where a single business entity can provide
      the backing DB, UIs, and APIs while satisfying trust assumptions

      - low-latency order-book in an exchange
      - many web 2.0 retail SaaS

    * business processes with very low latency requirements; e.g., HFT

    * business processes with very high throughput requirements; e.g.,
      Amazon's shopping cart management (Amazon DynamoDB sacrifices integrity
      for availability, which is OK for their shopping carts)

    * processes with weak integrity requirements; e.g., recording sensor data
      from IoT devices (it's OK if some measurements get lost or duplicated)

    * processes completely internal to a business entity (e.g., vacation
      tracking) that the business does not expect to *ever* connect to
      an external process


Getting Started
---------------

* What prerequisites do I need to build an application?

  * Install the Daml SDK: https://docs.daml.com/getting-started/installation.html
  * Choose your tech stack for building the app backend (we use Scala and ``sbt`` for :term:`CC`)
  * Choose your tech stack for building the app user UI (we use TypeScript and react for :term:`CC`)

* How to build, operate, and evolve a CN app?

  1. Become clear about the multi-party business process you would like the CN
     app to implement.
  2. Choose the topology of your CN app, see :doc:`topology`.
  3. Work with your Digital Asset partner to file a support ticket requesting
     access to the Canton Network DevNet.
  4. Build your CN app using test-driven development.
  5. Release, deploy, operate your app.
  6. If you offer a third-party API, then also release API definition files.
  7. Evolve your app by publishing new, backwards compatible APIs.

