# Use a lightweight version of Ubuntu
FROM ubuntu:22.04

# Avoids prompts from apt
ENV DEBIAN_FRONTEND=noninteractive

# Installs the missing dependencies
RUN apt-get update && apt-get install -y \
    build-essential \
    cmake \
    git \
    pkg-config \
    qtbase5-dev \
    qt5-qmake \
    libqt5serialport5-dev \
    libusb-1.0-0-dev \
    udev \
    && rm -rf /var/lib/apt/lists/*

# (Optional) Installs nice-to-have tools
RUN apt-get update && apt-get install -y \
    qtbase5-dev \
    qtdeclarative5-dev \
    libqt5svg5-dev \
    build-essential \
    cmake