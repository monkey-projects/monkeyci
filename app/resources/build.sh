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

if [ "$MONKEYCI_EXIT_FILE" = "" ]; then
    MONKEYCI_EXIT_FILE=${MONKEYCI_WORK_DIR}/exit
fi

wait_for_start()
{
    echo "Waiting for start conditions..."
    echo "Start file: $MONKEYCI_START_FILE"
    echo "Abort file: $MONKEYCI_ABORT_FILE"
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

cleanup()
{
    echo "Deleting run file"
    rm -f $MONKEYCI_START_FILE
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

# Ensure cleanup is executed on termination
trap cleanup EXIT

cd $MONKEYCI_WORK_DIR
# Run the build.  This assumes the necessary deps.edn is placed in $CLJ_CONFIG.
clojure -X:monkeyci/build
RESULT=$?
echo "Script finished with exit code $RESULT"
echo $RESULT > $MONKEYCI_EXIT_FILE
echo "All done."
exit $RESULT
