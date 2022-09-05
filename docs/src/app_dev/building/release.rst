Release Management
==================

.. TODO(M1-14): link and/or incorporate our recommended upgrade practices here? Versioning is pretty closely linked to this.

Versioning of Daml APIs
-----------------------

* Use `semantic versioning <https://semver.org/>`_ to version your APIs

    * Patch: bugfix to implementation of interface method/view
    * Minor: newly added choices/interfaces/templates, backwards & forwards instances for all existing templates/interfaces
    * Major: no backwards/forward instances for some template/interfaces

Versioning of Daml Implementations
----------------------------------

* Use `semantic versioning <https://semver.org/>`_ to version your APIs

    * Patch: bugfix to choice impl
    * Minor: newly added choice impls, existing choice impls functionally identical source (but recompiled) modulo bugfixes
    * Major: No choice impl for (interface, template) that existed before

Release of Daml APIs & Implementations
--------------------------------------

* Release via Package Distribution API (inital spec available from this `DA internal design doc <https://docs.google.com/document/d/1Oogaz1mZ54Ar5Avfttt1QG6Sh4AZ1nwLcUP_lFYZD48/edit#bookmark=id.gc276jfx71kq>`_)


Versioning and Release of Read Access APIs
------------------------------------------

.. todo::

    * Do some more thinking on this, do we want to version this separately from Daml APIs or do we have a shared app API?

Releasing Backends and Frontends
--------------------------------

* Release backends as docker containers (if they’re released at all)
* Make all URLs & ports of your own app and the ones you try to reach for dependencies configurable
* Release frontend as bundle of static files (if they’re released at all)
* Either make host/port configurable or assume they’re hosted on the
  same port behind a reverse proxy

.. todo::

   Release of documentation
