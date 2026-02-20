#ifndef UINPUT_H
#define UINPUT_H
#include "error.h"

// Android MotionEvent action codes (used by both uinput.cpp and virtualstylus.cpp)
const int ACTION_DOWN       = 0;
const int ACTION_MOVE       = 2;
const int ACTION_UP         = 1;
const int ACTION_HOVER_MOVE = 7;
const int ACTION_CANCEL     = 3;
const int ACTION_OUTSIDE    = 4;

// Maximum simultaneous MT slots. Defined canonically in protocol.h.
// Referenced here only as documentation — do not redefine.

extern "C" int  init_uinput_stylus(const char* name, Error* err);

// Creates a separate uinput device configured for Protocol B multi-touch.
// Returns the file descriptor on success, or a negative value on failure.
// The caller owns the fd and must call destroy_uinput_device() when done.
extern "C" int  init_uinput_mt(const char* name, Error* err);

extern "C" void send_uinput_event(int device, int type, int code, int value, Error* err);
extern "C" void destroy_uinput_device(int fd);

#endif // UINPUT_H