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

At present, Daml apps (#2 and #3) are distributed in a single `*.jar` archive available `here <https://github.com/DACH-NY/the-real-canton-coin/releases>`_

Onboarding Validator
--------------------

*As this is in a preview state, we do not yet have explicit disclosure, which means that each validator will have to have their own copy of a `CoinRules` contract. This step uses a propose/accept pattern to request the contract from the SVC*

To operate a validator node you will need to:

1) Run a participant node that connects to the supervalidator consortium
2) Run the validator app to register yourself with the supervalidator consortium.

The Canton participant is responsible for hosting your Daml apps; i.e. interpreting Daml code, securing your data, and talking to the public canton network. It connects to the global canton domain `canton.global`. We provide a bootstrap script to handle these steps for you. You can refer to the `canton tutorial <https://docs.daml.com/canton/tutorials/getting_started.html>`_ for greater detail on what each step does.

Run this now: ::

  daml canton-console --config examples/validator/validator.conf --bootstrap examples/validator/validator.canton

The script will prompt you with the validator party namespace, which you need to append to the app config file. Please do so now.

Next, start the canton-coin console in a separate window: ::

  java -jar cc.jar --config examples/validator-app/???.conf 
  
In the console, request onboarding from the supervalidator consortium ::

  @ validator.onboardRequest()
  
The request should be automatically approved by the supervalidator consortium in this feature preview.
  
You are now registered as a validator on the canton network, and are ready to start accruing fees. Congratulations! 
  
Tapping some Canton Coin from the Dev Faucet
--------------------------------------------

In order to create some free canton coin to play around with, you'll need to start up the validator console if you haven't done so already: ::

  java -jar cc.jar --config examples/validator-app/???.conf 
  
We can create free coins like so: ::

  @ wallet.tap(1000.0)
  Res2: ContractId Canton.Coin { ... }

Listing your Canton Coins
-------------------------

If not already done, start the canton-coin console: ::

  java -jar cc.jar --config examples/validator-app/???.conf
  
You can list your balances with the following command:

  @ wallet.list()
  Res1: Seq()
  
Unless you've run through this guide before, you should expect an empty balance, as show above.
You can create coins by tapping the dev faucet as explained in the previous section. Try that, and then re-run this command.


Transfer Canton Coin
--------------------

If not already done, start the canton-coin console: ::

  java -jar cc.jar --config examples/validator-app/???.conf

Now, let's transfer some of our coins to ourselves: ::

  @ wallet.transfer(validator.adminParty, 300.0)
  Res4 : ???
  @ wallet.list()
  Res5 : Seq(700.0, 300.0)
