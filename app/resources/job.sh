#!/bin/sh

if [ "$WORK_DIR" = "" ]; then
    WORK_DIR=$PWD
fi

if [ "$LOG_DIR" = "" ]; then
    LOG_DIR=${WORK_DIR}/logs
fi

if [ "$SCRIPT_DIR" = "" ]; then
    SCRIPT_DIR=${WORK_DIR}
fi

if [ "$EVENT_FILE" = "" ]; then
    EVENT_FILE=${LOG_DIR}/events.edn
fi

if [ "$START_FILE" = "" ]; then
    START_FILE=${LOG_DIR}/start
fi

wait_for_start()
{
    echo "Waiting for start conditions..."
    while [ ! -f "$START_FILE" ]; do
	sleep 1
    done
    echo "Ready to start"
}

post_event()
{
    echo $1 >> $EVENT_FILE
}

run_step()
{
    step=$1
    name=$step
    out=${LOG_DIR}/${step}_out
    err=${LOG_DIR}/${step}_err
    
    echo "Running step: $step"
    post_event "{:type :step/start :step \"$name\"}"
    cd $WORK_DIR
    sh ${SCRIPT_DIR}/${step} > $out 2>$err
    status=$?
    post_event "{:type :step/end :step \"$name\" :exit $status :stdout \"$out\" :stderr \"$err\"}"
    return $status
}

mkdir -p $LOG_DIR
post_event "{:type :script/wait}"
wait_for_start
post_event "{:type :script/start}"
# Execute all arguments as script steps
for v in $*
do
    run_step $v
    r=$?
    if [ $r -ne 0 ]; then
	echo "Got error at step $v: $r"
	# Nonzero return value means error, so don't proceed
	post_event "{:type :script/failed :exit $r :step \"$v\"}"
	exit $r
    fi
done
post_event "{:type :script/success}"
echo "All done."
