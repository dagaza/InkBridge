package com.inkbridge

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

data class TutorialPage(
    val title: String,
    val icon: ImageVector,
    val content: @Composable () -> Unit
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingTutorialDialog(
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF5F5F5)
    val textColor = if (isDark) Color.White else Color(0xFF1A1A1A)
    val primaryColor = if (isDark) Color(0xFFBB86FC) else Color(0xFF6200EE)

    val pages = listOf(
        TutorialPage("USB (Recommended)", Icons.Default.Usb) {
            Text("For professional workflows requiring absolute zero-latency input, the wired USB connection is the gold standard.\n\nInkBridge uses the Android Open Accessory (AOA) protocol to bypass standard network layers, delivering your pen strokes instantly to the desktop.\n\nHow to Connect:\n1. Open the InkBridge Desktop App.\n2. Plug your Android device into your computer.\n3. Accept the prompt on your Android device.", color = textColor)
        },
        TutorialPage("Wi-Fi Direct", Icons.Default.Wifi) {
            Text("If you want to untether without sacrificing speed, Wi-Fi Direct is your best option. It creates a dedicated, high-speed tunnel directly between your tablet and your computer.\n\nWhy is it built this way?\nStandard routers and Linux firewalls block inbound connections. Wi-Fi Direct bypasses your local router entirely.\n\nNote: Your computer will disconnect from your home Wi-Fi. Use an Ethernet cable if you need internet access while drawing.", color = textColor)
        },
        TutorialPage("Bluetooth (Backup)", Icons.Default.Bluetooth) {
            Text("Bluetooth is a convenient backup option, but you will notice some latency (30–80ms).\n\nWhy is there a delay?\nA dedicated Bluetooth mouse uses the HID profile, operating at the hardware level. Android forces third-party apps to use the SPP (Serial Port Profile) layer—a protocol designed for file transfers, not real-time drawing. The Android Bluetooth stack adds unavoidable jitter. For serious sessions, use USB or Wi-Fi Direct.", color = textColor)
        },
        TutorialPage("Mapping & Rotation", Icons.Default.Monitor) {
            Text("Map to Screen:\nIn the Desktop App, click on the monitor you want your tablet to control. If your stylus clicks aren't registering, ensure you've mapped to the correct screen!\n\nFixing Tablet Rotation:\nIf you hold your tablet in Portrait mode but draw on a Landscape monitor, your strokes will be swapped. Check the 'Fix Rotation (Swap X/Y)' box in the Advanced Settings to fix this.", color = textColor)
        },
        TutorialPage("Pen Physics", Icons.Default.Edit) {
            Text("Pressure Sensitivity:\nAdjusts the curve of your strokes. Higher values mean you must press harder to reach maximum thickness.\n\nMinimum Pressure (Deadzone):\nCreates a baseline threshold. If you experience 'ghost strokes' while hovering, increase this slightly to ignore light touches.\n\nThe Eraser:\nInkBridge uses a 'Clean Handover' protocol. If your stylus has a side button, you can confidently switch to the eraser mid-hover. \n\nFinger or Stylus:\nYou can select stylus input-only from the Android app's main menu to ensure touching the screen with your hand while drawing won't cause any unwanted input. You can also select finger input if that's your preferred input method.", color = textColor)
        },
        TutorialPage("Canvas Gestures", Icons.Default.TouchApp) {
            Text("Two-Finger Pan & Zoom:\nYou can navigate your canvas on the Linux machine completely natively! Simply use two fingers to smoothly pan, zoom, and navigate around your workspace just like a native mobile app.\n\nSmart Palm Rejection:\nThese multi-touch gestures work perfectly even if you have 'Stylus Only' mode turned on from the main menu. InkBridge intelligently ignores accidental single-finger palm rests, but instantly recognizes intentional two-finger canvas navigation.", color = textColor)
        }
    )

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val coroutineScope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = bgColor
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Welcome to InkBridge",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = textColor
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = textColor)
                    }
                }

                Divider(color = textColor.copy(alpha = 0.1f))

                // Pager
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) { page ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = pages[page].icon,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = pages[page].title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            color = primaryColor
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        pages[page].content()
                    }
                }

                // Bottom Navigation (Dots & Buttons)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Page Indicator Dots
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        repeat(pages.size) { iteration ->
                            val color = if (pagerState.currentPage == iteration) primaryColor else textColor.copy(alpha = 0.3f)
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .clickable {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(iteration)
                                        }
                                    }
                            )
                        }
                    }

                    // Next / Finish Button
                    Button(
                        onClick = {
                            if (pagerState.currentPage < pages.size - 1) {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            } else {
                                onDismiss()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                    ) {
                        Text(if (pagerState.currentPage < pages.size - 1) "Next" else "Got it!")
                    }
                }
            }
        }
    }
}