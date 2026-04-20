.. _module-splice-api-featuredapprightv2-80033:

Splice.Api.FeaturedAppRightV2
=============================

The API for featured apps to record their activity\.
This package extends FeaturedAppRightV1 with support for specifying
a weight when creating a marker to improve efficiency\.

Interfaces
----------

.. _type-splice-api-featuredapprightv2-featuredappactivitymarker-59464:

**interface** `FeaturedAppActivityMarker <type-splice-api-featuredapprightv2-featuredappactivitymarker-59464_>`_

  A marker created by a featured application for activity generated from that app\. This is used
  to record activity other than amulet transfers, which have built\-in support for recording featured app activity\.

  **viewtype** `FeaturedAppActivityMarkerView <type-splice-api-featuredapprightv2-featuredappactivitymarkerview-70739_>`_

  + **Choice** Archive

    Controller\: Signatories of implementing template

    Returns\: ()

    (no fields)


.. _type-splice-api-featuredapprightv2-featuredappright-28172:

**interface** `FeaturedAppRight <type-splice-api-featuredapprightv2-featuredappright-28172_>`_

  An interface for contracts allowing application providers to record their featured activity\.
  Note that most instances of amulet will likely define some fair usage constraints\.

  **viewtype** `FeaturedAppRightView <type-splice-api-featuredapprightv2-featuredapprightview-58075_>`_

  + **Choice** Archive

    Controller\: Signatories of implementing template

    Returns\: ()

    (no fields)

  + .. _type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarker-77229:

    **Choice** `FeaturedAppRight_CreateActivityMarker <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarker-77229_>`_

    Record activity due to a featured app\.

    Controller\: (DA\.Internal\.Record\.getField @\"provider\" (view this))

    Returns\: `FeaturedAppRight_CreateActivityMarkerResult <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarkerresult-27928_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - beneficiaries
         - \[`AppRewardBeneficiary <type-splice-api-featuredapprightv2-apprewardbeneficiary-35536_>`_\]
         - The set of beneficiaries and weights that define how the rewards should be split up between the beneficiary parties\.  Implementations SHOULD check that the weights are positive and add up to 1\.0\. Implementations MAY also impose a limit on the maximum number of beneficiaries\.
       * - weight
         - `Optional <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-prelude-optional-37153>`_ `Decimal <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_
         - A weight for the resulting markers\. None defaults to a weight of 1\.0\. Specifying a weight of 5 for example makes the resulting markers equivalent to 5 markers of weight 1 in terms of rewards they generate\. The weight must be \>\= 1\.0 Implementations MAY impose an upper limit on the weight\.

  + **Method featuredAppRight\_CreateActivityMarkerImpl \:** `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `FeaturedAppRight <type-splice-api-featuredapprightv2-featuredappright-28172_>`_ \-\> `FeaturedAppRight_CreateActivityMarker <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarker-77229_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `FeaturedAppRight_CreateActivityMarkerResult <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarkerresult-27928_>`_

Data Types
----------

.. _type-splice-api-featuredapprightv2-apprewardbeneficiary-35536:

**data** `AppRewardBeneficiary <type-splice-api-featuredapprightv2-apprewardbeneficiary-35536_>`_

  Specification of a beneficiary of featured app rewards\.

  .. _constr-splice-api-featuredapprightv2-apprewardbeneficiary-41661:

  `AppRewardBeneficiary <constr-splice-api-featuredapprightv2-apprewardbeneficiary-41661_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - beneficiary
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The party that is granted the right to mint the weighted amount of reward for this activity\.
       * - weight
         - `Decimal <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_
         - A weight between 0\.0 and 1\.0 that defines how much of the reward this beneficiary can mint\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `AppRewardBeneficiary <type-splice-api-featuredapprightv2-apprewardbeneficiary-35536_>`_

  **instance** `Ord <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-ord-6395>`_ `AppRewardBeneficiary <type-splice-api-featuredapprightv2-apprewardbeneficiary-35536_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `AppRewardBeneficiary <type-splice-api-featuredapprightv2-apprewardbeneficiary-35536_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"beneficiaries\" `FeaturedAppRight_CreateActivityMarker <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarker-77229_>`_ \[`AppRewardBeneficiary <type-splice-api-featuredapprightv2-apprewardbeneficiary-35536_>`_\]

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"beneficiary\" `AppRewardBeneficiary <type-splice-api-featuredapprightv2-apprewardbeneficiary-35536_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"weight\" `AppRewardBeneficiary <type-splice-api-featuredapprightv2-apprewardbeneficiary-35536_>`_ `Decimal <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"beneficiaries\" `FeaturedAppRight_CreateActivityMarker <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarker-77229_>`_ \[`AppRewardBeneficiary <type-splice-api-featuredapprightv2-apprewardbeneficiary-35536_>`_\]

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"beneficiary\" `AppRewardBeneficiary <type-splice-api-featuredapprightv2-apprewardbeneficiary-35536_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"weight\" `AppRewardBeneficiary <type-splice-api-featuredapprightv2-apprewardbeneficiary-35536_>`_ `Decimal <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

.. _type-splice-api-featuredapprightv2-featuredappactivitymarkerview-70739:

**data** `FeaturedAppActivityMarkerView <type-splice-api-featuredapprightv2-featuredappactivitymarkerview-70739_>`_

  .. _constr-splice-api-featuredapprightv2-featuredappactivitymarkerview-19000:

  `FeaturedAppActivityMarkerView <constr-splice-api-featuredapprightv2-featuredappactivitymarkerview-19000_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - dso
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The DSO party\.
       * - provider
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The featured app provider\.
       * - beneficiary
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The party that is granted the right to mint the weighted amount of reward for this activity\.
       * - weight
         - `Decimal <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_
         - A weight between 0\.0 and 1\.0 that defines how much of the reward this beneficiary can mint\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `FeaturedAppActivityMarkerView <type-splice-api-featuredapprightv2-featuredappactivitymarkerview-70739_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `FeaturedAppActivityMarkerView <type-splice-api-featuredapprightv2-featuredappactivitymarkerview-70739_>`_

  **instance** `HasFromAnyView <https://docs.daml.com/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `FeaturedAppActivityMarker <type-splice-api-featuredapprightv2-featuredappactivitymarker-59464_>`_ `FeaturedAppActivityMarkerView <type-splice-api-featuredapprightv2-featuredappactivitymarkerview-70739_>`_

  **instance** `HasInterfaceView <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `FeaturedAppActivityMarker <type-splice-api-featuredapprightv2-featuredappactivitymarker-59464_>`_ `FeaturedAppActivityMarkerView <type-splice-api-featuredapprightv2-featuredappactivitymarkerview-70739_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"beneficiary\" `FeaturedAppActivityMarkerView <type-splice-api-featuredapprightv2-featuredappactivitymarkerview-70739_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"dso\" `FeaturedAppActivityMarkerView <type-splice-api-featuredapprightv2-featuredappactivitymarkerview-70739_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"provider\" `FeaturedAppActivityMarkerView <type-splice-api-featuredapprightv2-featuredappactivitymarkerview-70739_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"weight\" `FeaturedAppActivityMarkerView <type-splice-api-featuredapprightv2-featuredappactivitymarkerview-70739_>`_ `Decimal <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"beneficiary\" `FeaturedAppActivityMarkerView <type-splice-api-featuredapprightv2-featuredappactivitymarkerview-70739_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"dso\" `FeaturedAppActivityMarkerView <type-splice-api-featuredapprightv2-featuredappactivitymarkerview-70739_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"provider\" `FeaturedAppActivityMarkerView <type-splice-api-featuredapprightv2-featuredappactivitymarkerview-70739_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"weight\" `FeaturedAppActivityMarkerView <type-splice-api-featuredapprightv2-featuredappactivitymarkerview-70739_>`_ `Decimal <https://docs.daml.com/daml/stdlib/Prelude.html#type-ghc-types-decimal-18135>`_

.. _type-splice-api-featuredapprightv2-featuredapprightview-58075:

**data** `FeaturedAppRightView <type-splice-api-featuredapprightv2-featuredapprightview-58075_>`_

  .. _constr-splice-api-featuredapprightv2-featuredapprightview-48166:

  `FeaturedAppRightView <constr-splice-api-featuredapprightv2-featuredapprightview-48166_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - dso
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The DSO party\.
       * - provider
         - `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_
         - The featured app provider\.

  **instance** `Eq <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-classes-eq-22713>`_ `FeaturedAppRightView <type-splice-api-featuredapprightv2-featuredapprightview-58075_>`_

  **instance** `Show <https://docs.daml.com/daml/stdlib/Prelude.html#class-ghc-show-show-65360>`_ `FeaturedAppRightView <type-splice-api-featuredapprightv2-featuredapprightview-58075_>`_

  **instance** `HasFromAnyView <https://docs.daml.com/daml/stdlib/DA-Internal-Interface-AnyView.html#class-da-internal-interface-anyview-hasfromanyview-30108>`_ `FeaturedAppRight <type-splice-api-featuredapprightv2-featuredappright-28172_>`_ `FeaturedAppRightView <type-splice-api-featuredapprightv2-featuredapprightview-58075_>`_

  **instance** `HasInterfaceView <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-interface-hasinterfaceview-4492>`_ `FeaturedAppRight <type-splice-api-featuredapprightv2-featuredappright-28172_>`_ `FeaturedAppRightView <type-splice-api-featuredapprightv2-featuredapprightview-58075_>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"dso\" `FeaturedAppRightView <type-splice-api-featuredapprightv2-featuredapprightview-58075_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"provider\" `FeaturedAppRightView <type-splice-api-featuredapprightv2-featuredapprightview-58075_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"dso\" `FeaturedAppRightView <type-splice-api-featuredapprightv2-featuredapprightview-58075_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"provider\" `FeaturedAppRightView <type-splice-api-featuredapprightv2-featuredapprightview-58075_>`_ `Party <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-party-57932>`_

.. _type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarkerresult-27928:

**data** `FeaturedAppRight_CreateActivityMarkerResult <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarkerresult-27928_>`_

  Result of calling the ``FeaturedAppRight_CreateActivityMarker`` choice\.

  .. _constr-splice-api-featuredapprightv2-featuredapprightcreateactivitymarkerresult-95607:

  `FeaturedAppRight_CreateActivityMarkerResult <constr-splice-api-featuredapprightv2-featuredapprightcreateactivitymarkerresult-95607_>`_

    .. list-table::
       :widths: 15 10 30
       :header-rows: 1

       * - Field
         - Type
         - Description
       * - activityMarkerCids
         - \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `FeaturedAppActivityMarker <type-splice-api-featuredapprightv2-featuredappactivitymarker-59464_>`_\]
         - The set of activity markers created by the choice\.

  **instance** HasMethod `FeaturedAppRight <type-splice-api-featuredapprightv2-featuredappright-28172_>`_ \"featuredAppRight\_CreateActivityMarkerImpl\" (`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `FeaturedAppRight <type-splice-api-featuredapprightv2-featuredappright-28172_>`_ \-\> `FeaturedAppRight_CreateActivityMarker <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarker-77229_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `FeaturedAppRight_CreateActivityMarkerResult <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarkerresult-27928_>`_)

  **instance** `GetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-getfield-53979>`_ \"activityMarkerCids\" `FeaturedAppRight_CreateActivityMarkerResult <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarkerresult-27928_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `FeaturedAppActivityMarker <type-splice-api-featuredapprightv2-featuredappactivitymarker-59464_>`_\]

  **instance** `SetField <https://docs.daml.com/daml/stdlib/DA-Record.html#class-da-internal-record-setfield-4311>`_ \"activityMarkerCids\" `FeaturedAppRight_CreateActivityMarkerResult <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarkerresult-27928_>`_ \[`ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `FeaturedAppActivityMarker <type-splice-api-featuredapprightv2-featuredappactivitymarker-59464_>`_\]

  **instance** `HasExercise <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexercise-70422>`_ `FeaturedAppRight <type-splice-api-featuredapprightv2-featuredappright-28172_>`_ `FeaturedAppRight_CreateActivityMarker <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarker-77229_>`_ `FeaturedAppRight_CreateActivityMarkerResult <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarkerresult-27928_>`_

  **instance** `HasExerciseGuarded <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasexerciseguarded-97843>`_ `FeaturedAppRight <type-splice-api-featuredapprightv2-featuredappright-28172_>`_ `FeaturedAppRight_CreateActivityMarker <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarker-77229_>`_ `FeaturedAppRight_CreateActivityMarkerResult <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarkerresult-27928_>`_

  **instance** `HasFromAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hasfromanychoice-81184>`_ `FeaturedAppRight <type-splice-api-featuredapprightv2-featuredappright-28172_>`_ `FeaturedAppRight_CreateActivityMarker <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarker-77229_>`_ `FeaturedAppRight_CreateActivityMarkerResult <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarkerresult-27928_>`_

  **instance** `HasToAnyChoice <https://docs.daml.com/daml/stdlib/Prelude.html#class-da-internal-template-functions-hastoanychoice-82571>`_ `FeaturedAppRight <type-splice-api-featuredapprightv2-featuredappright-28172_>`_ `FeaturedAppRight_CreateActivityMarker <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarker-77229_>`_ `FeaturedAppRight_CreateActivityMarkerResult <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarkerresult-27928_>`_

Functions
---------

.. _function-splice-api-featuredapprightv2-featuredapprightcreateactivitymarkerimpl-7767:

`featuredAppRight_CreateActivityMarkerImpl <function-splice-api-featuredapprightv2-featuredapprightcreateactivitymarkerimpl-7767_>`_
  \: `FeaturedAppRight <type-splice-api-featuredapprightv2-featuredappright-28172_>`_ \-\> `ContractId <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-contractid-95282>`_ `FeaturedAppRight <type-splice-api-featuredapprightv2-featuredappright-28172_>`_ \-\> `FeaturedAppRight_CreateActivityMarker <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarker-77229_>`_ \-\> `Update <https://docs.daml.com/daml/stdlib/Prelude.html#type-da-internal-lf-update-68072>`_ `FeaturedAppRight_CreateActivityMarkerResult <type-splice-api-featuredapprightv2-featuredapprightcreateactivitymarkerresult-27928_>`_
