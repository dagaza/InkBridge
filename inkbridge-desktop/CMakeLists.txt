cmake_minimum_required(VERSION 3.10)

project(InkBridge VERSION 0.1 LANGUAGES CXX C)

set(CMAKE_AUTOUIC ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# -----------------------------------------------------------------------------
# 1. Find Qt (Robust Detection)
# -----------------------------------------------------------------------------
find_package(Qt6 COMPONENTS Widgets Concurrent QUIET)
if(Qt6_FOUND)
    set(QT_VERSION_MAJOR 6)
    set(QT_VERSION ${Qt6_VERSION})  # Save the full version string (e.g., 6.4.2)
    set(QT_LIBS Qt6::Widgets Qt6::Concurrent)
    message(STATUS "Found Qt 6 (${QT_VERSION})")
else()
    find_package(Qt5 COMPONENTS Widgets Concurrent REQUIRED)
    set(QT_VERSION_MAJOR 5)
    set(QT_VERSION ${Qt5_VERSION})  # Save the full version string (e.g., 5.15.2)
    set(QT_LIBS Qt5::Widgets Qt5::Concurrent)
    message(STATUS "Found Qt 5 (${QT_VERSION})")
endif()

# -----------------------------------------------------------------------------
# 2. Find LibUSB via PkgConfig
# -----------------------------------------------------------------------------
find_package(PkgConfig REQUIRED)
pkg_check_modules(LIBUSB REQUIRED libusb-1.0)

# -----------------------------------------------------------------------------
# 3. Define Sources
# -----------------------------------------------------------------------------
set(PROJECT_SOURCES
        main.cpp
        mainwindow.cpp        
        mainwindow.h
        mainwindow.ui
        udevdialog.ui
        accessory.cpp
        accessory.h
        hid.cpp
        linux-adk.cpp
        linux-adk.h
        error.c
        error.h
        uinput.c
        uinput.h
        log.c
        log.h
        virtualstylus.cpp
        virtualstylus.h
        constants.h
        displayscreentranslator.cpp
        displayscreentranslator.h
        pressuretranslator.cpp
        pressuretranslator.h
        filepermissionvalidator.cpp
        filepermissionvalidator.h
        icon.qrc
)

add_executable(InkBridge ${PROJECT_SOURCES})

# -----------------------------------------------------------------------------
# 4. Link Libraries & Include Directories
# -----------------------------------------------------------------------------
# Include directories for LibUSB
target_include_directories(InkBridge PRIVATE 
    ${LIBUSB_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR} # Ensure headers in root are found
)

# Link Qt and LibUSB
target_link_libraries(InkBridge PRIVATE
        ${QT_LIBS}
        ${LIBUSB_LIBRARIES}
)

# Propagate pkg-config flags if necessary
if(LIBUSB_LDFLAGS)
    target_link_options(InkBridge PRIVATE ${LIBUSB_LDFLAGS})
endif()

# -----------------------------------------------------------------------------
# 5. Platform Specifics (macOS/iOS Bundle ID)
# -----------------------------------------------------------------------------
# Now this check will work because QT_VERSION is guaranteed to be set
if("${QT_VERSION}" VERSION_LESS "6.1.0")
  set(BUNDLE_ID_OPTION MACOSX_BUNDLE_GUI_IDENTIFIER com.example.pen-emulator-linux-host)
endif()

set_target_properties(InkBridge PROPERTIES
    ${BUNDLE_ID_OPTION}
    MACOSX_BUNDLE_BUNDLE_VERSION ${PROJECT_VERSION}
    MACOSX_BUNDLE_SHORT_VERSION_STRING ${PROJECT_VERSION_MAJOR}.${PROJECT_VERSION_MINOR}
    MACOSX_BUNDLE TRUE
    WIN32_EXECUTABLE TRUE
)

# -----------------------------------------------------------------------------
# 6. Installation
# -----------------------------------------------------------------------------
include(GNUInstallDirs)
install(TARGETS InkBridge
    BUNDLE DESTINATION .
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
)

install(FILES resources/io.github.androidvirtualpen.virtualpen.desktop DESTINATION share/applications/)
install(FILES resources/io.github.androidvirtualpen.virtualpen.svg DESTINATION share/icons/hicolor/scalable/apps/)
install(FILES resources/io.github.androidvirtualpen.virtualpen.metainfo.xml DESTINATION share/metainfo/)

if(QT_VERSION_MAJOR EQUAL 6)
    qt_finalize_executable(inkbridge)
endif()