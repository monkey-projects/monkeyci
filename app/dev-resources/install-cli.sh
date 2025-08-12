#!/bin/bash -e

VERSION={{version}}
SRC_URL="https://monkeyci-artifacts.s3.fr-par.scw.cloud/monkeyci/release-$VERSION.jar"
DEST_DIR=$HOME/.monkeyci
BIN_DIR=$HOME/bin

mkdir -p $DEST_DIR
echo "Downloading MonkeyCI jar into $DEST_DIR..."
wget -O $DEST_DIR/monkeyci.jar $SRC_URL
echo "Installing executable script..."
cat << 'EOF' > $BIN_DIR/monkeyci
#!/bin/sh
java --sun-misc-unsafe-memory-access=allow -jar $HOME/.monkeyci/monkeyci.jar $*
EOF
chmod a+x $BIN_DIR/monkeyci
echo "Installation successful, run 'monkeyci --help' for more."
