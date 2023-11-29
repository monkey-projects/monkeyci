#!/bin/sh

if [ "$LOG_FILE" = "" ]; then
    LOG_FILE=log.edn
fi

if [ "$START_FILE" = "" ]; then
    START_FILE=start
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
    echo $1 >> $LOG_FILE
}

run_step()
{
    step=$1
    name=$step
    echo "Running step: $step"
    post_event "{:type :step/start :step \"$name\"}"
    sh $step > ${step}_out 2>${step}_err
    status=$?
    post_event "{:type :step/end :step \"$name\" :exit $status :stdout \"${step}_out\" :stderr \"${step}_err\"}"
    return $status
}

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
