#!/bin/zsh

# Script to install requirements for building Sphinx docs locally

set -e
# Check for Xcode Command Line Tools
if ! xcode-select -p &>/dev/null; then
    echo "Xcode Command Line Tools not found. Installing..."
    xcode-select --install
    echo "Check for the install xcode dialog box. Please complete the Xcode Command Line Tools installation, then re-run this script."
    exit 1
fi

# Check for Homebrew and install if missing
if ! command -v brew &>/dev/null; then
    echo "Homebrew not found. Installing Homebrew..."
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
    # Add Homebrew to PATH for current session
    if [[ -d "/opt/homebrew/bin" ]]; then
        export PATH="/opt/homebrew/bin:$PATH"
    elif [[ -d "/usr/local/bin" ]]; then
        export PATH="/usr/local/bin:$PATH"
    fi
fi

# Check for pyenv and install if missing
if ! command -v pyenv &>/dev/null; then
    echo "pyenv not found. Installing with Homebrew..."
    if ! command -v brew &>/dev/null; then
        echo "Homebrew is required to install pyenv. Please install Homebrew first."
        exit 1
    fi
    brew install pyenv
fi

# Ensure desired Python version is installed via pyenv
PYTHON_VERSION="3.11.9"
if ! pyenv versions --bare | grep -qx "$PYTHON_VERSION"; then
    echo "Installing Python $PYTHON_VERSION with pyenv..."
    pyenv install "$PYTHON_VERSION"
fi

# Create virtual environment using pyenv if not exists
if [ ! -d "venv" ]; then
    pyenv shell "$PYTHON_VERSION"
    python -m venv venv-splice-docs
    echo "Created virtual environment in ./venv using pyenv Python $PYTHON_VERSION"
fi

# Activate virtual environment
source venv/bin/activate

# Upgrade pip
pip install --upgrade pip

# Install additional requirements if requirements.txt exists
echo "Installing build requirements..."
if [ -f "requirements.txt" ]; then
    pip install -r requirements.txt
fi

# Check for direnv and install if missing
if ! command -v direnv &>/dev/null; then
    echo "direnv not found. Installing with Homebrew..."
    if ! command -v brew &>/dev/null; then
        echo "Homebrew is required to install direnv. Please install Homebrew first."
        exit 1
    fi
    brew install direnv
fi

export SPLICE_ROOT="${PWD}"

echo "Sphinx documentation build requirements installed."
# echo "To activate the virtual environment, run: source venv/bin/activate"
