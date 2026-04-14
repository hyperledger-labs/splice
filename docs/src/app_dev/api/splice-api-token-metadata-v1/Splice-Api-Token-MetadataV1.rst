.. _module-splice-api-token-metadatav1-34807:

Splice.Api.Token.MetadataV1
===========================

Types and interfaces for retrieving metadata about tokens\.

Interfaces
----------

.. _type-splice-api-token-metadatav1-anycontract-39598:

**interface** `AnyContract <type-splice-api-token-metadatav1-anycontract-39598_>`_

  Interface used to represent arbitrary contracts\.
  Note that this is not expected to be implemented by any template,
  so it should only be used in the form ``ContractId AnyContract`` and through ``coerceContractId``
  but not through any of the interface functions like ``fetchFromInterface`` that check whether the template actually implements the interface\.

  **viewtype** `AnyContractView <type-splice-api-token-metadatav1-anycontractview-55353_>`_

  + **Choice** Archive

    Controller\: Signatories of implementing template

    Returns\: ()

    (no fields)


Data Types
----------

.. _type-splice-api-token-metadatav1-anycontractid-4875:

**type** `AnyContractId <type-splice-api-token-metadatav1-anycontractid-4875_>`_
  \= `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `AnyContract <type-splice-api-token-metadatav1-anycontract-39598_>`_

  A reference to some contract id\. Use ``coerceContractId`` to convert from and to this type\.

.. _type-splice-api-token-metadatav1-anycontractview-55353:

**data** `AnyContractView <type-splice-api-token-metadatav1-anycontractview-55353_>`_

  Not used\. See the ``AnyContract`` interface for more information\.

  .. _constr-splice-api-token-metadatav1-anycontractview-50558:

  `AnyContractView <constr-splice-api-token-metadatav1-anycontractview-50558_>`_

    (no fields)

  **instance** `HasFromAnyView <https://docs.daml.com/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `AnyContract <type-splice-api-token-metadatav1-anycontract-39598_>`_ `AnyContractView <type-splice-api-token-metadatav1-anycontractview-55353_>`_

  **instance** `HasInterfaceView <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `AnyContract <type-splice-api-token-metadatav1-anycontract-39598_>`_ `AnyContractView <type-splice-api-token-metadatav1-anycontractview-55353_>`_

.. _type-splice-api-token-metadatav1-anyvalue-34700:

**data** `AnyValue <type-splice-api-token-metadatav1-anyvalue-34700_>`_

  A generic representation of serializable Daml values\.

  Used to pass arbitrary data across interface boundaries\. For example to pass
  data from an an app backend to an interface choice implementation\.

  .. _constr-splice-api-token-metadatav1-avtext-20580:

  `AV_Text <constr-splice-api-token-metadatav1-avtext-20580_>`_ `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_


  .. _constr-splice-api-token-metadatav1-avint-52425:

  `AV_Int <constr-splice-api-token-metadatav1-avint-52425_>`_ `Int <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-int-37261>`_


  .. _constr-splice-api-token-metadatav1-avdecimal-71267:

  `AV_Decimal <constr-splice-api-token-metadatav1-avdecimal-71267_>`_ `Decimal <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_


  .. _constr-splice-api-token-metadatav1-avbool-76457:

  `AV_Bool <constr-splice-api-token-metadatav1-avbool-76457_>`_ `Bool <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-bool-66265>`_


  .. _constr-splice-api-token-metadatav1-avdate-46205:

  `AV_Date <constr-splice-api-token-metadatav1-avdate-46205_>`_ `Date <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-date-32253>`_


  .. _constr-splice-api-token-metadatav1-avtime-30398:

  `AV_Time <constr-splice-api-token-metadatav1-avtime-30398_>`_ `Time <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-time-63886>`_


  .. _constr-splice-api-token-metadatav1-avreltime-31008:

  `AV_RelTime <constr-splice-api-token-metadatav1-avreltime-31008_>`_ `RelTime <https://docs.daml.com/daml/stdlib/DA-Time.html#type-da-time-types-reltime-23082>`_


  .. _constr-splice-api-token-metadatav1-avparty-78344:

  `AV_Party <constr-splice-api-token-metadatav1-avparty-78344_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_


  .. _constr-splice-api-token-metadatav1-avcontractid-3242:

  `AV_ContractId <constr-splice-api-token-metadatav1-avcontractid-3242_>`_ `AnyContractId <type-splice-api-token-metadatav1-anycontractid-4875_>`_


  .. _constr-splice-api-token-metadatav1-avlist-50505:

  `AV_List <constr-splice-api-token-metadatav1-avlist-50505_>`_ \[`AnyValue <type-splice-api-token-metadatav1-anyvalue-34700_>`_\]


  .. _constr-splice-api-token-metadatav1-avmap-39932:

  `AV_Map <constr-splice-api-token-metadatav1-avmap-39932_>`_ (`TextMap <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ `AnyValue <type-splice-api-token-metadatav1-anyvalue-34700_>`_)


  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AnyValue <type-splice-api-token-metadatav1-anyvalue-34700_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AnyValue <type-splice-api-token-metadatav1-anyvalue-34700_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"values\" `ChoiceContext <type-splice-api-token-metadatav1-choicecontext-5566_>`_ (`TextMap <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ `AnyValue <type-splice-api-token-metadatav1-anyvalue-34700_>`_)

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"values\" `ChoiceContext <type-splice-api-token-metadatav1-choicecontext-5566_>`_ (`TextMap <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ `AnyValue <type-splice-api-token-metadatav1-anyvalue-34700_>`_)

.. _type-splice-api-token-metadatav1-choicecontext-5566:

**data** `ChoiceContext <type-splice-api-token-metadatav1-choicecontext-5566_>`_

  A type for passing extra data from an app's backends to the choices of that app
  exercised in commands submitted by app users\.

  .. _constr-splice-api-token-metadatav1-choicecontext-36049:

  `ChoiceContext <constr-splice-api-token-metadatav1-choicecontext-36049_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - values
         - `TextMap <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ `AnyValue <type-splice-api-token-metadatav1-anyvalue-34700_>`_
         - The values passed in by the app backend to the choice\. The keys are considered internal to the app and should not be read by third\-party code\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `ChoiceContext <type-splice-api-token-metadatav1-choicecontext-5566_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `ChoiceContext <type-splice-api-token-metadatav1-choicecontext-5566_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"context\" `ExtraArgs <type-splice-api-token-metadatav1-extraargs-90869_>`_ `ChoiceContext <type-splice-api-token-metadatav1-choicecontext-5566_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"values\" `ChoiceContext <type-splice-api-token-metadatav1-choicecontext-5566_>`_ (`TextMap <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ `AnyValue <type-splice-api-token-metadatav1-anyvalue-34700_>`_)

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"context\" `ExtraArgs <type-splice-api-token-metadatav1-extraargs-90869_>`_ `ChoiceContext <type-splice-api-token-metadatav1-choicecontext-5566_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"values\" `ChoiceContext <type-splice-api-token-metadatav1-choicecontext-5566_>`_ (`TextMap <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ `AnyValue <type-splice-api-token-metadatav1-anyvalue-34700_>`_)

.. _type-splice-api-token-metadatav1-choiceexecutionmetadata-98464:

**data** `ChoiceExecutionMetadata <type-splice-api-token-metadatav1-choiceexecutionmetadata-98464_>`_

  A generic result for choices that do not need to return specific data\.

  .. _constr-splice-api-token-metadatav1-choiceexecutionmetadata-73543:

  `ChoiceExecutionMetadata <constr-splice-api-token-metadatav1-choiceexecutionmetadata-73543_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - meta
         - `Metadata <type-splice-api-token-metadatav1-metadata-21206_>`_
         - Additional metadata specific to the result of exercising the choice, used for extensibility\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `ChoiceExecutionMetadata <type-splice-api-token-metadatav1-choiceexecutionmetadata-98464_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `ChoiceExecutionMetadata <type-splice-api-token-metadatav1-choiceexecutionmetadata-98464_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `ChoiceExecutionMetadata <type-splice-api-token-metadatav1-choiceexecutionmetadata-98464_>`_ `Metadata <type-splice-api-token-metadatav1-metadata-21206_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `ChoiceExecutionMetadata <type-splice-api-token-metadatav1-choiceexecutionmetadata-98464_>`_ `Metadata <type-splice-api-token-metadatav1-metadata-21206_>`_

.. _type-splice-api-token-metadatav1-extraargs-90869:

**data** `ExtraArgs <type-splice-api-token-metadatav1-extraargs-90869_>`_

  A common type for passing both the choice context and the metadata to a choice\.

  .. _constr-splice-api-token-metadatav1-extraargs-16750:

  `ExtraArgs <constr-splice-api-token-metadatav1-extraargs-16750_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - context
         - `ChoiceContext <type-splice-api-token-metadatav1-choicecontext-5566_>`_
         - Extra arguments to be passed to the implementation of an interface choice\. These are provided via an off\-ledger API by the app implementing the interface\.
       * - meta
         - `Metadata <type-splice-api-token-metadatav1-metadata-21206_>`_
         - Additional metadata to pass in\.  In contrast to the ``extraArgs``, these are provided by the caller of the choice\. The expectation is that the meaning of metadata fields will be agreed on in later standards, or on a case\-by\-case basis between the caller and the implementer of the interface\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `ExtraArgs <type-splice-api-token-metadatav1-extraargs-90869_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `ExtraArgs <type-splice-api-token-metadatav1-extraargs-90869_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"context\" `ExtraArgs <type-splice-api-token-metadatav1-extraargs-90869_>`_ `ChoiceContext <type-splice-api-token-metadatav1-choicecontext-5566_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `ExtraArgs <type-splice-api-token-metadatav1-extraargs-90869_>`_ `Metadata <type-splice-api-token-metadatav1-metadata-21206_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"context\" `ExtraArgs <type-splice-api-token-metadatav1-extraargs-90869_>`_ `ChoiceContext <type-splice-api-token-metadatav1-choicecontext-5566_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `ExtraArgs <type-splice-api-token-metadatav1-extraargs-90869_>`_ `Metadata <type-splice-api-token-metadatav1-metadata-21206_>`_

.. _type-splice-api-token-metadatav1-metadata-21206:

**data** `Metadata <type-splice-api-token-metadatav1-metadata-21206_>`_

  Machine\-readable metadata intended for communicating additional information
  using well\-known keys between systems\. This is mainly used to allow for the post\-hoc
  expansion of the information associated with contracts and choice arguments and results\.

  Modeled after by k8s support for annotations\: https\://kubernetes\.io/docs/concepts/overview/working\-with\-objects/annotations/(https://kubernetes.io/docs/concepts/overview/working-with-objects/annotations/)\.

  Implementors SHOULD follow the same conventions for allocating keys as used by k8s; i\.e\.,
  they SHOULD be prefixed using the DNS name of the app defining the key\.

  Implementors SHOULD keep metadata small, as on\-ledger data is costly\.

  .. _constr-splice-api-token-metadatav1-metadata-84299:

  `Metadata <constr-splice-api-token-metadatav1-metadata-84299_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - values
         - `TextMap <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_
         - Key\-value pairs of metadata entries\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `Metadata <type-splice-api-token-metadatav1-metadata-21206_>`_

  **instance** `Ord <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-ord-6395>`_ `Metadata <type-splice-api-token-metadatav1-metadata-21206_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `Metadata <type-splice-api-token-metadatav1-metadata-21206_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `ChoiceExecutionMetadata <type-splice-api-token-metadatav1-choiceexecutionmetadata-98464_>`_ `Metadata <type-splice-api-token-metadatav1-metadata-21206_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"meta\" `ExtraArgs <type-splice-api-token-metadatav1-extraargs-90869_>`_ `Metadata <type-splice-api-token-metadatav1-metadata-21206_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"values\" `Metadata <type-splice-api-token-metadatav1-metadata-21206_>`_ (`TextMap <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_)

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `ChoiceExecutionMetadata <type-splice-api-token-metadatav1-choiceexecutionmetadata-98464_>`_ `Metadata <type-splice-api-token-metadatav1-metadata-21206_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"meta\" `ExtraArgs <type-splice-api-token-metadatav1-extraargs-90869_>`_ `Metadata <type-splice-api-token-metadatav1-metadata-21206_>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"values\" `Metadata <type-splice-api-token-metadatav1-metadata-21206_>`_ (`TextMap <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-textmap-11691>`_ `Text <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-text-51952>`_)

Functions
---------

.. _function-splice-api-token-metadatav1-emptychoicecontext-1208:

`emptyChoiceContext <function-splice-api-token-metadatav1-emptychoicecontext-1208_>`_
  \: `ChoiceContext <type-splice-api-token-metadatav1-choicecontext-5566_>`_

  Empty choice context\.

.. _function-splice-api-token-metadatav1-emptymetadata-88176:

`emptyMetadata <function-splice-api-token-metadatav1-emptymetadata-88176_>`_
  \: `Metadata <type-splice-api-token-metadatav1-metadata-21206_>`_

  Empty metadata\.
