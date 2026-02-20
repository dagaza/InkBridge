#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/input-event-codes.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "error.h"
#include "constants.h"

void setup_abs(int fd, int code, int minimum, int maximum, int resolution, Error* err)
{
    if (ioctl(fd, UI_SET_ABSBIT, code) < 0)
        ERROR(err, 1, "error: ioctl UI_SET_ABSBIT, code %#x", code);

    struct uinput_abs_setup abs_setup;
    memset(&abs_setup, 0, sizeof(abs_setup));
    abs_setup.code = code;
    abs_setup.absinfo.value = 0;
    abs_setup.absinfo.minimum = minimum;
    abs_setup.absinfo.maximum = maximum;
    abs_setup.absinfo.fuzz = 0;
    abs_setup.absinfo.flat = 0;
    // units/mm
    abs_setup.absinfo.resolution = resolution;
    if (ioctl(fd, UI_ABS_SETUP, &abs_setup) < 0)
        ERROR(err, 1, "error: UI_ABS_SETUP, code: %#x", code);
}

void setup(int fd, const char* name, Error* err)
{
    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));
    strncpy(usetup.name, name, UINPUT_MAX_NAME_SIZE - 1);
    usetup.id.bustype = BUS_VIRTUAL;
    usetup.id.vendor  = 0x1701;
    usetup.id.product = 0x1701;
    usetup.id.version = 0x0001;
    usetup.ff_effects_max = 0;
    if (ioctl(fd, UI_DEV_SETUP, &usetup) < 0)
        ERROR(err, 1, "error: UI_DEV_SETUP");
}

void init_stylus(int fd, const char* name, Error* err)
{
    // enable synchronization
    if (ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0)
        ERROR(err, 1, "error: ioctl UI_SET_EVBIT EV_SYN");

    if (ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT) < 0)
        ERROR(err, 1, "error: ioctl UI_SET_PROPBIT INPUT_PROP_DIRECT");

    // enable buttons
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0)
        ERROR(err, 1, "error: ioctl UI_SET_EVBIT EV_KEY");
    if (ioctl(fd, UI_SET_KEYBIT, BTN_TOOL_PEN) < 0)
        ERROR(err, 1, "error: ioctl UI_SET_KEYBIT BTN_TOOL_PEN");
    if (ioctl(fd, UI_SET_KEYBIT, BTN_TOOL_RUBBER) < 0)
        ERROR(err, 1, "error: ioctl UI_SET_KEYBIT BTN_TOOL_RUBBER");
    if (ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH) < 0)
        ERROR(err, 1, "error: ioctl UI_SET_KEYBIT BTN_TOUCH");

    // setup sending timestamps
    if (ioctl(fd, UI_SET_EVBIT, EV_MSC) < 0)
        ERROR(err, 1, "error: ioctl UI_SET_EVBIT EV_MSC");
    if (ioctl(fd, UI_SET_MSCBIT, MSC_TIMESTAMP) < 0)
        ERROR(err, 1, "error: ioctl UI_SET_MSCBIT MSC_TIMESTAMP");

    if (ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0)
        ERROR(err, 1, "error: ioctl UI_SET_EVBIT EV_ABS");

    setup_abs(fd, ABS_X, 0, ABS_MAX_VAL, 1, err);
    OK_OR_ABORT(err);
    setup_abs(fd, ABS_Y, 0, ABS_MAX_VAL, 1, err);
    OK_OR_ABORT(err);
    setup_abs(fd, ABS_PRESSURE, 0, ABS_MAX_VAL, 12, err);
    OK_OR_ABORT(err);
    setup_abs(fd, ABS_TILT_X, -90, 90, 12, err);
    OK_OR_ABORT(err);
    setup_abs(fd, ABS_TILT_Y, -90, 90, 12, err);
    OK_OR_ABORT(err);

    setup(fd, name, err);
    OK_OR_ABORT(err);

    if (ioctl(fd, UI_DEV_CREATE) < 0)
        ERROR(err, 1, "error: ioctl");
}

int init_uinput_stylus(const char* name, Error* err)
{
    int device;

    if ((device = open("/dev/uinput", O_WRONLY | O_NONBLOCK)) < 0)
        fill_error(err, 101, "error: failed to open /dev/uinput");
    else
    {
        init_stylus(device, name, err);
    }
    return device;
}

// ----------------------------------------------------------------------------
// init_mt
//
// Configures a uinput file descriptor as a Protocol B multi-touch surface.
// This is intentionally a separate virtual device from the stylus so that
// libinput classifies them independently:
//   • stylus fd  → EVDEV_DEVICE_TABLET  (pen, pressure, tilt)
//   • mt fd      → EVDEV_DEVICE_TOUCH   (finger gestures)
//
// Mixing BTN_TOOL_PEN and ABS_MT_SLOT on one device confuses libinput into
// collapsing both into a generic tablet mode and dropping touch input.
//
// Protocol B requires the following axes in this exact order of registration:
//   ABS_MT_SLOT        — selects the active slot (finger channel)
//   ABS_MT_TRACKING_ID — per-contact identifier; -1 = finger lifted
//   ABS_MT_POSITION_X  — finger X in the same 0–ABS_MAX_VAL space as the stylus
//   ABS_MT_POSITION_Y  — finger Y
//
// BTN_TOUCH must also be declared so that libinput recognises the device as
// a touch screen rather than a bare digitiser. The kernel updates BTN_TOUCH
// automatically from the MT tracking IDs, but it still must be advertised.
//
// INPUT_PROP_DIRECT: tells libinput the touch surface is screen-integrated
// (as opposed to INPUT_PROP_POINTER for a trackpad). This is required for
// Krita's touch input to be routed as canvas gestures rather than cursor
// movement.
// ----------------------------------------------------------------------------
static void init_mt(int fd, const char* name, Error* err)
{
    /* Must match MT_MAX_SLOTS in protocol.h — C files cannot include that
       header directly because it uses C++-only constexpr syntax. */
    const int mt_max_slots = 10;

    /* Synchronisation events. */
    if (ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0)
        ERROR(err, 1, "error: ioctl UI_SET_EVBIT EV_SYN (mt)");

    /* BTN_TOUCH — required for libinput touch-device classification. */
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0)
        ERROR(err, 1, "error: ioctl UI_SET_EVBIT EV_KEY (mt)");
    if (ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH) < 0)
        ERROR(err, 1, "error: ioctl UI_SET_KEYBIT BTN_TOUCH (mt)");

    /* ABS axes — MT only, no ABS_X/ABS_Y and no INPUT_PROP_DIRECT.
       Registering ABS_X/ABS_Y or INPUT_PROP_DIRECT causes libinput to
       classify this as a touchscreen and take over cursor management,
       making the host mouse disappear. Without them, libinput leaves the
       device alone and Krita reads the MT axes directly. */
    if (ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0)
        ERROR(err, 1, "error: ioctl UI_SET_EVBIT EV_ABS (mt)");

    /* ABS_MT_SLOT: range 0..(mt_max_slots-1). Must be registered first. */
    setup_abs(fd, ABS_MT_SLOT, 0, mt_max_slots - 1, 0, err);
    OK_OR_ABORT(err);

    /* ABS_MT_TRACKING_ID: -1 = slot inactive, 0..65535 = active contact. */
    setup_abs(fd, ABS_MT_TRACKING_ID, -1, 65535, 0, err);
    OK_OR_ABORT(err);

    /* ABS_MT_POSITION_X/Y: same normalised range as the stylus device. */
    setup_abs(fd, ABS_MT_POSITION_X, 0, ABS_MAX_VAL, 1, err);
    OK_OR_ABORT(err);
    setup_abs(fd, ABS_MT_POSITION_Y, 0, ABS_MAX_VAL, 1, err);
    OK_OR_ABORT(err);

    /* Distinct product ID from the stylus device (0x1702 vs 0x1701)
       so libinput and Krita can tell the two virtual devices apart. */
    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));
    strncpy(usetup.name, name, UINPUT_MAX_NAME_SIZE - 1);
    usetup.id.bustype = BUS_VIRTUAL;
    usetup.id.vendor  = 0x1701;
    usetup.id.product = 0x1702;
    usetup.id.version = 0x0001;
    if (ioctl(fd, UI_DEV_SETUP, &usetup) < 0)
        ERROR(err, 1, "error: UI_DEV_SETUP (mt)");

    if (ioctl(fd, UI_DEV_CREATE) < 0)
        ERROR(err, 1, "error: ioctl UI_DEV_CREATE (mt)");
}

int init_uinput_mt(const char* name, Error* err)
{
    int device;

    if ((device = open("/dev/uinput", O_WRONLY | O_NONBLOCK)) < 0)
        fill_error(err, 101, "error: failed to open /dev/uinput (mt)");
    else
        init_mt(device, name, err);

    return device;
}

void destroy_uinput_device(int fd)
{
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
}

void send_uinput_event(int device, int type, int code, int value, Error* err)
{
    struct input_event ev;
    ev.type  = type;
    ev.code  = code;
    ev.value = value;
    if (write(device, &ev, sizeof(ev)) < 0)
        ERROR(err, 1, "error writing to device, filedescriptor: %d)", device);
}