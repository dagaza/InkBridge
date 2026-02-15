import QtQuick 2.12
import QtQuick.Controls 2.12
import QtQuick.Layouts 1.12
import QtQuick.Window 2.12
import QtGraphicalEffects 1.12

ApplicationWindow {
    id: window
    width: 1200
    height: 960
    minimumWidth: 1080
    minimumHeight: 900
    visible: true
    title: "InkBridge Desktop"
    
    // --- ENHANCED THEME PALETTE ---
    property bool isDark: true
    property color bgCol: isDark ? "#0a0a0a" : "#fafafa"
    property color sidebarCol: isDark ? "#161616" : "#ffffff"
    property color cardCol: isDark ? "#1e1e1e" : "#ffffff"
    property color cardHoverCol: isDark ? "#242424" : "#f8f8f8"
    property color textCol: isDark ? "#e8e8e8" : "#1a1a1a"
    property color subTextCol: isDark ? "#a0a0a0" : "#6b6b6b"
    property color borderCol: isDark ? "#2a2a2a" : "#e5e5e5"
    property color btnHoverCol: isDark ? "#2d2d2d" : "#f0f0f0"
    property color btnDownCol: isDark ? "#1a1a1a" : "#e0e0e0"
    
    // Brand Colors - More vibrant
    property color accentCol: "#0084ff"
    property color accentHoverCol: "#0073e6"
    property color wifiCol: "#00a8e8"
    property color successCol: "#00c853"
    property color errorCol: "#ff3b30"
    property color warningCol: "#ff9500"
    
    // Animation properties
    property int animDuration: 200
    property int longAnimDuration: 300

    color: bgCol

    // Smooth theme transition
    Behavior on bgCol { ColorAnimation { duration: animDuration } }

    RowLayout {
        anchors.fill: parent
        spacing: 0

        // ============================
        // LEFT SIDEBAR
        // ============================
        Rectangle {
            Layout.fillHeight: true
            Layout.preferredWidth: 280
            color: sidebarCol
            
            Behavior on color { ColorAnimation { duration: animDuration } }
            
            // Subtle gradient overlay for depth
            Rectangle {
                anchors.fill: parent
                gradient: Gradient {
                    GradientStop { position: 0.0; color: Qt.rgba(255, 255, 255, isDark ? 0.01 : 0.03) }
                    GradientStop { position: 1.0; color: "transparent" }
                }
            }
            
            // Border Line
            Rectangle {
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                width: 1
                color: borderCol
                Behavior on color { ColorAnimation { duration: animDuration } }
            }

            ColumnLayout {
                anchors.fill: parent
                anchors.leftMargin: 28
                anchors.rightMargin: 28
                anchors.topMargin: 36
                anchors.bottomMargin: 24
                spacing: 24

                // 1. HEADER with subtle animation
                Item {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 50
                    
                    ColumnLayout {
                        anchors.fill: parent
                        spacing: 4
                        
                        Label {
                            text: "InkBridge"
                            font.pixelSize: 32
                            font.weight: Font.Bold
                            font.letterSpacing: -0.5
                            color: textCol
                            Behavior on color { ColorAnimation { duration: animDuration } }
                        }
                        
                        Label {
                            text: "Tablet & Stylus Driver App"
                            font.pixelSize: 11
                            font.weight: Font.Normal
                            color: subTextCol
                            opacity: 0.8
                            Behavior on color { ColorAnimation { duration: animDuration } }
                        }
                    }
                }

                // 2. ENHANCED STATUS CARD
                Rectangle {
                    Layout.fillWidth: true
                    height: 72
                    radius: 12
                    color: backend.isConnected ? Qt.rgba(0, 200/255, 83/255, 0.12) : Qt.rgba(160/255, 160/255, 160/255, 0.08)
                    border.color: backend.isConnected ? Qt.rgba(0, 200/255, 83/255, 0.4) : Qt.rgba(160/255, 160/255, 160/255, 0.2)
                    border.width: 1
                    
                    Behavior on color { ColorAnimation { duration: longAnimDuration } }
                    Behavior on border.color { ColorAnimation { duration: longAnimDuration } }

                    RowLayout {
                        anchors.fill: parent
                        anchors.margins: 18
                        spacing: 16
                        
                        // Animated status indicator
                        Item {
                            width: 14
                            height: 14
                            
                            Rectangle {
                                anchors.centerIn: parent
                                width: 14
                                height: 14
                                radius: 7
                                color: backend.isConnected ? successCol : subTextCol
                                
                                Behavior on color { ColorAnimation { duration: longAnimDuration } }
                                
                                // Pulse animation when connected
                                SequentialAnimation on scale {
                                    running: backend.isConnected
                                    loops: Animation.Infinite
                                    NumberAnimation { from: 1.0; to: 1.2; duration: 1000; easing.type: Easing.InOutQuad }
                                    NumberAnimation { from: 1.2; to: 1.0; duration: 1000; easing.type: Easing.InOutQuad }
                                }
                            }
                        }
                        
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 3
                            
                            Label {
                                text: "STATUS"
                                font.pixelSize: 10
                                font.weight: Font.Bold
                                font.letterSpacing: 0.8
                                color: subTextCol
                                Behavior on color { ColorAnimation { duration: animDuration } }
                            }
                            
                            Label {
                                text: backend.isConnected ? "Connected" : "Disconnected"
                                font.pixelSize: 16
                                font.weight: Font.DemiBold
                                color: backend.isConnected ? successCol : textCol
                                Behavior on color { ColorAnimation { duration: longAnimDuration } }
                            }
                        }
                    }
                }

                // 3. ENHANCED ACTION BUTTONS
                
                // USB Connect Button with animation
                Button {
                    id: usbBtn
                    Layout.fillWidth: true
                    Layout.preferredHeight: 48
                    text: backend.isConnected ? "Disconnect (USB)" : "Connect (USB)"
                    
                    property color baseColor: backend.isConnected ? errorCol : accentCol
                    property color hoverColor: backend.isConnected ? Qt.darker(errorCol, 1.1) : accentHoverCol

                    contentItem: Text {
                        text: parent.text
                        font.weight: Font.DemiBold
                        font.pixelSize: 14
                        font.letterSpacing: 0.2
                        color: "white"
                        horizontalAlignment: Text.AlignHCenter
                        verticalAlignment: Text.AlignVCenter
                    }
                    
                    background: Rectangle {
                        color: parent.down ? Qt.darker(parent.baseColor, 1.2) : 
                               (parent.hovered ? parent.hoverColor : parent.baseColor)
                        radius: 10
                        
                        Behavior on color { ColorAnimation { duration: animDuration } }
                        
                        // Subtle shadow effect
                        layer.enabled: true
                        layer.effect: DropShadow {
                            transparentBorder: true
                            horizontalOffset: 0
                            verticalOffset: 2
                            radius: 8.0
                            samples: 17
                            color: Qt.rgba(0, 0, 0, 0.15)
                        }
                    }
                    
                    scale: down ? 0.97 : 1.0
                    Behavior on scale { NumberAnimation { duration: 100 } }
                    
                    onClicked: {
                        if (backend.isConnected) backend.disconnectDevice()
                        else backend.connectDevice(deviceCombo.currentIndex)
                    }
                }

                // Wi-Fi Connect Button with animation
                Button {
                    id: wifiBtn
                    Layout.fillWidth: true
                    Layout.preferredHeight: 48
                    text: backend.isWifiRunning ? "Disconnect (Wi-Fi)" : "Connect (Wi-Fi)"
                    
                    property color baseColor: backend.isWifiRunning ? errorCol : wifiCol
                    property color hoverColor: backend.isWifiRunning ? Qt.darker(errorCol, 1.1) : Qt.darker(wifiCol, 1.1)

                    contentItem: Text {
                        text: parent.text
                        font.weight: Font.DemiBold
                        font.pixelSize: 14
                        font.letterSpacing: 0.2
                        color: "white"
                        horizontalAlignment: Text.AlignHCenter
                        verticalAlignment: Text.AlignVCenter
                    }
                    
                    background: Rectangle {
                        color: parent.down ? Qt.darker(parent.baseColor, 1.2) : 
                               (parent.hovered ? parent.hoverColor : parent.baseColor)
                        radius: 10
                        
                        Behavior on color { ColorAnimation { duration: animDuration } }
                        
                        layer.enabled: true
                        layer.effect: DropShadow {
                            transparentBorder: true
                            horizontalOffset: 0
                            verticalOffset: 2
                            radius: 8.0
                            samples: 17
                            color: Qt.rgba(0, 0, 0, 0.15)
                        }
                    }
                    
                    scale: down ? 0.97 : 1.0
                    Behavior on scale { NumberAnimation { duration: 100 } }
                    
                    onClicked: backend.toggleWifi()
                }

                Item { Layout.fillHeight: true }
                
                // Enhanced footer
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 8
                    
                    Rectangle {
                        Layout.fillWidth: true
                        height: 1
                        color: borderCol
                        opacity: 0.5
                    }
                    
                    Label {
                        text: "v0.2.1-beta"
                        font.pixelSize: 11
                        font.weight: Font.Medium
                        color: subTextCol
                        opacity: 0.7
                        Layout.alignment: Qt.AlignHCenter
                    }
                }
            }
        }

        // ============================
        // RIGHT MAIN CONTENT
        // ============================
        ScrollView {
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            contentWidth: availableWidth
            ScrollBar.horizontal.policy: ScrollBar.AlwaysOff

            Item {
                width: parent.width
                implicitHeight: mainContent.implicitHeight + 60
                
                ColumnLayout {
                    id: mainContent
                    anchors.fill: parent
                    anchors.margins: 32
                    anchors.leftMargin: 48
                    anchors.rightMargin: 48
                    spacing: 32

                    // SECTION 1: INPUT DEVICE
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 12
                        
                        Label {
                            text: "Input Device"
                            font.weight: Font.Bold
                            font.pixelSize: 18
                            font.letterSpacing: -0.3
                            color: textCol
                            Behavior on color { ColorAnimation { duration: animDuration } }
                        }
                        
                        Rectangle {
                            Layout.fillWidth: true
                            implicitHeight: inputLayout.implicitHeight + 24
                            color: cardCol
                            radius: 12
                            border.color: borderCol
                            border.width: 1
                            
                            Behavior on color { ColorAnimation { duration: animDuration } }
                            Behavior on border.color { ColorAnimation { duration: animDuration } }

                            RowLayout {
                                id: inputLayout
                                anchors.fill: parent
                                anchors.margins: 18
                                spacing: 12

                                ComboBox {
                                    id: deviceCombo
                                    Layout.fillWidth: true
                                    Layout.minimumWidth: 200
                                    Layout.preferredHeight: 42
                                    model: backend.usbDevices
                                    
                                    background: Rectangle {
                                        color: isDark ? "#252525" : "#f5f5f5"
                                        radius: 8
                                        border.color: parent.activeFocus ? accentCol : borderCol
                                        border.width: parent.activeFocus ? 2 : 1
                                        
                                        Behavior on color { ColorAnimation { duration: animDuration } }
                                        Behavior on border.color { ColorAnimation { duration: animDuration } }
                                        Behavior on border.width { NumberAnimation { duration: 100 } }
                                    }
                                    
                                    contentItem: Text {
                                        leftPadding: 14
                                        rightPadding: 14
                                        text: parent.displayText
                                        font.pixelSize: 14
                                        color: textCol
                                        verticalAlignment: Text.AlignVCenter
                                        elide: Text.ElideRight
                                    }
                                    
                                    delegate: ItemDelegate {
                                        width: deviceCombo.width
                                        height: 40
                                        
                                        contentItem: Text {
                                            text: modelData
                                            color: textCol
                                            font.pixelSize: 14
                                            elide: Text.ElideRight
                                            verticalAlignment: Text.AlignVCenter
                                            leftPadding: 14
                                        }
                                        
                                        background: Rectangle {
                                            anchors.fill: parent
                                            color: parent.hovered ? btnHoverCol : "transparent"
                                        }
                                    }
                                    
                                    popup: Popup {
                                        y: parent.height + 4
                                        width: parent.width
                                        implicitHeight: Math.min(contentItem.implicitHeight, 300)
                                        padding: 4

                                        contentItem: ListView {
                                            clip: true
                                            implicitHeight: contentHeight
                                            model: deviceCombo.popup.visible ? deviceCombo.delegateModel : null
                                            currentIndex: deviceCombo.highlightedIndex
                                            
                                            ScrollIndicator.vertical: ScrollIndicator { }
                                        }

                                        background: Rectangle {
                                            color: cardCol
                                            border.color: borderCol
                                            border.width: 1
                                            radius: 8
                                            
                                            layer.enabled: true
                                            layer.effect: DropShadow {
                                                transparentBorder: true
                                                horizontalOffset: 0
                                                verticalOffset: 4
                                                radius: 12.0
                                                samples: 25
                                                color: Qt.rgba(0, 0, 0, 0.12)
                                            }
                                        }
                                        
                                        enter: Transition {
                                            NumberAnimation { property: "opacity"; from: 0.0; to: 1.0; duration: 150 }
                                            NumberAnimation { property: "scale"; from: 0.95; to: 1.0; duration: 150; easing.type: Easing.OutQuad }
                                        }
                                        exit: Transition {
                                            NumberAnimation { property: "opacity"; from: 1.0; to: 0.0; duration: 100 }
                                        }
                                    }
                                }

                                Button {
                                    text: "Refresh"
                                    Layout.preferredWidth: 110
                                    Layout.preferredHeight: 42
                                    
                                    contentItem: Text {
                                        text: parent.text
                                        font.pixelSize: 14
                                        font.weight: Font.Medium
                                        color: textCol
                                        horizontalAlignment: Text.AlignHCenter
                                        verticalAlignment: Text.AlignVCenter
                                    }
                                    
                                    background: Rectangle {
                                        color: parent.down ? btnDownCol : (parent.hovered ? btnHoverCol : "transparent")
                                        border.color: borderCol
                                        border.width: 1
                                        radius: 8
                                        
                                        Behavior on color { ColorAnimation { duration: animDuration } }
                                        Behavior on border.color { ColorAnimation { duration: animDuration } }
                                    }
                                    
                                    scale: down ? 0.97 : 1.0
                                    Behavior on scale { NumberAnimation { duration: 100 } }
                                    
                                    onClicked: backend.refreshUsbDevices()
                                }
                            }
                        }
                    }

                    // SECTION 2: MAP TO SCREEN
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 12
                        
                        Label {
                            text: "Map to Screen"
                            font.weight: Font.Bold
                            font.pixelSize: 18
                            font.letterSpacing: -0.3
                            color: textCol
                            Behavior on color { ColorAnimation { duration: animDuration } }
                        }

                        Rectangle {
                            Layout.fillWidth: true
                            height: 280
                            color: cardCol
                            radius: 12
                            border.color: borderCol
                            border.width: 1
                            
                            Behavior on color { ColorAnimation { duration: animDuration } }
                            Behavior on border.color { ColorAnimation { duration: animDuration } }

                            ColumnLayout {
                                anchors.fill: parent
                                anchors.margins: 20
                                spacing: 12

                                Item {
                                    id: screenCanvas
                                    Layout.fillWidth: true
                                    Layout.fillHeight: true
                                    
                                    property var geometries: backend.screenGeometries
                                    property int selectedIndex: 0

                                    property real minX: 0
                                    property real minY: 0
                                    property real totalW: 1
                                    property real totalH: 1
                                    
                                    onGeometriesChanged: {
                                        if (geometries.length === 0) return;
                                        var mx = geometries[0].x, my = geometries[0].y;
                                        var maxX = mx + geometries[0].width, maxY = my + geometries[0].height;
                                        for (var i = 1; i < geometries.length; i++) {
                                            mx = Math.min(mx, geometries[i].x);
                                            my = Math.min(my, geometries[i].y);
                                            maxX = Math.max(maxX, geometries[i].x + geometries[i].width);
                                            maxY = Math.max(maxY, geometries[i].y + geometries[i].height);
                                        }
                                        minX = mx;
                                        minY = my;
                                        totalW = maxX - mx;
                                        totalH = maxY - my;
                                    }

                                    Rectangle {
                                        anchors.fill: parent
                                        color: isDark ? "#0d0d0d" : "#f0f0f0"
                                        radius: 8
                                        
                                        Behavior on color { ColorAnimation { duration: animDuration } }
                                    }

                                    Repeater {
                                        model: screenCanvas.geometries
                                        delegate: Rectangle {
                                            property real scaleFactor: Math.min((screenCanvas.width - 40) / screenCanvas.totalW, (screenCanvas.height - 40) / screenCanvas.totalH)
                                            property bool isSelected: index === screenCanvas.selectedIndex
                                            
                                            x: 20 + (modelData.x - screenCanvas.minX) * scaleFactor + (screenCanvas.width - 40 - (screenCanvas.totalW * scaleFactor))/2
                                            y: 20 + (modelData.y - screenCanvas.minY) * scaleFactor + (screenCanvas.height - 40 - (screenCanvas.totalH * scaleFactor))/2
                                            width: modelData.width * scaleFactor
                                            height: modelData.height * scaleFactor

                                            color: isSelected ? accentCol : (mouseArea.containsMouse ? Qt.lighter(isDark ? "#2a2a2a" : "#d5d5d5", 1.1) : (isDark ? "#2a2a2a" : "#d5d5d5"))
                                            border.color: isSelected ? "white" : (mouseArea.containsMouse ? accentCol : "transparent")
                                            border.width: isSelected ? 3 : (mouseArea.containsMouse ? 2 : 0)
                                            radius: 6
                                            
                                            scale: mouseArea.containsMouse ? 1.02 : 1.0
                                            
                                            Behavior on color { ColorAnimation { duration: animDuration } }
                                            Behavior on border.color { ColorAnimation { duration: animDuration } }
                                            Behavior on border.width { NumberAnimation { duration: 150 } }
                                            Behavior on scale { NumberAnimation { duration: 150; easing.type: Easing.OutQuad } }

                                            Text {
                                                anchors.centerIn: parent
                                                text: modelData.name
                                                color: isSelected ? "white" : (isDark ? "#e8e8e8" : "#1a1a1a")
                                                font.bold: true
                                                font.pixelSize: 24
                                                style: Text.Outline
                                                styleColor: isSelected ? Qt.rgba(0, 0, 0, 0.3) : Qt.rgba(0, 0, 0, 0.1)
                                            }
                                            
                                            MouseArea {
                                                id: mouseArea
                                                anchors.fill: parent
                                                hoverEnabled: true
                                                cursorShape: Qt.PointingHandCursor
                                                onClicked: {
                                                    screenCanvas.selectedIndex = index;
                                                    backend.selectScreen(index)
                                                }
                                            }
                                        }
                                    }
                                }
                                
                                Label {
                                    text: "Select the monitor where the tablet cursor should appear"
                                    color: subTextCol
                                    font.pixelSize: 13
                                    Layout.alignment: Qt.AlignHCenter
                                    Behavior on color { ColorAnimation { duration: animDuration } }
                                }
                            }
                        }
                    }

                    // SECTION 3: ADVANCED SETTINGS
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 12
                        
                        Label {
                            text: "Advanced Settings"
                            font.weight: Font.Bold
                            font.pixelSize: 18
                            font.letterSpacing: -0.3
                            color: textCol
                            Behavior on color { ColorAnimation { duration: animDuration } }
                        }

                        Rectangle {
                            Layout.fillWidth: true
                            implicitHeight: settingsLayout.implicitHeight + 32
                            color: cardCol
                            radius: 12
                            border.color: borderCol
                            border.width: 1
                            
                            Behavior on color { ColorAnimation { duration: animDuration } }
                            Behavior on border.color { ColorAnimation { duration: animDuration } }

                            ColumnLayout {
                                id: settingsLayout
                                anchors.fill: parent
                                anchors.margins: 24
                                spacing: 24

                                // Sensitivity
                                RowLayout {
                                    Layout.fillWidth: true
                                    spacing: 20
                                    
                                    Label {
                                        text: "Pressure Sensitivity"
                                        color: textCol
                                        font.pixelSize: 14
                                        font.weight: Font.Medium
                                        Layout.preferredWidth: 160
                                        Behavior on color { ColorAnimation { duration: animDuration } }
                                    }
                                    
                                    Slider {
                                        Layout.fillWidth: true
                                        from: 0
                                        to: 100
                                        value: backend.pressureSensitivity
                                        onMoved: backend.setPressureSensitivity(value)
                                        
                                        background: Rectangle {
                                            x: parent.leftPadding
                                            y: parent.topPadding + parent.availableHeight / 2 - height / 2
                                            implicitWidth: 200
                                            implicitHeight: 6
                                            width: parent.availableWidth
                                            height: implicitHeight
                                            radius: 3
                                            color: isDark ? "#2a2a2a" : "#e5e5e5"
                                            
                                            Behavior on color { ColorAnimation { duration: animDuration } }

                                            Rectangle {
                                                width: parent.parent.visualPosition * parent.width
                                                height: parent.height
                                                color: accentCol
                                                radius: 3
                                                
                                                Behavior on width { NumberAnimation { duration: 100 } }
                                            }
                                        }
                                        
                                        handle: Rectangle {
                                            x: parent.leftPadding + parent.visualPosition * (parent.availableWidth - width)
                                            y: parent.topPadding + parent.availableHeight / 2 - height / 2
                                            width: 22
                                            height: 22
                                            radius: 11
                                            color: "white"
                                            border.color: accentCol
                                            border.width: 2
                                            
                                            scale: parent.pressed ? 1.1 : 1.0
                                            Behavior on scale { NumberAnimation { duration: 100 } }
                                            
                                            layer.enabled: true
                                            layer.effect: DropShadow {
                                                transparentBorder: true
                                                horizontalOffset: 0
                                                verticalOffset: 1
                                                radius: 4.0
                                                samples: 9
                                                color: Qt.rgba(0, 0, 0, 0.2)
                                            }
                                        }
                                    }
                                    
                                    Label {
                                        text: Math.round(backend.pressureSensitivity) + "%"
                                        color: subTextCol
                                        font.pixelSize: 14
                                        font.weight: Font.Medium
                                        Layout.preferredWidth: 50
                                        horizontalAlignment: Text.AlignRight
                                        Behavior on color { ColorAnimation { duration: animDuration } }
                                    }
                                }

                                // Min Pressure
                                RowLayout {
                                    Layout.fillWidth: true
                                    spacing: 20
                                    
                                    Label {
                                        text: "Minimum Pressure"
                                        color: textCol
                                        font.pixelSize: 14
                                        font.weight: Font.Medium
                                        Layout.preferredWidth: 160
                                        Behavior on color { ColorAnimation { duration: animDuration } }
                                    }
                                    
                                    Slider {
                                        Layout.fillWidth: true
                                        from: 0
                                        to: 100
                                        value: backend.minPressure
                                        onMoved: backend.setMinPressure(value)
                                        
                                        background: Rectangle {
                                            x: parent.leftPadding
                                            y: parent.topPadding + parent.availableHeight / 2 - height / 2
                                            implicitWidth: 200
                                            implicitHeight: 6
                                            width: parent.availableWidth
                                            height: implicitHeight
                                            radius: 3
                                            color: isDark ? "#2a2a2a" : "#e5e5e5"
                                            
                                            Behavior on color { ColorAnimation { duration: animDuration } }

                                            Rectangle {
                                                width: parent.parent.visualPosition * parent.width
                                                height: parent.height
                                                color: accentCol
                                                radius: 3
                                                
                                                Behavior on width { NumberAnimation { duration: 100 } }
                                            }
                                        }
                                        
                                        handle: Rectangle {
                                            x: parent.leftPadding + parent.visualPosition * (parent.availableWidth - width)
                                            y: parent.topPadding + parent.availableHeight / 2 - height / 2
                                            width: 22
                                            height: 22
                                            radius: 11
                                            color: "white"
                                            border.color: accentCol
                                            border.width: 2
                                            
                                            scale: parent.pressed ? 1.1 : 1.0
                                            Behavior on scale { NumberAnimation { duration: 100 } }
                                            
                                            layer.enabled: true
                                            layer.effect: DropShadow {
                                                transparentBorder: true
                                                horizontalOffset: 0
                                                verticalOffset: 1
                                                radius: 4.0
                                                samples: 9
                                                color: Qt.rgba(0, 0, 0, 0.2)
                                            }
                                        }
                                    }
                                    
                                    Label {
                                        text: Math.round(backend.minPressure) + "%"
                                        color: subTextCol
                                        font.pixelSize: 14
                                        font.weight: Font.Medium
                                        Layout.preferredWidth: 50
                                        horizontalAlignment: Text.AlignRight
                                        Behavior on color { ColorAnimation { duration: animDuration } }
                                    }
                                }
                                
                                Rectangle {
                                    height: 1
                                    Layout.fillWidth: true
                                    color: borderCol
                                    opacity: 0.6
                                    Behavior on color { ColorAnimation { duration: animDuration } }
                                }

                                // Toggles
                                RowLayout {
                                    Layout.fillWidth: true
                                    spacing: 40
                                    
                                    RowLayout {
                                        spacing: 12
                                        Label {
                                            text: "Dark Mode"
                                            color: textCol
                                            font.pixelSize: 14
                                            font.weight: Font.Medium
                                            Behavior on color { ColorAnimation { duration: animDuration } }
                                        }
                                        Switch {
                                            checked: isDark
                                            onToggled: isDark = checked
                                            
                                            indicator: Rectangle {
                                                implicitWidth: 48
                                                implicitHeight: 28
                                                x: parent.leftPadding
                                                y: parent.height / 2 - height / 2
                                                radius: 14
                                                color: parent.checked ? accentCol : (isDark ? "#3a3a3a" : "#cccccc")
                                                
                                                Behavior on color { ColorAnimation { duration: animDuration } }

                                                Rectangle {
                                                    x: parent.parent.checked ? parent.width - width - 3 : 3
                                                    y: (parent.height - height) / 2
                                                    width: 22
                                                    height: 22
                                                    radius: 11
                                                    color: "white"
                                                    
                                                    Behavior on x { NumberAnimation { duration: animDuration; easing.type: Easing.OutQuad } }
                                                    
                                                    layer.enabled: true
                                                    layer.effect: DropShadow {
                                                        transparentBorder: true
                                                        horizontalOffset: 0
                                                        verticalOffset: 1
                                                        radius: 3.0
                                                        samples: 7
                                                        color: Qt.rgba(0, 0, 0, 0.25)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    
                                    RowLayout {
                                        spacing: 12
                                        Label {
                                            text: "Fix Rotation (Swap X/Y)"
                                            color: textCol
                                            font.pixelSize: 14
                                            font.weight: Font.Medium
                                            Behavior on color { ColorAnimation { duration: animDuration } }
                                        }
                                        CheckBox {
                                            checked: backend.swapAxis
                                            onToggled: backend.setSwapAxis(checked)
                                            
                                            indicator: Rectangle {
                                                implicitWidth: 22
                                                implicitHeight: 22
                                                x: parent.leftPadding
                                                y: parent.height / 2 - height / 2
                                                radius: 5
                                                border.color: parent.checked ? accentCol : borderCol
                                                border.width: 2
                                                color: parent.checked ? accentCol : "transparent"
                                                
                                                Behavior on color { ColorAnimation { duration: animDuration } }
                                                Behavior on border.color { ColorAnimation { duration: animDuration } }
                                                
                                                Text {
                                                    anchors.centerIn: parent
                                                    text: "✓"
                                                    color: "white"
                                                    font.pixelSize: 14
                                                    font.bold: true
                                                    opacity: parent.parent.checked ? 1 : 0
                                                    
                                                    Behavior on opacity { NumberAnimation { duration: 150 } }
                                                }
                                            }
                                        }
                                    }
                                }

                                Rectangle {
                                    height: 1
                                    Layout.fillWidth: true
                                    color: borderCol
                                    opacity: 0.6
                                    Behavior on color { ColorAnimation { duration: animDuration } }
                                }

                                // Footer Buttons
                                RowLayout {
                                    Layout.fillWidth: true
                                    spacing: 12
                                    
                                    Button {
                                        text: "Tilt Calibration/Debugging"
                                        Layout.fillWidth: true
                                        Layout.preferredHeight: 44
                                        checkable: true
                                        
                                        property color baseColor: checked ? accentCol : "transparent"
                                        
                                        contentItem: Text {
                                            text: parent.text
                                            font.pixelSize: 14
                                            font.weight: Font.Medium
                                            color: parent.checked ? "white" : textCol
                                            horizontalAlignment: Text.AlignHCenter
                                            verticalAlignment: Text.AlignVCenter
                                            
                                            Behavior on color { ColorAnimation { duration: animDuration } }
                                        }
                                        
                                        background: Rectangle {
                                            color: parent.checked ? parent.baseColor : (parent.down ? btnDownCol : (parent.hovered ? btnHoverCol : "transparent"))
                                            border.color: parent.checked ? "transparent" : borderCol
                                            border.width: 1
                                            radius: 8
                                            
                                            Behavior on color { ColorAnimation { duration: animDuration } }
                                            Behavior on border.color { ColorAnimation { duration: animDuration } }
                                        }
                                        
                                        scale: down ? 0.97 : 1.0
                                        Behavior on scale { NumberAnimation { duration: 100 } }
                                        
                                        onCheckedChanged: backend.toggleDebug(checked)
                                    }
                                    
                                    Button {
                                        text: "Reset Defaults"
                                        Layout.preferredWidth: 160
                                        Layout.preferredHeight: 44
                                        
                                        contentItem: Text {
                                            text: parent.text
                                            font.pixelSize: 14
                                            font.weight: Font.Medium
                                            color: textCol
                                            horizontalAlignment: Text.AlignHCenter
                                            verticalAlignment: Text.AlignVCenter
                                            
                                            Behavior on color { ColorAnimation { duration: animDuration } }
                                        }
                                        
                                        background: Rectangle {
                                            color: parent.down ? btnDownCol : (parent.hovered ? btnHoverCol : "transparent")
                                            border.color: borderCol
                                            border.width: 1
                                            radius: 8
                                            
                                            Behavior on color { ColorAnimation { duration: animDuration } }
                                            Behavior on border.color { ColorAnimation { duration: animDuration } }
                                        }
                                        
                                        scale: down ? 0.97 : 1.0
                                        Behavior on scale { NumberAnimation { duration: 100 } }
                                        
                                        onClicked: backend.resetDefaults()
                                    }
                                }
                            }
                        }
                    }
                    
                    Item {
                        height: 20
                        Layout.fillWidth: true
                    }
                }
            }
        }
    }
}