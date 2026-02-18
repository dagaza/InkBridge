cmake_minimum_required(VERSION 3.16)

# Project Definition
project(InkBridge VERSION 1.0.0 LANGUAGES CXX C)

# -----------------------------------------------------------------------------
# 1. Project Configuration & C++20 Standard
# -----------------------------------------------------------------------------
set(CMAKE_AUTOUIC ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# -----------------------------------------------------------------------------
# 2. Dependencies (Qt Quick + LibUSB + Network)
# -----------------------------------------------------------------------------
# UPDATED: Added 'Network' component to both Qt6 and Qt5 blocks
find_package(Qt6 COMPONENTS Quick Qml Core Gui Svg Widgets Network Bluetooth QUIET)

if(Qt6_FOUND)
    message(STATUS "Building with Qt 6 (${Qt6_VERSION})")
    # UPDATED: Added Qt6::Network
    set(QT_LIBS Qt6::Quick Qt6::Qml Qt6::Core Qt6::Gui Qt6::Svg Qt6::Widgets Qt6::Network Qt6::Bluetooth)
else()
    message(STATUS "Qt 6 not found, falling back to Qt 5...")
    # UPDATED: Added Network component
    find_package(Qt5 COMPONENTS Quick Qml Core Gui Svg Widgets Network Bluetooth REQUIRED)
    # UPDATED: Added Qt5::Network
    set(QT_LIBS Qt5::Quick Qt5::Qml Qt5::Core Qt5::Gui Qt5::Svg Qt5::Widgets Qt5::Network Qt5::Bluetooth)
endif()

find_package(PkgConfig REQUIRED)
pkg_check_modules(LIBUSB REQUIRED libusb-1.0)

# -----------------------------------------------------------------------------
# 3. Source Definitions
# -----------------------------------------------------------------------------
set(PROJECT_SOURCES
    # Core Application
    main.cpp
    
    # QML Bridge & Logic (Phase 5)
    backend.cpp
    backend.h
    
    # NEW: Networking (Phase 6)
    wifiserver.cpp
    wifiserver.h
    bluetoothserver.cpp
    bluetoothserver.h
    protocol.h

    # Logic (Preserved from Phase 3/4)
    accessory.cpp
    accessory.h
    linux-adk.cpp
    linux-adk.h
    virtualstylus.cpp
    virtualstylus.h
    displayscreentranslator.cpp
    displayscreentranslator.h
    pressuretranslator.cpp
    pressuretranslator.h
    filepermissionvalidator.cpp
    filepermissionvalidator.h
    
    # Resources
    assets.qrc
    Main.qml  # Included here so it shows up in IDEs
    
    # Legacy C Sources (Required for uinput injection)
    error.c
    error.h
    uinput.c
    uinput.h
    log.c
    log.h
)

add_executable(InkBridge ${PROJECT_SOURCES})

# -----------------------------------------------------------------------------
# 4. Linkage & Include Directories
# -----------------------------------------------------------------------------
target_include_directories(InkBridge PRIVATE
    ${LIBUSB_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(InkBridge PRIVATE
    ${QT_LIBS}
    ${LIBUSB_LIBRARIES}
    
)

if(LIBUSB_LDFLAGS)
    target_link_options(InkBridge PRIVATE ${LIBUSB_LDFLAGS})
endif()

# -----------------------------------------------------------------------------
# 5. Compiler Warnings
# -----------------------------------------------------------------------------
if(MSVC)
    target_compile_options(InkBridge PRIVATE /W4)
else()
    target_compile_options(InkBridge PRIVATE -Wall -Wextra -Wpedantic)
endif()

# -----------------------------------------------------------------------------
# 6. Installation & Deployment
# -----------------------------------------------------------------------------
include(GNUInstallDirs)

install(TARGETS InkBridge
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
    BUNDLE DESTINATION .
)

# Standard Linux Desktop integrations
install(FILES resources/io.github.androidvirtualpen.virtualpen.desktop DESTINATION ${CMAKE_INSTALL_DATAROOTDIR}/applications/)
install(FILES resources/io.github.androidvirtualpen.virtualpen.svg DESTINATION ${CMAKE_INSTALL_DATAROOTDIR}/icons/hicolor/scalable/apps/)
install(FILES resources/io.github.androidvirtualpen.virtualpen.metainfo.xml DESTINATION ${CMAKE_INSTALL_DATAROOTDIR}/metainfo/)

# Deployment helper for Qt6
if(Qt6_FOUND)
    qt_finalize_executable(InkBridge)
endif()

add_custom_command(TARGET InkBridge POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
    ${CMAKE_CURRENT_SOURCE_DIR}/Main.qml
    $<TARGET_FILE_DIR:InkBridge>)


#    =============================================================================
# LINUX SYSTEM & Bluetooth NOTE
# =============================================================================
# Qt Bluetooth on Linux uses BlueZ under the hood. Make sure it's installed:
#
#   sudo apt install libbluetooth-dev bluetooth bluez
#
# The desktop machine's Bluetooth adapter must also be:
#   1. Enabled:       sudo systemctl start bluetooth
#   2. Powered on:    bluetoothctl power on
#   3. Paired with the Android device before first use
#      (pairing only needs to happen once)