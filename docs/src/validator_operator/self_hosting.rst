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

To obtain the Canton Coin network binary (required to run validator and wallet apps), please clone the
`the-real-canton-coin <https://github.com/DACH-NY/the-real-canton-coin>`_ repository from GitHub and follow the setup instructions.
Then, run ``sbt bundle``. This will create a release bundle in ``the-real-canton-coin/apps/app/target/release/coin/``.

(Note that a release bundle may also be downloaded here:
|bundle_download_link|. However, you will still need a local clone of
the github repository and the instructions do not yet reflect the use
of this bundle.)


Please now navigate to the examples directory in your local clone of the `the-real-canton-coin` Git repository: ::

  cd apps/app/target/release/coin/examples


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

  coin -c validator/validator-participant.conf --bootstrap validator/validator-participant.canton

For convenience, this uses the coin binary at the moment. At a later point, the participant will need to be
started as usual through Canton (``bin/canton``). Note that in the ``the-real-canton-coin``, we use ``direnv``
to automatically add the alias ``coin`` for the path to the CC network binary.

Next, open a second terminal, navigate to the `examples` directory again, and start a console with the CN apps: ::

  cd apps/app/target/release/coin/examples
  coin --config validator/validator.conf

In the console, initialize the validator.  ::

  @ val validatorParty = validatorApp.initialize()

This exposes a `CoinRules` contract to the validator party through automation running on the SVC node.
In this feature preview, the SVC automatically accepts any validator onboard requests.

Now, onboard a new user called "alice" via the validator app: ::

  @ val aliceParty = validatorApp.onboardUser("alice")

You are now registered as a validator on the Canton network. You've also configured a user that can transact through a wallet. Congratulations!
  
Tapping some Canton Coin from the Dev Faucet
--------------------------------------------

In order to create some free canton coin to play around with, you'll need to initialize the wallet backend by passing in the validator party.
Reusing the console from the previous section: ::

  @ walletApp.initialize(validatorParty)
  
To use the wallet, you interact with it using a specific party user. In our example, ``aliceWallet`` has been
configured to interact with the wallet app using the previously created user ``alice``.

For all users created via ``validatorApp.onboardUser`` the wallet app is automatically installed.
To allow the validator party to use the wallet, we need to install it manually via: ::


  @ validatorApp.installWalletAppForValidator()


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

Preparing for the first transfer
--------------------------------

In order to have someone for Alice to transfer some coin to, please first create a second user for Bob: ::

  @ val bobParty = validatorApp.onboardUser("bob")

You can double check that Bob has no coins yet. The following should return an empty list: ::

  @ bobWallet.list()

Before Alice can transfer coins to Bob, they first need to initiate a payment channel between them. Alice, being the sender, will initiate the request for the channel: ::

  @ val aliceProposal = aliceWallet.proposePaymentChannel(bobParty)

Bob can then see the request to create the channel: ::

  @ bobWallet.listPaymentChannelProposals()

And accept the request: ::

  @ val channelId = bobWallet.acceptPaymentChannelProposal(aliceProposal)

Transferring coins
------------------

Payment channels by default allow direct transfers (transfers that do not require the recipient's approval). Alice can therefore simply transfer some coins to Bob now: ::

  @ aliceWallet.executeDirectTransfer(bobParty, 10, coinId)

Check Alice and Bob's wallets to see that Alice now has slightly less than 990 coins (due to transfer fees), and Bob has 10: ::

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

Requesting a transfer
---------------------

Using the same channel as before, Bob can also ask for a payment. First, initiate the request from Bob: ::

  @ val paymentReq = bobWallet.createOnChannelPaymentRequest(aliceParty, 10, "Please transfer coins")

Check Alice's wallet to see the request, and then accept it: ::

  @ aliceWallet.listOnChannelPaymentRequests()
  @ aliceWallet.acceptOnChannelPaymentRequest(paymentReq, aliceWallet.list().head.contractId)

Note that you did not reuse the same coinCid from before for the transfer - that contract ID has been archived, and replaced with a new one containing the change from her previous transfer.
You can now check again Bob's and Alice's wallets - Bob received 10 coins again, and Alice's holdings were reduced by slightly more than 10 coin again: ::

  @ bobWallet.list()
  @ aliceWallet.list()
