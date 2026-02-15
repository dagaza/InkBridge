# Use a lightweight version of Ubuntu
FROM ubuntu:22.04

# Avoid prompts from apt
ENV DEBIAN_FRONTEND=noninteractive

# Install the missing dependencies
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

# (Optional) Install nice-to-have tools
RUN apt-get update && apt-get install -y \
    gdb \
    cppcheck \
    clang-format