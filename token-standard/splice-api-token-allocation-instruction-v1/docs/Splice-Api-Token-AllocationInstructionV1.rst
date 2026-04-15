.. _module-splice-api-token-allocationinstructionv1-81747:

Splice.Api.Token.AllocationInstructionV1
========================================

Interfaces to enable wallets to instruct the registry to create allocations\.

Interfaces
----------

.. _type-splice-api-token-allocationinstructionv1-allocationfactory-42588:

**interface** `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_

  Contracts implementing ``AllocationFactory`` are retrieved from the registry app and are
  used by the wallet to create allocation instructions (or allocations directly)\.

  **viewtype** `AllocationFactoryView <type-splice-api-token-allocationinstructionv1-allocationfactoryview-21751_>`_

  + .. _type-splice-api-token-allocationinstructionv1-allocationfactoryallocate-8885:

    **Choice** `AllocationFactory_Allocate <type-splice-api-token-allocationinstructionv1-allocationfactoryallocate-8885_>`_

    Generic choice for the sender's wallet to request the allocation of
    assets to a specific leg of a settlement\. It depends on the registry
    whether this results in the allocation being created directly
    or in an allocation instruction being created instead\.

    Controller\: (DA\.Internal\.Record\.getField @\"sender\" (DA\.Internal\.Record\.getField @\"transferLeg\" allocation))

    Returns\: `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - expectedAdmin
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The expected admin party issuing the factory\. Implementations MUST validate that this matches the admin of the factory\. Callers should ensure they get ``expectedAdmin`` from a trusted source, e\.g\., a read against their own participant\. That way they can ensure that it is safe to exercise a choice on a factory contract acquired from an untrusted source *provided* all vetted Daml packages only contain interface implementations that check the expected admin party\.
       * - allocation
         - AllocationSpecification
         - The allocation which should be created\.
       * - requestedAt
         - `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - The time at which the allocation was requested\.
       * - inputHoldingCids
         - \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - The holdings that SHOULD be used to fund the allocation\.  MAY be empty for registries that do not represent their holdings on\-ledger; or for registries that support automatic selection of holdings for allocations\.  If specified, then the successful allocation MUST archive all of these holdings, so that the execution of the allocation conflicts with any other allocations using these holdings\. Thereby allowing that the sender can use deliberate contention on holdings to prevent duplicate allocations\.
       * - extraArgs
         - ExtraArgs
         - Additional choice arguments\.

  + .. _type-splice-api-token-allocationinstructionv1-allocationfactorypublicfetch-20324:

    **Choice** `AllocationFactory_PublicFetch <type-splice-api-token-allocationinstructionv1-allocationfactorypublicfetch-20324_>`_

    Controller\: actor

    Returns\: `AllocationFactoryView <type-splice-api-token-allocationinstructionv1-allocationfactoryview-21751_>`_

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
         -

  + **Choice** Archive

    Controller\: Signatories of implementing template

    Returns\: ()

    (no fields)

  + **Method allocationFactory\_allocateImpl \:** `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_ \-\> `AllocationFactory_Allocate <type-splice-api-token-allocationinstructionv1-allocationfactoryallocate-8885_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

  + **Method allocationFactory\_publicFetchImpl \:** `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_ \-\> `AllocationFactory_PublicFetch <type-splice-api-token-allocationinstructionv1-allocationfactorypublicfetch-20324_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv1-allocationfactoryview-21751_>`_

.. _type-splice-api-token-allocationinstructionv1-allocationinstruction-29622:

**interface** `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_

  An interface for tracking the status of an allocation instruction,
  i\.e\., a request to a registry app to create an allocation\.

  Registries MAY evolve the allocation instruction in multiple steps\. They SHOULD
  do so using only the choices on this interface, so that wallets can reliably
  parse the transaction history and determine whether the creation of the allocation ultimately
  succeeded or failed\.

  **viewtype** `AllocationInstructionView <type-splice-api-token-allocationinstructionv1-allocationinstructionview-72001_>`_

  + .. _type-splice-api-token-allocationinstructionv1-allocationinstructionupdate-50223:

    **Choice** `AllocationInstruction_Update <type-splice-api-token-allocationinstructionv1-allocationinstructionupdate-50223_>`_

    Update the state of the allocation instruction\. Used by the registry to
    execute registry internal workflow steps that advance the state of the
    allocation instruction\. A reason may be communicated via the metadata\.

    Controller\: (DA\.Internal\.Record\.getField @\"admin\" (DA\.Internal\.Record\.getField @\"instrumentId\" (DA\.Internal\.Record\.getField @\"transferLeg\" (DA\.Internal\.Record\.getField @\"allocation\" (view this))))), extraActors

    Returns\: `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

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

  + .. _type-splice-api-token-allocationinstructionv1-allocationinstructionwithdraw-35988:

    **Choice** `AllocationInstruction_Withdraw <type-splice-api-token-allocationinstructionv1-allocationinstructionwithdraw-35988_>`_

    Withdraw the allocation instruction as the sender\.

    Controller\: (DA\.Internal\.Record\.getField @\"sender\" (DA\.Internal\.Record\.getField @\"transferLeg\" (DA\.Internal\.Record\.getField @\"allocation\" (view this))))

    Returns\: `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - extraArgs
         - ExtraArgs
         - Additional context required in order to exercise the choice\.

  + **Choice** Archive

    Controller\: Signatories of implementing template

    Returns\: ()

    (no fields)

  + **Method allocationInstruction\_updateImpl \:** `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_ \-\> `AllocationInstruction_Update <type-splice-api-token-allocationinstructionv1-allocationinstructionupdate-50223_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

  + **Method allocationInstruction\_withdrawImpl \:** `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_ \-\> `AllocationInstruction_Withdraw <type-splice-api-token-allocationinstructionv1-allocationinstructionwithdraw-35988_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

Data Types
----------

.. _type-splice-api-token-allocationinstructionv1-allocationfactoryview-21751:

**data** `AllocationFactoryView <type-splice-api-token-allocationinstructionv1-allocationfactoryview-21751_>`_

  View for ``AllocationFactory``\.

  .. _constr-splice-api-token-allocationinstructionv1-allocationfactoryview-66354:

  `AllocationFactoryView <constr-splice-api-token-allocationinstructionv1-allocationfactoryview-66354_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - admin
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The party representing the registry app that administers the instruments for which this allocation factory can be used\.
       * - meta
         - Metadata
         - Additional metadata specific to the allocation factory, used for extensibility\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv1-allocationfactoryview-21751_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv1-allocationfactoryview-21751_>`_

  **instance** HasMethod `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_ \"allocationFactory\_publicFetchImpl\" (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_ \-\> `AllocationFactory_PublicFetch <type-splice-api-token-allocationinstructionv1-allocationfactorypublicfetch-20324_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv1-allocationfactoryview-21751_>`_)

  **instance** `HasFromAnyView <https://docs.daml.com/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv1-allocationfactoryview-21751_>`_

  **instance** `HasInterfaceView <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv1-allocationfactoryview-21751_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"admin\" `AllocationFactoryView <type-splice-api-token-allocationinstructionv1-allocationfactoryview-21751_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `AllocationFactoryView <type-splice-api-token-allocationinstructionv1-allocationfactoryview-21751_>`_ Metadata

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"admin\" `AllocationFactoryView <type-splice-api-token-allocationinstructionv1-allocationfactoryview-21751_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `AllocationFactoryView <type-splice-api-token-allocationinstructionv1-allocationfactoryview-21751_>`_ Metadata

  **instance** `HasExercise <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_ `AllocationFactory_PublicFetch <type-splice-api-token-allocationinstructionv1-allocationfactorypublicfetch-20324_>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv1-allocationfactoryview-21751_>`_

  **instance** `HasExerciseGuarded <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_ `AllocationFactory_PublicFetch <type-splice-api-token-allocationinstructionv1-allocationfactorypublicfetch-20324_>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv1-allocationfactoryview-21751_>`_

  **instance** `HasFromAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_ `AllocationFactory_PublicFetch <type-splice-api-token-allocationinstructionv1-allocationfactorypublicfetch-20324_>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv1-allocationfactoryview-21751_>`_

  **instance** `HasToAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_ `AllocationFactory_PublicFetch <type-splice-api-token-allocationinstructionv1-allocationfactorypublicfetch-20324_>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv1-allocationfactoryview-21751_>`_

.. _type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943:

**data** `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

  The result of instructing an allocation or advancing the state of an allocation instruction\.

  .. _constr-splice-api-token-allocationinstructionv1-allocationinstructionresult-54130:

  `AllocationInstructionResult <constr-splice-api-token-allocationinstructionv1-allocationinstructionresult-54130_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - output
         - `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv1-allocationinstructionresultoutput-46212_>`_
         - The output of the step\.
       * - senderChangeCids
         - \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - New holdings owned by the sender created to return \"change\"\. Can be used by callers to batch creating or updating multiple allocation instructions in a single Daml transaction\.
       * - meta
         - Metadata
         - Additional metadata specific to the allocation instruction, used for extensibility; e\.g\., fees charged\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

  **instance** HasMethod `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_ \"allocationFactory\_allocateImpl\" (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_ \-\> `AllocationFactory_Allocate <type-splice-api-token-allocationinstructionv1-allocationfactoryallocate-8885_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_)

  **instance** HasMethod `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_ \"allocationInstruction\_updateImpl\" (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_ \-\> `AllocationInstruction_Update <type-splice-api-token-allocationinstructionv1-allocationinstructionupdate-50223_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_)

  **instance** HasMethod `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_ \"allocationInstruction\_withdrawImpl\" (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_ \-\> `AllocationInstruction_Withdraw <type-splice-api-token-allocationinstructionv1-allocationinstructionwithdraw-35988_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_)

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_ Metadata

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"output\" `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_ `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv1-allocationinstructionresultoutput-46212_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"senderChangeCids\" `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_ Metadata

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"output\" `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_ `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv1-allocationinstructionresultoutput-46212_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"senderChangeCids\" `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `HasExercise <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_ `AllocationFactory_Allocate <type-splice-api-token-allocationinstructionv1-allocationfactoryallocate-8885_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

  **instance** `HasExercise <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_ `AllocationInstruction_Update <type-splice-api-token-allocationinstructionv1-allocationinstructionupdate-50223_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

  **instance** `HasExercise <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_ `AllocationInstruction_Withdraw <type-splice-api-token-allocationinstructionv1-allocationinstructionwithdraw-35988_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

  **instance** `HasExerciseGuarded <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_ `AllocationFactory_Allocate <type-splice-api-token-allocationinstructionv1-allocationfactoryallocate-8885_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

  **instance** `HasExerciseGuarded <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_ `AllocationInstruction_Update <type-splice-api-token-allocationinstructionv1-allocationinstructionupdate-50223_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

  **instance** `HasExerciseGuarded <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_ `AllocationInstruction_Withdraw <type-splice-api-token-allocationinstructionv1-allocationinstructionwithdraw-35988_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

  **instance** `HasFromAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_ `AllocationFactory_Allocate <type-splice-api-token-allocationinstructionv1-allocationfactoryallocate-8885_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

  **instance** `HasFromAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_ `AllocationInstruction_Update <type-splice-api-token-allocationinstructionv1-allocationinstructionupdate-50223_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

  **instance** `HasFromAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_ `AllocationInstruction_Withdraw <type-splice-api-token-allocationinstructionv1-allocationinstructionwithdraw-35988_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

  **instance** `HasToAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_ `AllocationFactory_Allocate <type-splice-api-token-allocationinstructionv1-allocationfactoryallocate-8885_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

  **instance** `HasToAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_ `AllocationInstruction_Update <type-splice-api-token-allocationinstructionv1-allocationinstructionupdate-50223_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

  **instance** `HasToAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_ `AllocationInstruction_Withdraw <type-splice-api-token-allocationinstructionv1-allocationinstructionwithdraw-35988_>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

.. _type-splice-api-token-allocationinstructionv1-allocationinstructionresultoutput-46212:

**data** `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv1-allocationinstructionresultoutput-46212_>`_

  The output of instructing an allocation or advancing the state of an allocation instruction\.

  .. _constr-splice-api-token-allocationinstructionv1-allocationinstructionresultpending-58494:

  `AllocationInstructionResult_Pending <constr-splice-api-token-allocationinstructionv1-allocationinstructionresultpending-58494_>`_

    Use this result to communicate that the creation of the allocation is pending further steps\.

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - allocationInstructionCid
         - `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_
         - Contract id of the allocation instruction representing the pending state\.

  .. _constr-splice-api-token-allocationinstructionv1-allocationinstructionresultcompleted-89210:

  `AllocationInstructionResult_Completed <constr-splice-api-token-allocationinstructionv1-allocationinstructionresultcompleted-89210_>`_

    Use this result to communicate that the allocation was created\.

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - allocationCid
         - `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Allocation
         - The newly created allocation\.

  .. _constr-splice-api-token-allocationinstructionv1-allocationinstructionresultfailed-56799:

  `AllocationInstructionResult_Failed <constr-splice-api-token-allocationinstructionv1-allocationinstructionresultfailed-56799_>`_

    Use this result to communicate that the creation of the allocation did not succeed and
    all holdings reserved for funding the allocation have been released\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv1-allocationinstructionresultoutput-46212_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv1-allocationinstructionresultoutput-46212_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"allocationCid\" `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv1-allocationinstructionresultoutput-46212_>`_ (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Allocation)

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"allocationInstructionCid\" `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv1-allocationinstructionresultoutput-46212_>`_ (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_)

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"output\" `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_ `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv1-allocationinstructionresultoutput-46212_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"allocationCid\" `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv1-allocationinstructionresultoutput-46212_>`_ (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Allocation)

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"allocationInstructionCid\" `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv1-allocationinstructionresultoutput-46212_>`_ (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_)

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"output\" `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_ `AllocationInstructionResult_Output <type-splice-api-token-allocationinstructionv1-allocationinstructionresultoutput-46212_>`_

.. _type-splice-api-token-allocationinstructionv1-allocationinstructionview-72001:

**data** `AllocationInstructionView <type-splice-api-token-allocationinstructionv1-allocationinstructionview-72001_>`_

  View for ``AllocationInstruction``\.

  .. _constr-splice-api-token-allocationinstructionv1-allocationinstructionview-32140:

  `AllocationInstructionView <constr-splice-api-token-allocationinstructionv1-allocationinstructionview-32140_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - originalInstructionCid
         - `Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_)
         - The contract id of the original allocation instruction contract\. Used by the wallet to track the lineage of allocation instructions through multiple steps\.  Only set if the registry evolves the allocation instruction in multiple steps\.
       * - allocation
         - AllocationSpecification
         - The allocation that this instruction should create\.
       * - pendingActions
         - `Map <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_ `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - The pending actions to be taken by different actors to create the allocation\.  \^ This field can by used to report on the progress of registry specific workflows that are required to prepare the allocation\.
       * - requestedAt
         - `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - The time at which the allocation was requested\.
       * - inputHoldingCids
         - \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - The holdings to be used to fund the allocation\.  MAY be empty for registries that do not represent their holdings on\-ledger\.
       * - meta
         - Metadata
         - Additional metadata specific to the allocation instruction, used for extensibility; e\.g\., more detailed status information\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationInstructionView <type-splice-api-token-allocationinstructionv1-allocationinstructionview-72001_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationInstructionView <type-splice-api-token-allocationinstructionv1-allocationinstructionview-72001_>`_

  **instance** `HasFromAnyView <https://docs.daml.com/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_ `AllocationInstructionView <type-splice-api-token-allocationinstructionv1-allocationinstructionview-72001_>`_

  **instance** `HasInterfaceView <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_ `AllocationInstructionView <type-splice-api-token-allocationinstructionv1-allocationinstructionview-72001_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"allocation\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv1-allocationinstructionview-72001_>`_ AllocationSpecification

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"inputHoldingCids\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv1-allocationinstructionview-72001_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv1-allocationinstructionview-72001_>`_ Metadata

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"originalInstructionCid\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv1-allocationinstructionview-72001_>`_ (`Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_))

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"pendingActions\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv1-allocationinstructionview-72001_>`_ (`Map <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_ `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_)

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"requestedAt\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv1-allocationinstructionview-72001_>`_ `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"allocation\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv1-allocationinstructionview-72001_>`_ AllocationSpecification

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"inputHoldingCids\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv1-allocationinstructionview-72001_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv1-allocationinstructionview-72001_>`_ Metadata

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"originalInstructionCid\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv1-allocationinstructionview-72001_>`_ (`Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_))

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"pendingActions\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv1-allocationinstructionview-72001_>`_ (`Map <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-map-90052>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_ `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_)

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"requestedAt\" `AllocationInstructionView <type-splice-api-token-allocationinstructionv1-allocationinstructionview-72001_>`_ `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

Functions
---------

.. _function-splice-api-token-allocationinstructionv1-allocationinstructionwithdrawimpl-26170:

`allocationInstruction_withdrawImpl <function-splice-api-token-allocationinstructionv1-allocationinstructionwithdrawimpl-26170_>`_
  \: `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_ \-\> `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_ \-\> `AllocationInstruction_Withdraw <type-splice-api-token-allocationinstructionv1-allocationinstructionwithdraw-35988_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

.. _function-splice-api-token-allocationinstructionv1-allocationinstructionupdateimpl-49061:

`allocationInstruction_updateImpl <function-splice-api-token-allocationinstructionv1-allocationinstructionupdateimpl-49061_>`_
  \: `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_ \-\> `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationInstruction <type-splice-api-token-allocationinstructionv1-allocationinstruction-29622_>`_ \-\> `AllocationInstruction_Update <type-splice-api-token-allocationinstructionv1-allocationinstructionupdate-50223_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

.. _function-splice-api-token-allocationinstructionv1-allocationfactoryallocateimpl-1231:

`allocationFactory_allocateImpl <function-splice-api-token-allocationinstructionv1-allocationfactoryallocateimpl-1231_>`_
  \: `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_ \-\> `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_ \-\> `AllocationFactory_Allocate <type-splice-api-token-allocationinstructionv1-allocationfactoryallocate-8885_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationInstructionResult <type-splice-api-token-allocationinstructionv1-allocationinstructionresult-30943_>`_

.. _function-splice-api-token-allocationinstructionv1-allocationfactorypublicfetchimpl-77778:

`allocationFactory_publicFetchImpl <function-splice-api-token-allocationinstructionv1-allocationfactorypublicfetchimpl-77778_>`_
  \: `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_ \-\> `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AllocationFactory <type-splice-api-token-allocationinstructionv1-allocationfactory-42588_>`_ \-\> `AllocationFactory_PublicFetch <type-splice-api-token-allocationinstructionv1-allocationfactorypublicfetch-20324_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `AllocationFactoryView <type-splice-api-token-allocationinstructionv1-allocationfactoryview-21751_>`_
