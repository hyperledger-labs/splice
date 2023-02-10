.. _self_hosted_validator:

Self-Hosted Validator
=====================

These pages give a step-by-step guide how to deploy your own validator node to the Canton network.

Prerequisites
-------------

To locally start a validator node that connects against the DevNet domain, you will need to run

1) a Canton participant node, in order to host:
2) the Daml validator app and
3) the Daml wallet app

Additionally, you'll also need to enable the GCP DA Canton DevNet VPN. If you can view
this documentation, you already enabled the VPN successfully.

To run a participant node, download Canton research: |canton_research_download_link|. If you do not have access, please reach out to Digital Asset.
Canton research is an in-development version of Canton research that will eventually turn into a new Canton release.

Please now extract Canton:

.. parsed-literal::

   tar xzvf canton-research-|canton_version|.tar.gz


To obtain the Canton Coin network binary (required to run validator
and wallet apps), please download a release bundle here:
|bundle_download_link|. (Source is available by cloning the
`the-real-canton-coin <https://github.com/DACH-NY/the-real-canton-coin>`_
repository from GitHub.)


Please now extract the downloaded bundle and change into the resulting
root directory. The commands will look similar to these:

.. parsed-literal::

  tar xzvf |version|\_coin-0.1.0-SNAPSHOT.tar.gz
  cd coin-0.1.0-SNAPSHOT

Onboarding Validator
--------------------

To operate a validator node you will need to:

1) Run a participant node that connects to the supervalidator consortium
2) Run the validator app to register yourself with the supervalidator consortium.

The Canton participant is responsible for hosting your Daml apps; i.e. interpreting Daml code, securing your data, and talking to the public canton network. It connects to the global canton domain `canton.global`. We provide a bootstrap script to handle these steps for you. You can refer to the `canton tutorial <https://docs.daml.com/canton/tutorials/getting_started.html>`_ for greater detail on what each step does.

..
   We recommend respectively adding the paths to the Canton and Canton Coin network binaries from your release
   bundles (`<release-bundle-dir>/canton/bin/canton` and `<release-bundle-dir>/coin/bin/coin`) to your PATH as `canton` and `coin`.
   This is also the convention we will use in this tutorial.

First off, you will need to start the validator participant and connect it to the domain: We assume here that
you extracted Canton research next to the Canton network tarball. If you placed it somewhere else, you might need to adjust the path.

.. parsed-literal::

  DOMAIN_URL=http://|cn_cluster|.network.canton.global:5008 ../canton-research-2.6.0-SNAPSHOT/bin/canton --config examples/validator/validator-participant.conf \
      --bootstrap examples/validator/validator-participant.sc

Next, open a second terminal, navigate to the extracted bundle's root directory, and start a console with the CN apps:

.. parsed-literal::

  NETWORK_APPS_ADDRESS_PROTOCOL=https NETWORK_APPS_ADDRESS=\ |cn_cluster|.network.canton.global bin/coin --config examples/validator/validator.conf \
      --bootstrap examples/validator/validator.sc

This exposes a `CoinRules` contract to the validator party through automation running on the SVC node.
In this feature preview, the SVC automatically accepts any validator onboard requests.

Now, onboard a new user called "alice" via the validator app: ::

  @ val aliceParty = validatorApp.onboardUser("alice")

You are now registered as a validator on the Canton network. You've also configured a user that can transact through a wallet. Congratulations!

Tapping some Canton Coin from the Dev Faucet
--------------------------------------------

To use the wallet, you interact with the wallet setup in the previous section using a specific party user. In our example, ``aliceWallet`` has been
configured to interact with the wallet app using the previously created user ``alice``.

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

Hosting the Wallet Web UI
-------------------------

The Wallet Web UI is distributed as static files that connect to the
wallet backend that we started in the previous section via `gRPC-Web
<https://github.com/grpc/grpc-web>`_. We use `envoy
<https://www.envoyproxy.io/>`_ as a proxy that translates between gRPC
and gRPC-Web.

First,
`install envoy <https://www.envoyproxy.io/docs/envoy/latest/start/install>`_
following the instructions for your operating system.

Next, start the envoy proxy. This proxies the wallet gRPC API on port
5004 to a gRPC-Web API on port 6004. Open a new terminal and run: ::

  envoy -c examples/validator/envoy.yaml

Before we can deploy the wallet UI, we need to configure the URL of the directory service so the wallet can resolve party IDs as well as the URL of CC Scan.
For that, open ``web-uis/wallet/config.js`` and change ``TARGET_CLUSTER`` to |cn_cluster| for both directory and scan:

.. literalinclude:: ../../../../../apps/wallet/frontend/public/config.js
    :start-after: BEGIN_WALLET_CLUSTER_BACKEND_CONFIG
    :end-before: END_WALLET_CLUSTER_BACKEND_CONFIG

Lastly, we have to host the frontend files. You can use any static
file server for that, e.g., `NGINX <https://www.nginx.com/>`_. To keep
things simple, we use the builtin HTTP Server in PHP. If you don't have PHP installed, please install it now, e.g. using the following on a Debian-based OS: ::

  apt install php-cli

Start another terminal and run: ::

  cd web-uis/wallet
  php -S 127.0.0.1:3000

The Wallet Web UI is now accessible at http://localhost:3000, where you can login as alice and see the coins you tapped earlier in this tutorial.

Hosting the Directory UI
------------------------

The Canton Network includes a Canton Name Service (CNS), which maps party IDs to human-readable names, much like the DNS does for IP addresses.

In order to use it in your self-hosted validator, you will need to serve one more UI - that of the Directory app.

First - you will need to upload the directory's dar file to your validator's participant. Go to the terminal in which you are running the validator (the one using "validator.conf"), and type ::

  validatorApp.remoteParticipant.dars.upload("dars/directory-service-0.1.0.dar")

Before you can use the directory UI, you need to configure the URL of the directory backend similar to
how you configured the wallet UI earlier. For that,
open ``web-uis/directory/config.js`` and change ``TARGET_CLUSTER`` to |cn_cluster|:

.. literalinclude:: ../../../../../apps/directory/frontend/public/config.js
    :start-after: BEGIN_DIRECTORY_CONFIG
    :end-before: END_DIRECTORY_CONFIG

We are now ready to host the frontend - start another terminal and run: ::

  cd web-uis/directory
  php -S 127.0.0.1:3001

The Directory Web UI should now be accessible at http://localhost:3001. You can login there using the same method you used for the wallet (either username if using the default insecure test
authentication, or through Auth0 if configured). To begin with - you will have no registered entries.
Insert a cns entry name of your choice, e.g. "alice.cns" in the "Request new entry" field, and click "Request Entry".
You will be redirected to your wallet to confirm the Canton Coin payment for your directory entry (a subscription-based payment).
Once confirmed, you will be redirected back to the directory UI, and should see your new entry listed.

If you navigate back to your wallet (refresh the page if it was left open from before) - in the top left corner, under the "CC Wallet" title, you should see that your party ID is now being resolved to your new cns entry name.

Enabling Authentication
-----------------------

By default, the wallet and validator backends in your own validator node are configured to require authentication using JWT access tokens. However, all tokens are self-signed with a hardcoded secret.
This setup is not secure, and should only be used when hosting a validator locally on your machine.

For a secure production setup, set up an external OAuth 2.0 provider for authenticating your backends and end-users and change your config files accordingly.
Any OAuth 2.0 OIDC provider should work, but the following section will walk through an example configuration using `Auth0 <https://auth0.com>`_.

Auth0 Example IAM Setup
+++++++++++++++++++++++

To integrate Auth0 as your validator's IAM provider, perform the following:

.. TODO(#2567) Revisit the flow once we have customizable JWT audiences.

1. Create an Auth0 tenant for your validator
2. Set the environment variable ``NETWORK_AUTH_JWKS_URL`` to the "JSON Web Key Set" endpoint of your tenant. You can find this value at the bottom of any application's settings page (there will be one default application), in the "Advanced Settings" section, in the "Endpoints" tab.
3. Start Canton and configure it to validate tokens against your new Auth0 tenant:

.. parsed-literal::

    DOMAIN_URL=http://|cn_cluster|.network.canton.global:5008 ../canton-research-2.6.0-SNAPSHOT/bin/canton --config examples/validator/validator-participant-secure.conf \
      --bootstrap examples/validator/validator-participant-secure.sc

4. Get participant id from the Canton console. We'll need to configure Auth0 to issue tokens for this participant. The actual participant id you see will vary slightly::

     @ validatorParticipant.id.toProtoPrimitive
     res1: String = "PAR::validatorParticipant::12207d5f2bee9947583e39ae8a890963e9a09b32bcbd47c44329408d144e0f6e2ae1"

5. Create an Auth0 API that controls access to the ledger API:

    .. TODO(#2052) use a unique audience for each app

    a. Navigate to Applications > APIs and click "Create API". Set name to ``Daml Ledger API``, set identifier to ``https://daml.com/jwt/aud/participant/validatorParticipant::12207d5f2bee9947583e39ae8a890963e9a09b32bcbd47c44329408d144e0f6e2ae1`` (replace the participant id by the value you got from the previous step)
    b. Under the Permissions tab in the new API, add a permission with scope ``daml_ledger_api``, and a description of your choice.
    c. On the Settings tab, scroll down to "Access Settings" and enable "Allow Offline Access", for automatic token refreshing.

6. Create another API. Set name to ``CN App API``, set identifier to ``https://canton.network.global``. This API will be used by the wallet UI to authenticate itself towards the wallet & validator app.
7. Create an Auth0 Application for the validator backend:

    a. In Auth0, navigate to Applications -> Applications, and click the "Create Application" button
    b. Choose "Machine to Machine Applications", call it "Validator app backend", and click Create
    c. Choose the ``Daml Ledger API`` API you created in step 2 in the "Authorize Machine to Machine Application" dialog and check the ``daml_ledger_api`` permission, and click Authorize.

8. Create an Auth0 Application for the wallet backend.
   Repeat the steps used for creating the validator backend application, this time calling your application "Wallet app backend" and granting access to both ``Daml Ledger API`` and ``CN App API``.
9. Create an Auth0 Application for the wallet web UI.

    a. In Auth0, navigate to Applications -> Applications, and click the "Create Application" button
    b. Choose "Single Page Web Applications", call it "Wallet web UI", and click Create
    c. Determine the URL for your validator's wallet UI (if you've been following this runbook guide, it will be ``http://localhost:3000``)
    d. In the Auth0 application settings, add the wallet URL to the following:

       - "Allowed Callback URLs"
       - "Allowed Logout URLs"
       - "Allowed Web Origins"
       - "Allowed Origins (CORS)"
    e. Save your application settings
10. Create an Auth0 Application for the directory web UI. Repeat the steps used for creating the wallet web UI, this time calling your application "Directory web UI", and replacing the URL determined in step c with that of the directory UI (if you've been following this runbook guide, it will be ``http://localhost:3001``)
11. Allocate the validator service party and user.

   a. First, go to ``Validator app backend`` application that you configured earlier and copy the Client ID.
   b. Next go to the terminal in which you started Canton earlier.
   c. Create the party. Your output will look slightly different::

        @ val validatorParty = validatorParticipant.parties.enable("validator_service_user")
        validatorParty: PartyId = validator_service_user::12207d5f2bee...

   d. Create the user. Replace the ``$CLIENT_ID`` by the client id you got from the ``Validator app backend``::

        @ validatorParticipant.ledger_api.users.create(
              id = "$CLIENT_ID@clients",
              actAs = Set(validatorParty.toLf),
              readAs = Set.empty,
              primaryParty = Some(validatorParty.toLf),
              participantAdmin = true,
          )
        res5: User = User(id = "0wricJfT4Zm5RfBYhGKnyi695mzTXGsn@clients", primaryParty = Some(value = "validator_service_user::12207d5f2bee9947583e39ae8a890963e9a09b32bcbd47c44329408d144e0f6e2ae1"), isActive = true, annotations = Map())

12. Set the following environment variables on the system that will be running your wallet and validator app backends:

====================================  =====
Name                                  Value
------------------------------------  -----
NETWORK_AUTH_JWKS_URL                 The "JSON Web Key Set" endpoint of your tenant. You can find this value at the bottom of any application's settings page, in the "Advanced Settings" section, in the "Endpoints" tab.
NETWORK_AUTH_WELLKNOWN_URL            The "OpenID Configuration" endpoint of your tenant. You can find this value at the bottom of any application's settings page, in the "Advanced Settings" section, in the "Endpoints" tab.
NETWORK_AUTH_VALIDATOR_CLIENT_ID      The "Client ID" of your "Validator app backend" application (at the top of the application's settings page)
NETWORK_AUTH_VALIDATOR_CLIENT_SECRET  The "Client Secret" of your "Validator app backend" application (at the top of the application's settings page)
NETWORK_AUTH_VALIDATOR_USER_NAME      The subject identifier of your "Validator app backend" application. Equal to the "Client ID" of the "Validator app backend" application with `@clients` appended.
NETWORK_AUTH_WALLET_CLIENT_ID         The "Client ID" of your "Wallet app backend" application (at the top of the application's settings page)
NETWORK_AUTH_WALLET_CLIENT_SECRET     The "Client Secret" of your "Wallet app backend" application (at the top of the application's settings page)
NETWORK_AUTH_WALLET_USER_NAME         The subject identifier of your "Wallet app backend" application. Equal to the "Client ID" of the "Wallet app backend" application with `@clients` appended.
NETWORK_AUTH_LEDGER_API_AUDIENCE      The audience you configured for the ``Daml ledger API`` API in Auth0, e.g, ```https://daml.com/jwt/aud/participant/validatorParticipant::12207d5f2bee9947583e39ae8a890963e9a09b32bcbd47c44329408d144e0f6e2ae1``
====================================  =====

13. Now start the validator & the wallet again:

.. parsed-literal::

    NETWORK_APPS_ADDRESS_PROTOCOL=https NETWORK_APPS_ADDRESS=\ |cn_cluster|.network.canton.global bin/coin --config examples/validator/validator-secure.conf \
      --bootstrap examples/validator/validator.sc

14. Modify the ``auth`` section in your wallet web UI configuration at ``web-uis/wallet/config.js`` with the following block, manually replacing variables with values described below:

  ::

    auth: {
      algorithm: "rs-256",
      authority: "https://<NETWORK_AUTH_DOMAIN_URL>",
      client_id: "<NETWORK_AUTH_WALLET_UI_CLIENT_ID>",
      token_audience: "https://canton.network.global",
      token_scope: "daml_ledger_api",
    },

====================================  =====
Name                                  Value
------------------------------------  -----
NETWORK_AUTH_DOMAIN_URL               The "Domain" of your tenant (at the top of any application's settings page)
NETWORK_AUTH_WALLET_UI_CLIENT_ID      The "Client ID" of your "Wallet web UI" application (at the top of the application's settings page)
====================================  =====

15. Repeat step 9 for the directory UI configuration, at ``web-uis/directory/config.js``. This time, set the audience to the same value you set ``NETWORK_AUTH_LEDGER_API_AUDIENCE`` to earlier, e.g., ``https://daml.com/jwt/aud/participant/validatorParticipant::12207d5f2bee9947583e39ae8a890963e9a09b32bcbd47c44329408d144e0f6e2ae1``.

16. Refresh your browser with the wallet UI, and click the "Log in with OAuth2" button

This will kick off an interactive log-in flow where the user is redirected from the locally running wallet UI to auth0's login portal, then upon a successful authentication back to the local wallet UI.

If this user is logging in for the first time, a page will appear in the wallet UI prompting the user to onboard themselves. This creates the Daml user & its primary party on the ledger, associated with the external account.
