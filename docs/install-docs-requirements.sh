#!/bin/bash

# Script to install requirements for building Sphinx docs locally

set -e
# Check for Xcode Command Line Tools
if ! xcode-select -p &>/dev/null; then
    echo "Xcode Command Line Tools not found. Installing..."
    xcode-select --install
    echo "Check for the install xcode dialog box. Please complete the Xcode Command Line Tools installation, then re-run this script."
    exit 1
fi
# Check for Python 3 and pip
if ! command -v python3 &>/dev/null; then
    echo "Python 3 is required. Please install it first."
    exit 1
fi

if ! command -v pip3 &>/dev/null; then
    echo "pip3 is required. Please install it first."
    exit 1
fi

# Create virtual environment if not exists
if [ ! -d "venv" ]; then
    python3 -m venv venv
    echo "Created virtual environment in ./venv"
fi

# Activate virtual environment
source venv/bin/activate

# Upgrade pip
pip install --upgrade pip

# Install Sphinx and common extensions
pip install sphinx sphinx_rtd_theme

# Install additional requirements if requirements.txt exists
if [ -f "requirements.txt" ]; then
    pip install -r requirements.txt
fi

echo "Sphinx documentation build requirements installed."
echo "To activate the virtual environment, run: source venv/bin/activate"