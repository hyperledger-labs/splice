import pino from "pino";

export const logger = pino({
  level: "debug",
  base: null,
  formatters: {
    level: (label) => ({ level: label }),
  },
  timestamp: pino.stdTimeFunctions.isoTime,
});
