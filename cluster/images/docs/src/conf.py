# Configuration file for the Sphinx documentation builder.
#
# This file only contains a selection of the most common options. For a full
# list see the documentation:
# https://www.sphinx-doc.org/en/master/usage/configuration.html

# -- Path setup --------------------------------------------------------------

# If extensions (or modules to document with autodoc) are in another directory,
# add these directories to sys.path here. If the directory is relative to the
# documentation root, use os.path.abspath to make it absolute, like shown here.
#
import os
# import sys
# sys.path.insert(0, os.path.abspath('.'))

import json


# -- Project information -----------------------------------------------------

project = u'Canton Network'
copyright = u'2022, Digital Asset'
author = u'Digital Asset'


# -- General configuration ---------------------------------------------------

# Add any Sphinx extension module names here, as strings. They can be
# extensions coming with Sphinx (named 'sphinx.ext.*') or your custom
# ones.
extensions = [
    'sphinx.ext.todo'
]

todo_include_todos = True

# Add any paths that contain templates here, relative to this directory.
templates_path = ['_templates']

# List of patterns, relative to source directory, that match files and
# directories to ignore when looking for source files.
# This pattern also affects html_static_path and html_extra_path.
exclude_patterns = ['_build', 'Thumbs.db', '.DS_Store']


# -- Options for HTML output -------------------------------------------------

# The theme to use for HTML and HTML Help pages.  See the documentation for
# a list of builtin themes.
#
html_theme = 'sphinx_rtd_theme'

# Add any paths that contain custom static files (such as style sheets) here,
# relative to this directory. They are copied after the builtin static files,
# so a file named "default.css" will overwrite the builtin "default.css".
# html_static_path = ['_static']

# Theme options are theme-specific and customize the look and feel of a theme
# further.  For a list of options available for each theme, see the
# documentation.
#
html_theme_options = {
    'navigation_depth': -1,
    'collapse_navigation': False,
    # 'analytics_id': 'UA-64532708-4'
}

with open("../../../../nix/canton-sources.json") as f:
    obj = json.load(f)
    canton_research_version = obj['version']

# Sphinx does not allow something like ``|version|``
# so instead we define a replacement that includes the formatting.
rst_prolog = """
.. role:: raw-html(raw)
   :format: html

.. |version_literal| replace:: ``{version}``
.. |canton_version| replace:: {canton_research_version}

.. |bundle_download_link| replace:: :raw-html:`<a class="reference external" href="/release-bundles/{version}_coin-0.1.0-SNAPSHOT.tar.gz">Download Bundle</a>`
.. |canton_research_download_link| replace:: :raw-html:`<a class="reference external" href="https://digitalasset.jfrog.io/artifactory/canton-research/snapshot/canton-research-{canton_research_version}.tar.gz">Download Canton research</a>`
""".format(version = os.getenv("VERSION"), canton_research_version = canton_research_version)
