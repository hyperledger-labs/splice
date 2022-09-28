Deployment
==========

* How to host a Daml application?

  * Note that both the app backend and the app UI are a standard web 2.0
    applications. The only thing special about Daml applications is that they
    use transactional writes across many apps by writing all data using the
    users' validator nodes.

  * Deploy all components of the :doc:`../topology` operated by the provider
    on your host machine(s) (we use k8s for :term:`CC`)
  * More concretely, for the default application topology

    * deploy a :ref:`self_hosted_validator`

      * you can host multiple applications on the same validator node provided
        there is no resource contention

    * deploy the app backend and make its app read API accessible to all users
      (VPN for internal applications, internet-accessible for a public app)
    * deploy a web-server hosting the app user UI
    * instruct your validator node to host your app Daml package

.. TODO(M1-04): add instructions on how to set up auth & IAM integration


.. todo::

  Explain how to instruct validator nodes to host app Daml package once
  we've built the app manager component of the validator node, which serves
  the app installation API.


* How to deploy a change to the app backend?

  * Redeploy your app backend, as you do for web application.

* How to deploy a change to the app user UI?

  * Redeploy your app UI, as you do for a web application.

* How to deploy a change to the app read API?

  * Make it backwards compatible, and redeploy app backend and app UI as you
    do for a web application.

* How to deploy a change to the app Daml packages?

  * Make it backwards compatible using `Daml's upgrading support (WIP) <https://docs.google.com/document/d/1aMx5y_yuVK71iZ72guFKqHYk1YhPvxXobZcus3aTNJ4/edit#heading=h.j1o9vy5fqmrz>`_
  * Redeploy using your validator node's app manager


.. todo::

  Expand these sections wrt how to achieve zero-downtime deployments.
