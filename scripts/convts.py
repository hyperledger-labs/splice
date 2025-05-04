#!/usr/bin/env python


# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Process browser log files in a given directory converting the timestamps
to human readable / lnav compatible ISO 8601 date time format.
"""

import sys
import re
from datetime import datetime, timedelta

def process_files(files):
    for filepath in files:
        print("processing", filepath, file=sys.stderr)
        try:
            process_file(filepath)
        except Exception as e:
            print("WARN could not process file:", filepath, file=sys.stderr)
            print(e, file=sys.stderr)

def process_file(filepath):
    with open(filepath, 'r+') as file:
        lines = file.readlines()

        formatted_dt = None

        def convert_timestamp(m):
            nonlocal formatted_dt
            try:
                timestamp = int(m[0])
            except ValueError:
                if formatted_dt is None:
                    return m[0]
                else:
                    return f"{formatted_dt} {m[0]}"
            else:
                dt = datetime.utcfromtimestamp(timestamp // 1000)
                dt += timedelta(milliseconds=timestamp % 1000)
                formatted_dt = dt.isoformat()
                return formatted_dt

        # Process each line and modify the timestamp if necessary
        processed_lines = [ re.sub(r'^\S+\b', convert_timestamp, line) for line in lines ]

        file.truncate(0)
        file.writelines(processed_lines)

if __name__ == '__main__':
    process_files(sys.argv[1:])
