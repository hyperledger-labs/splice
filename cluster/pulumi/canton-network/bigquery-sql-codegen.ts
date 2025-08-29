import { allFunctions } from "./src/bigQuery_functions";
import { existsSync, unlinkSync, writeFileSync } from 'fs';

if (process.argv.length != 3) {
  console.error("Usage: npm run bigquery-sql-codegen <output-file>");
  process.exit(1);
}

const out = process.argv[2];

if (existsSync(out)) {
  unlinkSync(out)
}
allFunctions.forEach(f => writeFileSync(out, f.toSql('functions'), { flag: 'a' }));
