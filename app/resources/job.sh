#!/bin/sh

LOG_FILE=log.edn

post_event()
{
    echo $1 >> $LOG_FILE
}

run_step()
{
    step=$1
    name=$step
    post_event "{:type :step/start :step \"$name\"}"
    sh $step > ${step}_out 2>${step}_err
    status=$?
    post_event "{:type :step/end :step \"$name\" :exit $status :stdout \"${step}_out\" :stderr \"${step}_err\"}"
    return $status
}

post_event "{:type :script/start}"
# Execute all arguments as script steps
for v in $*
do
    run_step $v
    r=$?
    if [ $r -gt 0 ]; then
	post_event "{:type :script/failed :exit $r :step \"$v\"}"
	exit $r
    fi
done
post_event "{:type :script/success}"
