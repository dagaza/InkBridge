cmake_minimum_required(VERSION 3.16)

# Project Definition
project(InkBridge VERSION 0.1 LANGUAGES CXX C)

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
# 2. Dependencies (Qt6 + LibUSB)
# -----------------------------------------------------------------------------
find_package(Qt6 COMPONENTS Widgets Concurrent QUIET)

if(Qt6_FOUND)
    message(STATUS "Building with Qt 6 (${Qt6_VERSION})")
    set(QT_LIBS Qt6::Widgets Qt6::Concurrent)
else()
    message(STATUS "Qt 6 not found, falling back to Qt 5...")
    find_package(Qt5 COMPONENTS Widgets Concurrent REQUIRED)
    set(QT_LIBS Qt5::Widgets Qt5::Concurrent)
endif()

find_package(PkgConfig REQUIRED)
pkg_check_modules(LIBUSB REQUIRED libusb-1.0)

# -----------------------------------------------------------------------------
# 3. Source Definitions (Removed hid.cpp)
# -----------------------------------------------------------------------------
set(PROJECT_SOURCES
    main.cpp
    mainwindow.cpp
    mainwindow.h
    mainwindow.ui
    udevdialog.ui
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
    icon.qrc
    
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

install(FILES resources/io.github.androidvirtualpen.virtualpen.desktop DESTINATION ${CMAKE_INSTALL_DATAROOTDIR}/applications/)
install(FILES resources/io.github.androidvirtualpen.virtualpen.svg DESTINATION ${CMAKE_INSTALL_DATAROOTDIR}/icons/hicolor/scalable/apps/)
install(FILES resources/io.github.androidvirtualpen.virtualpen.metainfo.xml DESTINATION ${CMAKE_INSTALL_DATAROOTDIR}/metainfo/)

if(Qt6_FOUND)
    qt_finalize_executable(InkBridge)
endif()