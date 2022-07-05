# -*- makefile -*-

export MAKE

#########
# common
#########

# only load common once
ifneq ($(_did_common),true)


# execute a makefile shell but crash make if the command fails
must-shell = $(or $(shell _out="$$($(1))" && echo "$${_out}" || echo "$${_out}" >&2),$(error command '$(1)' failed))

# fails dirty builds in CI
check-dirty = $(if $(findstring true,$(CI)),$(if $(findstring dirty,$(git_sha)),$(error build is dirty$(\n)$(call must-shell,git status --porcelain)$(\n))))


#######
# vars
#######

app  := $(notdir $(abspath .))
repo_root := $(call must-shell,cd $(dir $(lastword $(MAKEFILE_LIST))).. && pwd)

gcp-project-id = $(eval gcp-project-id := $$(shell gcloud config get project))$(gcp-project-id)

############
# srcdigest
############

srcdigest := target/srcdigest

.PHONY: clean-srcdigest
clean-srcdigest:
	rm -fv $(srcdigest)

.PHONY: srcdigest
srcdigest: clean-srcdigest $(srcdigest)

$(srcdigest):
	@mkdir -p $(@D)
	@git ls-files -s $^ | tee >(sha256sum | sed -E 's/^(\w*).*$$/M\1/') | tee $@


_did_common := true
endif
