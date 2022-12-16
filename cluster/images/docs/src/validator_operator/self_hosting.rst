.. _self_hosted_validator:

Self-Hosted Validator (Preview)
===============================

These pages give a step-by-step guide how to deploy your own validator node to the Canton network.

Intended audience
-----------------

At the moment, the intended audience of this guide is technical DA staff that has some background on CC (e.g. through having
watched the `CC technical overview presentation <https://digitalasset.atlassian.net/wiki/spaces/CN/overview>`_).

..
   *Succinctly: What is a 'validator' node? Link to further doc*
   *How does it relate to 'canton coin'?*

Prerequisites
-------------

To locally start a validator node that connects against the DevNet domain, you will need to run

1) a Canton participant node, in order to host:
2) the Daml validator app and
3) the Daml wallet app

Additionally, you'll also need access to one of the following four `Digital Asset VPNs <https://digitalasset.atlassian.net/wiki/spaces/DEVSECOPS/pages/1076822828/VPN+IP+Whitelist+for+Digital+Asset>`_:

* GCP Virginia Full Tunnel
* GCP Frankfurt Full Tunnel
* GCP Sydney Full Tunnel
* GCP DA Canton DevNet

Please activate the VPN now.

.. To run a participant node, please `download and install Canton version 2.3.2 <https://docs.daml.com/canton/usermanual/downloading.html>`_.

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

.. As this is in a preview state, we do not yet have explicit disclosure, which means that each validator will have to have their own copy of a `CoinRules` contract. This step uses a propose/accept pattern to request the contract from the SVC

To operate a validator node you will need to:

1) Run a participant node that connects to the supervalidator consortium
2) Run the validator app to register yourself with the supervalidator consortium.

The Canton participant is responsible for hosting your Daml apps; i.e. interpreting Daml code, securing your data, and talking to the public canton network. It connects to the global canton domain `canton.global`. We provide a bootstrap script to handle these steps for you. You can refer to the `canton tutorial <https://docs.daml.com/canton/tutorials/getting_started.html>`_ for greater detail on what each step does.

..
   We recommend respectively adding the paths to the Canton and Canton Coin network binaries from your release
   bundles (`<release-bundle-dir>/canton/bin/canton` and `<release-bundle-dir>/coin/bin/coin`) to your PATH as `canton` and `coin`.
   This is also the convention we will use in this tutorial.

First off, you will need to start the validator participant and connect it to the devnet domain: ::

  bin/coin --config examples/validator/validator-participant.conf \
      --bootstrap examples/validator/validator-participant.canton

For convenience, this uses the coin binary at the moment. At a later point, the participant will need to be
started as usual through Canton (``bin/canton``).

Next, open a second terminal, navigate to the extracted bundle's root directory, and start a console with the CN apps: ::

  bin/coin --config examples/validator/validator.conf \
      --bootstrap examples/validator/validator.canton

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
  res4: Seq[...] = Vector(
    Contract(
      contractId = ...,
      payload = Coin(
        svc = ...,
        owner = ...,
        quantity = ExpiringQuantity(initialQuantity = 100.00, ...)
      )
    )
  )


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
  @ val transferOffer = aliceWallet.createTransferOffer(bobParty, 10.0, "p2ptransfer", expiration)

Bob can then see the transfer offer: ::

  @ bobWallet.listTransferOffers()

And accept the request: ::

  @ bobWallet.acceptTransferOffer(transferOffer)

Check Alice and Bob's wallets to see that Alice now has slightly less than 90 coins (due to transfer fees), and Bob has 10: ::

  @ aliceWallet.list()
  res12: Seq[...] = Vector(
    Contract(
      contractId = ...,
      payload = Coin(
        svc = ...,
        owner = ...,
        quantity = ExpiringQuantity(initialQuantity = 89.8000000000, ...)
      )
    )
  )

  @ bobWallet.list()
  res13: Seq[...] = Vector(
    Contract(
      contractId = ...,
      payload = Coin(
        svc = ...,
        owner = ...,
        quantity = ExpiringQuantity(initialQuantity = 10.0000000000, ...)
      )
    )
  )

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

Lastly, we have to host the frontend files. You can use any static
file server for that, e.g., `NGINX <https://www.nginx.com/>`_. To keep
things simple, we use the builtin HTTP Server in PHP. If you don't have PHP installed, please install it now, e.g. using the following on a Debian-based OS: ::

  apt install php-cli

Start another terminal and run: ::

  cd web-uis/wallet
  php -S 127.0.0.1:3000

The Wallet Web UI is now accessible at http://localhost:3000, where you can login as alice and see the coins you tapped earlier in this tutorial.

Configuring Authentication
--------------------------

All requests to the wallet must be authenticated by attaching an ``Authorization: Bearer $JWT`` header to API requests, with a JWT-encoded authentication token. The token should use the standard ``sub`` (subject) claim to identify the Daml user name for the end-user making the request.

The wallet backend can be configured to run in one of two auth modes:

1. ``hs-256-unsafe`` - this configuration will accept tokens signed with HMAC256 signature with a specified symmetric key
2. ``rs-256`` - this configuration will accept tokens signed with RSA256, verifying against the public key exposed by a trusted JWKS URL

By default, the validator configures the wallet backend to run in the former mode where the HMAC secret key is ``test``. In turn, the Wallet UI self-signs tokens with ``test`` for the username specified on the login page and attaches them to wallet API requests.

This set-up is not secure, and should only be used when hosting a validator locally on your machine.

For a secure production setup, you should securely configure an external OAuth 2.0 provider to authenticate end-users and redirect them back to the wallet application with RSA256-signed JWT tokens. Any OAuth 2.0 OIDC provider should work, but the following section will walk through an example configuration using `Auth0 <https://auth0.com>`_.

Auth0 Example IAM Setup
+++++++++++++++++++++++

To integrate Auth0 as your validator's IAM provider, perform the following:

1. Create an Auth0 tenant for your validator
2. Create an Application in the tenant - in Auth0:
    a. Navigate to Applications -> Applications, and click the "Create Application" button
    b. Choose "Single Page Application", and click Create
3. Take note of some relevant values that you will use to configure your backend and frontend:
    a. Your app's "Domain" (at the top of your application's settings page)
    b. Your app's "Client ID" (at the top of your application's settings page)
    c. Your app's JWKS endpoint (at the bottom of your application's settings page, under "Advanced Settings"->"Endpoints"->"JSON Web Key Set")
4. Determine the URL for your validator's wallet UI (if you've been following this runbook guide, it will be ``http://localhost:3000``)
5. In the Auth0 application settings, add the wallet URL to the following:

   - "Allowed Callback URLs"
   - "Allowed Logout URLs"
   - "Allowed Web Origins"
   - "Allowed Origins (CORS)"

6. Save your application settings

7. Create an API for your application - in Auth0:
    a. Navigate to Applications > APIs and click "Create API". Name can be anything, set identifier to https://canton.network.global (that is the audience that we will configure the backend to expect)
    b. Under the Permissions tab in the new API, add a permission with scope "daml_ledger_api", and a description of your choice
    c. On the Settings tab, scroll down to "Access Settings" and enable "Allow Offline Access", for automatic token refreshing

8. Modify the ``auth`` section in your backend configuration, under wallet-app-backends.walletApp at ``examples/validator/validator.conf``

  ::

    auth {
      algorithm = "rs-256"
      audience = "https://canton.network.global"
      jwks-url = "<JWKS_ENDPOINT from step 3c above>"
    }

9. Kill the running CN apps process started at the beginning, and restart them

  ::

    bin/coin --config examples/validator/validator.conf

  (Note that the way the validator's bootstrap script is currently implemented, this requires restarting also the participant, or commenting out creating the user in the ``examples/validator/validator.canton``).

10. Modify the ``auth`` section in your frontend configuration, at ``web-uis/wallet/config.js``

  ::

    auth: {
      algorithm: "rs-256",
      authority: "https://<AUTH0_DOMAIN_URL from step 3a above>",
      client_id: "<AUTH0_CLIENT_ID from step 3b above>",
      token_audience: "https://canton.network.global"
    },

11. Refresh your browser with the wallet UI, and click the "Log in with OAuth2" button

This will kick off an interactive log-in flow where the user is redirected from the locally running wallet UI to auth0's login portal, then upon a successful authentication back to the local wallet UI.

If this user is logging in for the first time, a page will appear in the wallet UI prompting the user to onboard themselves. This creates the Daml user & its primary party on the ledger, associated with the external account.

Hosting the Directory UI
------------------------

The Canton Network includes a Canton Name Service (CNS), which maps party IDs to human-readable names, much like the DNS does for IP addresses.

In order to use it in your self-hosted validator, you will need to serve one more UI - that of the Directory app.

First - you will need to upload the directory's dar file to your validator's participant. Go to the terminal in which you are running the validator (the one using "validator.conf"), and type ::

  validatorApp.remoteParticipant.dars.upload("dars/directory-service-0.1.0.dar")

The default configuration in the release bundle is configured with test authentication. If you followed the instructions above for enabling Auth0 authentication, then a similar modification of the config file would be required for the directory app.
Modify the ``auth`` section in the directory frontend configuration file, at ``web-uis/directory/config.js``

  ::

    auth: {
      algorithm: "rs-256",
      authority: "https://<AUTH0_DOMAIN_URL from step 3a above>",
      client_id: "<AUTH0_CLIENT_ID from step 3b above>",
      token_audience: "https://canton.network.global",
      token_scope: "daml_ledger_api",
    },

Note that this configuration is very similar to that of the wallet frontend, with the addition of the "daml_ledger_api" token_scope, as the directory frontend will need access to the ledger API directly.

We are now ready to host the frontend - start another terminal and run: ::

  cd web-uis/directory
  php -S 127.0.0.1:3001

The Directory Web UI should now be accessible at http://localhost:3001. You can login there using the same method you used for the wallet (either username if using the default insecure test
authentication, or through Auth0 if configured). To begin with - you will have no registered entries.
Insert a cns entry name of your choice, e.g. "alice.cns" in the "Request new entry" field, and click "Request Entry".
You will be redirected to your wallet to confirm the Canton Coin payment for your directory entry (a subscription-based payment).
Once confirmed, you will be redirected back to the directory UI, and should see your new entry listed.

If you navigate back to your wallet (refresh the page if it was left open from before) - in the top left corner, under the "CC Wallet" title, you should see that your party ID is now being resolved to your new cns entry name.
