..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. TODO(DACH-NY/canton-network-node#14303): consider reducing duplication. Some requirements & validator onboarding can be moved to a section before we choose the deployment method


.. _compose_validator:

Docker Compose-Based Deployment of a Validator Node
===================================================

This section describes how to deploy a standalone validator node on a VM or a local machine
using `Docker Compose <https://docs.docker.com/compose/>`_. The deployment consists of the validator node along with associated
wallet and CNS UIs, and onboards the validator node to the target network.

This deployment is useful for:

- Application development, where one needs an ephemeral validator that is easy to deploy.

- Production validators, with the following caveats:

  - The default deployment is highly insecure. Authentication should be enabled as described in :ref:`the authentication section <compose_validator_auth>`.

  - There is no support for ingress from outside your machine, nor is there support for TLS.
    The deployment should be kept local to your machine only and not exposed externally.

  - Reliability & scalability: docker-compose will restart containers that crash, and the deployment supports backup&restore as detailed below, but a
    docker-compose deployment is inherently more limited than a cloud-based Kubernetes one.

  - Monitoring: The deployment, as opposed to a Kubernetes-based one, does not include monitoring.

  - For production settings, you should aim to keep your validator up and running constantly,
    in order to avoid losing out on rewards, and avoid issues with catching up on ledger state
    after significant downtime.

.. _validator_compose_prerequisites:

Requirements
++++++++++++

1) A linux/MacOS machine with the following:

   a. `docker compose <https://docs.docker.com/compose/install/>`__ - at least version 2.26.0 or newer
   b. `curl <https://curl.se/>`__
   c. `jq <https://jqlang.org/>`__

   Note that both AMD64 and ARM64 architectures are supported.

To validate that the dependencies are set up correctly, run the
following commands. All commands should succeed and print out the
version. Note that the exact versions you see may be different from
the example here. As long as you have docker-compose 2.26.0 or newer you should be fine.

.. code-block:: bash

   > docker compose version
   Docker Compose version 2.32.1
   > curl --version
   curl 8.11.0 (x86_64-pc-linux-gnu) libcurl/8.11.0 OpenSSL/3.3.2 zlib/1.3.1 brotli/1.1.0 zstd/1.5.6 libidn2/2.3.7 libpsl/0.21.5 libssh2/1.11.1 nghttp2/1.64.0
   Release-Date: 2024-11-06
   Protocols: dict file ftp ftps gopher gophers http https imap imaps ipfs ipns mqtt pop3 pop3s rtsp scp sftp smb smbs smtp smtps telnet tftp
   Features: alt-svc AsynchDNS brotli GSS-API HSTS HTTP2 HTTPS-proxy IDN IPv6 Kerberos Largefile libz NTLM PSL SPNEGO SSL threadsafe TLS-SRP UnixSockets zstd
   > jq --version
   jq-1.7.1

2) Your machine should either be connected to a VPN that is whitelisted on the network
   (contact your sponsor SV to obtain access), or have a static egress IP address.
   In the latter case, please provide that IP address to your sponsor SV to
   add it to the firewall rules.

3) Please download the release artifacts containing the docker-compose files, from here: |bundle_download_link|, and extract the bundle:

.. parsed-literal::

  tar xzvf |version|\_splice-node.tar.gz

.. include:: ../common/backup_suggestion.rst

.. include:: required_network_parameters.rst

Additional parameters describing your own setup as opposed to the connection to the network are described below.

Deployment
++++++++++

1) Change to the `docker-compose` directory inside the extracted bundle:

.. code-block:: bash

   cd splice-node/docker-compose/validator

2) Export the current version to an environment variable: |image_tag_set|

3) Run the following command to start the validator node, and wait for it to become ready (could take a few minutes):

  .. code-block:: bash

    ./start.sh -s "<SPONSOR_SV_URL>" -o "<ONBOARDING_SECRET>" -p "<party_hint>" -m "<MIGRATION_ID>" -w

  Where:

  ``<party_hint>`` will be used as the prefix of the Party ID of your validator's administrator.
     This must be of format `<organization>-<function>-<enumerator>`, e.g. `myCompany-myWallet-1`.

Note that the validator may be stopped with the command ``./stop.sh`` and restarted again with the same ``start.sh``
command as above. Its data will be retained between invocations. In subseqent invocations, the secret itself may be
left empty, but the ``-o`` is still mandatory, so a ``-o ""`` argument should be provided.

Logging into the wallet UI
++++++++++++++++++++++++++

.. note::

   Docker Compose-based validator deployments use ``.localhost`` subdomains for addressing, such as ``wallet.localhost``.
   ``.localhost`` URLs reportedly do not work on some browsers.
   If you encounter issues please try using a different browser such as Firefox or Chrome.
   If you're encountering issues with reaching APIs from a custom program or script,
   you may need to set the ``HOST`` header on HTTP requests explicitly to the target ``.localhost`` address.

The wallet UI is accessible at http://wallet.localhost in your browser. The validator administrator's
username is `administrator`. Insert that name into the username field and click `Log in`, and
you should see the wallet of the administrator of your wallet.

You can also logout of the administrator account and login as any other username. The first time a
user logs in, they will be prompted with a message asking them to confirm whether they wish to be
onboarded to the validator node.

.. todo:: link to section that explains what this onbarding means


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

Once you have set up your OAuth provider,
you need to configure it by seting the following environment variables in the ``.env`` file:

============================= ===========================================================================
Name                          Value
----------------------------- ---------------------------------------------------------------------------
AUTH_URL                      The URL of your OIDC provider for obtaining the ``openid-configuration`` and ``jwks.json``.
AUTH_JWKS_URL                 The URL of your OIDC provider for obtaining the ``jwks.json``, will typically be ``${AUTH_URL}/.well-known/jwks.json``.
AUTH_WELLKNOWN_URL            The URL of your OIDC provider for obtaining the ``openid-configuration``, will typically be ``${AUTH_URL}/.well-known/openid-configuration``.
LEDGER_API_AUTH_AUDIENCE      The audience for the participant ledger API. e.g. ``https://ledger_api.example.com``.
LEDGER_API_AUTH_SCOPE         The scope for the participant ledger API. Optional
VALIDATOR_AUTH_AUDIENCE       The audience for the validator backend API. e.g. ``https://validator.example.com``.
VALIDATOR_AUTH_CLIENT_ID      The client id of the OAuth app for the validator app backend.
VALIDATOR_AUTH_CLIENT_SECRET  The client secret of the OAuth app for the validator app backend.
LEDGER_API_ADMIN_USER         Should match the `sub` field of JWTs issued for the validator app. For some auth providers, this would be formed as ``CLIENT_ID@clients``.
WALLET_ADMIN_USER             The user ID of the user which should login as the wallet administrator. Note that this should be the full user id, e.g., ``auth0|43b68e1e4978b000cefba352``, *not* only the suffix ``43b68e1e4978b000cefba352``.
WALLET_UI_CLIENT_ID           The client id of the OAuth app for the wallet UI.
ANS_UI_CLIENT_ID              The client id of the OAuth app for the CNS UI.
============================= ===========================================================================

If you have already deployed a validator on your machine, you will first need to irrecoverably destroy
it and wipe its data, as that cannot be migrated to an authenticated validator on the same machine.
To do that, first stop the validator with `./stop.sh` and wipe out all its data with
`docker volume rm compose_postgres-splice`. You can now deploy a new validator with the
new configuration. In order to enable auth in the deployment, add the `-a` flag to the `start.sh`
command, as follows:

.. code-block:: bash

    ./start.sh -s "<SPONSOR_SV_URL>" -o "<ONBOARDING_SECRET>" -p "<party_hint>" -m "<MIGRATION_ID>" -w -a

Integration with systemd and other init systems
+++++++++++++++++++++++++++++++++++++++++++++++

If you want to manage the validator through systemd or a similar init
system, create a service that calls the ``start.sh`` script with the
right arguments. However, note that ``start.sh`` invokes ``docker
compose up`` with the ``-d/--detach`` option so the script exits after
the containers are up instead of continuing running.

You need to make sure that your service does not stop docker compose
at that point. To accomplish this with systemd set
``RemainAfterExit=true``. Refer to the
`systemd documentation <https://www.freedesktop.org/software/systemd/man/latest/systemd.service.html>`_
for more details. If you are using another init system, look for similar options to ensure that docker compose continues running after the script exits.

Alternatively, you can edit the script to remove the ``-d`` option so the script continues running.
