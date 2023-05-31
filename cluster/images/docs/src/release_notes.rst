.. _release_notes:

Release Notes
=============

2023-06-04
----------

* Frontend updates:

  * Reorganized the information tab in SV UI and included rules governing canton coin (e.g. fees) in SV UI.
  * Added support for displaying details of governance vote requests, casting a vote, and updating a casted vote.

* Bugfixes

  * Fixed the SV onboarding URL in the Helm runbook. It must be ``https://sv.sv-1.svc.TARGET_CLUSTER.network.canton.global/api/v0/sv``
    rather than ``https://sv-1.svc.TARGET_CLUSTER.network.canton.global/api/v0/sv``.
  * Fixed an issue in last week’s release where the public/private SV keys were required in both the K8s secret and in ``sv-values.yaml``.
    Now they only need to be specified through the secret.
  * Fixed how the first round for which a new SV is eligible to receive SV rewards is determined.
    With the fix, SVs start receiving SV rewards starting from the next round that opens after that SV has joined, i.e.,
    an SVs will not receive SV rewards for any of the rounds that have opened before the time it has joined.


2023-05-28
----------

* Deployment updates:

  * ``joinWithKeyOnboarding.keyName`` in ``sv-values.yaml`` has been renamed to ``onboardingName``.
  * ``svSponsorPort`` in ``validator-values.yaml`` has been removed. The port is now included in ``svSponsorAddress``. The default sponsor address has
    been changed to ``https://sv.sv-1.svc.TARGET_CLUSTER.network.canton.global/api/v0/sv``.
  * ``sponsorApiPort`` in ``sv-values.yaml`` has been removed. The port is now included in ``sponsorApiUrl``. The default sponsor address has
    been changed to ``https://sv.sv-1.svc.TARGET_CLUSTER.network.canton.global/api/v0/sv``.
  * The SV private and public key are now stored in k8s secrets.
  * Kubernetes `liveness <https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/#define-a-grpc-liveness-probe>`_ and `readiness <https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/#define-readiness-probes>`_ probes are configured to probe the `GRPC Health Checking Protocol <https://github.com/grpc/grpc/blob/master/doc/health-checking.md>`_ of the participant node.
  * The instructions for generating your SV keys now also work on MacOS.
  * Ingress Helm charts and instructions have been rewritten to be simpler and are now based on Istio instead of Nginx. See :ref:`Configuring the Cluster Ingress <helm-sv-ingress>`

* Add new ``initial-coin-price-vote`` config option to SV app

  * for configuring an SV node to vote for a given coin price during initialization, if no coin price has been voted for by this SV node yet
  * useful for persisting coin price vote preferences across cluster (re-)deployments

* SV UI:

  * Added support for proposing a vote on an action, currently only on removing an SV member.

* Bugfixes

  * DA's internal automated tests are now resilient to coin price changes allowing us to change coin price votes on DA’s SVs so votes from other SVs have an observable effect.
  * Fix SV reward collection for cases in which an SV has been offline for an extended period of time. Previously, the collection of new rewards was blocked for a potentially very long time after restarting.


2023-05-21
----------

* Make the Kubernetes namespace for the SV node configurable in the Helm charts (now defaulting to `sv` in the runbook), see :ref:`deployment using Helm <helm-sv-wallet-ui>`.

* Features introduced to the SV UI:

  * Set your desired coin price (price per round determined using median of all coin price votes by SVs)

  * View currently open mining rounds, along with their coin prices

* Documentation improvements

  * Extend and improve documentation for :ref:`setting up authentication for SV nodes <helm-sv-auth>`
  * Add documentation for :ref:`validator onboarding through SV UI <generate_onboarding_secret>`

* Bugfixes

  * Fix an issue where the validator and SV app were unable to pass
    the well known response from IAMs other than Auth0.


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
