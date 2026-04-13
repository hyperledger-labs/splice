.. _module-splice-api-token-burnmintv1-45547:

Splice.Api.Token.BurnMintV1
===========================

An interface for registries to expose burn/mint operations in a generic way
for bridges to use them, and for wallets to parse their use\.

Interfaces
----------

.. _type-splice-api-token-burnmintv1-burnmintfactory-79205:

**interface** `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_

  A factory for generic burn/mint operations\.

  **viewtype** `BurnMintFactoryView <type-splice-api-token-burnmintv1-burnmintfactoryview-72718_>`_

  + **Choice** Archive

    Controller\: Signatories of implementing template

    Returns\: ()

    (no fields)

  + .. _type-splice-api-token-burnmintv1-burnmintfactoryburnmint-20312:

    **Choice** `BurnMintFactory_BurnMint <type-splice-api-token-burnmintv1-burnmintfactoryburnmint-20312_>`_

    Burn the holdings in ``inputHoldingCids`` and create holdings with the
    owners and amounts specified in outputs\.

    Note that this is jointly authorized by the admin and the ``extraActors``\.
    The ``admin`` thus controls all calls to this choice, and some implementations might
    required ``extraActors`` to be present, e\.g\., the owners of the minted and burnt holdings\.

    Implementations are free to restrict the implementation of this choice
    including failing on all inputs or not implementing this interface at all\.

    Controller\: (DA\.Internal\.Record\.getField @\"admin\" (view this)) \:\: extraActors

    Returns\: `BurnMintFactory_BurnMintResult <type-splice-api-token-burnmintv1-burnmintfactoryburnmintresult-61365_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - expectedAdmin
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The expected ``admin`` party issuing the factory\. Implementations MUST validate that this matches the admin of the factory\. Callers should ensure they get ``expectedAdmin`` from a trusted source, e\.g\., a read against their own participant\. That way they can ensure that it is safe to exercise a choice on a factory contract acquired from an untrusted source *provided* all vetted Daml packages only contain interface implementations that check the expected admin party\.
       * - instrumentId
         - InstrumentId
         - The instrument id of the holdings\. All holdings in ``inputHoldingCids`` as well as the resulting output holdings MUST have this instrument id\.
       * - inputHoldingCids
         - \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - The holdings that should be burnt\. All holdings in ``inputHoldingCids`` MUST be archived by this choice\. Implementations MAY enforce additional restrictions such as all ``inputHoldingCids`` belonging to the same owner\.
       * - outputs
         - \[`BurnMintOutput <type-splice-api-token-burnmintv1-burnmintoutput-36483_>`_\]
         - The holdings that should be created\. The choice MUST create new holdings with the given amounts\.
       * - extraActors
         - \[`Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Extra actors, the full actors required are the admin \+ extraActors\. This is often the owners of inputHoldingCids and outputs but we allow different sets of actors to support token implementations with different authorization setups\.
       * - extraArgs
         - ExtraArgs
         - Additional context\. Implementations SHOULD include a ``splice.lfdecentralizedtrust.org/reason`` key in the metadata to provide a human readable description for explain why the ``BurnMintFactory_BurnMint`` choice was called, so that generic wallets can display it to the user\.

  + .. _type-splice-api-token-burnmintv1-burnmintfactorypublicfetch-64185:

    **Choice** `BurnMintFactory_PublicFetch <type-splice-api-token-burnmintv1-burnmintfactorypublicfetch-64185_>`_

    Controller\: actor

    Returns\: `BurnMintFactoryView <type-splice-api-token-burnmintv1-burnmintfactoryview-72718_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - expectedAdmin
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The expected admin party issuing the factory\. Implementations MUST validate that this matches the admin of the factory\. Callers should ensure they get ``expectedAdmin`` from a trusted source, e\.g\., a read against their own participant\. That way they can ensure that it is safe to exercise a choice on a factory contract acquired from an untrusted source *provided* all vetted Daml packages only contain interface implementations that check the expected admin party\.
       * - actor
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The party fetching the data\.

  + **Method burnMintFactory\_burnMintImpl \:** `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_ \-\> `BurnMintFactory_BurnMint <type-splice-api-token-burnmintv1-burnmintfactoryburnmint-20312_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `BurnMintFactory_BurnMintResult <type-splice-api-token-burnmintv1-burnmintfactoryburnmintresult-61365_>`_

  + **Method burnMintFactory\_publicFetchImpl \:** `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_ \-\> `BurnMintFactory_PublicFetch <type-splice-api-token-burnmintv1-burnmintfactorypublicfetch-64185_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `BurnMintFactoryView <type-splice-api-token-burnmintv1-burnmintfactoryview-72718_>`_

Data Types
----------

.. _type-splice-api-token-burnmintv1-burnmintfactoryview-72718:

**data** `BurnMintFactoryView <type-splice-api-token-burnmintv1-burnmintfactoryview-72718_>`_

  The view of a ``BurnMintFactory``\.

  .. _constr-splice-api-token-burnmintv1-burnmintfactoryview-23617:

  `BurnMintFactoryView <constr-splice-api-token-burnmintv1-burnmintfactoryview-23617_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - admin
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The party representing the registry app that administers the instruments for which this burnt\-mint factory can be used\.
       * - meta
         - Metadata
         - Additional metadata specific to the burn\-mint factory, used for extensibility\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `BurnMintFactoryView <type-splice-api-token-burnmintv1-burnmintfactoryview-72718_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `BurnMintFactoryView <type-splice-api-token-burnmintv1-burnmintfactoryview-72718_>`_

  **instance** HasMethod `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_ \"burnMintFactory\_publicFetchImpl\" (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_ \-\> `BurnMintFactory_PublicFetch <type-splice-api-token-burnmintv1-burnmintfactorypublicfetch-64185_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `BurnMintFactoryView <type-splice-api-token-burnmintv1-burnmintfactoryview-72718_>`_)

  **instance** `HasFromAnyView <https://docs.daml.com/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_ `BurnMintFactoryView <type-splice-api-token-burnmintv1-burnmintfactoryview-72718_>`_

  **instance** `HasInterfaceView <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_ `BurnMintFactoryView <type-splice-api-token-burnmintv1-burnmintfactoryview-72718_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"admin\" `BurnMintFactoryView <type-splice-api-token-burnmintv1-burnmintfactoryview-72718_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `BurnMintFactoryView <type-splice-api-token-burnmintv1-burnmintfactoryview-72718_>`_ Metadata

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"admin\" `BurnMintFactoryView <type-splice-api-token-burnmintv1-burnmintfactoryview-72718_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `BurnMintFactoryView <type-splice-api-token-burnmintv1-burnmintfactoryview-72718_>`_ Metadata

  **instance** `HasExercise <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_ `BurnMintFactory_PublicFetch <type-splice-api-token-burnmintv1-burnmintfactorypublicfetch-64185_>`_ `BurnMintFactoryView <type-splice-api-token-burnmintv1-burnmintfactoryview-72718_>`_

  **instance** `HasExerciseGuarded <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_ `BurnMintFactory_PublicFetch <type-splice-api-token-burnmintv1-burnmintfactorypublicfetch-64185_>`_ `BurnMintFactoryView <type-splice-api-token-burnmintv1-burnmintfactoryview-72718_>`_

  **instance** `HasFromAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_ `BurnMintFactory_PublicFetch <type-splice-api-token-burnmintv1-burnmintfactorypublicfetch-64185_>`_ `BurnMintFactoryView <type-splice-api-token-burnmintv1-burnmintfactoryview-72718_>`_

  **instance** `HasToAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_ `BurnMintFactory_PublicFetch <type-splice-api-token-burnmintv1-burnmintfactorypublicfetch-64185_>`_ `BurnMintFactoryView <type-splice-api-token-burnmintv1-burnmintfactoryview-72718_>`_

.. _type-splice-api-token-burnmintv1-burnmintfactoryburnmintresult-61365:

**data** `BurnMintFactory_BurnMintResult <type-splice-api-token-burnmintv1-burnmintfactoryburnmintresult-61365_>`_

  The result of calling the ``BurnMintFactory_BurnMint`` choice\.

  .. _constr-splice-api-token-burnmintv1-burnmintfactoryburnmintresult-13976:

  `BurnMintFactory_BurnMintResult <constr-splice-api-token-burnmintv1-burnmintfactoryburnmintresult-13976_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - outputCids
         - \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - The holdings created by the choice\. Must contain exactly the outputs specified in the choice argument in the same order\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `BurnMintFactory_BurnMintResult <type-splice-api-token-burnmintv1-burnmintfactoryburnmintresult-61365_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `BurnMintFactory_BurnMintResult <type-splice-api-token-burnmintv1-burnmintfactoryburnmintresult-61365_>`_

  **instance** HasMethod `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_ \"burnMintFactory\_burnMintImpl\" (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_ \-\> `BurnMintFactory_BurnMint <type-splice-api-token-burnmintv1-burnmintfactoryburnmint-20312_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `BurnMintFactory_BurnMintResult <type-splice-api-token-burnmintv1-burnmintfactoryburnmintresult-61365_>`_)

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"outputCids\" `BurnMintFactory_BurnMintResult <type-splice-api-token-burnmintv1-burnmintfactoryburnmintresult-61365_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"outputCids\" `BurnMintFactory_BurnMintResult <type-splice-api-token-burnmintv1-burnmintfactoryburnmintresult-61365_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `HasExercise <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_ `BurnMintFactory_BurnMint <type-splice-api-token-burnmintv1-burnmintfactoryburnmint-20312_>`_ `BurnMintFactory_BurnMintResult <type-splice-api-token-burnmintv1-burnmintfactoryburnmintresult-61365_>`_

  **instance** `HasExerciseGuarded <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_ `BurnMintFactory_BurnMint <type-splice-api-token-burnmintv1-burnmintfactoryburnmint-20312_>`_ `BurnMintFactory_BurnMintResult <type-splice-api-token-burnmintv1-burnmintfactoryburnmintresult-61365_>`_

  **instance** `HasFromAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_ `BurnMintFactory_BurnMint <type-splice-api-token-burnmintv1-burnmintfactoryburnmint-20312_>`_ `BurnMintFactory_BurnMintResult <type-splice-api-token-burnmintv1-burnmintfactoryburnmintresult-61365_>`_

  **instance** `HasToAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_ `BurnMintFactory_BurnMint <type-splice-api-token-burnmintv1-burnmintfactoryburnmint-20312_>`_ `BurnMintFactory_BurnMintResult <type-splice-api-token-burnmintv1-burnmintfactoryburnmintresult-61365_>`_

.. _type-splice-api-token-burnmintv1-burnmintoutput-36483:

**data** `BurnMintOutput <type-splice-api-token-burnmintv1-burnmintoutput-36483_>`_

  The specification of a holding to create as part of the burn/mint operation\.

  .. _constr-splice-api-token-burnmintv1-burnmintoutput-15750:

  `BurnMintOutput <constr-splice-api-token-burnmintv1-burnmintoutput-15750_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - owner
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The owner of the holding to create\.
       * - amount
         - `Decimal <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_
         - The amount of the holding to create\.
       * - context
         - ChoiceContext
         - Context specific to this output which can be used to support locking or other registry\-specific features\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `BurnMintOutput <type-splice-api-token-burnmintv1-burnmintoutput-36483_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `BurnMintOutput <type-splice-api-token-burnmintv1-burnmintoutput-36483_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"amount\" `BurnMintOutput <type-splice-api-token-burnmintv1-burnmintoutput-36483_>`_ `Decimal <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"context\" `BurnMintOutput <type-splice-api-token-burnmintv1-burnmintoutput-36483_>`_ ChoiceContext

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"outputs\" `BurnMintFactory_BurnMint <type-splice-api-token-burnmintv1-burnmintfactoryburnmint-20312_>`_ \[`BurnMintOutput <type-splice-api-token-burnmintv1-burnmintoutput-36483_>`_\]

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"owner\" `BurnMintOutput <type-splice-api-token-burnmintv1-burnmintoutput-36483_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"amount\" `BurnMintOutput <type-splice-api-token-burnmintv1-burnmintoutput-36483_>`_ `Decimal <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"context\" `BurnMintOutput <type-splice-api-token-burnmintv1-burnmintoutput-36483_>`_ ChoiceContext

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"outputs\" `BurnMintFactory_BurnMint <type-splice-api-token-burnmintv1-burnmintfactoryburnmint-20312_>`_ \[`BurnMintOutput <type-splice-api-token-burnmintv1-burnmintoutput-36483_>`_\]

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"owner\" `BurnMintOutput <type-splice-api-token-burnmintv1-burnmintoutput-36483_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

Functions
---------

.. _function-splice-api-token-burnmintv1-burnmintfactoryburnmintimpl-9738:

`burnMintFactory_burnMintImpl <function-splice-api-token-burnmintv1-burnmintfactoryburnmintimpl-9738_>`_
  \: `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_ \-\> `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_ \-\> `BurnMintFactory_BurnMint <type-splice-api-token-burnmintv1-burnmintfactoryburnmint-20312_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `BurnMintFactory_BurnMintResult <type-splice-api-token-burnmintv1-burnmintfactoryburnmintresult-61365_>`_

.. _function-splice-api-token-burnmintv1-burnmintfactorypublicfetchimpl-75623:

`burnMintFactory_publicFetchImpl <function-splice-api-token-burnmintv1-burnmintfactorypublicfetchimpl-75623_>`_
  \: `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_ \-\> `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `BurnMintFactory <type-splice-api-token-burnmintv1-burnmintfactory-79205_>`_ \-\> `BurnMintFactory_PublicFetch <type-splice-api-token-burnmintv1-burnmintfactorypublicfetch-64185_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `BurnMintFactoryView <type-splice-api-token-burnmintv1-burnmintfactoryview-72718_>`_
