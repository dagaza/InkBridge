Maintainer: Dan Zadobrischi <dan.zadobrischi@gmail.com>
pkgname=inkbridge
pkgver=0.2.1
pkgrel=1
pkgdesc="Turn your Android Stylus device into a professional Graphics Tablet"
arch=('x86_64')
url="https://github.com/dagaza/InkBridge"
license=('MIT')
depends=('qt5-base' 'qt5-declarative' 'qt5-quickcontrols2' 'libusb')
makedepends=('qt5-tools' 'gcc')
source=("https://github.com/dagaza/InkBridge/archive/v$pkgver.tar.gz")
sha256sums=('SKIP') # You should update this with the real hash of your tar.gz

build() {
    cd "$pkgname-$pkgver"
    qmake
    make
}

package() {
    cd "$pkgname-$pkgver"
    
    # Binaries and Configs
    install -Dm755 inkbridge "$pkgdir/usr/bin/inkbridge"
    install -Dm644 assets/99-inkbridge.rules "$pkgdir/etc/udev/rules.d/99-inkbridge.rules"
    
    # Desktop Entry
    install -Dm644 assets/inkbridge.desktop "$pkgdir/usr/share/applications/inkbridge.desktop"
    
    # Icon (New Line)
    install -Dm644 assets/inkbridge.png "$pkgdir/usr/share/icons/hicolor/512x512/apps/inkbridge.png"
}