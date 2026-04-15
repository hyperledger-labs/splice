.. _module-splice-api-token-transferinstructionv1-72974:

Splice.Api.Token.TransferInstructionV1
======================================

Instruct transfers of holdings between parties\.

Interfaces
----------

.. _type-splice-api-token-transferinstructionv1-transferfactory-55548:

**interface** `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_

  A factory contract to instruct transfers of holdings between parties\.

  **viewtype** `TransferFactoryView <type-splice-api-token-transferinstructionv1-transferfactoryview-36679_>`_

  + **Choice** Archive

    Controller\: Signatories of implementing template

    Returns\: ()

    (no fields)

  + .. _type-splice-api-token-transferinstructionv1-transferfactorypublicfetch-56912:

    **Choice** `TransferFactory_PublicFetch <type-splice-api-token-transferinstructionv1-transferfactorypublicfetch-56912_>`_

    Fetch the view of the factory contract\.

    Controller\: actor

    Returns\: `TransferFactoryView <type-splice-api-token-transferinstructionv1-transferfactoryview-36679_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - expectedAdmin
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The expected admin party issuing the factory\. Implementations MUST validate that this matches the admin of the factory\. Callers SHOULD ensure they get ``expectedAdmin`` from a trusted source, e\.g\., a read against their own participant\. That way they can ensure that it is safe to exercise a choice on a factory contract acquired from an untrusted source *provided* all vetted Daml packages only contain interface implementations that check the expected admin party\.
       * - actor
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The party fetching the contract\.

  + .. _type-splice-api-token-transferinstructionv1-transferfactorytransfer-25365:

    **Choice** `TransferFactory_Transfer <type-splice-api-token-transferinstructionv1-transferfactorytransfer-25365_>`_

    Instruct the registry to execute a transfer\.
    Implementations MUST ensure that this choice fails if ``transfer.executeBefore`` is in the past\.

    Controller\: (DA\.Internal\.Record\.getField @\"sender\" transfer)

    Returns\: `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - expectedAdmin
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The expected admin party issuing the factory\. Implementations MUST validate that this matches the admin of the factory\. Callers SHOULD ensure they get ``expectedAdmin`` from a trusted source, e\.g\., a read against their own participant\. That way they can ensure that it is safe to exercise a choice on a factory contract acquired from an untrusted source *provided* all vetted Daml packages only contain interface implementations that check the expected admin party\.
       * - transfer
         - `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_
         - The transfer to execute\.
       * - extraArgs
         - ExtraArgs
         - The extra arguments to pass to the transfer implementation\.

  + **Method transferFactory\_publicFetchImpl \:** `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_ \-\> `TransferFactory_PublicFetch <type-splice-api-token-transferinstructionv1-transferfactorypublicfetch-56912_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv1-transferfactoryview-36679_>`_

  + **Method transferFactory\_transferImpl \:** `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_ \-\> `TransferFactory_Transfer <type-splice-api-token-transferinstructionv1-transferfactorytransfer-25365_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

.. _type-splice-api-token-transferinstructionv1-transferinstruction-69882:

**interface** `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_

  An interface for tracking the status of a transfer instruction,
  i\.e\., a request to a registry app to execute a transfer\.

  Registries MAY evolve the transfer instruction in multiple steps\. They SHOULD
  do so using only the choices on this interface, so that wallets can reliably
  parse the transaction history and determine whether the instruction ultimately
  succeeded or failed\.

  **viewtype** `TransferInstructionView <type-splice-api-token-transferinstructionv1-transferinstructionview-46309_>`_

  + **Choice** Archive

    Controller\: Signatories of implementing template

    Returns\: ()

    (no fields)

  + .. _type-splice-api-token-transferinstructionv1-transferinstructionaccept-36884:

    **Choice** `TransferInstruction_Accept <type-splice-api-token-transferinstructionv1-transferinstructionaccept-36884_>`_

    Accept the transfer as the receiver\.

    This choice is only available if the instruction is in
    ``TransferPendingReceiverAcceptance`` state\.

    Note that while implementations will typically return ``TransferInstructionResult_Completed``,
    this is not guaranteed\. The result of the choice is implementation\-specific and MAY
    be any of the three possible results\.

    Controller\: (DA\.Internal\.Record\.getField @\"receiver\" (DA\.Internal\.Record\.getField @\"transfer\" (view this)))

    Returns\: `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - extraArgs
         - ExtraArgs
         - Additional context required in order to exercise the choice\.

  + .. _type-splice-api-token-transferinstructionv1-transferinstructionreject-89645:

    **Choice** `TransferInstruction_Reject <type-splice-api-token-transferinstructionv1-transferinstructionreject-89645_>`_

    Reject the transfer as the receiver\.

    This choice is only available if the instruction is in
    ``TransferPendingReceiverAcceptance`` state\.

    Controller\: (DA\.Internal\.Record\.getField @\"receiver\" (DA\.Internal\.Record\.getField @\"transfer\" (view this)))

    Returns\: `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - extraArgs
         - ExtraArgs
         - Additional context required in order to exercise the choice\.

  + .. _type-splice-api-token-transferinstructionv1-transferinstructionupdate-27423:

    **Choice** `TransferInstruction_Update <type-splice-api-token-transferinstructionv1-transferinstructionupdate-27423_>`_

    Update the state of the transfer instruction\. Used by the registry to
    execute registry internal workflow steps that advance the state of the
    transfer instruction\. A reason may be communicated via the metadata\.

    Controller\: (DA\.Internal\.Record\.getField @\"admin\" (DA\.Internal\.Record\.getField @\"instrumentId\" (DA\.Internal\.Record\.getField @\"transfer\" (view this)))), extraActors

    Returns\: `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - extraActors
         - \[`Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]
         - Extra actors authorizing the update\. Implementations MUST check that this field contains the expected actors for the specific update\.
       * - extraArgs
         - ExtraArgs
         - Additional context required in order to exercise the choice\.

  + .. _type-splice-api-token-transferinstructionv1-transferinstructionwithdraw-43648:

    **Choice** `TransferInstruction_Withdraw <type-splice-api-token-transferinstructionv1-transferinstructionwithdraw-43648_>`_

    Withdraw the transfer instruction as the sender\.

    Controller\: (DA\.Internal\.Record\.getField @\"sender\" (DA\.Internal\.Record\.getField @\"transfer\" (view this)))

    Returns\: `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - extraArgs
         - ExtraArgs
         - Additional context required in order to exercise the choice\.

  + **Method transferInstruction\_acceptImpl \:** `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ \-\> `TransferInstruction_Accept <type-splice-api-token-transferinstructionv1-transferinstructionaccept-36884_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  + **Method transferInstruction\_rejectImpl \:** `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ \-\> `TransferInstruction_Reject <type-splice-api-token-transferinstructionv1-transferinstructionreject-89645_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  + **Method transferInstruction\_updateImpl \:** `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ \-\> `TransferInstruction_Update <type-splice-api-token-transferinstructionv1-transferinstructionupdate-27423_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  + **Method transferInstruction\_withdrawImpl \:** `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ \-\> `TransferInstruction_Withdraw <type-splice-api-token-transferinstructionv1-transferinstructionwithdraw-43648_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

Data Types
----------

.. _type-splice-api-token-transferinstructionv1-transfer-51973:

**data** `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_

  A specification of a transfer of holdings between two parties\.

  .. _constr-splice-api-token-transferinstructionv1-transfer-5022:

  `Transfer <constr-splice-api-token-transferinstructionv1-transfer-5022_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - sender
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The sender of the transfer\.
       * - receiver
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The receiver of the transfer\.
       * - amount
         - `Decimal <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_
         - The amount to transfer\.
       * - instrumentId
         - InstrumentId
         - The instrument identifier\.
       * - requestedAt
         - `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - Wallet provided timestamp when the transfer was requested\. MUST be in the past when instructing the transfer\.
       * - executeBefore
         - `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - Until when (exclusive) the transfer may be executed\. MUST be in the future when instructing the transfer\.  Registries SHOULD NOT execute the transfer instruction after this time, so that senders can retry creating a new transfer instruction after this time\.
       * - inputHoldingCids
         - \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - The holding contracts that should be used to fund the transfer\.  MAY be empty if the registry supports automatic selection of holdings for transfers or does not represent holdings on\-ledger\.  If specified, then the transfer MUST archive all of these holdings, so that the execution of the transfer conflicts with any other transfers using these holdings\. Thereby allowing that the sender can use deliberate contention on holdings to prevent duplicate transfers\.
       * - meta
         - Metadata
         - Metadata\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"amount\" `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_ `Decimal <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"executeBefore\" `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_ `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"inputHoldingCids\" `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"instrumentId\" `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_ InstrumentId

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_ Metadata

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"receiver\" `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"requestedAt\" `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_ `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"sender\" `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"transfer\" `TransferFactory_Transfer <type-splice-api-token-transferinstructionv1-transferfactorytransfer-25365_>`_ `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"transfer\" `TransferInstructionView <type-splice-api-token-transferinstructionv1-transferinstructionview-46309_>`_ `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"amount\" `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_ `Decimal <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"executeBefore\" `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_ `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"inputHoldingCids\" `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"instrumentId\" `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_ InstrumentId

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_ Metadata

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"receiver\" `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"requestedAt\" `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_ `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"sender\" `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"transfer\" `TransferFactory_Transfer <type-splice-api-token-transferinstructionv1-transferfactorytransfer-25365_>`_ `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"transfer\" `TransferInstructionView <type-splice-api-token-transferinstructionv1-transferinstructionview-46309_>`_ `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_

.. _type-splice-api-token-transferinstructionv1-transferfactoryview-36679:

**data** `TransferFactoryView <type-splice-api-token-transferinstructionv1-transferfactoryview-36679_>`_

  View for ``TransferFactory``\.

  .. _constr-splice-api-token-transferinstructionv1-transferfactoryview-43930:

  `TransferFactoryView <constr-splice-api-token-transferinstructionv1-transferfactoryview-43930_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - admin
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The party representing the registry app that administers the instruments for which this transfer factory can be used\.
       * - meta
         - Metadata
         - Additional metadata specific to the transfer factory, used for extensibility\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv1-transferfactoryview-36679_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv1-transferfactoryview-36679_>`_

  **instance** HasMethod `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_ \"transferFactory\_publicFetchImpl\" (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_ \-\> `TransferFactory_PublicFetch <type-splice-api-token-transferinstructionv1-transferfactorypublicfetch-56912_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv1-transferfactoryview-36679_>`_)

  **instance** `HasFromAnyView <https://docs.daml.com/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv1-transferfactoryview-36679_>`_

  **instance** `HasInterfaceView <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv1-transferfactoryview-36679_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"admin\" `TransferFactoryView <type-splice-api-token-transferinstructionv1-transferfactoryview-36679_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `TransferFactoryView <type-splice-api-token-transferinstructionv1-transferfactoryview-36679_>`_ Metadata

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"admin\" `TransferFactoryView <type-splice-api-token-transferinstructionv1-transferfactoryview-36679_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `TransferFactoryView <type-splice-api-token-transferinstructionv1-transferfactoryview-36679_>`_ Metadata

  **instance** `HasExercise <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_ `TransferFactory_PublicFetch <type-splice-api-token-transferinstructionv1-transferfactorypublicfetch-56912_>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv1-transferfactoryview-36679_>`_

  **instance** `HasExerciseGuarded <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_ `TransferFactory_PublicFetch <type-splice-api-token-transferinstructionv1-transferfactorypublicfetch-56912_>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv1-transferfactoryview-36679_>`_

  **instance** `HasFromAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_ `TransferFactory_PublicFetch <type-splice-api-token-transferinstructionv1-transferfactorypublicfetch-56912_>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv1-transferfactoryview-36679_>`_

  **instance** `HasToAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_ `TransferFactory_PublicFetch <type-splice-api-token-transferinstructionv1-transferfactorypublicfetch-56912_>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv1-transferfactoryview-36679_>`_

.. _type-splice-api-token-transferinstructionv1-transferinstructionresult-24735:

**data** `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  The result of instructing a transfer or advancing the state of a transfer instruction\.

  .. _constr-splice-api-token-transferinstructionv1-transferinstructionresult-25274:

  `TransferInstructionResult <constr-splice-api-token-transferinstructionv1-transferinstructionresult-25274_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - output
         - `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv1-transferinstructionresultoutput-59824_>`_
         - The output of the step\.
       * - senderChangeCids
         - \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - New holdings owned by the sender created to return \"change\"\. Can be used by callers to batch creating or updating multiple transfer instructions in a single Daml transaction\.
       * - meta
         - Metadata
         - Additional metadata specific to the transfer instruction, used for extensibility; e\.g\., fees charged\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** HasMethod `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_ \"transferFactory\_transferImpl\" (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_ \-\> `TransferFactory_Transfer <type-splice-api-token-transferinstructionv1-transferfactorytransfer-25365_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_)

  **instance** HasMethod `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ \"transferInstruction\_acceptImpl\" (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ \-\> `TransferInstruction_Accept <type-splice-api-token-transferinstructionv1-transferinstructionaccept-36884_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_)

  **instance** HasMethod `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ \"transferInstruction\_rejectImpl\" (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ \-\> `TransferInstruction_Reject <type-splice-api-token-transferinstructionv1-transferinstructionreject-89645_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_)

  **instance** HasMethod `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ \"transferInstruction\_updateImpl\" (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ \-\> `TransferInstruction_Update <type-splice-api-token-transferinstructionv1-transferinstructionupdate-27423_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_)

  **instance** HasMethod `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ \"transferInstruction\_withdrawImpl\" (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ \-\> `TransferInstruction_Withdraw <type-splice-api-token-transferinstructionv1-transferinstructionwithdraw-43648_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_)

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_ Metadata

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"output\" `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_ `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv1-transferinstructionresultoutput-59824_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"senderChangeCids\" `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_ Metadata

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"output\" `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_ `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv1-transferinstructionresultoutput-59824_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"senderChangeCids\" `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `HasExercise <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_ `TransferFactory_Transfer <type-splice-api-token-transferinstructionv1-transferfactorytransfer-25365_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** `HasExercise <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ `TransferInstruction_Accept <type-splice-api-token-transferinstructionv1-transferinstructionaccept-36884_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** `HasExercise <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ `TransferInstruction_Reject <type-splice-api-token-transferinstructionv1-transferinstructionreject-89645_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** `HasExercise <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ `TransferInstruction_Update <type-splice-api-token-transferinstructionv1-transferinstructionupdate-27423_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** `HasExercise <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ `TransferInstruction_Withdraw <type-splice-api-token-transferinstructionv1-transferinstructionwithdraw-43648_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** `HasExerciseGuarded <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_ `TransferFactory_Transfer <type-splice-api-token-transferinstructionv1-transferfactorytransfer-25365_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** `HasExerciseGuarded <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ `TransferInstruction_Accept <type-splice-api-token-transferinstructionv1-transferinstructionaccept-36884_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** `HasExerciseGuarded <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ `TransferInstruction_Reject <type-splice-api-token-transferinstructionv1-transferinstructionreject-89645_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** `HasExerciseGuarded <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ `TransferInstruction_Update <type-splice-api-token-transferinstructionv1-transferinstructionupdate-27423_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** `HasExerciseGuarded <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ `TransferInstruction_Withdraw <type-splice-api-token-transferinstructionv1-transferinstructionwithdraw-43648_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** `HasFromAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_ `TransferFactory_Transfer <type-splice-api-token-transferinstructionv1-transferfactorytransfer-25365_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** `HasFromAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ `TransferInstruction_Accept <type-splice-api-token-transferinstructionv1-transferinstructionaccept-36884_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** `HasFromAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ `TransferInstruction_Reject <type-splice-api-token-transferinstructionv1-transferinstructionreject-89645_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** `HasFromAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ `TransferInstruction_Update <type-splice-api-token-transferinstructionv1-transferinstructionupdate-27423_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** `HasFromAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ `TransferInstruction_Withdraw <type-splice-api-token-transferinstructionv1-transferinstructionwithdraw-43648_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** `HasToAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_ `TransferFactory_Transfer <type-splice-api-token-transferinstructionv1-transferfactorytransfer-25365_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** `HasToAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ `TransferInstruction_Accept <type-splice-api-token-transferinstructionv1-transferinstructionaccept-36884_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** `HasToAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ `TransferInstruction_Reject <type-splice-api-token-transferinstructionv1-transferinstructionreject-89645_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** `HasToAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ `TransferInstruction_Update <type-splice-api-token-transferinstructionv1-transferinstructionupdate-27423_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

  **instance** `HasToAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ `TransferInstruction_Withdraw <type-splice-api-token-transferinstructionv1-transferinstructionwithdraw-43648_>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

.. _type-splice-api-token-transferinstructionv1-transferinstructionresultoutput-59824:

**data** `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv1-transferinstructionresultoutput-59824_>`_

  The output of instructing a transfer or advancing the state of a transfer instruction\.

  .. _constr-splice-api-token-transferinstructionv1-transferinstructionresultpending-18902:

  `TransferInstructionResult_Pending <constr-splice-api-token-transferinstructionv1-transferinstructionresultpending-18902_>`_

    Use this result to communicate that the transfer is pending further steps\.

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - transferInstructionCid
         - `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_
         - Contract id of the transfer instruction representing the pending state\.

  .. _constr-splice-api-token-transferinstructionv1-transferinstructionresultcompleted-47798:

  `TransferInstructionResult_Completed <constr-splice-api-token-transferinstructionv1-transferinstructionresultcompleted-47798_>`_

    Use this result to communicate that the transfer succeeded and the receiver
    has received their holdings\.

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - receiverHoldingCids
         - \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - The newly created holdings owned by the receiver as part of successfully completing the transfer\.

  .. _constr-splice-api-token-transferinstructionv1-transferinstructionresultfailed-21543:

  `TransferInstructionResult_Failed <constr-splice-api-token-transferinstructionv1-transferinstructionresultfailed-21543_>`_

    Use this result to communicate that the transfer did not succeed and all holdings (minus fees)
    have been returned to the sender\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv1-transferinstructionresultoutput-59824_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv1-transferinstructionresultoutput-59824_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"output\" `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_ `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv1-transferinstructionresultoutput-59824_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"receiverHoldingCids\" `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv1-transferinstructionresultoutput-59824_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"transferInstructionCid\" `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv1-transferinstructionresultoutput-59824_>`_ (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_)

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"output\" `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_ `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv1-transferinstructionresultoutput-59824_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"receiverHoldingCids\" `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv1-transferinstructionresultoutput-59824_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"transferInstructionCid\" `TransferInstructionResult_Output <type-splice-api-token-transferinstructionv1-transferinstructionresultoutput-59824_>`_ (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_)

.. _type-splice-api-token-transferinstructionv1-transferinstructionstatus-36298:

**data** `TransferInstructionStatus <type-splice-api-token-transferinstructionv1-transferinstructionstatus-36298_>`_

  Status of a transfer instruction\.

  .. _constr-splice-api-token-transferinstructionv1-transferpendingreceiveracceptance-84710:

  `TransferPendingReceiverAcceptance <constr-splice-api-token-transferinstructionv1-transferpendingreceiveracceptance-84710_>`_

    The transfer is pending acceptance by the receiver\.

  .. _constr-splice-api-token-transferinstructionv1-transferpendinginternalworkflow-24806:

  `TransferPendingInternalWorkflow <constr-splice-api-token-transferinstructionv1-transferpendinginternalworkflow-24806_>`_

    The transfer is pending actions to be taken as part of registry internal workflows\.

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - pendingActions
         - `Map <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_ `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - The actions that a party could take to advance the transfer\.  This field can by used to inform wallet users whether they need to take an action or not\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `TransferInstructionStatus <type-splice-api-token-transferinstructionv1-transferinstructionstatus-36298_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `TransferInstructionStatus <type-splice-api-token-transferinstructionv1-transferinstructionstatus-36298_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"pendingActions\" `TransferInstructionStatus <type-splice-api-token-transferinstructionv1-transferinstructionstatus-36298_>`_ (`Map <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_ `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_)

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"status\" `TransferInstructionView <type-splice-api-token-transferinstructionv1-transferinstructionview-46309_>`_ `TransferInstructionStatus <type-splice-api-token-transferinstructionv1-transferinstructionstatus-36298_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"pendingActions\" `TransferInstructionStatus <type-splice-api-token-transferinstructionv1-transferinstructionstatus-36298_>`_ (`Map <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_ `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_)

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"status\" `TransferInstructionView <type-splice-api-token-transferinstructionv1-transferinstructionview-46309_>`_ `TransferInstructionStatus <type-splice-api-token-transferinstructionv1-transferinstructionstatus-36298_>`_

.. _type-splice-api-token-transferinstructionv1-transferinstructionview-46309:

**data** `TransferInstructionView <type-splice-api-token-transferinstructionv1-transferinstructionview-46309_>`_

  View for ``TransferInstruction``\.

  .. _constr-splice-api-token-transferinstructionv1-transferinstructionview-88808:

  `TransferInstructionView <constr-splice-api-token-transferinstructionv1-transferinstructionview-88808_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - originalInstructionCid
         - `Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_)
         - The contract id of the original transfer instruction contract\. Used by the wallet to track the lineage of transfer instructions through multiple steps\.  Only set if the registry evolves the transfer instruction in multiple steps\.
       * - transfer
         - `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_
         - The transfer specified by the transfer instruction\.
       * - status
         - `TransferInstructionStatus <type-splice-api-token-transferinstructionv1-transferinstructionstatus-36298_>`_
         - The status of the transfer instruction\.
       * - meta
         - Metadata
         - Additional metadata specific to the transfer instruction, used for extensibility; e\.g\., more detailed status information\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `TransferInstructionView <type-splice-api-token-transferinstructionv1-transferinstructionview-46309_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `TransferInstructionView <type-splice-api-token-transferinstructionv1-transferinstructionview-46309_>`_

  **instance** `HasFromAnyView <https://docs.daml.com/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ `TransferInstructionView <type-splice-api-token-transferinstructionv1-transferinstructionview-46309_>`_

  **instance** `HasInterfaceView <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ `TransferInstructionView <type-splice-api-token-transferinstructionv1-transferinstructionview-46309_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `TransferInstructionView <type-splice-api-token-transferinstructionv1-transferinstructionview-46309_>`_ Metadata

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"originalInstructionCid\" `TransferInstructionView <type-splice-api-token-transferinstructionv1-transferinstructionview-46309_>`_ (`Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_))

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"status\" `TransferInstructionView <type-splice-api-token-transferinstructionv1-transferinstructionview-46309_>`_ `TransferInstructionStatus <type-splice-api-token-transferinstructionv1-transferinstructionstatus-36298_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"transfer\" `TransferInstructionView <type-splice-api-token-transferinstructionv1-transferinstructionview-46309_>`_ `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `TransferInstructionView <type-splice-api-token-transferinstructionv1-transferinstructionview-46309_>`_ Metadata

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"originalInstructionCid\" `TransferInstructionView <type-splice-api-token-transferinstructionv1-transferinstructionview-46309_>`_ (`Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_))

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"status\" `TransferInstructionView <type-splice-api-token-transferinstructionv1-transferinstructionview-46309_>`_ `TransferInstructionStatus <type-splice-api-token-transferinstructionv1-transferinstructionstatus-36298_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"transfer\" `TransferInstructionView <type-splice-api-token-transferinstructionv1-transferinstructionview-46309_>`_ `Transfer <type-splice-api-token-transferinstructionv1-transfer-51973_>`_

Functions
---------

.. _function-splice-api-token-transferinstructionv1-transferinstructionacceptimpl-78274:

`transferInstruction_acceptImpl <function-splice-api-token-transferinstructionv1-transferinstructionacceptimpl-78274_>`_
  \: `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ \-\> `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ \-\> `TransferInstruction_Accept <type-splice-api-token-transferinstructionv1-transferinstructionaccept-36884_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

.. _function-splice-api-token-transferinstructionv1-transferinstructionrejectimpl-50311:

`transferInstruction_rejectImpl <function-splice-api-token-transferinstructionv1-transferinstructionrejectimpl-50311_>`_
  \: `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ \-\> `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ \-\> `TransferInstruction_Reject <type-splice-api-token-transferinstructionv1-transferinstructionreject-89645_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

.. _function-splice-api-token-transferinstructionv1-transferinstructionwithdrawimpl-10586:

`transferInstruction_withdrawImpl <function-splice-api-token-transferinstructionv1-transferinstructionwithdrawimpl-10586_>`_
  \: `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ \-\> `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ \-\> `TransferInstruction_Withdraw <type-splice-api-token-transferinstructionv1-transferinstructionwithdraw-43648_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

.. _function-splice-api-token-transferinstructionv1-transferinstructionupdateimpl-73329:

`transferInstruction_updateImpl <function-splice-api-token-transferinstructionv1-transferinstructionupdateimpl-73329_>`_
  \: `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ \-\> `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferInstruction <type-splice-api-token-transferinstructionv1-transferinstruction-69882_>`_ \-\> `TransferInstruction_Update <type-splice-api-token-transferinstructionv1-transferinstructionupdate-27423_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

.. _function-splice-api-token-transferinstructionv1-transferfactorytransferimpl-82483:

`transferFactory_transferImpl <function-splice-api-token-transferinstructionv1-transferfactorytransferimpl-82483_>`_
  \: `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_ \-\> `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_ \-\> `TransferFactory_Transfer <type-splice-api-token-transferinstructionv1-transferfactorytransfer-25365_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferInstructionResult <type-splice-api-token-transferinstructionv1-transferinstructionresult-24735_>`_

.. _function-splice-api-token-transferinstructionv1-transferfactorypublicfetchimpl-930:

`transferFactory_publicFetchImpl <function-splice-api-token-transferinstructionv1-transferfactorypublicfetchimpl-930_>`_
  \: `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_ \-\> `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `TransferFactory <type-splice-api-token-transferinstructionv1-transferfactory-55548_>`_ \-\> `TransferFactory_PublicFetch <type-splice-api-token-transferinstructionv1-transferfactorypublicfetch-56912_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `TransferFactoryView <type-splice-api-token-transferinstructionv1-transferfactoryview-36679_>`_
