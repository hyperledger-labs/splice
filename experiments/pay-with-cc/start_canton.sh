#!/usr/bin/env bash

#!/usr/bin/env bash

cd "$(dirname "${BASH_SOURCE[0]}")"
daml start --on-start 'daml script --dar=.daml/dist/pay-with-cc-0.1.0.dar --ledger-host=localhost --ledger-port=6865 --script-name Init:setup --static-time'
