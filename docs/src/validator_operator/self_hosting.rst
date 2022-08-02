Self-Hosted Validator (Preview)
===============================

These pages give a step-by-step guide how to deploy your own validator node to the Canton network. 

- *Succinctly: What is a 'validator' node? Link to further doc*
- *How does it relate to 'canton coin'?*

Local Installation
------------------

To run a validator node you will need 
1) a canton participant node, in order to host:
2) the Daml validator app and
3) the Daml wallet app

The participant node (#1) is part of the canton distribution bundled with each Daml 2.x SDK. Refer to the SDK `Getting Started page <https://docs.daml.com/getting-started/installation.html>`_ for installation instructions.

Canton coin apps (#2 and #3) are distributed in a single Canton Coin network binary available as part of a release
bundle from http://dev.network.canton.global/.
Please now extract the release bundle to a directory and then navigate to that directory.

Onboarding Validator
--------------------

.. As this is in a preview state, we do not yet have explicit disclosure, which means that each validator will have to have their own copy of a `CoinRules` contract. This step uses a propose/accept pattern to request the contract from the SVC

To operate a validator node you will need to:

1) Run a participant node that connects to the supervalidator consortium
2) Run the validator app to register yourself with the supervalidator consortium.

The Canton participant is responsible for hosting your Daml apps; i.e. interpreting Daml code, securing your data, and talking to the public canton network. It connects to the global canton domain `canton.global`. We provide a bootstrap script to handle these steps for you. You can refer to the `canton tutorial <https://docs.daml.com/canton/tutorials/getting_started.html>`_ for greater detail on what each step does.

We recommend adding the path to the Canton Coin network binary from your release
bundle (`<release-bundle-dir>/coin/bin/coin`) to your PATH as `coin`.
This is also the convention we will use in this document.

Run this now: ::

  coin --config examples/validator/validator.conf --bootstrap examples/validator/validator.canton

In the console, initialize the validator ::

  @ val validatorParty = validatorApp.initialize()

In the console, request onboarding a new user called "wallet" to the validator ::

  @ validatorApp.onboardUser("wallet")

.. TODO(i250): Automatic acceptance.

The request should be automatically approved by the supervalidator consortium in this feature preview.
  
You are now registered as a validator on the canton network. You've also configured a user that can transact through a wallet. Congratulations! 
  
Tapping some Canton Coin from the Dev Faucet
--------------------------------------------

In order to create some free canton coin to play around with, you'll need to initialize the wallet by passing in the validator party: ::

@ val validatorP = wallet.remoteParticipant.parties.list(filterParty = "validator").head.party
@ wallet.initialize(validatorParty)
  
We can create free coins like so: ::

  @ wallet.tap("1000.0")
  Res2: ContractId Canton.Coin { ... }


Listing your Canton Coins
-------------------------
  
You can list your balances with the following command:

  @ wallet.list()
  Res1: Seq()
  
Unless you've run through this guide before, you should expect an empty balance, as show above.
You can create coins by tapping the dev faucet as explained in the previous section. Try that, and then re-run this command.


Transfer Canton Coin
--------------------

.. TODO(M1-02): Still needs to be updated.

If not already done, start the canton-coin console with a wallet: ::

  coin --config examples/wallet/wallet.conf

Now, let's transfer some of our coins to ourselves: ::

  @ wallet.transfer(validator.adminParty, 300.0)
  Res4 : ???
  @ wallet.list()
  Res5 : Seq(700.0, 300.0)
