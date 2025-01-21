..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. TODO(#14303): consider reducing duplication. Some requirements & validator onboarding can be moved to a section before we choose the deployment method


Docker-Compose Based Deployment
===============================

.. _compose_validator:

Docker-Compose Based Deployment of a Validator Node
---------------------------------------------------

This section describes how to deploy a standalone validator node on a local machine
using Docker-Compose. The deployment consists of the validator node along with associated
wallet and CNS UIs, and onboards it to the target network.

This deployment is useful for:

- Application development, where one needs an ephemeral validator that is easy to deploy

- Production validators, with the following caveats:

  - The default deployment is highly insecure. Authentication should be enabled as described in :ref:`the authentication section <compose_validator_auth>`.

  - There is no support for ingress from outside your machine, nor tls. The deployment should be kept local to your machine only and not exposed externally.

  - Reliability & scalability: docker-compose will restart containers that crash, and the deployment supports backup&restore as detailed below, but a
    docker-compose deployment is inherently more limited than a cloud-based Kubernetes one.

  - Monitoring: The deployment, as opposed to a Kubernetes-based one, does not include monitoring.

  - For productions settings, you should aim to keep your validator up and running constantly,
    in order to avoid losing out on rewards, and avoid issues with catching up on ledger state
    after significant downtime.

.. _validator_compose_prerequisites:

Requirements
++++++++++++

1) Access to the following artifactory:

    a. `Canton Network Docker repository <https://digitalasset.jfrog.io/ui/native/canton-network-docker>`_

2) A linux/MacOS machine with the following:

   a. ``docker`` - at least version 2.26.0 for Docker Engine, or an up-to-date version of Docker Desktop.
   b. ``curl``
   c. ``jq``

3) Your machine should either be connected to a VPN that is whitelisted on the network
   (contact your sponsor SV to obtain access), or have a static egress IP address.
   In the latter case, please provide that IP address to your sponsor SV to
   add it to the firewall rules.

4) Please download the release artifacts containing the docker-compose files, from here: |bundle_download_link|, and extract the bundle:

.. parsed-literal::

  tar xzvf |version|\_splice-node.tar.gz

5) Please inquire for the current migration ID of the synchronizer from your sponsor SV.
   The migration ID is 0 for the initial synchronizer deployment and is incremented by 1 for each subsequent migration.

.. code-block:: bash

   export MIGRATION_ID=0

Preparing for Validator Onboarding
++++++++++++++++++++++++++++++++++

In order to become a validator, you need the sponsorship of an SV.
Your SV will provide you with a required secret to authorize yourself towards their SV.

The onboarding secret is a one-time use secret that expires after 24 hours. If you don't join before it expires, you need to request a new secret from your SV sponsor.

.. admonition:: DevNet-only

  On DevNet, you can obtain an onboarding secret automatically by
  calling the following endpoint on any SV (the GSF URL used here for illustration):

  .. parsed-literal::

     curl -X POST |gsf_sv_url|/api/sv/v0/devnet/onboard/validator/prepare

Deployment
++++++++++

1) Change to the `docker-compose`` directory inside the extracted bundle:

.. code-block:: bash

   cd splice-node/docker-compose/validator

2) Export the current version to an environment variable: |image_tag_set|

3) Login to the digitalasset-canton-network-docker.jfrog.io registry:

  .. code-block:: bash

    docker login digitalasset-canton-network-docker.jfrog.io

4) Run the following command to start the validator node, and wait for it to become ready (could take a few minutes):

  .. code-block:: bash

    ./start.sh -s <sponsor_sv_address> -o "<onboarding_secret>" -p <party_hint> -m $MIGRATION_ID -w


  Where:

  a) ``<sponsor_sv_address>`` is the URL of the sv-app app of the SV that is sponsoring you.
     You should have received this from your SV sponsor, typically starts with `https://sv.sv-N`
     for some number N.

     For example, if your sponsor SV is the GSF, this URL would be |gsf_sv_url|

  b) ``<onboarding_secret>``
     is the onboarding secret you obtained above. Please surround
     this with quotes to avoid shell interpretation of special characters.

  c) ``<party_hint>`` will be used as the prefix of the Party ID of your validator's administrator.
     This must be of format `<organization>-<function>-<enumerator>`, e.g. `myCompany-myWallet-1`.

  d) ``$MIGRATION_ID`` is the migration ID of the synchronizer on the target network, as exported above.

Note that the validator may be stopped with the command `./stop.sh` and restarted again with the same `start`
command as above. Its data will be retained between invocations. In subseqent invocations, the secret itself may be
left empty, but the `-o` is still mandatory, so a `-o ""` argument should be provided.

Logging into the wallet UI
++++++++++++++++++++++++++

The wallet UI is accessible at http://wallet.localhost in your browser. The validator administrator's
username is `administrator`. Insert that name into the username field and click `Log in`, and
you should see the wallet of the administrator of your wallet.

You can also logout of the administrator account and login as any other username. The first time a
user logs in, they will be prompted with a message asking them to confirm whether they wish to be
onboarded.

Logging into the CNS UI
+++++++++++++++++++++++

You can open your browser at http://ans.localhost (note that this is currently by default
`ans` and not `cns`), and login using the same administrator user, or any other user that has been onboarded
via the wallet, in order to purchase a CNS entry for that user.


.. _compose_validator_auth:

Configuring Authentication
++++++++++++++++++++++++++

.. warning::

  The default deployment uses highly insecure self-signed tokens. Anyone with access to the wallet UI
  (or the machine and/or its network interface) may log in to your wallet as a user of their choice.
  For any production use, you should configure proper authentication as described in this section.

Please refer to the :ref:`authentication section <helm-validator-auth-requirements>` for instructions on how
to set up an OAuth provider for your validator. The URLs to configure for callbacks are
``http://wallet.localhost`` and ``http://ans.localhost``.

To configure the OAuth provider, you will need to set the following environment variables in the
``.env`` file:

============================= ===========================================================================
Name                          Value
----------------------------- ---------------------------------------------------------------------------
AUTH_URL                      The URL of your OIDC provider for obtaining the ``openid-configuration`` and ``jwks.json``.
AUTH_JWKS_URL                 The URL of your OIDC provider for obtaining the ``jwks.json``, will typically be ``${AUTH_URL}/.well-known/jwks.json``.
AUTH_WELLKNOWN_URL            The URL of your OIDC provider for obtaining the ``openid-configuration``, will typically be ``${AUTH_URL}/.well-known/openid-configuration``.
LEDGER_API_AUTH_AUDIENCE      The audience for the participant ledger API. e.g. ``https://ledger_api.example.com``
VALIDATOR_AUTH_AUDIENCE       The audience for the validator backend API. e.g. ``https://validator.example.com``
VALIDATOR_AUTH_CLIENT_ID      The client id of the OAuth app for the validator app backend.
VALIDATOR_AUTH_CLIENT_SECRET  The client secret of the OAuth app for the validator app backend.
LEDGER_API_ADMIN_USER         Should match the `sub` field of JWTs issued for the validator app. For some auth providers, this would be formed as ``CLIENT_ID@clients``.
WALLET_ADMIN_USER             The user ID of the user which should login as the wallet administrator. Note that this should be the full user id, e.g., ``auth0|43b68e1e4978b000cefba352``, *not* only the suffix ``43b68e1e4978b000cefba352``
WALLET_UI_CLIENT_ID           The client id of the OAuth app for the wallet UI.
ANS_UI_CLIENT_ID              The client id of the OAuth app for the CNS UI.
============================= ===========================================================================

If you have already deployed a validator on your machine, you will first need to irrecoverably destroy
it and wipe its data, as that cannot be migrated to an authenticated validator.
To do that, first stop the validator with `./stop.sh` and wipe out all its data with
`docker volume rm compose_postgres-splice`. You can now deploy a new validator with the
new configuration. In order to enable auth in the deployment, add the `-a` flag to the `start.sh`
command, as follows:

.. code-block:: bash

    ./start.sh -s <sponsor_sv_address> -o <onboarding_secret> -p <party_hint> -m $MIGRATION_ID -w -a
