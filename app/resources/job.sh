#!/bin/sh

if [ "$MONKEYCI_WORK_DIR" = "" ]; then
    MONKEYCI_WORK_DIR=$PWD
fi

if [ "$MONKEYCI_LOG_DIR" = "" ]; then
    MONKEYCI_LOG_DIR=${MONKEYCI_WORK_DIR}/logs
fi

if [ "$MONKEYCI_SCRIPT_DIR" = "" ]; then
    MONKEYCI_SCRIPT_DIR=${MONKEYCI_WORK_DIR}
fi

if [ "$MONKEYCI_EVENT_FILE" = "" ]; then
    MONKEYCI_EVENT_FILE=${MONKEYCI_LOG_DIR}/events.edn
fi

if [ "$MONKEYCI_START_FILE" = "" ]; then
    MONKEYCI_START_FILE=${MONKEYCI_LOG_DIR}/start
fi

wait_for_start()
{
    echo "Waiting for start conditions..."
    while [ ! -f "$MONKEYCI_START_FILE" ]; do
	sleep 1
    done
    echo "Ready to start"
}

post_event()
{
    echo $1 >> $MONKEYCI_EVENT_FILE
}

run_command()
{
    command=$1
    name=$command
    out=${MONKEYCI_LOG_DIR}/${command}_out
    err=${MONKEYCI_LOG_DIR}/${command}_err
    
    echo "Running command: $command"
    # Pass out and err files in the start command so the sidecar can already read them
    post_event "{:type :command/start :command \"$name\" :stdout \"$out\" :stderr \"$err\"}"
    sh ${MONKEYCI_SCRIPT_DIR}/${command} > $out 2>$err
    status=$?
    post_event "{:type :command/end :command \"$name\" :exit $status :stdout \"$out\" :stderr \"$err\"}"
    return $status
}

mkdir -p $MONKEYCI_LOG_DIR
post_event "{:type :job/wait}"
wait_for_start
post_event "{:type :job/start}"
cd $MONKEYCI_WORK_DIR
# Execute all arguments as script commands
for v in $*
do
    run_command $v
    r=$?
    if [ $r -ne 0 ]; then
	echo "Got error at step $v: $r"
	# Nonzero return value means error, so don't proceed
	post_event "{:type :job/failed :done? true :exit $r :step \"$v\"}"
	exit $r
    fi
done
post_event "{:type :job/success :done? true}"
echo "All done."
