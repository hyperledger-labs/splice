coin_api_dar=coin-api-v2v1/.daml/dist/coin-api-v1-v2-v3-0.1.0.dar
coin_impl_dar=rules-data-impl/.daml/dist/coin-impl-v3-0.1.0.dar
tests_dar=tests/.daml/dist/tests-0.1.0.dar

$(coin_api_dar): coin-api-v1-v2-v3/daml.yaml $(shell find coin-api-v1-v2-v3 -name '*.daml' -type f)
	DAML_PROJECT=coin-api-v1-v2-v3 daml build --ghc-option=-Werror

$(coin_impl_dar): coin-impl-v3/daml.yaml $(shell find coin-impl-v3 -name '*.daml' -type f) $(coin_api_dar)
	DAML_PROJECT=coin-impl-v3 daml build --ghc-option=-Werror

$(tests_dar): tests/daml.yaml $(shell find tests -name '*.daml' -type f) $(coin_iface_dar) $(coin_data_impl_dar) $(coin_rules_impl_dar)
	DAML_PROJECT=tests daml build --ghc-option=-Werror

.PHONY: all
.DEFAULT_GOAL := all
all: $(coin_api_dar) $(coin_impl_dar)

.PHONY: coin-api-v1-v2-v3
coin-api-v1-v2-v3: $(coin_api_dar)

.PHONY: coin-impl-v3
coin-impl-v3: $(coin_impl_dar)

.PHONY: test-cc
test-cc: $(tests_dar)
	DAML_PROJECT=tests daml test --ghc-option=-Werror

.PHONY: clean
clean:
	find . -name daml.yaml -exec sh -c 'DAML_PROJECT=$$(dirname $$1) daml clean' sh '{}' \;
