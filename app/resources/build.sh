#!/bin/sh

# This script is responsible for starting the build process, when it is
# run in a pod alongside a controller.  The controller should create a
# run file, and this script should start the clojure process running the
# build script as soon as that file has been created.  Afterwards the
# run file should be deleted, which is the signal for the controller to
# terminate as well.

if [ "$MONKEYCI_WORK_DIR" = "" ]; then
    MONKEYCI_WORK_DIR=$PWD
fi

if [ "$MONKEYCI_START_FILE" = "" ]; then
    MONKEYCI_START_FILE=${MONKEYCI_WORK_DIR}/start
fi

if [ "$MONKEYCI_ABORT_FILE" = "" ]; then
    MONKEYCI_ABORT_FILE=${MONKEYCI_WORK_DIR}/abort
fi

wait_for_start()
{
    echo "Waiting for start conditions..."
    while true; do
	sleep 1
	if [ -f "$MONKEYCI_START_FILE" ]; then
	    echo "Ready to start"
	    START=yes
	    break
	fi
	# Abortion indicates there is something wrong with the sidecar
	if [ -f "$MONKEYCI_ABORT_FILE" ]; then
	    echo "Aborting execution"
	    ABORT=yes
	    break
	fi
    done
}

post_event()
{
    echo $1 >> $MONKEYCI_EVENT_FILE
}

echo "Starting build with working directory $MONKEYCI_WORK_DIR"
# Create any directories
mkdir -p `dirname $MONKEYCI_START_FILE`
mkdir -p `dirname $MONKEYCI_ABORT_FILE`

wait_for_start
if [ "$ABORT" = "yes" ]; then
    echo "Aborted."
    exit 1
fi

cd $MONKEYCI_WORK_DIR
# Run the build.  This assumes the necessary deps.edn is placed in $CLJ_CONFIG.
clojure -X:monkeyci/build
echo "All done."
