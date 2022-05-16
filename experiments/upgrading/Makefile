coin_iface_dar=coin-iface/.daml/dist/coin-iface-0.1.0.dar
coin_impl_dar=coin-impl/.daml/dist/coin-impl-0.1.0.dar
rules_iface_dar=rules-iface/.daml/dist/rules-iface-0.1.0.dar
rules_impl_dar=rules-impl/.daml/dist/rules-impl-0.1.0.dar
tests_dar=tests/.daml/dist/tests-0.1.0.dar

$(coin_iface_dar): coin-iface/daml.yaml $(shell find coin-iface -name '*.daml' -type f)
	DAML_PROJECT=coin-iface daml build --ghc-option=-Werror

$(coin_impl_dar): coin-impl/daml.yaml $(shell find coin-impl -name '*.daml' -type f) $(coin_iface_dar) $(rules_iface_dar)
	DAML_PROJECT=coin-impl daml build --ghc-option=-Werror

$(rules_iface_dar): rules-iface/daml.yaml $(shell find rules-iface -name '*.daml' -type f) $(coin_iface_dar)
	DAML_PROJECT=rules-iface daml build --ghc-option=-Werror

$(rules_impl_dar): rules-impl/daml.yaml $(shell find rules-impl -name '*.daml' -type f) $(coin_iface_dar) $(rules_iface_dar) $(coin_impl_dar)
	DAML_PROJECT=rules-impl daml build --ghc-option=-Werror

$(tests_dar): tests/daml.yaml $(shell find tests -name '*.daml' -type f) $(coin_iface_dar) $(rules_iface_dar) $(coin_impl) $(rules_impl_dar)
	DAML_PROJECT=tests daml build --ghc-option=-Werror

.PHONY: test
test: $(tests_dar)
	DAML_PROJECT=tests daml test --ghc-option=-Werror

.PHONY: all
.DEFAULT_GOAL := all
all: $(coin_iface_dar) $(rules_iface_dar) $(coin_impl_dar) $(rules_impl_dar) $(tests_dar)

.PHONY: coin-iface
coin-iface: $(coin_iface_dar)

.PHONY: coin-impl
coin-impl: $(coin_impl_dar)

.PHONY: rules-iface
rules-iface: $(rules_iface_dar)

.PHONY: rules-impl
rules-impl: $(rules_impl_dar)

.PHONY: tests
tests: $(tests_dar)

.PHONY: clean
clean:
	for dir in coin-iface coin-impl rules-iface rules-impl tests; do DAML_PROJECT=$$dir daml clean; done

