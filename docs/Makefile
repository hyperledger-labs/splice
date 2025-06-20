# Makefile for Sphinx documentation
#

# You can set these variables from the command line.
SPHINXOPTS    =
SPHINXBUILD   = sphinx-build
SPHINXAUTOBUILD = sphinx-autobuild
SOURCEDIR     = src
BUILDDIR      = build

# Put all phony targets here so that file names with the same name as phony targets
# will not confuse make.
.PHONY: help html livehtml clean

# Default target: show help.
help:
	@echo "Please use \`make <target>' where <target> is one of"
	@echo "  html       to build the HTML documentation"
	@echo "  livehtml   to automatically rebuild and preview the site on changes"
	@echo "  clean      to remove all build files"

# Build the HTML documentation
html:
	@echo "Building HTML documentation..."
	$(SPHINXBUILD) -b html $(SOURCEDIR) $(BUILDDIR)/html $(SPHINXOPTS)
	@echo "Build finished. Open $(BUILDDIR)/html/index.html to view."

# Run the live-reloading server for previewing
livehtml:
	@echo "Starting live-reloading server..."
	@echo "Your browser should open automatically. Press Ctrl+C to stop."
	$(SPHINXAUTOBUILD) $(SOURCEDIR) $(BUILDDIR)/html $(SPHINXOPTS)

# Remove all generated build files
clean:
	@echo "Cleaning build directory..."
	rm -rf $(BUILDDIR)/*