const fs = require('fs');

fs.writeFileSync('./lib/esm/package.json', '{"type":"module"}');
fs.writeFileSync('./lib/cjs/package.json', '{"type":"commonjs"}');
