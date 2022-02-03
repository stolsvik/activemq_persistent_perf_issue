# Interesting performance wrt. NON_PERSISTENT messaging

When running ActiveMQ with default config, it uses KahaDB as backend with ```JournalDiskSyncStrategy.ALWAYS``` which
flushes to disk after every operation. This is to live up to the JMS promises of persistent messaging where operations
should actually have been stored to disk when e.g. producer.send() or session.commit() returns.

This constant flushing gives a very heavy performance impact, and one should really consider the pros and cons of using
PERIODIC instead.

However, I was a bit surprised by an observation: If you  run the JMS Session in Transactional mode, but also do 
```producer.setDeliveryMode(DeliveryMode.NON_PERSISTENT)```, one might believe that there wouldn't be a need to flush
the disk, as one has specifically told the system that "this doesn't really matter". However, the performance impact
from "Transactional, non-Persistent" is just as heavy as with non-Transactional+Persistent and Transactional+Persistent.

With non-Transactional + non-Persistent, even with ```JournalDiskSyncStrategy.ALWAYS```, the speed is blazing.

One might argue, well don't use Transactional then. But Transactional also brings another feature, whereby you may
receive a message, and send a message, and have these two operations inside a transactional boundary. This aspect
of Transactional is valuable even if though the messages are non-Persistent. And the broker evidently handles this
logic already even without persistence, since if you say ```BrokerService.setPersistent(false)``` and thus turn off the
entire storage of the broker, it still handles this aspect of Transactional messaging.

It would have been valuable to be able to "selectively" turn off this pretty massive hit of flushing to disk if the
messages inside the Transaction was non-Persistent. 

Note: The Java class in this repo uses an in-vm broker, with the "vm://" connection. However, this situation was first
observed with a remote broker, so the effects of interest are the same over TCP or over VM.

When setting DeliveryMode (PERSISTENT vs. NON_PERSISTENT) on the jmsProducer
(```producer.setDeliveryMode(deliveryMode)```):
```
 Each test is time for sending and in case of 'Transactional', committing, [1000] messages
 
 no store, non-Transactional, non-Persistent:   [4.26321216] ms 
 no store, non-Transactional, Persistent:   [26.784713] ms 
 no store, Transactional, non-Persistent:   [32.404009] ms 
 no store, Transactional, Persistent:   [46.314925] ms 
 ALWAYS, non-Transactional, non-Persistent:   [3.98337124] ms  <-- (This is great, avoiding the hit.)
 ALWAYS, non-Transactional, Persistent:   [4235.887625] ms 
 ALWAYS, Transactional, non-Persistent:   [4040.549101] ms     <-- This one is unfortunate!
 ALWAYS, Transactional, Persistent:   [3902.525469] ms 
 PERIODIC, non-Transactional, non-Persistent:   [3.85038125] ms 
 PERIODIC, non-Transactional, Persistent:   [78.012904] ms 
 PERIODIC, Transactional, non-Persistent:   [39.030052] ms 
 PERIODIC, Transactional, Persistent:   [94.339609] ms 

```

An alternative wrt. when to set the DeliveryMode (PERSISTENT vs. NON_PERSISTENT) on the actual send of the
message (```producer.send(msg, deliveryMode, pri, ttl)```):
```
Each test is time for sending and in case of 'Transactional', committing, [1000] messages
no store, non-Transactional, non-Persistent:   [4.36516976] ms
no store, non-Transactional, Persistent:   [27.665534] ms
no store, Transactional, non-Persistent:   [29.056998] ms
no store, Transactional, Persistent:   [30.520276] ms
ALWAYS, non-Transactional, non-Persistent:   [4.41806886] ms  <-- (This is great, avoiding the hit.)
ALWAYS, non-Transactional, Persistent:   [4107.414315] ms
ALWAYS, Transactional, non-Persistent:   [4118.322262] ms     <-- This one is unfortunate!
ALWAYS, Transactional, Persistent:   [4143.173852] ms
PERIODIC, non-Transactional, non-Persistent:   [4.453423] ms
PERIODIC, non-Transactional, Persistent:   [58.694872] ms
PERIODIC, Transactional, non-Persistent:   [28.286106] ms
PERIODIC, Transactional, Persistent:   [64.690608] ms
```
