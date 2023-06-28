.. _self_hosted_validator:

Self-Hosted Validator
=====================

These pages give a step-by-step guide how to deploy your own validator node to the Canton network.

.. _validator_prerequisites:

Prerequisites
-------------

To locally start a validator node that connects against the DevNet domain, you will need to run

1) a Canton participant node, in order to host:
2) the Daml validator app which also serves as the backend for the wallet

Additionally, you'll also need to enable the GCP DA Canton DevNet VPN. If you can view
this documentation, you already enabled the VPN successfully.

To run a participant node, download Canton enterprise: |canton_download_link|. If you do not have access, please reach out to Digital Asset.

Please now extract Canton:

.. parsed-literal::

   tar xzvf canton-enterprise-|canton_version|.tar.gz


To obtain the Canton Network node binary (required to run the validator app),
please download a release bundle here:
|bundle_download_link|. (Source is available by cloning the
`canton-network-node <https://github.com/DACH-NY/canton-network-node>`_
repository from GitHub.)


Please now extract the downloaded bundle and change into the resulting
root directory. The commands will look similar to these:

.. parsed-literal::

  tar xzvf |version|\_cn-node-0.1.0-SNAPSHOT.tar.gz
  cd cn-node-0.1.0-SNAPSHOT

Install the Daml SDK. We will use the JSON API from that later on. For full details refer to the `documentation <https://docs.daml.com/getting-started/installation.html#install-daml-open-source-sdk>`_. On MacOS and Linux you can use the following command:

.. code-block:: bash

  curl -sSL https://get.daml.com/ | sh

.. _validator_onboarding:

Onboarding Validator
--------------------

To operate a validator node you will need to:

1) Run a participant node that connects to the global domain operated by the supervalidator collective.
2) Run the validator app to register yourself with the supervalidator collective.

The Canton participant is responsible for hosting your Daml apps; i.e. interpreting Daml code, securing your data, and talking to the canton network. It connects to the global canton domain `canton.global`. We provide a bootstrap script to handle these steps for you. You can refer to the `canton tutorial <https://docs.daml.com/canton/tutorials/getting_started.html>`_ for greater detail on what each step does.

..
   We recommend respectively adding the paths to the Canton and Canton Network node binaries from your release
   bundles (`<release-bundle-dir>/canton/bin/canton` and `<release-bundle-dir>/cn-node/bin/cn-node`) to your PATH as `canton` and `cn-node`.
   This is also the convention we will use in this tutorial.

First off, you will need to start the validator participant and connect it to the domain: We assume here that
you extracted Canton next to the Canton network tarball. If you placed it somewhere else, you might need to adjust the path.

.. parsed-literal::

  DOMAIN_URL=http://|cn_cluster|.network.canton.global:5008 ../|canton_subdir|/bin/canton --config examples/validator/validator-participant.conf --bootstrap examples/validator/validator-participant.sc

Next, open a second terminal and navigate to the extracted bundle's root directory.
In order to become a validator, you need the sponsorship of a current supervalidator.
In this feature preview, you can obtain such a sponsorship without interacting with a supervalidator operator.
Use the following shell command to get sponsored by supervalidator 1 and obtain an onboarding secret from it (saved to `validator-onboarding.conf`):

.. parsed-literal::

   curl -X POST https://sv.sv-1.svc.\ |cn_cluster|.network.canton.global/api/v0/sv/devnet/onboard/validator/prepare | xargs -I _ sed 's#PLACEHOLDER#_#' examples/validator/validator-onboarding-nosecret.conf > validator-onboarding.conf

You can now start a console with the CN apps. Use the following command, making sure that the `validator-onboarding.conf` matches the file you created in the previous step.

.. parsed-literal::

  NETWORK_APPS_ADDRESS_PROTOCOL=https NETWORK_APPS_ADDRESS=\ |cn_cluster|.network.canton.global bin/cn-node --config examples/validator/validator.conf --config validator-onboarding.conf --bootstrap examples/validator/validator.sc

The `validator-onboarding.conf` enables the validator to request its onboarding from the sponsoring supervalidator.
Upon verification of the onboarding secret, the sponsoring supervalidator issues a `ValidatorLicense` to the validator party and exposes a `CoinRules` contract to it.

Now, onboard a new user called "alice" via the validator app: ::

  @ val aliceParty = validatorApp.onboardUser("alice")

You are now registered as a validator on the Canton network. You've also configured a user that can transact through a wallet. Congratulations!

Tapping some Canton Coin from the Dev Faucet
--------------------------------------------

To use the wallet, you interact with the wallet setup in the previous section using a specific party user. In our example, ``aliceWallet`` has been
configured to interact with the validator app using the previously created user ``alice``.

Using Alice’s wallet, you can create free coins like so: ::

  @ val coinId = aliceWallet.tap(100.0)

Creating free coins will only be possible in temporary test- and devnets.

Listing your Canton Coins
-------------------------

You can list your balances with the following command: ::

  @ aliceWallet.list()

If you've followed the previous instructions, you should already see one coin.
If not, try calling ``aliceWallet.tap(100.0)`` and then rerunning this command.

Peer-to-peer coin transfers
---------------------------

In order to have someone for Alice to transfer some coin to, please first create a second user for Bob: ::

  @ val bobParty = validatorApp.onboardUser("bob")

You can double check that Bob has no coins yet. The following should return an empty list: ::

  @ bobWallet.list()

Peer-to-peer transfers consist of three steps:

1. First, the sender creates a transfer offer visible to the receiver.
2. Next, the receiver accepts (or rejects) the transfer offer.
3. If the receiver accepts the offer, automation in the sender’s wallet will actually transfer the coin.

In our example, Alice creates the transfer offer for bob::

  @ import com.digitalasset.canton.data.CantonTimestamp
  @ import java.time.Duration
  @ val expiration = CantonTimestamp.now().plus(Duration.ofMinutes(10))
  @ import java.util.UUID
  @ val uuid = UUID.randomUUID().toString()
  @ val transferOffer = aliceWallet.createTransferOffer(bobParty, 10.0, "p2ptransfer", expiration, uuid)

Bob can then see the transfer offer: ::

  @ bobWallet.listTransferOffers()

And accept the request: ::

  @ bobWallet.acceptTransferOffer(transferOffer)

Check Alice and Bob's wallets to see that Alice now has slightly less than 90 coins (due to transfer fees), and Bob has 10: ::

  @ aliceWallet.list()

  @ bobWallet.list()

.. _configuring-wallet-ui:

Configuring the Wallet UI
-------------------------

The Wallet UI is distributed as static files that connect to the
validator backend that we started in the previous section.

Before we can deploy the Wallet UI, we need to configure the URL of the directory service so the wallet can resolve party IDs as well as the URL of CC Scan.
For that, open ``web-uis/wallet/config.js`` and change ``TARGET_CLUSTER`` to |cn_cluster_literal| for both directory and scan:

.. literalinclude:: ../../../../../apps/wallet/frontend/public/config.js
    :start-after: BEGIN_WALLET_CLUSTER_BACKEND_CONFIG
    :end-before: END_WALLET_CLUSTER_BACKEND_CONFIG

.. _configuring-directory-ui:

Configuring the Directory UI
----------------------------

The Canton Network includes a Canton Name Service (CNS), which maps party IDs to human-readable names, much like the DNS does for IP addresses.

First - you will need to upload the directory's dar file to your validator's participant. Go to the terminal in which you are running the validator (the one using "validator.conf"), and type ::

  validatorApp.participantClient.upload_dar_unless_exists("dars/directory-service-0.1.0.dar")

Before you can use the Directory UI, you need to configure the URL of the directory backend similar to
how you configured the Wallet UI earlier. For that,
open ``web-uis/directory/config.js`` and change ``TARGET_CLUSTER`` to |cn_cluster_literal|:

.. literalinclude:: ../../../../../apps/directory/frontend/public/config.js
    :start-after: BEGIN_DIRECTORY_CONFIG
    :end-before: END_DIRECTORY_CONFIG

The Directory UI connects to the validator's participant by using the JSON API server. Open a new terminal and run:

.. parsed-literal::

    DAML_SDK_VERSION=\ |daml_sdk_version| daml json-api --config examples/validator/json-api-app.conf

.. _splitwell-user:

Configuring the Splitwell UI
----------------------------

.. note::
   This section only describes how to connect as an application user to the splitwell instance run by Digital Asset.
   If you are interested in hosting your own instance as an application provider, take a
   look at the :ref:`instructions for deploying splitwell <splitwell-provider>`.

To use splitwell, you first need to connect your participant to the
splitwell domain and upload the DAR.  Go to the terminal in which you
are running the validator (the one using "validator.conf"), and type:

.. parsed-literal::

   @ validatorApp.participantClient.domains.connect("splitwell", "http://|cn_cluster|.network.canton.global:5108")
   @ validatorApp.participantClient.upload_dar_unless_exists("dars/splitwell-0.1.0.dar")

.. note::
   Note, that if you are not connecting to the splitwell instance
   operated by Digital Asset, the Domain URL here will be different. Ask
   the operator for the correct URL.

The splitwell UI connects to the ledger API of your participant
through `Envoy
<https://www.envoyproxy.io/docs/envoy/latest/start/install>`_. Follow
the installation instructions to install it on your machine.

Once you completed the installation, open a new terminal and start it ::

  envoy -c examples/validator/envoy.yaml

As the last step before you can start the frontend, open ``web-uis/splitwell/config.js`` and change ``TARGET_CLUSTER`` to |cn_cluster_literal| like you did earlier for the directory and wallet UIs:

.. literalinclude:: ../../../../../apps/splitwell/frontend/public/config.js
    :start-after: BEGIN_SPLITWELL_CLUSTER_BACKEND_CONFIG
    :end-before: END_SPLITWELL_CLUSTER_BACKEND_CONFIG

If you are not connecting to the splitwell instance operated by
digital asset, also edit the splitwell URL in the ``config.js`` file
to point at the application backend. If you are unsure about the URL
ask the application provider.

.. _hosting-the-uis:

Hosting the UIs
---------------

Lastly, we have to host the frontend files for the wallet UI, the directory UI and the splitwell UI.
We're going to use a standard NGINX Docker container to host all the frontends.
If you don't have Docker installed, please install it now by following the `Docker installation documentation <https://docs.docker.com/get-docker/>`_.
Please make sure to install version 20.10.0 or higher on Linux,which supports ``host.docker.internal``.

If you are using Linux, start another terminal and run: ::

  docker run -d --name nginx_cn_frontends --add-host=host.docker.internal:host-gateway -p 3000:3000 -v $(pwd)/examples/nginx/conf:/etc/nginx/conf.d -v $(pwd)/web-uis:/usr/share/nginx/html nginx

On Windows or Mac you can omit the ``--add-host=host.docker.internal:host-gateway``: ::

  docker run -d --name nginx_cn_frontends -p 3000:3000 -v $(pwd)/examples/nginx/conf:/etc/nginx/conf.d -v $(pwd)/web-uis:/usr/share/nginx/html nginx

The Wallet UI is now accessible at http://wallet.localhost:3000, where you can login as alice and see the coins you tapped earlier in this tutorial.

The Directory UI should be accessible at http://directory.localhost:3000.

You can login there using the same method you used for the wallet (either username if using the default insecure test
authentication, or through Auth0 if configured).

In case the page doesn't load after the login, please make sure that your local firewall rules allow you to proxy the JSON API endpoint running outside of Docker through the NGINX container that serves the UIs.
On Linux, you can try the following command to update the firewall rules if you're using iptables: ::

  sudo iptables -A INPUT -i docker0 -p tcp -m tcp --dport 7575:7575 -j ACCEPT

After this change please restart the docker container.

After logging in for the first time, you will have no registered entries.
Insert a cns entry name of your choice, e.g. "alice.cns" in the "Request new entry" field, and click "Request Entry".
You will be redirected to your wallet to confirm the Canton Coin payment for your directory entry (a subscription-based payment).
Once confirmed, you will be redirected back to the directory UI, and should see your new entry listed.

If you navigate back to your wallet (refresh the page if it was left open from before) - in the top left corner, under the "CC Wallet" title, you should see that your party ID is now being resolved to your new cns entry name.

The Splitwell UI is now accessible at http://splitwell.localhost:3000. You can now log in and start creating groups and split payments with
other users on the Canton Network. Note that you need to onboard your
user through the wallet first before you can use it in splitwell.

Enabling Authentication
-----------------------

By default, the validator backend in your own validator node is configured to require authentication using JWT access tokens. However, all tokens are self-signed with a hardcoded secret.
This setup is not secure, and should only be used when hosting a validator locally on your machine.

For a secure production setup, set up an external OAuth 2.0 provider for authenticating your backends and end-users and change your config files accordingly.
Any OAuth 2.0 OIDC provider should work, but the following section will walk through an example configuration using `Auth0 <https://auth0.com>`_.

Auth0 Example IAM Setup
+++++++++++++++++++++++

To integrate Auth0 as your validator's IAM provider, perform the following:

1. Create an Auth0 tenant for your validator
2. Create an Auth0 API that controls access to the ledger API:

    .. TODO(#2052) use a unique audience for each app

    a. Navigate to Applications > APIs and click "Create API". Set name to ``Daml Ledger API``, set identifier to ``https://ledger_api.example.com``. You can change the audience as you see fit but if you do, you will need to replace all instances of this string below as well.
    b. Under the Permissions tab in the new API, add a permission with scope ``daml_ledger_api``, and a description of your choice.
    c. On the Settings tab, scroll down to "Access Settings" and enable "Allow Offline Access", for automatic token refreshing.

3. Create another API. Set name to ``CN App API``, set identifier to ``https://cn_api.example.com``. This API will be used by the wallet UI to authenticate itself towards the wallet & validator app. You can change the audience as you see fit but if you do, you will need to replace all instances of this string below as well.

4. Create an Auth0 Application for the validator backend:

    a. In Auth0, navigate to Applications -> Applications, and click the "Create Application" button
    b. Name it "Validator app backend", choose "Machine to Machine Applications", and click Create
    c. Choose the ``Daml Ledger API`` API you created in step 2 in the "Authorize Machine to Machine Application" dialog and click Authorize.

5. Create an Auth0 Application for the wallet web UI.

    a. In Auth0, navigate to Applications -> Applications, and click the "Create Application" button
    b. Choose "Single Page Web Applications", call it "Wallet web UI", and click Create
    c. Determine the URL for your validator's wallet UI (if you've been following this runbook guide, it will be ``http://wallet.localhost:3000``)
    d. In the Auth0 application settings, add the wallet URL to the following:

       - "Allowed Callback URLs"
       - "Allowed Logout URLs"
       - "Allowed Web Origins"
       - "Allowed Origins (CORS)"
    e. Save your application settings
6. Create an Auth0 Application for the directory web UI. Repeat the steps used for creating the wallet web UI, this time calling your application "Directory web UI", and replacing the URL determined in step c with that of the directory UI (if you've been following this runbook guide, it will be ``http://directory.localhost:3000``)

7. Configure your system that will be running Canton, your wallet and validator app backends with the following variables:

Note that on Linux and MacOs, you can simply use and fill in the above values in the provided file ``examples/env-private`` and run ``source examples/env-private`` in the two terminals you start Canton and the Validator in.

====================================  =====
Name                                  Value
------------------------------------  -----
NETWORK_AUTH_JWKS_URL                 The "JSON Web Key Set" endpoint of your tenant. You can find this value at the bottom of any application's settings page, in the "Advanced Settings" section, in the "Endpoints" tab.
NETWORK_AUTH_WELLKNOWN_URL            The "OpenID Configuration" endpoint of your tenant. You can find this value at the bottom of any application's settings page, in the "Advanced Settings" section, in the "Endpoints" tab.
NETWORK_AUTH_VALIDATOR_CLIENT_ID      The "Client ID" of your "Validator app backend" application (at the top of the application's settings page)
NETWORK_AUTH_VALIDATOR_CLIENT_SECRET  The "Client Secret" of your "Validator app backend" application (at the top of the application's settings page)
NETWORK_AUTH_VALIDATOR_USER_NAME      The subject identifier of your "Validator app backend" application. Equal to the "Client ID" of the "Validator app backend" application with `@clients` appended.
====================================  =====

8. Start Canton and configure it to validate tokens against your new Auth0 tenant:

.. parsed-literal::

    DOMAIN_URL=http://|cn_cluster|.network.canton.global:5008 ../|canton_subdir|/bin/canton --config examples/validator/validator-participant-secure.conf --bootstrap examples/validator/validator-participant-secure.sc

9. Now start the validator again:

    a. generate the validator-onboarding.conf against your new Auth0 tenant:

    .. parsed-literal::

        curl -X POST https://sv.sv-1.svc.\ |cn_cluster|.network.canton.global/api/v0/sv/devnet/onboard/validator/prepare | xargs -I _ sed 's#PLACEHOLDER#_#' examples/validator/validator-onboarding-nosecret.conf > validator-onboarding.conf

    b. start Canton Network:

    .. parsed-literal::

        NETWORK_APPS_ADDRESS_PROTOCOL=https NETWORK_APPS_ADDRESS=\ |cn_cluster|.network.canton.global bin/cn-node --config examples/validator/validator-secure.conf --config validator-onboarding.conf --bootstrap examples/validator/validator.sc

10. Upload the directory DAR.

  ::

    validatorApp.participantClient.upload_dar_unless_exists("dars/directory-service-0.1.0.dar")

11. If you have not already done so, while trying out the insecure
    setup.  Follow the steps for :ref:`configuring the wallet UI <configuring-wallet-ui>`
    and :ref:`configuring the directory UI <configuring-directory-ui>`. For the next steps, the occurences of
    ``TARGET_CLUSTER`` in the ``config.js`` files should have been
    replaced and the JSON API should be running.

12. Modify the ``auth`` section in your wallet web UI configuration at ``web-uis/wallet/config.js`` with the following block, manually replacing variables with values described below:

  ::

    auth: {
      algorithm: "rs-256",
      authority: "https://<NETWORK_AUTH_DOMAIN_URL>",
      client_id: "<NETWORK_AUTH_WALLET_UI_CLIENT_ID>",
      token_audience: "https://cn_api.example.com",
    },

====================================  =====
Name                                  Value
------------------------------------  -----
NETWORK_AUTH_DOMAIN_URL               The "Domain" of your tenant (at the top of any application's settings page)
NETWORK_AUTH_WALLET_UI_CLIENT_ID      The "Client ID" of your "Wallet web UI" application (at the top of the application's settings page)
====================================  =====

13. Repeat step 12 for the directory UI configuration, at
    ``web-uis/directory/config.js``.  The final section ``auth``
    section should look close to this but you need to replace the
    authority and client_id as explained for the wallet above.

  ::

    auth: {
      algorithm: "rs-256",
      authority: "https://<NETWORK_AUTH_DOMAIN_URL>",
      client_id: "<NETWORK_AUTH_WALLET_UI_CLIENT_ID>",
      token_audience: "https://ledger_api.example.com",
      token_scope: "daml_ledger_api",
    },

14. Start Nginx to host the static files as described in the :ref:`previous section on hosting the UIs <hosting-the-uis>`.
15. Open the wallet UI at http://wallet.localhost:3000 and click the "Log in with OAuth2" button

This will kick off an interactive log-in flow where the user is redirected from the locally running wallet UI to auth0's login portal, then upon a successful authentication back to the local wallet UI.

If this user is logging in for the first time, a page will appear in the wallet UI prompting the user to onboard themselves. This creates the Daml user & its primary party on the ledger, associated with the external account.

Validator Operator Login
++++++++++++++++++++++++

So far, we enabled the validator backend and its end-users to use Auth0 as an IAM provider. The validator backend has
been using a username that we configured above, of the form "$CLIENT_ID@clients", which corresponds to a machine-to-machine
user in Auth0. However, Auth0 does not support human login using the same account, so in order to view and operate
the wallet of the validator provider itself (e.g. in order to see validator rewards received by your validator), you will
need to create a separate user for it, and configure your validator app accordingly. To achieve that, please follow these steps:

1. Login to auth0, and find the user that you wish to make the administrator for your validator, or create a new user for it.
2. Open that user's details in Auth0, and locate its user_id, e.g. auth0|63e3d75ff4114d87a2c1e4f5
3. Set one more environment variable on your system:

=========================================  =====
Name                                       Value
-----------------------------------------  -----
NETWORK_AUTH_VALIDATOR_WALLET_USER_NAME    The user ID of the user you wish to adminster the validator's wallet, e.g. auth0|63e3d75ff4114d87a2c1e4f5
=========================================  =====

4. Stop the validator that you started above
5. Edit the examples/validator/validator-secure.conf file, locate the commented-out out line "validator-wallet-user = ${NETWORK_AUTH_VALIDATOR_WALLET_USER_NAME}", and uncomment it. This will instruct the validator app to automatically onboard that user as a wallet user, and allocate the validator app's primary party as the primary party of that user.
6. Start the validator again:

.. parsed-literal::

    NETWORK_APPS_ADDRESS_PROTOCOL=https NETWORK_APPS_ADDRESS=\ |cn_cluster|.network.canton.global bin/cn-node --config examples/validator/validator-secure.conf --bootstrap examples/validator/validator.sc

7. Refresh your browser with the wallet UI, log out of any user you may be logged in as, and login again using the validator admin user defined above.

This user should be automatically onboarded to the wallet, so you should NOT be seeing a prompt to onboard yourself. This user will now administer the rewards earned by your validator.

.. _validator_continuity:

Transitioning Across Network Resets
-----------------------------------

To support data continuity across network resets (i.e., among others, the preservation of coin balances),
validator operators need to:

1. Back up identities data from their participants on the "old" network.
2. Set up their participants on "new" networks so they are initialized with that identities data.

We cover these steps in the following.

Backing Up Participant Identities Data
++++++++++++++++++++++++++++++++++++++

Backed-up participant identities data is necessary for enabling validator users to reclaim their coin balances after a network reset.
Obtaining the necessary data is currently as simple as:

.. parsed-literal::

  curl <YOUR_VALIDATOR_API_URL>/admin/participant/identities > participant-dump.json

.. TODO(#5979): !!! We need auth here !!!

If your are following this runbook, you can replace ``YOUR_VALIDATOR_API_URL`` with ``http://localhost:5003``.
If you are following the :ref:`Kubernetes-based SV runbook <sv-helm>`, you can replace it with ``https://wallet.sv.svc.<YOUR_HOSTNAME>/api/v0/validator``.

Please store the resulting ``participant-dump.json`` in a safe location.
We recommend performing a fresh participant identities dump (on your "old" network validator) just before re-initializing your participant and validator on a freshly reset ("new") network.
To account for cases where this is not possible, we additionally recommend setting up periodical backups of your validator's participant identities data.

.. TODO(#6217): Consider covering dumping to GCP buckets as well

Initialize With Existing Identities Data
++++++++++++++++++++++++++++++++++++++++

Detailed instructions will be added soon.

.. TODO(#6219): Add instructions once we have actually implemented this and know it works
