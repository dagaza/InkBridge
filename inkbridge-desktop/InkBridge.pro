TEMPLATE = app
TARGET = InkBridge
QT += core gui widgets concurrent network
CONFIG += c++17

# Link against LibUSB (Required for the tablet connection)
LIBS += -lusb-1.0

SOURCES += \
    main.cpp \
    mainwindow.cpp \
    virtualstylus.cpp \
    linux-adk.cpp \
    hid.cpp \
    accessory.cpp \
    displayscreentranslator.cpp \
    pressuretranslator.cpp \
    filepermissionvalidator.cpp \
    error.c \
    log.c \
    uinput.c

HEADERS += \
    mainwindow.h \
    virtualstylus.h \
    linux-adk.h \
    hid.h \
    accessory.h \
    displayscreentranslator.h \
    pressuretranslator.h \
    filepermissionvalidator.h \
    error.h \
    log.h \
    uinput.h \
    protocol.h \
    constants.h

FORMS += \
    mainwindow.ui \
    udevdialog.ui

RESOURCES += \
    icon.qrc
