.. _module-splice-api-token-allocationv1-39050:

Splice.Api.Token.AllocationV1
=============================

This module defines the ``Allocation`` interface and supporting types\.

Contracts implementing the ``Allocation`` interface represent a reservation of
assets to transfer them as part of an atomic on\-ledger settlement requested
by an app\.

Interfaces
----------

.. _type-splice-api-token-allocationv1-allocation-9928:

**interface** `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_

  A contract representing an allocation of some amount of aasset holdings to
  a specific leg of a settlement\.

  **viewtype** `AllocationView <type-splice-api-token-allocationv1-allocationview-9103_>`_

  + .. _type-splice-api-token-allocationv1-allocationcancel-25504:

    **Choice** `Allocation_Cancel <type-splice-api-token-allocationv1-allocationcancel-25504_>`_

    Cancel the allocation\. Requires authorization from sender, receiver, and
    executor\.

    Typically this authorization is granted by sender and receiver to the
    executor as part of the contract coordinating the settlement, so that
    that the executor can release the allocated assets early in case the
    settlement is aborted or it has definitely failed\.

    Controller\: allocationControllers (view this)

    Returns\: `Allocation_CancelResult <type-splice-api-token-allocationv1-allocationcancelresult-26037_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - extraArgs
         - ExtraArgs
         - Additional context required in order to exercise the choice\.

  + .. _type-splice-api-token-allocationv1-allocationexecutetransfer-74529:

    **Choice** `Allocation_ExecuteTransfer <type-splice-api-token-allocationv1-allocationexecutetransfer-74529_>`_

    Execute the transfer of the allocated assets\. Intended to be used to execute the settlement\.
    This choice SHOULD succeed provided the ``settlement.settleBefore`` deadline has not yet passed\.

    Controller\: allocationControllers (view this)

    Returns\: `Allocation_ExecuteTransferResult <type-splice-api-token-allocationv1-allocationexecutetransferresult-74160_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - extraArgs
         - ExtraArgs
         - Additional context required in order to exercise the choice\.

  + .. _type-splice-api-token-allocationv1-allocationwithdraw-60458:

    **Choice** `Allocation_Withdraw <type-splice-api-token-allocationv1-allocationwithdraw-60458_>`_

    Withdraw the allocated assets\. Used by the sender to withdraw the assets before settlement
    was completed\. This SHOULD not fail settlement if the sender has still time to allocate the
    assets again; i\.e\., the ``settlement.allocateBefore`` deadline has not yet passed\.

    Controller\: (DA\.Internal\.Record\.getField @\"sender\" (DA\.Internal\.Record\.getField @\"transferLeg\" (DA\.Internal\.Record\.getField @\"allocation\" (view this))))

    Returns\: `Allocation_WithdrawResult <type-splice-api-token-allocationv1-allocationwithdrawresult-40879_>`_

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

  + **Method allocation\_cancelImpl \:** `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ \-\> `Allocation_Cancel <type-splice-api-token-allocationv1-allocationcancel-25504_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `Allocation_CancelResult <type-splice-api-token-allocationv1-allocationcancelresult-26037_>`_

  + **Method allocation\_executeTransferImpl \:** `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ \-\> `Allocation_ExecuteTransfer <type-splice-api-token-allocationv1-allocationexecutetransfer-74529_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `Allocation_ExecuteTransferResult <type-splice-api-token-allocationv1-allocationexecutetransferresult-74160_>`_

  + **Method allocation\_withdrawImpl \:** `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ \-\> `Allocation_Withdraw <type-splice-api-token-allocationv1-allocationwithdraw-60458_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `Allocation_WithdrawResult <type-splice-api-token-allocationv1-allocationwithdrawresult-40879_>`_

Data Types
----------

.. _type-splice-api-token-allocationv1-allocationspecification-94148:

**data** `AllocationSpecification <type-splice-api-token-allocationv1-allocationspecification-94148_>`_

  The specification of an allocation of assets to a specific leg of a settlement\.

  In contrast to an ``AllocationView`` this just specifies what should be allocated,
  but not the holdings that are backing the allocation\.

  .. _constr-splice-api-token-allocationv1-allocationspecification-66543:

  `AllocationSpecification <constr-splice-api-token-allocationv1-allocationspecification-66543_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - settlement
         - `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_
         - The settlement for whose execution the assets are being allocated\.
       * - transferLegId
         - `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - A unique identifer for the transfer leg within the settlement\.
       * - transferLeg
         - `TransferLeg <type-splice-api-token-allocationv1-transferleg-71662_>`_
         - The transfer for which the assets are being allocated\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationSpecification <type-splice-api-token-allocationv1-allocationspecification-94148_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationSpecification <type-splice-api-token-allocationv1-allocationspecification-94148_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"allocation\" `AllocationView <type-splice-api-token-allocationv1-allocationview-9103_>`_ `AllocationSpecification <type-splice-api-token-allocationv1-allocationspecification-94148_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"settlement\" `AllocationSpecification <type-splice-api-token-allocationv1-allocationspecification-94148_>`_ `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"transferLeg\" `AllocationSpecification <type-splice-api-token-allocationv1-allocationspecification-94148_>`_ `TransferLeg <type-splice-api-token-allocationv1-transferleg-71662_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"transferLegId\" `AllocationSpecification <type-splice-api-token-allocationv1-allocationspecification-94148_>`_ `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"allocation\" `AllocationView <type-splice-api-token-allocationv1-allocationview-9103_>`_ `AllocationSpecification <type-splice-api-token-allocationv1-allocationspecification-94148_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"settlement\" `AllocationSpecification <type-splice-api-token-allocationv1-allocationspecification-94148_>`_ `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"transferLeg\" `AllocationSpecification <type-splice-api-token-allocationv1-allocationspecification-94148_>`_ `TransferLeg <type-splice-api-token-allocationv1-transferleg-71662_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"transferLegId\" `AllocationSpecification <type-splice-api-token-allocationv1-allocationspecification-94148_>`_ `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

.. _type-splice-api-token-allocationv1-allocationview-9103:

**data** `AllocationView <type-splice-api-token-allocationv1-allocationview-9103_>`_

  View of a funded allocation of assets to a specific leg of a settlement\.

  .. _constr-splice-api-token-allocationv1-allocationview-89090:

  `AllocationView <constr-splice-api-token-allocationv1-allocationview-89090_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - allocation
         - `AllocationSpecification <type-splice-api-token-allocationv1-allocationspecification-94148_>`_
         - The settlement for whose execution the assets are being allocated\.
       * - holdingCids
         - \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - The holdings that are backing this allocation\.  Provided so that that wallets can correlate the allocation with the holdings\.  MAY be empty for registries that do not represent their holdings on\-ledger\.
       * - meta
         - Metadata
         - Additional metadata specific to the allocation, used for extensibility\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AllocationView <type-splice-api-token-allocationv1-allocationview-9103_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AllocationView <type-splice-api-token-allocationv1-allocationview-9103_>`_

  **instance** `HasFromAnyView <https://docs.daml.com/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ `AllocationView <type-splice-api-token-allocationv1-allocationview-9103_>`_

  **instance** `HasInterfaceView <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ `AllocationView <type-splice-api-token-allocationv1-allocationview-9103_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"allocation\" `AllocationView <type-splice-api-token-allocationv1-allocationview-9103_>`_ `AllocationSpecification <type-splice-api-token-allocationv1-allocationspecification-94148_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"holdingCids\" `AllocationView <type-splice-api-token-allocationv1-allocationview-9103_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `AllocationView <type-splice-api-token-allocationv1-allocationview-9103_>`_ Metadata

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"allocation\" `AllocationView <type-splice-api-token-allocationv1-allocationview-9103_>`_ `AllocationSpecification <type-splice-api-token-allocationv1-allocationspecification-94148_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"holdingCids\" `AllocationView <type-splice-api-token-allocationv1-allocationview-9103_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `AllocationView <type-splice-api-token-allocationv1-allocationview-9103_>`_ Metadata

.. _type-splice-api-token-allocationv1-allocationcancelresult-26037:

**data** `Allocation_CancelResult <type-splice-api-token-allocationv1-allocationcancelresult-26037_>`_

  The result of the ``Allocation_Cancel`` choice\.

  .. _constr-splice-api-token-allocationv1-allocationcancelresult-74846:

  `Allocation_CancelResult <constr-splice-api-token-allocationv1-allocationcancelresult-74846_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - senderHoldingCids
         - \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - The holdings that were released back to the sender\.
       * - meta
         - Metadata
         - Additional metadata specific to the allocation, used for extensibility\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `Allocation_CancelResult <type-splice-api-token-allocationv1-allocationcancelresult-26037_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `Allocation_CancelResult <type-splice-api-token-allocationv1-allocationcancelresult-26037_>`_

  **instance** HasMethod `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ \"allocation\_cancelImpl\" (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ \-\> `Allocation_Cancel <type-splice-api-token-allocationv1-allocationcancel-25504_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `Allocation_CancelResult <type-splice-api-token-allocationv1-allocationcancelresult-26037_>`_)

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `Allocation_CancelResult <type-splice-api-token-allocationv1-allocationcancelresult-26037_>`_ Metadata

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"senderHoldingCids\" `Allocation_CancelResult <type-splice-api-token-allocationv1-allocationcancelresult-26037_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `Allocation_CancelResult <type-splice-api-token-allocationv1-allocationcancelresult-26037_>`_ Metadata

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"senderHoldingCids\" `Allocation_CancelResult <type-splice-api-token-allocationv1-allocationcancelresult-26037_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `HasExercise <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ `Allocation_Cancel <type-splice-api-token-allocationv1-allocationcancel-25504_>`_ `Allocation_CancelResult <type-splice-api-token-allocationv1-allocationcancelresult-26037_>`_

  **instance** `HasExerciseGuarded <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ `Allocation_Cancel <type-splice-api-token-allocationv1-allocationcancel-25504_>`_ `Allocation_CancelResult <type-splice-api-token-allocationv1-allocationcancelresult-26037_>`_

  **instance** `HasFromAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ `Allocation_Cancel <type-splice-api-token-allocationv1-allocationcancel-25504_>`_ `Allocation_CancelResult <type-splice-api-token-allocationv1-allocationcancelresult-26037_>`_

  **instance** `HasToAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ `Allocation_Cancel <type-splice-api-token-allocationv1-allocationcancel-25504_>`_ `Allocation_CancelResult <type-splice-api-token-allocationv1-allocationcancelresult-26037_>`_

.. _type-splice-api-token-allocationv1-allocationexecutetransferresult-74160:

**data** `Allocation_ExecuteTransferResult <type-splice-api-token-allocationv1-allocationexecutetransferresult-74160_>`_

  The result of the ``Allocation_ExecuteTransfer`` choice\.

  .. _constr-splice-api-token-allocationv1-allocationexecutetransferresult-50341:

  `Allocation_ExecuteTransferResult <constr-splice-api-token-allocationv1-allocationexecutetransferresult-50341_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - senderHoldingCids
         - \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - The holdings that were created for the sender\. Can be used to return \"change\" to the sender if required\.
       * - receiverHoldingCids
         - \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - The holdings that were created for the receiver\.
       * - meta
         - Metadata
         - Additional metadata specific to the transfer instruction, used for extensibility\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `Allocation_ExecuteTransferResult <type-splice-api-token-allocationv1-allocationexecutetransferresult-74160_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `Allocation_ExecuteTransferResult <type-splice-api-token-allocationv1-allocationexecutetransferresult-74160_>`_

  **instance** HasMethod `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ \"allocation\_executeTransferImpl\" (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ \-\> `Allocation_ExecuteTransfer <type-splice-api-token-allocationv1-allocationexecutetransfer-74529_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `Allocation_ExecuteTransferResult <type-splice-api-token-allocationv1-allocationexecutetransferresult-74160_>`_)

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `Allocation_ExecuteTransferResult <type-splice-api-token-allocationv1-allocationexecutetransferresult-74160_>`_ Metadata

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"receiverHoldingCids\" `Allocation_ExecuteTransferResult <type-splice-api-token-allocationv1-allocationexecutetransferresult-74160_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"senderHoldingCids\" `Allocation_ExecuteTransferResult <type-splice-api-token-allocationv1-allocationexecutetransferresult-74160_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `Allocation_ExecuteTransferResult <type-splice-api-token-allocationv1-allocationexecutetransferresult-74160_>`_ Metadata

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"receiverHoldingCids\" `Allocation_ExecuteTransferResult <type-splice-api-token-allocationv1-allocationexecutetransferresult-74160_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"senderHoldingCids\" `Allocation_ExecuteTransferResult <type-splice-api-token-allocationv1-allocationexecutetransferresult-74160_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `HasExercise <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ `Allocation_ExecuteTransfer <type-splice-api-token-allocationv1-allocationexecutetransfer-74529_>`_ `Allocation_ExecuteTransferResult <type-splice-api-token-allocationv1-allocationexecutetransferresult-74160_>`_

  **instance** `HasExerciseGuarded <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ `Allocation_ExecuteTransfer <type-splice-api-token-allocationv1-allocationexecutetransfer-74529_>`_ `Allocation_ExecuteTransferResult <type-splice-api-token-allocationv1-allocationexecutetransferresult-74160_>`_

  **instance** `HasFromAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ `Allocation_ExecuteTransfer <type-splice-api-token-allocationv1-allocationexecutetransfer-74529_>`_ `Allocation_ExecuteTransferResult <type-splice-api-token-allocationv1-allocationexecutetransferresult-74160_>`_

  **instance** `HasToAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ `Allocation_ExecuteTransfer <type-splice-api-token-allocationv1-allocationexecutetransfer-74529_>`_ `Allocation_ExecuteTransferResult <type-splice-api-token-allocationv1-allocationexecutetransferresult-74160_>`_

.. _type-splice-api-token-allocationv1-allocationwithdrawresult-40879:

**data** `Allocation_WithdrawResult <type-splice-api-token-allocationv1-allocationwithdrawresult-40879_>`_

  The result of the ``Allocation_Withdraw`` choice\.

  .. _constr-splice-api-token-allocationv1-allocationwithdrawresult-86620:

  `Allocation_WithdrawResult <constr-splice-api-token-allocationv1-allocationwithdrawresult-86620_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - senderHoldingCids
         - \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]
         - The holdings that were released back to the sender\.
       * - meta
         - Metadata
         - Additional metadata specific to the allocation, used for extensibility\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `Allocation_WithdrawResult <type-splice-api-token-allocationv1-allocationwithdrawresult-40879_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `Allocation_WithdrawResult <type-splice-api-token-allocationv1-allocationwithdrawresult-40879_>`_

  **instance** HasMethod `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ \"allocation\_withdrawImpl\" (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ \-\> `Allocation_Withdraw <type-splice-api-token-allocationv1-allocationwithdraw-60458_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `Allocation_WithdrawResult <type-splice-api-token-allocationv1-allocationwithdrawresult-40879_>`_)

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `Allocation_WithdrawResult <type-splice-api-token-allocationv1-allocationwithdrawresult-40879_>`_ Metadata

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"senderHoldingCids\" `Allocation_WithdrawResult <type-splice-api-token-allocationv1-allocationwithdrawresult-40879_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `Allocation_WithdrawResult <type-splice-api-token-allocationv1-allocationwithdrawresult-40879_>`_ Metadata

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"senderHoldingCids\" `Allocation_WithdrawResult <type-splice-api-token-allocationv1-allocationwithdrawresult-40879_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ Holding\]

  **instance** `HasExercise <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ `Allocation_Withdraw <type-splice-api-token-allocationv1-allocationwithdraw-60458_>`_ `Allocation_WithdrawResult <type-splice-api-token-allocationv1-allocationwithdrawresult-40879_>`_

  **instance** `HasExerciseGuarded <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ `Allocation_Withdraw <type-splice-api-token-allocationv1-allocationwithdraw-60458_>`_ `Allocation_WithdrawResult <type-splice-api-token-allocationv1-allocationwithdrawresult-40879_>`_

  **instance** `HasFromAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ `Allocation_Withdraw <type-splice-api-token-allocationv1-allocationwithdraw-60458_>`_ `Allocation_WithdrawResult <type-splice-api-token-allocationv1-allocationwithdrawresult-40879_>`_

  **instance** `HasToAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ `Allocation_Withdraw <type-splice-api-token-allocationv1-allocationwithdraw-60458_>`_ `Allocation_WithdrawResult <type-splice-api-token-allocationv1-allocationwithdrawresult-40879_>`_

.. _type-splice-api-token-allocationv1-reference-53456:

**data** `Reference <type-splice-api-token-allocationv1-reference-53456_>`_

  A generic type to refer to data defined within an app\.

  .. _constr-splice-api-token-allocationv1-reference-11427:

  `Reference <constr-splice-api-token-allocationv1-reference-11427_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - id
         - `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - The key that identifies the data\. Can be set to the empty string if the contract\-id is provided and is sufficient\.
       * - cid
         - `Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ AnyContractId
         - Optional contract\-id to use for referring to contracts\.  This field is there for technical reasons, as contract\-ids cannot be converted to text from within Daml, which is due to their full textual representation being only known after transactions have been prepared\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `Reference <type-splice-api-token-allocationv1-reference-53456_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `Reference <type-splice-api-token-allocationv1-reference-53456_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"cid\" `Reference <type-splice-api-token-allocationv1-reference-53456_>`_ (`Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ AnyContractId)

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"id\" `Reference <type-splice-api-token-allocationv1-reference-53456_>`_ `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"settlementRef\" `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_ `Reference <type-splice-api-token-allocationv1-reference-53456_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"cid\" `Reference <type-splice-api-token-allocationv1-reference-53456_>`_ (`Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ AnyContractId)

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"id\" `Reference <type-splice-api-token-allocationv1-reference-53456_>`_ `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"settlementRef\" `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_ `Reference <type-splice-api-token-allocationv1-reference-53456_>`_

.. _type-splice-api-token-allocationv1-settlementinfo-4367:

**data** `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_

  The minimal set of information about a settlement that an app would like to execute\.

  .. _constr-splice-api-token-allocationv1-settlementinfo-25294:

  `SettlementInfo <constr-splice-api-token-allocationv1-settlementinfo-25294_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - executor
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The party that is responsible for executing the settlement\.
       * - settlementRef
         - `Reference <type-splice-api-token-allocationv1-reference-53456_>`_
         - Reference to the settlement that app would like to execute\.
       * - requestedAt
         - `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - When the settlement was requested\. Provided for display and debugging purposes, but SHOULD be in the past\.
       * - allocateBefore
         - `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - Until when (exclusive) the senders are given time to allocate their assets\. This field has a particular relevance with respect to instrument versioning / corporate actions, in that the settlement pertains to the instrument version resulting from the processing of all corporate actions falling strictly before the ``allocateBefore`` time\.
       * - settleBefore
         - `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_
         - Until when (exclusive) the executor is given time to execute the settlement\.  SHOULD be strictly after ``allocateBefore``\.
       * - meta
         - Metadata
         - Additional metadata about the settlement, used for extensibility\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"allocateBefore\" `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_ `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"executor\" `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_ Metadata

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"requestedAt\" `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_ `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"settleBefore\" `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_ `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"settlement\" `AllocationSpecification <type-splice-api-token-allocationv1-allocationspecification-94148_>`_ `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"settlementRef\" `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_ `Reference <type-splice-api-token-allocationv1-reference-53456_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"allocateBefore\" `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_ `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"executor\" `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_ Metadata

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"requestedAt\" `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_ `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"settleBefore\" `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_ `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"settlement\" `AllocationSpecification <type-splice-api-token-allocationv1-allocationspecification-94148_>`_ `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"settlementRef\" `SettlementInfo <type-splice-api-token-allocationv1-settlementinfo-4367_>`_ `Reference <type-splice-api-token-allocationv1-reference-53456_>`_

.. _type-splice-api-token-allocationv1-transferleg-71662:

**data** `TransferLeg <type-splice-api-token-allocationv1-transferleg-71662_>`_

  A specification of a transfer of holdings between two parties for the
  purpose of a settlement, which often requires the atomic execution of multiple legs\.

  .. _constr-splice-api-token-allocationv1-transferleg-72717:

  `TransferLeg <constr-splice-api-token-allocationv1-transferleg-72717_>`_

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
       * - meta
         - Metadata
         - Additional metadata about the transfer leg, used for extensibility\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `TransferLeg <type-splice-api-token-allocationv1-transferleg-71662_>`_

  **instance** `Ord <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-ord-6395>`_ `TransferLeg <type-splice-api-token-allocationv1-transferleg-71662_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `TransferLeg <type-splice-api-token-allocationv1-transferleg-71662_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"amount\" `TransferLeg <type-splice-api-token-allocationv1-transferleg-71662_>`_ `Decimal <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"instrumentId\" `TransferLeg <type-splice-api-token-allocationv1-transferleg-71662_>`_ InstrumentId

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `TransferLeg <type-splice-api-token-allocationv1-transferleg-71662_>`_ Metadata

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"receiver\" `TransferLeg <type-splice-api-token-allocationv1-transferleg-71662_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"sender\" `TransferLeg <type-splice-api-token-allocationv1-transferleg-71662_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"transferLeg\" `AllocationSpecification <type-splice-api-token-allocationv1-allocationspecification-94148_>`_ `TransferLeg <type-splice-api-token-allocationv1-transferleg-71662_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"amount\" `TransferLeg <type-splice-api-token-allocationv1-transferleg-71662_>`_ `Decimal <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"instrumentId\" `TransferLeg <type-splice-api-token-allocationv1-transferleg-71662_>`_ InstrumentId

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `TransferLeg <type-splice-api-token-allocationv1-transferleg-71662_>`_ Metadata

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"receiver\" `TransferLeg <type-splice-api-token-allocationv1-transferleg-71662_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"sender\" `TransferLeg <type-splice-api-token-allocationv1-transferleg-71662_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"transferLeg\" `AllocationSpecification <type-splice-api-token-allocationv1-allocationspecification-94148_>`_ `TransferLeg <type-splice-api-token-allocationv1-transferleg-71662_>`_

Functions
---------

.. _function-splice-api-token-allocationv1-allocationcontrollers-10222:

`allocationControllers <function-splice-api-token-allocationv1-allocationcontrollers-10222_>`_
  \: `AllocationView <type-splice-api-token-allocationv1-allocationview-9103_>`_ \-\> \[`Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_\]

  Convenience function to refer to the union of sender, receiver, and
  executor of the settlement, which jointly control the execution of the
  allocation\.

.. _function-splice-api-token-allocationv1-allocationexecutetransferimpl-90251:

`allocation_executeTransferImpl <function-splice-api-token-allocationv1-allocationexecutetransferimpl-90251_>`_
  \: `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ \-\> `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ \-\> `Allocation_ExecuteTransfer <type-splice-api-token-allocationv1-allocationexecutetransfer-74529_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `Allocation_ExecuteTransferResult <type-splice-api-token-allocationv1-allocationexecutetransferresult-74160_>`_

.. _function-splice-api-token-allocationv1-allocationcancelimpl-12334:

`allocation_cancelImpl <function-splice-api-token-allocationv1-allocationcancelimpl-12334_>`_
  \: `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ \-\> `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ \-\> `Allocation_Cancel <type-splice-api-token-allocationv1-allocationcancel-25504_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `Allocation_CancelResult <type-splice-api-token-allocationv1-allocationcancelresult-26037_>`_

.. _function-splice-api-token-allocationv1-allocationwithdrawimpl-40108:

`allocation_withdrawImpl <function-splice-api-token-allocationv1-allocationwithdrawimpl-40108_>`_
  \: `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ \-\> `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `Allocation <type-splice-api-token-allocationv1-allocation-9928_>`_ \-\> `Allocation_Withdraw <type-splice-api-token-allocationv1-allocationwithdraw-60458_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `Allocation_WithdrawResult <type-splice-api-token-allocationv1-allocationwithdrawresult-40879_>`_
