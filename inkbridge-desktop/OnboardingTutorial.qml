import QtQuick 2.12
import QtQuick.Controls 2.12
import QtQuick.Layouts 1.12
import QtGraphicalEffects 1.12

Rectangle {
    id: tutorialRoot
    anchors.fill: parent
    color: Qt.rgba(0, 0, 0, 0.75) // Darken slightly more for focus
    z: 100 
    visible: false

    property int currentPage: 0
    signal closed()

    // Catch all mouse events to prevent clicking through the overlay
    MouseArea {
        anchors.fill: parent
        hoverEnabled: true
        preventStealing: true
    }

    Rectangle {
        id: dialogCard
        width: Math.min(parent.width * 0.8, 900)
        height: Math.min(parent.height * 0.8, 800)
        anchors.centerIn: parent
        radius: 20
        color: isDark ? "#1e1e1e" : "#ffffff"

        layer.enabled: true
        layer.effect: DropShadow {
            radius: 24
            samples: 32
            color: "#A0000000"
        }

        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 35
            spacing: 25

            // --- HEADER ---
            RowLayout {
                Layout.fillWidth: true
                Label {
                    text: "Welcome to InkBridge"
                    font.pixelSize: 24
                    font.weight: Font.Bold
                    color: textCol
                    Layout.fillWidth: true
                }
                
                // Modern Close Button
                Button {
                    id: closeBtn
                    text: "✕"
                    flat: true
                    onClicked: {
                        currentPage = 0 // Reset for next time
                        tutorialRoot.closed()
                    }
                    contentItem: Text {
                        text: parent.text
                        color: closeBtn.hovered ? errorCol : subTextCol
                        font.pixelSize: 22
                        horizontalAlignment: Text.AlignHCenter
                        Behavior on color { ColorAnimation { duration: 150 } }
                    }
                    background: Item {} // Transparent
                }
            }

            // --- CONTENT AREA ---
            StackLayout {
                id: pager
                Layout.fillWidth: true
                Layout.fillHeight: true
                currentIndex: tutorialRoot.currentPage

                // Page 1: USB
                TutorialSlide {
                    title: "1. Choosing Your Connection: USB (Recommended)"
                    body: "For professional workflows requiring absolute zero-latency input, the wired USB connection is the gold standard. InkBridge uses the <b>Android Open Accessory (AOA)</b> protocol to bypass standard network layers, delivering your pen strokes instantly to the desktop.<br><br><b>How to Connect via USB:</b><br>1. Open the InkBridge Desktop App on your Linux machine.<br><br>2. Plug your Android tablet or phone into your computer using a high-quality data USB cable.<br><br>3. A prompt will appear on your Android device asking to 'Open with InkBridge.' Accept this prompt.<br><br>4. The status indicator on your desktop app will turn green, and you are ready to draw!"
                }

                // Page 2: Wi-Fi Direct
                TutorialSlide {
                    title: "2. Going Wireless: Wi-Fi Direct (The Wireless Hero)"
                    body: "If you want to untether from your desk without sacrificing speed, Wi-Fi Direct is your best option. It creates a dedicated, high-speed tunnel directly between your tablet and your computer.<br><br><b>How to Connect via Wi-Fi Direct:</b><br>1. Open the InkBridge app on your Android tablet and tap Connect via Wi-Fi Direct.<br><br>2. On your Linux computer, open your Wi-Fi settings and connect to the network named DIRECT-IB-InkBridge using the password displayed on your tablet screen.<br><br>3. Once your computer is connected to that network, tap Desktop is Connected on your tablet.<br><br>4. The devices will automatically handshake and open the drawing stream.<br><br><b>Why is it built this way?</b><br>Connecting Linux devices over standard home routers is notoriously unreliable due to varying firewall (ufw, firewalld) and routing configurations. Wi-Fi Direct safely bypasses these restrictions entirely.<br><br><b>⚠️ Important Note on Internet Access:</b> Your computer's Wi-Fi card will be dedicated to the tablet and will disconnect from your home Wi-Fi. We highly recommend using an Ethernet cable for your PC to maintain internet access while drawing."
                }

                // Page 3: Bluetooth
                TutorialSlide {
                    title: "3. The Backup Option: Bluetooth"
                    body: "Bluetooth is a convenient, completely wireless backup option for drawing on the go when creating a Wi-Fi Direct group isn't feasible.<br><br><b>How to Connect via Bluetooth:</b><br>1. Ensure your tablet and computer are paired in your system's Bluetooth settings.<br><br>2. Tap Connect via Bluetooth in the Android app and select your computer.<br><br><b>A Note on Bluetooth Latency:</b><br>You will notice some latency (typically 30 to 80 milliseconds) when drawing over Bluetooth. While a standard Bluetooth mouse uses the HID profile for real-time hardware polling, InkBridge is forced to use the SPP (Serial Port Profile) layer. This protocol was designed for file transfers and adds unavoidable jitter at the Android system level. For professional drawing sessions, always use USB or Wi-Fi Direct."
                }

                // Page 4: Mapping
                TutorialSlide {
                    title: "4. Mapping Your Workspace"
                    body: "If you have multiple monitors, InkBridge makes it incredibly easy to choose exactly where your stylus input goes.<br><br><b>Map to Screen:</b><br>In the Desktop App, look for the Map to Screen section. You will see a visual representation of all your connected monitors. Simply click on the screen you want your tablet to control.<br><br><b>Troubleshooting Tip:</b><br>If you notice your stylus is moving but your clicks/strokes are not registering, ensure you have mapped the tablet to the correct screen. Clicks outside the active screen's boundaries are naturally ignored by the Linux system.<br><br><b>Fixing Tablet Rotation (Swap X/Y):</b><br>If you are holding your tablet in Portrait mode but drawing on a Landscape monitor, your Up/Down strokes might move the cursor Left/Right. To fix this, scroll to the Advanced Settings and check the Fix Rotation (Swap X/Y) box."
                }

                // Page 5: Pen Physics
                TutorialSlide {
                    title: "5. Customizing Your Pen Physics"
                    body: "Every artist has a different hand weight. InkBridge uses a custom logarithmic pressure curve, and you can tune it exactly to your liking using the sliders in the Advanced Settings.<br><br><b>Pressure Sensitivity (0-100%):</b><br>This adjusts the curve of your strokes. Higher values require more pressure to reach maximum brush thickness (great for heavy-handed sketchers), while lower values make the pen much more sensitive.<br><br><b>Minimum Pressure (Deadzone):</b><br>This creates a baseline threshold before a stroke is registered. If you are experiencing 'ghost strokes' (the tablet registering strokes when your pen is barely hovering or your palm is resting), increase this slider slightly to ignore those accidental light touches."
                }

                // Page 6: Pro Features
                TutorialSlide {
                    title: "6. Professional Features & Troubleshooting"
                    body: "<b>3D Tilt & The Eraser:</b><br>• <b>True 3D Tilt:</b> If your stylus supports it (like the Samsung S-Pen), InkBridge automatically calculates and projects true 3D Tilt (-90° to +90°) directly to your Linux creative suite for realistic shading.<br><br>• <b>Clean Handover Eraser:</b> If your stylus has a side button, you can confidently switch between the pen tip and the eraser mid-hover without dropping inputs.<br><br><b>The 'Reset Connection' Button:</b><br>Sometimes the Linux kernel grabs your Android device's USB port before InkBridge can. If a USB connection fails, click 'Reset Connection' in the sidebar. This forces a hardware-level reset to help InkBridge claim the device.<br><br><b>Dark Mode:</b><br>Both apps feature a beautifully themed Dark Mode. Toggle it at the bottom of this sidebar or via the theme icon in the tablet app!"
                }

                // Page 7: Canvas Gestures
                TutorialSlide {
                    title: "7. Canvas Gestures"
                    body: "<br><br><b>Two-Finger Pan & Zoom:</b><br>You can navigate your canvas on the Linux desktop completely natively! Simply use two fingers to smoothly pan, zoom, and navigate around your workspace just like a native mobile app.<br><br><b>Smart Palm Rejection:</b><br>These multi-touch gestures work perfectly even if you have 'Stylus Only' mode turned on from the main menu. InkBridge intelligently ignores accidental single-finger palm rests, but instantly recognizes intentional two-finger canvas navigation."
                }
            }
            

            // --- FOOTER (PAGINATION & NAVIGATION) ---
            RowLayout {
                Layout.fillWidth: true
                spacing: 15

                // Back Button (Only visible after first page)
                Button {
                    id: backBtn
                    text: "Back"
                    visible: currentPage > 0
                    Layout.preferredWidth: 100
                    Layout.preferredHeight: 44
                    
                    contentItem: Text {
                        text: parent.text
                        font.weight: Font.Medium
                        font.pixelSize: 14
                        color: textCol
                        horizontalAlignment: Text.AlignHCenter
                        verticalAlignment: Text.AlignVCenter
                    }
                    
                    background: Rectangle {
                        color: backBtn.down ? btnDownCol : (backBtn.hovered ? btnHoverCol : "transparent")
                        border.color: borderCol
                        border.width: 1
                        radius: 10
                    }
                    
                    onClicked: if (currentPage > 0) currentPage--
                }

                // Page Indicator Dots
                Row {
                    spacing: 10
                    Layout.alignment: Qt.AlignVCenter
                    Layout.fillWidth: true
                    leftPadding: backBtn.visible ? 0 : 115 // Keep dots centered when Back is hidden

                    Repeater {
                        model: 7 // Changed from 4 to 7 to match slide count
                        Rectangle {
                            width: index === currentPage ? 20 : 8
                            height: 8
                            radius: 4
                            color: index === currentPage ? accentCol : borderCol
                            Behavior on width { NumberAnimation { duration: 200; easing.type: Easing.OutQuad } }
                            Behavior on color { ColorAnimation { duration: 200 } }
                        }
                    }
                }

                // Next / Finish Button
                Button {
                    id: nextBtn
                    text: currentPage < 6 ? "Next" : "Start Drawing"
                    Layout.preferredWidth: 140
                    Layout.preferredHeight: 46
                    
                    contentItem: Text {
                        text: parent.text
                        font.weight: Font.Bold
                        font.pixelSize: 14
                        color: "white" // Always white for brand color background
                        horizontalAlignment: Text.AlignHCenter
                        verticalAlignment: Text.AlignVCenter
                    }
                    
                    background: Rectangle {
                        color: nextBtn.down ? Qt.darker(accentCol, 1.2) : (nextBtn.hovered ? accentHoverCol : accentCol)
                        radius: 10
                        
                        layer.enabled: true
                        layer.effect: DropShadow {
                            transparentBorder: true
                            radius: 8
                            samples: 17
                            color: Qt.rgba(0, 0, 0, 0.2)
                        }
                    }
                    
                    onClicked: {
                        if (currentPage < 6) currentPage++ // Changed from 3 to 6
                        else {
                            currentPage = 0 // Reset
                            tutorialRoot.closed()
                        }
                    }
                }
            }
        }
    }

    // Internal Helper for Slides
    component TutorialSlide: ColumnLayout {
        property alias title: tLabel.text
        property alias body: bLabel.text
        spacing: 15

        Label {
            id: tLabel
            font.pixelSize: 22
            font.bold: true
            color: accentCol
            Layout.alignment: Qt.AlignHCenter
        }

        Label {
            id: bLabel
            Layout.fillWidth: true
            wrapMode: Text.WordWrap
            horizontalAlignment: Text.AlignHCenter
            font.pixelSize: 16
            lineHeight: 1.4
            color: subTextCol

            textFormat: Text.StyledText
        }
        
        Item { Layout.fillHeight: true } // Spacer
    }
}