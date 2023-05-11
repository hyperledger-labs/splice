.. _release_notes:

Release Notes
=============

2023-05-21
----------

* Make the Kubernetes namespace for the SV node configurable in the Helm charts (now defaulting to `sv` in the runbook), see :ref:`deployment using Helm <helm-sv-wallet-ui>`.


2023-05-14
----------

* Introduce a UI for the Super Validator operator, see :ref:`SV Helm-Based Runbook <sv-helm>`.

  This UI currently allows the SV operator to see information about their SV party, and the rest of the SV collective.
  It allows allows the SV operator to onboard a validator by generating a validator onboarding secret
  (see the :ref:`Self-Hosted Validator runbook <self_hosted_validator>` for how that secret is then used by the validator operator).

* Fix a bug where ``cn-node`` sometimes failed to start with a ``ClassNotFoundException``.

2023-05-07
----------

* Add wallet UI for SV user to SV runbook. Instructions exist for
  :ref:`deployment using Helm <helm-sv-wallet-ui>` and :ref:`local
  deployment <local-sv-wallet-ui>`. This allows the SV operator to
  login to their wallet and e.g. observe SV rewards accumulating.

* Various simplifications and extensions of :ref:`SV Helm-based runbook <sv-helm>`:

  * Added :ref:`instructions <helm-sv-auth0>` for setting up Auth0, and creating the corresponding k8s secrets.
  * Consolidate namespaces. Everything other than docs now resides in the sv-1 namespace.
  * Simplify the ingress setup.

2023-04-30
----------

* Helm chart deployment of a node connected to either ``TestNet`` or ``DevNet``. Instructions :ref:`here <sv-helm>`
* Helm chart deployment documentation online :ref:`here <sv-helm>`.

2023-04-23
----------

* Initial Helm chart deployment of a standalone SV node. Instructions :ref:`here <sv-helm>`
